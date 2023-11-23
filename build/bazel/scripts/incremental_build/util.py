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
import functools
import glob
import logging
import os
import re
import subprocess
import sys
from datetime import date
from pathlib import Path
from typing import Final
from typing import Generator

INDICATOR_FILE: Final[str] = 'build/soong/soong_ui.bash'
METRICS_TABLE: Final[str] = 'metrics.csv'
SUMMARY_TABLE: Final[str] = 'summary.csv'
RUN_DIR_PREFIX: Final[str] = 'run'
BUILD_INFO_JSON: Final[str] = 'build_info.json'


@functools.cache
def _is_important(column) -> bool:
  patterns = {
      'description', 'build_type', r'build\.ninja(\.size)?', 'targets',
      'log', 'actions', 'time',
      'soong/soong', 'bp2build/', 'symlink_forest/', r'soong_build/\*',
      r'soong_build/\*\.bazel', 'bp2build/', 'kati/kati build', 'ninja/ninja'
      }
  for pattern in patterns:
    if re.fullmatch(pattern, column):
      return True
  return False


def get_csv_columns_cmd(d: Path) -> str:
  """
  :param d: the log directory
  :return: a quick shell command to view columns in metrics.csv
  """
  csv_file = d.joinpath(METRICS_TABLE)
  return f'head -n 1 "{csv_file.absolute()}" | sed "s/,/\\n/g" | nl'


def get_cmd_to_display_tabulated_metrics(d: Path) -> str:
  """
  :param d: the log directory
  :return: a quick shell command to view some collected metrics
  """
  csv_file = d.joinpath(METRICS_TABLE)
  headers: list[str] = []
  if csv_file.exists():
    with open(csv_file) as r:
      reader = csv.DictReader(r)
      headers = reader.fieldnames or []

  columns: list[int] = [i for i, h in enumerate(headers) if _is_important(h)]
  f = ','.join(str(i + 1) for i in columns)
  return f'grep -v rebuild- "{csv_file}" | grep -v FAILED | ' \
         f'cut -d, -f{f} | column -t -s,'


@functools.cache
def get_top_dir(d: Path = Path('.').absolute()) -> Path:
  """Get the path to the root of the Android source tree"""
  top_dir = os.environ.get('ANDROID_BUILD_TOP')
  if top_dir:
    logging.info('ANDROID BUILD TOP = %s', d)
    return Path(top_dir)
  logging.debug('Checking if Android source tree root is %s', d)
  if d.parent == d:
    sys.exit('Unable to find ROOT source directory, specifically,'
             f'{INDICATOR_FILE} not found anywhere. '
             'Try `m nothing` and `repo sync`')
  if d.joinpath(INDICATOR_FILE).is_file():
    logging.info('ANDROID BUILD TOP assumed to be %s', d)
    return d
  return get_top_dir(d.parent)


@functools.cache
def get_out_dir() -> Path:
  out_dir = os.environ.get('OUT_DIR')
  return Path(out_dir) if out_dir else get_top_dir().joinpath('out')


@functools.cache
def get_default_log_dir() -> Path:
  return get_top_dir().parent.joinpath(
      f'timing-{date.today().strftime("%b%d")}')


def is_interactive_shell() -> bool:
  return sys.__stdin__.isatty() and sys.__stdout__.isatty() \
         and sys.__stderr__.isatty()


# see test_next_path_helper() for examples
def _next_path_helper(basename: str) -> str:
  name = re.sub(r'(?<=-)\d+(?=(\..*)?$)', lambda d: str(int(d.group(0)) + 1),
                basename)
  if name == basename:
    name = re.sub(r'(\..*)$', r'-1\1', name, 1)
  if name == basename:
    name = f'{name}-1'
  return name


def next_path(path: Path) -> Generator[Path, None, None]:
  """
  :returns a new Path with an increasing number suffix to the name
  e.g. _to_file('a.txt') = a-5.txt (if a-4.txt already exists)
  """
  path.parent.mkdir(parents=True, exist_ok=True)
  while True:
    name = _next_path_helper(path.name)
    path = path.parent.joinpath(name)
    if not path.exists():
      yield path


def has_uncommitted_changes() -> bool:
  """
  effectively a quick 'repo status' that fails fast
  if any project has uncommitted changes
  """
  for cmd in ['diff', 'diff --staged']:
    diff = subprocess.run(
        args=f'repo forall -c git {cmd} --quiet --exit-code'.split(),
        cwd=get_top_dir(), text=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL)
    if diff.returncode != 0:
      return True
  return False


@functools.cache
def is_ninja_dry_run(ninja_args: str = None) -> bool:
  if ninja_args is None:
    ninja_args = os.environ.get('NINJA_ARGS') or ''
  ninja_dry_run = re.compile(r'(?:^|\s)-n\b')
  return ninja_dry_run.search(ninja_args) is not None


def count_explanations(process_log_file: Path) -> int:
  """
  Builds are run with '-d explain' flag and ninja's explanations for running
  build statements (except for phony outputs) are counted. The explanations
  help debugging. The count is an over-approximation of actions run, but it
  will be ZERO for a no-op build.
  """
  explanations = 0
  pattern = re.compile(
      r'^ninja explain:(?! edge with output .* is a phony output,'
      r' so is always dirty$)')
  with open(process_log_file) as f:
    for line in f:
      if pattern.match(line):
        explanations += 1
  return explanations


def is_git_repo(p: Path) -> bool:
  """checks if p is in a directory that's under git version control"""
  git = subprocess.run(args=f'git remote'.split(), cwd=p,
                       stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)
  return git.returncode == 0


def any_file(pattern: str) -> Path:
  return any_file_under(get_top_dir(), pattern)


def any_file_under(root: Path, pattern: str) -> Path:
  if pattern.startswith('!'):
    raise RuntimeError(f'provide a filename instead of {pattern}')
  d, files = any_match_under(get_top_dir() if root is None else root, pattern)
  files = [d.joinpath(f) for f in files]
  try:
    file = next(f for f in files if f.is_file())
    return file
  except StopIteration:
    raise RuntimeError(f'no file matched {pattern}')


def any_dir_under(root: Path, *patterns: str) -> Path:
  d, _ = any_match_under(root, *patterns)
  return d


def any_match(*patterns: str) -> (Path, list[str]):
  return any_match_under(get_top_dir(), *patterns)


@functools.cache
def any_match_under(root: Path, *patterns: str) -> (Path, list[str]):
  """
  :param patterns glob pattern to match or unmatch if starting with "!"
  :param root the first directory to start searching from
  :returns the dir and sub-paths matching the pattern
  """
  bfs: list[Path] = [root]
  while len(bfs) > 0:
    first = bfs.pop(0)
    if is_git_repo(first):
      matches: list[str] = []
      for pattern in patterns:
        negate = pattern.startswith('!')
        if negate:
          pattern = pattern.removeprefix('!')
        try:
          found_match = next(
              glob.iglob(pattern, root_dir=first, recursive=True))
        except StopIteration:
          found_match = None
        if negate and found_match is not None:
          break
        if not negate:
          if found_match is None:
            break
          else:
            matches.append(found_match)
      else:
        return Path(first), matches

    def should_visit(c: os.DirEntry) -> bool:
      return c.is_dir() and not (c.is_symlink() or
                                 '.' in c.name or
                                 'test' in c.name or
                                 Path(c.path) == get_out_dir())

    children = [Path(c.path) for c in os.scandir(first) if should_visit(c)]
    children.sort()
    bfs.extend(children)
  raise RuntimeError(f'No suitable directory for {patterns}')


def hhmmss(t: datetime.timedelta) -> str:
  """pretty prints time periods, prefers mm:ss.sss and resorts to hh:mm:ss.sss
  only if t >= 1 hour.
  Examples: 02:12.231, 00:00.512, 00:01:11.321, 1:12:13.121
  See unit test for more examples."""
  h, f = divmod(t.seconds, 60 * 60)
  m, f = divmod(f, 60)
  s = f + t.microseconds / 1000_000
  return f'{h}:{m:02d}:{s:06.3f}' if h else f'{m:02d}:{s:06.3f}'


def period_to_seconds(s: str) -> float:
  """converts a time period into seconds. The input is expected to be in the
  format used by hhmmss().
  Example: 02:04.000 -> 125.0
  See unit test for more examples."""
  if s == '':
    return 0.0
  acc = 0.0
  while True:
    [left, *right] = s.split(':', 1)
    acc = acc * 60 + float(left)
    if right:
      s = right[0]
    else:
      return acc
