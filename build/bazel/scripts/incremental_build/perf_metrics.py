#!/usr/bin/env python3

# Copyright (C) 2022 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
import dataclasses
import datetime
import glob
import json
import logging
import re
import shutil
import subprocess
import textwrap
from pathlib import Path
from typing import Iterable

from bp2build_metrics_proto.bp2build_metrics_pb2 import Bp2BuildMetrics
from metrics_proto.metrics_pb2 import MetricsBase
from metrics_proto.metrics_pb2 import PerfInfo
from metrics_proto.metrics_pb2 import SoongBuildMetrics

import util


@dataclasses.dataclass
class PerfInfoOrEvent:
  """
  A duck-typed union of `soong_build_metrics.PerfInfo` and
  `soong_build_bp2build_metrics.Event` protobuf message types
  """
  name: str
  real_time: datetime.timedelta
  start_time: datetime.datetime
  description: str = ''  # Bp2BuildMetrics#Event doesn't have description

  def __post_init__(self):
    if isinstance(self.real_time, int):
      self.real_time = datetime.timedelta(microseconds=self.real_time / 1000)
    if isinstance(self.start_time, int):
      epoch = datetime.datetime(1970, 1, 1, tzinfo=datetime.timezone.utc)
      self.start_time = epoch + datetime.timedelta(
          microseconds=self.start_time / 1000)


SOONG_PB = 'soong_metrics'
SOONG_BUILD_PB = 'soong_build_metrics.pb'
BP2BUILD_PB = 'bp2build_metrics.pb'


def _copy_pbs_to(d: Path):
  soong_pb = util.get_out_dir().joinpath(SOONG_PB)
  soong_build_pb = util.get_out_dir().joinpath(SOONG_BUILD_PB)
  bp2build_pb = util.get_out_dir().joinpath(BP2BUILD_PB)
  if soong_pb.exists():
    shutil.copy(soong_pb, d.joinpath(SOONG_PB))
  if soong_build_pb.exists():
    shutil.copy(soong_build_pb, d.joinpath(SOONG_BUILD_PB))
  if bp2build_pb.exists():
    shutil.copy(bp2build_pb, d.joinpath(BP2BUILD_PB))


def archive_run(d: Path, build_info: dict[str, any]):
  _copy_pbs_to(d)
  with open(d.joinpath(util.BUILD_INFO_JSON), 'w') as f:
    json.dump(build_info, f, indent=True)


def read_pbs(d: Path) -> dict[str, str]:
  """
  Reads metrics data from pb files and archives the file by copying
  them under the log_dir.
  Soong_build event names may contain "mixed_build" event. To normalize the
  event names between mixed builds and soong-only build, convert
    `soong_build/soong_build.xyz` and `soong_build/soong_build.mixed_build.xyz`
  both to simply `soong_build/*.xyz`
  """
  soong_pb = d.joinpath(SOONG_PB)
  soong_build_pb = d.joinpath(SOONG_BUILD_PB)
  bp2build_pb = d.joinpath(BP2BUILD_PB)

  events: list[PerfInfoOrEvent] = []

  def extract_perf_info(root_obj):
    for field_name in dir(root_obj):
      if field_name.startswith('__'):
        continue
      field_value = getattr(root_obj, field_name)
      if isinstance(field_value, Iterable):
        for item in field_value:
          if not isinstance(item, PerfInfo):
            break
          events.append(
            PerfInfoOrEvent(item.name, item.real_time, item.start_time,
                            item.description))

  if soong_pb.exists():
    metrics_base = MetricsBase()
    with open(soong_pb, "rb") as f:
      metrics_base.ParseFromString(f.read())
    extract_perf_info(metrics_base)

  if soong_build_pb.exists():
    soong_build_metrics = SoongBuildMetrics()
    with open(soong_build_pb, "rb") as f:
      soong_build_metrics.ParseFromString(f.read())
    extract_perf_info(soong_build_metrics)

  if bp2build_pb.exists():
    bp2build_metrics = Bp2BuildMetrics()
    with open(bp2build_pb, "rb") as f:
      bp2build_metrics.ParseFromString(f.read())
    for event in bp2build_metrics.events:
      events.append(
        PerfInfoOrEvent(event.name, event.real_time, event.start_time, ''))

  events.sort(key=lambda e: e.start_time)

  def normalize(desc: str) -> str:
    return re.sub(r'^(?:soong_build|mixed_build)', '*', desc)

  return {f'{m.name}/{normalize(m.description)}': util.hhmmss(m.real_time) for m
          in events}


Row = dict[str, any]


def _get_column_headers(rows: list[Row], allow_cycles: bool) -> list[str]:
  """
  Basically a topological sort or column headers. For each Row, the column order
  can be thought of as a partial view of a chain of events in chronological
  order. It's a partial view because not all events may have needed to occur for
  a build.
  """

  @dataclasses.dataclass
  class Column:
    header: str
    indegree: int
    nexts: set[str]

    def __str__(self):
      return f'#{self.indegree}->{self.header}->{self.nexts}'

    def dfs(self, target: str, visited: set[str] = None) -> list[str]:
      if not visited:
        visited = set()
      if target == self.header and self.header in visited:
        return [self.header]
      for n in self.nexts:
        if n in visited:
          continue
        visited.add(n)
        next_col = all_cols[n]
        path = next_col.dfs(target, visited)
        if path:
          return [self.header, *path]
      return []

  all_cols: dict[str, Column] = {}
  for row in rows:
    prev_col = None
    for col in row:
      if col not in all_cols:
        column = Column(col, 0, set())
        all_cols[col] = column
      if prev_col is not None and col not in prev_col.nexts:
        all_cols[col].indegree += 1
        prev_col.nexts.add(col)
      prev_col = all_cols[col]

  acc = []
  entries = [c for c in all_cols.values()]
  while len(entries) > 0:
    # sorting alphabetically to break ties for concurrent events
    entries.sort(key=lambda c: c.header, reverse=True)
    entries.sort(key=lambda c: c.indegree, reverse=True)
    entry = entries.pop()
    # take only one to maintain alphabetical sort
    if entry.indegree != 0:
      cycle = '->'.join(entry.dfs(entry.header))
      s = f'event ordering has a cycle {cycle}'
      logging.warning(s)
      if not allow_cycles:
        raise ValueError(s)
    acc.append(entry.header)
    for n in entry.nexts:
      n = all_cols.get(n)
      if n is not None:
        n.indegree -= 1
      else:
        if not allow_cycles:
          raise ValueError(f'unexpected error for: {n}')
  return acc


def get_build_info_and_perf(d: Path) -> dict[str, any]:
  perf = read_pbs(d)
  build_info_json = d.joinpath(util.BUILD_INFO_JSON)
  if not build_info_json.exists():
    return perf
  with open(build_info_json, 'r') as f:
    build_info = json.load(f)
    return build_info | perf


def tabulate_metrics_csv(log_dir: Path):
  rows: list[dict[str, any]] = []
  dirs = glob.glob(f'{util.RUN_DIR_PREFIX}*', root_dir=log_dir)
  dirs.sort(key=lambda x: int(x[1 + len(util.RUN_DIR_PREFIX):]))
  for d in dirs:
    d = log_dir.joinpath(d)
    row = get_build_info_and_perf(d)
    rows.append(row)

  headers: list[str] = _get_column_headers(rows, allow_cycles=True)

  def row2line(r):
    return ','.join([str(r.get(col) or '') for col in headers])

  lines = [','.join(headers)]
  lines.extend(row2line(r) for r in rows)

  with open(log_dir.joinpath(util.METRICS_TABLE), mode='wt') as f:
    f.writelines(f'{line}\n' for line in lines)


def display_tabulated_metrics(log_dir: Path):
  cmd_str = util.get_cmd_to_display_tabulated_metrics(log_dir)
  output = subprocess.check_output(cmd_str, shell=True, text=True)
  logging.info(textwrap.dedent(f'''
  %s
  TIPS:
  1 To view key metrics in metrics.csv:
    %s
  2 To view column headers:
    %s
    '''), output, cmd_str, util.get_csv_columns_cmd(log_dir))
