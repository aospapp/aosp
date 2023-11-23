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
import enum
import functools
import io
import logging
import os
import shutil
import tempfile
import textwrap
import uuid
from enum import Enum
from pathlib import Path
from typing import Callable, Optional
from typing import Final
from typing import TypeAlias

import util
import ui

"""
Provides some representative CUJs. If you wanted to manually run something but
would like the metrics to be collated in the metrics.csv file, use
`perf_metrics.py` as a stand-alone after your build.
"""


class BuildResult(Enum):
  SUCCESS = enum.auto()
  FAILED = enum.auto()
  TEST_FAILURE = enum.auto()


Action: TypeAlias = Callable[[], None]
Verifier: TypeAlias = Callable[[], None]


def skip_when_soong_only(func: Verifier) -> Verifier:
  """A decorator for Verifiers that are not applicable to soong-only builds"""

  def wrapper():
    if InWorkspace.ws_counterpart(util.get_top_dir()).exists():
      func()

  return wrapper


@skip_when_soong_only
def verify_symlink_forest_has_only_symlink_leaves():
  """Verifies that symlink forest has only symlinks or directories but no
  files except for merged BUILD.bazel files"""

  top_in_ws = InWorkspace.ws_counterpart(util.get_top_dir())

  for root, dirs, files in os.walk(top_in_ws, topdown=True, followlinks=False):
    for file in files:
      if file == 'symlink_forest_version' and top_in_ws.samefile(root):
        continue
      f = Path(root).joinpath(file)
      if file != 'BUILD.bazel' and not f.is_symlink():
        raise AssertionError(f'{f} unexpected')

  logging.info('VERIFIED Symlink Forest has no real files except BUILD.bazel')


@dataclasses.dataclass(frozen=True)
class CujStep:
  verb: str
  """a human-readable description"""
  apply_change: Action
  """user action(s) that are performed prior to a build attempt"""
  verify: Verifier = verify_symlink_forest_has_only_symlink_leaves
  """post-build assertions, i.e. tests.
  Should raise `Exception` for failures.
  """


@dataclasses.dataclass(frozen=True)
class CujGroup:
  """A sequence of steps to be performed, such that at the end of all steps the
  initial state of the source tree is attained.
  NO attempt is made to achieve atomicity programmatically. It is left as the
  responsibility of the user.
  """
  description: str
  steps: list[CujStep]

  def __str__(self) -> str:
    if len(self.steps) < 2:
      return f'{self.steps[0].verb} {self.description}'.strip()
    return ' '.join(
        [f'({chr(ord("a") + i)}) {step.verb} {self.description}'.strip() for
         i, step in enumerate(self.steps)])


Warmup: Final[CujGroup] = CujGroup('WARMUP',
                                   [CujStep('no change', lambda: None)])


class InWorkspace(Enum):
  """For a given file in the source tree, the counterpart in the symlink forest
   could be one of these kinds.
  """
  SYMLINK = enum.auto()
  NOT_UNDER_SYMLINK = enum.auto()
  UNDER_SYMLINK = enum.auto()
  OMISSION = enum.auto()

  @staticmethod
  def ws_counterpart(src_path: Path) -> Path:
    return util.get_out_dir().joinpath('soong/workspace').joinpath(
        de_src(src_path))

  def verifier(self, src_path: Path) -> Verifier:
    @skip_when_soong_only
    def f():
      ws_path = InWorkspace.ws_counterpart(src_path)
      actual: Optional[InWorkspace] = None
      if ws_path.is_symlink():
        actual = InWorkspace.SYMLINK
        if not ws_path.exists():
          logging.warning('Dangling symlink %s', ws_path)
      elif not ws_path.exists():
        actual = InWorkspace.OMISSION
      else:
        for p in ws_path.parents:
          if not p.is_relative_to(util.get_out_dir()):
            actual = InWorkspace.NOT_UNDER_SYMLINK
            break
          if p.is_symlink():
            actual = InWorkspace.UNDER_SYMLINK
            break

      if self != actual:
        raise AssertionError(
            f'{ws_path} expected {self.name} but got {actual.name}')
      logging.info(f'VERIFIED {de_src(ws_path)} {self.name}')

    return f


def de_src(p: Path) -> str:
  return str(p.relative_to(util.get_top_dir()))


def src(p: str) -> Path:
  return util.get_top_dir().joinpath(p)


def modify_revert(file: Path, text: str = '//BOGUS line\n') -> CujGroup:
  """
  :param file: the file to be modified and reverted
  :param text: the text to be appended to the file to modify it
  :return: A pair of CujSteps, where the first modifies the file and the
  second reverts the modification
  """
  if not file.exists():
    raise RuntimeError(f'{file} does not exist')

  def add_line():
    with open(file, mode="a") as f:
      f.write(text)

  def revert():
    with open(file, mode="rb+") as f:
      # assume UTF-8
      f.seek(-len(text), io.SEEK_END)
      f.truncate()

  return CujGroup(de_src(file), [
      CujStep('modify', add_line),
      CujStep('revert', revert)
  ])


def create_delete(file: Path, ws: InWorkspace,
    text: str = '//Test File: safe to delete\n') -> CujGroup:
  """
  :param file: the file to be created and deleted
  :param ws: the expectation for the counterpart file in symlink
  forest (aka the synthetic bazel workspace) when its created
  :param text: the content of the file
  :return: A pair of CujSteps, where the fist creates the file and the
  second deletes it
  """
  missing_dirs = [f for f in file.parents if not f.exists()]
  shallowest_missing_dir = missing_dirs[-1] if len(missing_dirs) else None

  def create():
    if file.exists():
      raise RuntimeError(
          f'File {file} already exists. Interrupted an earlier run?\n'
          'TIP: `repo status` and revert changes!!!')
    file.parent.mkdir(parents=True, exist_ok=True)
    file.touch(exist_ok=False)
    with open(file, mode="w") as f:
      f.write(text)

  def delete():
    if shallowest_missing_dir:
      shutil.rmtree(shallowest_missing_dir)
    else:
      file.unlink(missing_ok=False)

  return CujGroup(de_src(file), [
      CujStep('create', create, ws.verifier(file)),
      CujStep('delete', delete, InWorkspace.OMISSION.verifier(file)),
  ])


def create_delete_bp(bp_file: Path) -> CujGroup:
  """
  This is basically the same as "create_delete" but with canned content for
  an Android.bp file.
  """
  return create_delete(
      bp_file, InWorkspace.SYMLINK,
      'filegroup { name: "test-bogus-filegroup", srcs: ["**/*.md"] }')


def delete_restore(original: Path, ws: InWorkspace) -> CujGroup:
  """
  :param original: The file to be deleted then restored
  :param ws: When restored, expectation for the file's counterpart in the
  symlink forest (aka synthetic bazel workspace)
  :return: A pair of CujSteps, where the first deletes a file and the second
  restores it
  """
  tempdir = Path(tempfile.gettempdir())
  if tempdir.is_relative_to(util.get_top_dir()):
    raise SystemExit(f'Temp dir {tempdir} is under source tree')
  if tempdir.is_relative_to(util.get_out_dir()):
    raise SystemExit(f'Temp dir {tempdir} is under '
                     f'OUT dir {util.get_out_dir()}')
  copied = tempdir.joinpath(f'{original.name}-{uuid.uuid4()}.bak')

  def move_to_tempdir_to_mimic_deletion():
    logging.warning('MOVING %s TO %s', de_src(original), copied)
    original.rename(copied)

  return CujGroup(de_src(original), [
      CujStep('delete',
              move_to_tempdir_to_mimic_deletion,
              InWorkspace.OMISSION.verifier(original)),
      CujStep('restore',
              lambda: copied.rename(original),
              ws.verifier(original))
  ])


def replace_link_with_dir(p: Path):
  """Create a file, replace it with a non-empty directory, delete it"""
  cd = create_delete(p, InWorkspace.SYMLINK)
  create_file: CujStep
  delete_file: CujStep
  create_file, delete_file, *tail = cd.steps
  assert len(tail) == 0

  # an Android.bp is always a symlink in the workspace and thus its parent
  # will be a directory in the workspace
  create_dir: CujStep
  delete_dir: CujStep
  create_dir, delete_dir, *tail = create_delete_bp(
      p.joinpath('Android.bp')).steps
  assert len(tail) == 0

  def replace_it():
    delete_file.apply_change()
    create_dir.apply_change()

  return CujGroup(cd.description, [
      create_file,
      CujStep(f'{de_src(p)}/Android.bp instead of',
              replace_it,
              create_dir.verify),
      delete_dir
  ])


def _sequence(*vs: Verifier) -> Verifier:
  def f():
    for v in vs:
      v()

  return f


def content_verfiers(
    ws_build_file: Path, content: str) -> (Verifier, Verifier):
  def search() -> bool:
    with open(ws_build_file, "r") as f:
      for line in f:
        if line == content:
          return True
    return False

  @skip_when_soong_only
  def contains():
    if not search():
      raise AssertionError(
          f'{de_src(ws_build_file)} expected to contain {content}')
    logging.info(f'VERIFIED {de_src(ws_build_file)} contains {content}')

  @skip_when_soong_only
  def does_not_contain():
    if search():
      raise AssertionError(
          f'{de_src(ws_build_file)} not expected to contain {content}')
    logging.info(f'VERIFIED {de_src(ws_build_file)} does not contain {content}')

  return contains, does_not_contain


def modify_revert_kept_build_file(build_file: Path) -> CujGroup:
  content = f'//BOGUS {uuid.uuid4()}\n'
  step1, step2, *tail = modify_revert(build_file, content).steps
  assert len(tail) == 0
  ws_build_file = InWorkspace.ws_counterpart(build_file).with_name(
      'BUILD.bazel')
  merge_prover, merge_disprover = content_verfiers(ws_build_file, content)
  return CujGroup(de_src(build_file), [
      CujStep(step1.verb,
              step1.apply_change,
              _sequence(step1.verify, merge_prover)),
      CujStep(step2.verb,
              step2.apply_change,
              _sequence(step2.verify, merge_disprover))
  ])


def create_delete_kept_build_file(build_file: Path) -> CujGroup:
  content = f'//BOGUS {uuid.uuid4()}\n'
  ws_build_file = InWorkspace.ws_counterpart(build_file).with_name(
      'BUILD.bazel')
  if build_file.name == 'BUILD.bazel':
    ws = InWorkspace.NOT_UNDER_SYMLINK
  elif build_file.name == 'BUILD':
    ws = InWorkspace.SYMLINK
  else:
    raise RuntimeError(f'Illegal name for a build file {build_file}')

  merge_prover, merge_disprover = content_verfiers(ws_build_file, content)

  step1: CujStep
  step2: CujStep
  step1, step2, *tail = create_delete(build_file, ws, content).steps
  assert len(tail) == 0
  return CujGroup(de_src(build_file), [
      CujStep(step1.verb,
              step1.apply_change,
              _sequence(step1.verify, merge_prover)),
      CujStep(step2.verb,
              step2.apply_change,
              _sequence(step2.verify, merge_disprover))
  ])


def create_delete_unkept_build_file(build_file) -> CujGroup:
  content = f'//BOGUS {uuid.uuid4()}\n'
  ws_build_file = InWorkspace.ws_counterpart(build_file).with_name(
      'BUILD.bazel')
  step1: CujStep
  step2: CujStep
  step1, step2, *tail = create_delete(
      build_file, InWorkspace.SYMLINK, content).steps
  assert len(tail) == 0
  _, merge_disprover = content_verfiers(ws_build_file, content)
  return CujGroup(de_src(build_file), [
      CujStep(step1.verb,
              step1.apply_change,
              _sequence(step1.verify, merge_disprover)),
      CujStep(step2.verb,
              step2.apply_change,
              _sequence(step2.verify, merge_disprover))
  ])


NON_LEAF = '*/*'
"""If `a/*/*` is a valid path `a` is not a leaf directory"""
LEAF = '!*/*'
"""If `a/*/*` is not a valid path `a` is a leaf directory, i.e. has no other
non-empty sub-directories"""
PKG = ['Android.bp', '!BUILD', '!BUILD.bazel']
"""limiting the candidate to Android.bp file with no sibling bazel files"""
PKG_FREE = ['!**/Android.bp', '!**/BUILD', '!**/BUILD.bazel']
"""no Android.bp or BUILD or BUILD.bazel file anywhere"""


def _kept_build_cujs() -> list[CujGroup]:
  # Bp2BuildKeepExistingBuildFile(build/bazel) is True(recursive)
  kept = src('build/bazel')
  pkg = util.any_dir_under(kept, *PKG)
  examples = [pkg.joinpath('BUILD'),
              pkg.joinpath('BUILD.bazel')]

  return [
      *[create_delete_kept_build_file(build_file) for build_file in examples],
      create_delete(pkg.joinpath('BUILD/kept-dir'), InWorkspace.SYMLINK),
      modify_revert_kept_build_file(util.any_file_under(kept, 'BUILD'))]


def _unkept_build_cujs() -> list[CujGroup]:
  # Bp2BuildKeepExistingBuildFile(bionic) is False(recursive)
  unkept = src('bionic')
  pkg = util.any_dir_under(unkept, *PKG)
  return [
      *[create_delete_unkept_build_file(build_file) for build_file in [
          pkg.joinpath('BUILD'),
          pkg.joinpath('BUILD.bazel'),
      ]],
      *[create_delete(build_file, InWorkspace.OMISSION) for build_file in [
          unkept.joinpath('bogus-unkept/BUILD'),
          unkept.joinpath('bogus-unkept/BUILD.bazel'),
      ]],
      create_delete(pkg.joinpath('BUILD/unkept-dir'), InWorkspace.SYMLINK)
  ]


@functools.cache
def get_cujgroups() -> list[CujGroup]:
  # we are choosing "package" directories that have Android.bp but
  # not BUILD nor BUILD.bazel because
  # we can't tell if ShouldKeepExistingBuildFile would be True or not
  pkg, p_why = util.any_match(NON_LEAF, *PKG)
  pkg_free, f_why = util.any_match(NON_LEAF, *PKG_FREE)
  leaf_pkg_free, _ = util.any_match(LEAF, *PKG_FREE)
  ancestor, a_why = util.any_match('!Android.bp', '!BUILD', '!BUILD.bazel',
                                   '**/Android.bp')
  logging.info(textwrap.dedent(f'''Choosing:
            package: {de_src(pkg)} has {p_why}
   package ancestor: {de_src(ancestor)} has {a_why} but no direct Android.bp
       package free: {de_src(pkg_free)} has {f_why} but no Android.bp anywhere
  leaf package free: {de_src(leaf_pkg_free)} has neither Android.bp nor sub-dirs
  '''))

  android_bp_cujs = [
      modify_revert(src('Android.bp')),
      *[create_delete_bp(d.joinpath('Android.bp')) for d in
        [ancestor, pkg_free, leaf_pkg_free]]
  ]
  mixed_build_launch_cujs = [
      modify_revert(src('bionic/libc/tzcode/asctime.c')),
      modify_revert(src('bionic/libc/stdio/stdio.cpp')),
      modify_revert(src('packages/modules/adb/daemon/main.cpp')),
      modify_revert(src('frameworks/base/core/java/android/view/View.java')),
  ]
  unreferenced_file_cujs = [
      *[create_delete(d.joinpath('unreferenced.txt'), InWorkspace.SYMLINK) for
        d in [ancestor, pkg]],
      *[create_delete(d.joinpath('unreferenced.txt'), InWorkspace.UNDER_SYMLINK)
        for d
        in [pkg_free, leaf_pkg_free]]
  ]

  def clean():
    if ui.get_user_input().log_dir.is_relative_to(util.get_top_dir()):
      raise AssertionError(
          f'specify a different LOG_DIR: {ui.get_user_input().log_dir}')
    if util.get_out_dir().exists():
      shutil.rmtree(util.get_out_dir())

  return [
      CujGroup('', [CujStep('clean', clean)]),
      Warmup,

      create_delete(src('bionic/libc/tzcode/globbed.c'),
                    InWorkspace.UNDER_SYMLINK),

      # TODO (usta): find targets that should be affected
      *[delete_restore(f, InWorkspace.SYMLINK) for f in [
          util.any_file('version_script.txt'),
          util.any_file('AndroidManifest.xml')]],

      *unreferenced_file_cujs,
      *mixed_build_launch_cujs,
      *android_bp_cujs,
      *_unkept_build_cujs(),
      *_kept_build_cujs(),
      replace_link_with_dir(pkg.joinpath('bogus.txt')),
      # TODO(usta): add a dangling symlink
  ]
