#!/usr/bin/env python2
#
# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
"""Script for running llvm validation tests on ChromeOS.

This script launches a buildbot to build ChromeOS with the llvm on
a particular board; then it finds and downloads the trybot image and the
corresponding official image, and runs test for correctness.
It then generates a report, emails it to the c-compiler-chrome, as
well as copying the result into a directory.
"""

# Script to test different toolchains against ChromeOS benchmarks.

from __future__ import print_function

import argparse
import datetime
import os
import sys
import time

from cros_utils import command_executer
from cros_utils import logger

from cros_utils import buildbot_utils

CROSTC_ROOT = '/usr/local/google/crostc'
ROLE_ACCOUNT = 'mobiletc-prebuild'
TOOLCHAIN_DIR = os.path.dirname(os.path.realpath(__file__))
MAIL_PROGRAM = '~/var/bin/mail-sheriff'
VALIDATION_RESULT_DIR = os.path.join(CROSTC_ROOT, 'validation_result')
START_DATE = datetime.date(2016, 1, 1)
TEST_PER_DAY = 4
DATA_DIR = '/google/data/rw/users/mo/mobiletc-prebuild/waterfall-report-data/'

# Information about Rotating Boards
#  Board        Arch     Reference    Platform      Kernel
#                        Board                      Version
#  ------------ -------  ------------ ------------- -------
#  cave         x86_64   glados       skylake-y     3.18
#  daisy        armv7    daisy        exynos-5250   3.8.11
#  elm          aarch64  oak          mediatek-8173 3.18
#  fizz         x86_64   fizz         kabylake-u/r  4.4.*
#  gale         armv7                               3.18
#  grunt        x86_64   grunt        stoney ridge  4.14.*
#  guado_moblab x86_64                              3.14
#  kevin        aarch64  gru          rockchip-3399 4.4.*
#  lakitu       x86_64                              4.4.*
#  lars         x86_64   kunimitsu    skylake-u     3.18
#  link         x86_64   ivybridge    ivybridge     3.8.11
#  nautilus     x86_64   poppy        kabylake-y    4.4.*
#  nyan_big     armv7    nyan         tegra         3.10.18
#  peach_pit    armv7    peach        exynos-5420   3.8.11
#  peppy        x86_64   slippy       haswell       3.8.11
#  samus        x86_64   auron        broadwell     3.14
#  snappy       x86_64   reef         apollo lake   4.4.*
#  swanky       x86_64   rambi        baytrail      4.4.*
#  terra        x86_64   strago       braswell      3.18
#  veyron_jaq   armv7    veyron-pinky rockchip-3288 3.14
#  whirlwind    armv7                               3.14
#  zoombini     x86_64   zoombini     cannonlake-y  4.14.*

TEST_BOARD = [
    'cave',
    'daisy',
    # 'elm', tested by arm64-llvm-next-toolchain builder.
    'fizz',
    'gale',
    'grunt',
    'guado_moblab',
    'kevin',
    'lakitu',
    'lars',
    'link',
    'nautilus',
    'nyan_big',
    'peach_pit',
    'peppy',
    # 'samus', tested by amd64-llvm-next-toolchain builder.
    'snappy',
    'swanky',
    'terra',
    # 'veyron_jaq', tested by arm-llvm-next-toolchain builder.
    'whirlwind',
    'zoombini',
]


class ToolchainVerifier(object):
  """Class for the toolchain verifier."""

  def __init__(self, board, chromeos_root, weekday, patches, compiler):
    self._board = board
    self._chromeos_root = chromeos_root
    self._base_dir = os.getcwd()
    self._ce = command_executer.GetCommandExecuter()
    self._l = logger.GetLogger()
    self._compiler = compiler
    self._build = '%s-%s-toolchain-tryjob' % (board, compiler)
    self._patches = patches.split(',') if patches else []
    self._patches_string = '_'.join(str(p) for p in self._patches)

    if not weekday:
      self._weekday = time.strftime('%a')
    else:
      self._weekday = weekday
    self._reports = os.path.join(VALIDATION_RESULT_DIR, compiler, board)

  def DoAll(self):
    """Main function inside ToolchainComparator class.

    Launch trybot, get image names, create crosperf experiment file, run
    crosperf, and copy images into seven-day report directories.
    """
    buildbucket_id, _ = buildbot_utils.GetTrybotImage(
        self._chromeos_root,
        self._build,
        self._patches,
        tryjob_flags=['--hwtest'],
        async=True)

    return buildbucket_id


def WriteRotatingReportsData(results_dict, date):
  """Write data for waterfall report."""
  fname = '%d-%02d-%02d.builds' % (date.year, date.month, date.day)
  filename = os.path.join(DATA_DIR, 'rotating-builders', fname)
  with open(filename, 'w') as out_file:
    for board in results_dict.keys():
      buildbucket_id = results_dict[board]
      out_file.write('%s,%s\n' % (buildbucket_id, board))


def Main(argv):
  """The main function."""

  # Common initializations
  command_executer.InitCommandExecuter()
  parser = argparse.ArgumentParser()
  parser.add_argument(
      '--chromeos_root',
      dest='chromeos_root',
      help='The chromeos root from which to run tests.')
  parser.add_argument(
      '--weekday',
      default='',
      dest='weekday',
      help='The day of the week for which to run tests.')
  parser.add_argument(
      '--board', default='', dest='board', help='The board to test.')
  parser.add_argument(
      '--patch',
      dest='patches',
      default='',
      help='The patches to use for the testing, '
      "seprate the patch numbers with ',' "
      'for more than one patches.')
  parser.add_argument(
      '--compiler',
      dest='compiler',
      help='Which compiler (llvm, llvm-next or gcc) to use for '
      'testing.')

  options = parser.parse_args(argv[1:])
  if not options.chromeos_root:
    print('Please specify the ChromeOS root directory.')
    return 1
  if not options.compiler:
    print('Please specify which compiler to test (gcc, llvm, or llvm-next).')
    return 1

  if options.board:
    fv = ToolchainVerifier(options.board, options.chromeos_root,
                           options.weekday, options.patches, options.compiler)
    return fv.Doall()

  today = datetime.date.today()
  delta = today - START_DATE
  days = delta.days

  start_board = (days * TEST_PER_DAY) % len(TEST_BOARD)
  results_dict = dict()
  for i in range(TEST_PER_DAY):
    try:
      board = TEST_BOARD[(start_board + i) % len(TEST_BOARD)]
      fv = ToolchainVerifier(board, options.chromeos_root, options.weekday,
                             options.patches, options.compiler)
      buildbucket_id = fv.DoAll()
      if buildbucket_id:
        results_dict[board] = buildbucket_id
    except SystemExit:
      logfile = os.path.join(VALIDATION_RESULT_DIR, options.compiler, board)
      with open(logfile, 'w') as f:
        f.write('Verifier got an exception, please check the log.\n')
  WriteRotatingReportsData(results_dict, today)


if __name__ == '__main__':
  retval = Main(sys.argv)
  sys.exit(retval)
