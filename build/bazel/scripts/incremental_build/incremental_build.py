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

"""
A tool for running builds (soong or b) and measuring the time taken.
"""
import datetime
import functools
import hashlib
import logging
import os
import subprocess
import sys
import textwrap
import time
from pathlib import Path
from typing import Final
from typing import Mapping

import cuj_catalog
import perf_metrics
import pretty
import ui
import util

MAX_RUN_COUNT: int = 5


@functools.cache
def _prepare_env() -> (Mapping[str, str], str):
  def get_soong_build_ninja_args():
    ninja_args = os.environ.get('NINJA_ARGS') or ''
    if ninja_args != '':
      ninja_args += ' '
    ninja_args += '-d explain --quiet'
    if util.is_ninja_dry_run(ninja_args):
      global MAX_RUN_COUNT
      MAX_RUN_COUNT = 1
      logging.warning(f'Running dry ninja runs NINJA_ARGS={ninja_args}')
    return ninja_args

  def get_soong_ui_ninja_args():
    soong_ui_ninja_args = os.environ.get('SOONG_UI_NINJA_ARGS') or ''
    if util.is_ninja_dry_run(soong_ui_ninja_args):
      sys.exit('"-n" in SOONG_UI_NINJA_ARGS would not update build.ninja etc')

    if soong_ui_ninja_args != '':
      soong_ui_ninja_args += ' '
    soong_ui_ninja_args += '-d explain --quiet'
    return soong_ui_ninja_args

  overrides: Mapping[str, str] = {
    'NINJA_ARGS': get_soong_build_ninja_args(),
    'SOONG_UI_NINJA_ARGS': get_soong_ui_ninja_args()
  }
  env = {**os.environ, **overrides}
  # TODO: Switch to oriole when it works
  default_product: Final[str] = 'cf_x86_64_phone' \
    if util.get_top_dir().joinpath('vendor/google/build').exists() \
    else 'aosp_cf_x86_64_phone'
  target_product = os.environ.get('TARGET_PRODUCT') or default_product
  variant = os.environ.get('TARGET_BUILD_VARIANT') or 'eng'

  if target_product != default_product or variant != 'eng':
    if util.is_interactive_shell():
      response = input(f'Are you sure you want {target_product}-{variant} '
                       f'and not {default_product}-eng? [Y/n]')
      if response.upper() != 'Y':
        sys.exit(1)
    else:
      logging.warning(
        f'Using {target_product}-{variant} instead of {default_product}-eng')
  env['TARGET_PRODUCT'] = target_product
  env['TARGET_BUILD_VARIANT'] = variant
  pretty_env_str = [f'{k}={v}' for (k, v) in env.items()]
  pretty_env_str.sort()
  return env, '\n'.join(pretty_env_str)


def _build_file_sha() -> str:
  build_file = util.get_out_dir().joinpath('soong/build.ninja')
  if not build_file.exists():
    return '--'
  with open(build_file, mode="rb") as f:
    h = hashlib.sha256()
    for block in iter(lambda: f.read(4096), b''):
      h.update(block)
    return h.hexdigest()[0:8]


def _build_file_size() -> int:
  build_file = util.get_out_dir().joinpath('soong/build.ninja')
  return os.path.getsize(build_file) if build_file.exists() else 0


BuildInfo = dict[str, any]


def _build(build_type: ui.BuildType, run_dir: Path) -> (int, BuildInfo):
  logfile = run_dir.joinpath('output.txt')
  logging.info('TIP: to see the log:\n  tail -f "%s"', logfile)
  cmd = [*build_type.value, *ui.get_user_input().targets]
  logging.info('Command: %s', cmd)
  env, env_str = _prepare_env()
  ninja_log_file = util.get_out_dir().joinpath('.ninja_log')

  def get_action_count() -> int:
    if not ninja_log_file.exists():
      return 0
    with open(ninja_log_file, 'r') as ninja_log:
      # subtracting 1 to account for "# ninja log v5" in the first line
      return sum(1 for _ in ninja_log) - 1

  def recompact_ninja_log():
    subprocess.run([
      util.get_top_dir().joinpath(
        'prebuilts/build-tools/linux-x86/bin/ninja'),
      '-f',
      util.get_out_dir().joinpath(
        f'combined-{env.get("TARGET_PRODUCT", "aosp_arm")}.ninja'),
      '-t', 'recompact'],
      check=False, cwd=util.get_top_dir(), shell=False,
      stdout=f, stderr=f)

  with open(logfile, mode='w') as f:
    action_count_before = get_action_count()
    if action_count_before > 0:
      recompact_ninja_log()
      action_count_before = get_action_count()
    f.write(f'Command: {cmd}\n')
    f.write(f'Environment Variables:\n{textwrap.indent(env_str, "  ")}\n\n\n')
    f.flush()
    start_ns = time.perf_counter_ns()
    p = subprocess.run(cmd, check=False, cwd=util.get_top_dir(), env=env,
                       shell=False, stdout=f, stderr=f)
    elapsed_ns = time.perf_counter_ns() - start_ns
    action_count_after = get_action_count()

  return (p.returncode, {
    'build_type': build_type.to_flag(),
    'build.ninja': _build_file_sha(),
    'build.ninja.size': _build_file_size(),
    'targets': ' '.join(ui.get_user_input().targets),
    'log': str(run_dir.relative_to(ui.get_user_input().log_dir)),
    'ninja_explains': util.count_explanations(logfile),
    'actions': action_count_after - action_count_before,
    'time': util.hhmmss(datetime.timedelta(microseconds=elapsed_ns / 1000))
  })


def _run_cuj(run_dir: Path, build_type: ui.BuildType,
    cujstep: cuj_catalog.CujStep, desc: str, run) -> BuildInfo:
  run_dir.mkdir(parents=True, exist_ok=False)
  (exit_code, build_info) = _build(build_type, run_dir)
  # if build was successful, run test
  if exit_code != 0:
    build_result = cuj_catalog.BuildResult.FAILED.name
  else:
    try:
      cujstep.verify()
      build_result = cuj_catalog.BuildResult.SUCCESS.name
    except Exception as e:
      logging.error(e)
      build_result = (cuj_catalog.BuildResult.TEST_FAILURE.name +
                      ':' + str(e))
  # summarize
  log_desc = desc if run == 0 else f'rebuild-{run} after {desc}'
  build_info = {
                 'description': log_desc,
                 'build_result': build_result
               } | build_info
  logging.info('%s after %s: %s',
               build_info["build_result"], build_info["time"], log_desc)
  return build_info


def main():
  """
  Run provided target(s) under various CUJs and collect metrics.
  In pseudocode:
    time build <target> with m or b
    collect metrics
    for each cuj:
        make relevant changes
        time rebuild
        collect metrics
        revert those changes
        time rebuild
        collect metrics
  """
  user_input = ui.get_user_input()

  logging.warning(textwrap.dedent('''
  If you kill this process, make sure to revert unwanted changes.
  TIP: If you have no local changes of interest you may
       `repo forall -p -c git reset --hard`  and
       `repo forall -p -c git clean --force` and even
       `m clean && rm -rf out`
  '''))

  run_dir_gen = util.next_path(user_input.log_dir.joinpath(util.RUN_DIR_PREFIX))

  def run_cuj_group(cuj_group: cuj_catalog.CujGroup):
    for cujstep in cuj_group.steps:
      desc = cujstep.verb
      desc = f'{desc} {cuj_group.description}'.strip()
      desc = f'{desc} {user_input.description}'.strip()
      logging.info('START %s %s [%s]', build_type.name,
                   ' '.join(user_input.targets), desc)
      cujstep.apply_change()
      for run in range(0, MAX_RUN_COUNT):
        run_dir = next(run_dir_gen)
        build_info = _run_cuj(run_dir, build_type, cujstep, desc, run)
        perf_metrics.archive_run(run_dir, build_info)
        if build_info['ninja_explains'] == 0:
          break
      logging.info(' DONE %s %s [%s]', build_type.name,
                   ' '.join(user_input.targets), desc)

  for build_type in user_input.build_types:
    # warm-up run reduces variations attributable to OS caches
    run_cuj_group(cuj_catalog.Warmup)
    for i in user_input.chosen_cujgroups:
      run_cuj_group(cuj_catalog.get_cujgroups()[i])

  perf_metrics.tabulate_metrics_csv(user_input.log_dir)
  perf_metrics.display_tabulated_metrics(user_input.log_dir)
  pretty.summarize_metrics(user_input.log_dir)
  pretty.display_summarized_metrics(user_input.log_dir)


if __name__ == '__main__':
  logging.root.setLevel(logging.INFO)
  main()
