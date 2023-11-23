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
import csv
import datetime
import logging
import re
import statistics
import subprocess
import textwrap
from pathlib import Path
from typing import Callable

from typing.io import TextIO

import util


def normalize_rebuild(line: dict) -> dict:
  line['description'] = re.sub(r'^(rebuild)-[\d+](.*)$', '\\1\\2',
                               line['description'])
  return line


def groupby(xs: list[dict], keyfn: Callable[[dict], str]) -> dict[
  str, list[dict]]:
  grouped = {}
  for x in xs:
    k = keyfn(x)
    grouped.setdefault(k, []).append(x)
  return grouped


def write_table(out: TextIO, rows: list[list[str]]):
  for r in rows:
    for c in r:
      out.write(str(c) + ',')
    out.write('\n')
  return


def _get_build_types(xs: list[dict]) -> list[str]:
  build_types = []
  for x in xs:
    b = x["build_type"]
    if b not in build_types:
      build_types.append(b)
  return build_types


def summarize_metrics(log_dir: Path):
  filename = log_dir if log_dir.is_file() else log_dir.joinpath(
      util.METRICS_TABLE)
  with open(filename) as f:
    csv_lines = [normalize_rebuild(line) for line in csv.DictReader(f)]

  lines: list[dict] = []
  for line in csv_lines:
    if line["build_result"] == "FAILED":
      logging.warning(f"{line['description']} / {line['build_type']}")
    else:
      lines.append(line)

  build_types = _get_build_types(lines)
  headers = ["cuj", "targets"] + build_types
  rows: list[list[str]] = [headers]

  by_cuj = groupby(lines, lambda l: l["description"])
  for (cuj, cuj_rows) in by_cuj.items():
    for (targets, target_rows) in groupby(cuj_rows,
                                          lambda l: l["targets"]).items():
      row = [cuj, targets]
      by_build_type = groupby(target_rows, lambda l: l["build_type"])
      for build_type in build_types:
        selected_lines = by_build_type.get(build_type)
        if not selected_lines:
          row.append('')
        else:
          times = [util.period_to_seconds(sl['time']) for sl in selected_lines]
          cell = util.hhmmss(
              datetime.timedelta(seconds=statistics.median(times)))
          if len(selected_lines) > 1:
            cell = f'{cell}[N={len(selected_lines)}]'
          row.append(cell)
      rows.append(row)

  with open(log_dir.joinpath(util.SUMMARY_TABLE), mode='wt') as f:
    write_table(f, rows)


def display_summarized_metrics(log_dir: Path):
  f = log_dir.joinpath(util.SUMMARY_TABLE)
  cmd = f'grep -v rebuild {f} | grep -v WARMUP | column -t -s,'
  output = subprocess.check_output(cmd, shell=True, text=True)
  logging.info(textwrap.dedent(f'''
  %s
  TIPS:
  To view condensed summary:
  %s
  '''), output, cmd)
