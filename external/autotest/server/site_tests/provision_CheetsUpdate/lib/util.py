# Copyright (C) 2016 The Android Open-Source Project
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

"""Various utility functions"""

import logging
import os
import shlex
import subprocess


# The default location in which symbols and minidumps will be saved.
_DEFAULT_ARTIFACT_CACHE_ROOT = os.environ.get('ARC_ARTIFACT_CACHE_ROOT',
                                              '/tmp/arc-artifact-cache')


def get_command_str(command):
  """Returns a quoted version of the command, friendly to copy/paste."""
  return ' '.join(shlex.quote(arg) for arg in command)


def check_call(*subprocess_args, dryrun=False, **kwargs):
  """Runs a subprocess and returns its exit code."""
  if logging.getLogger().isEnabledFor(logging.DEBUG):
    logging.debug('Calling: %s', get_command_str(subprocess_args))
  if dryrun:
    return
  try:
    return subprocess.check_call(subprocess_args, **kwargs)
  except subprocess.CalledProcessError as e:
    logging.error('Error while executing %s', get_command_str(subprocess_args))
    logging.error(e.output)
    raise


def check_output(*subprocess_args, dryrun=False, **kwargs):
  """Runs a subprocess and returns its output."""
  if logging.getLogger().isEnabledFor(logging.DEBUG):
    logging.debug('Calling: %s', get_command_str(subprocess_args))
  if dryrun:
    logging.info('Cannot return any output without running the command. '
                 'Returning an empty string instead.')
    return ''
  try:
    return subprocess.check_output(subprocess_args, universal_newlines=True,
                                   **kwargs)
  except subprocess.CalledProcessError as e:
    logging.error('Error while executing %s', get_command_str(subprocess_args))
    logging.error(e.output)
    raise


def makedirs(path):
  """Makes directories if necessary, like 'mkdir -p'"""
  if not os.path.exists(path):
    os.makedirs(path)


def get_prebuilt(tool):
  """Locates a prebuilt file to run."""
  return os.path.abspath(os.path.join(
      os.path.dirname(os.path.dirname(__file__)), 'prebuilt/x86-linux/', tool))


def helper_temp_path(*path, artifact_cache_root=_DEFAULT_ARTIFACT_CACHE_ROOT):
  """Returns the path to use for temporary/cached files."""
  return os.path.join(artifact_cache_root, *path)


def get_product_arch(product):
  """Returns the architecture of a given target |product|."""
  # The prefix can itself have other prefixes, like 'generic_' or 'aosp_'.
  product_prefix = 'cheets_'

  idx = product.index(product_prefix)
  assert idx >= 0, 'Unrecognized product name: %s' % product
  return product[idx + len(product_prefix):]
