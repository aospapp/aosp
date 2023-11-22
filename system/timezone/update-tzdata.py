#!/usr/bin/python -B

# Copyright 2017 The Android Open Source Project
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

"""Generates the timezone data files used by Android."""

import glob
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

sys.path.append('%s/external/icu/tools' % os.environ.get('ANDROID_BUILD_TOP'))
import i18nutil
import icuutil
import tzdatautil

regions = ['africa', 'antarctica', 'asia', 'australasia',
           'etcetera', 'europe', 'northamerica', 'southamerica',
           # These two deliberately come last so they override what came
           # before (and each other).
           'backward', 'backzone' ]

# Calculate the paths that are referred to by multiple functions.
android_build_top = i18nutil.GetAndroidRootOrDie()
timezone_dir = os.path.realpath('%s/system/timezone' % android_build_top)
i18nutil.CheckDirExists(timezone_dir, 'system/timezone')

zone_compactor_dir = os.path.realpath('%s/system/timezone/zone_compactor' % android_build_top)
i18nutil.CheckDirExists(timezone_dir, 'system/timezone/zone_zompactor')

timezone_input_data_dir = os.path.realpath('%s/input_data' % timezone_dir)

timezone_output_data_dir = '%s/output_data' % timezone_dir
i18nutil.CheckDirExists(timezone_output_data_dir, 'output_data')

tmp_dir = tempfile.mkdtemp('-tzdata')


def WriteSetupFile(extracted_iana_dir):
  """Writes the list of zones that ZoneCompactor should process."""
  links = []
  zones = []
  for region in regions:
    for line in open('%s/%s' % (extracted_iana_dir, region)):
      fields = line.split()
      if fields:
        if fields[0] == 'Link':
          links.append('%s %s %s' % (fields[0], fields[1], fields[2]))
          zones.append(fields[2])
        elif fields[0] == 'Zone':
          zones.append(fields[1])
  zones.sort()

  zone_compactor_setup_file = '%s/setup' % tmp_dir
  setup = open(zone_compactor_setup_file, 'w')
  for link in sorted(set(links)):
    setup.write('%s\n' % link)
  for zone in sorted(set(zones)):
    setup.write('%s\n' % zone)
  setup.close()
  return zone_compactor_setup_file


def BuildIcuData(iana_tar_file):
  icu_build_dir = '%s/icu' % tmp_dir

  icuutil.PrepareIcuBuild(icu_build_dir)
  icuutil.MakeTzDataFiles(icu_build_dir, iana_tar_file)

  # Create ICU system image files.
  icuutil.MakeAndCopyIcuDataFiles(icu_build_dir)

  # Create the ICU overlay time zone file.
  icu_overlay_dat_file = '%s/icu_overlay/icu_tzdata.dat' % timezone_output_data_dir
  icuutil.MakeAndCopyOverlayTzIcuData(icu_build_dir, icu_overlay_dat_file)


def ExtractIanaVersion(iana_tar_file):
  iana_tar_filename = os.path.basename(iana_tar_file)
  iana_version = re.search('tzdata(.+)\\.tar\\.gz', iana_tar_filename).group(1)
  return iana_version


def BuildTzdata(iana_tar_file):
  iana_version = ExtractIanaVersion(iana_tar_file)
  header_string = 'tzdata%s' % iana_version

  print 'Extracting...'
  extracted_iana_dir = '%s/extracted_iana' % tmp_dir
  os.mkdir(extracted_iana_dir)
  tar = tarfile.open(iana_tar_file, 'r')
  tar.extractall(extracted_iana_dir)

  print 'Calling zic(1)...'
  zic_output_dir = '%s/data' % tmp_dir
  os.mkdir(zic_output_dir)
  zic_generator_template = '%s/%%s' % extracted_iana_dir
  zic_inputs = [ zic_generator_template % x for x in regions ]
  zic_cmd = ['zic', '-d', zic_output_dir ]
  zic_cmd.extend(zic_inputs)
  subprocess.check_call(zic_cmd)

  zone_compactor_setup_file = WriteSetupFile(extracted_iana_dir)

  print 'Calling ZoneCompactor to update tzdata to %s...' % iana_version
  class_files_dir = '%s/classes' % tmp_dir
  os.mkdir(class_files_dir)

  subprocess.check_call(['javac', '-d', class_files_dir,
                         '%s/main/java/ZoneCompactor.java' % zone_compactor_dir])

  zone_tab_file = '%s/zone.tab' % extracted_iana_dir

  iana_output_data_dir = '%s/iana' % timezone_output_data_dir
  subprocess.check_call(['java', '-cp', class_files_dir, 'ZoneCompactor',
                         zone_compactor_setup_file, zic_output_dir, zone_tab_file,
                         iana_output_data_dir, header_string])


def BuildTzlookup():
  # We currently just copy a manually-maintained xml file.
  tzlookup_source_file = '%s/android/tzlookup.xml' % timezone_input_data_dir
  tzlookup_dest_file = '%s/android/tzlookup.xml' % timezone_output_data_dir
  shutil.copyfile(tzlookup_source_file, tzlookup_dest_file)


def CreateDistroFiles(iana_version, output_dir):
  create_distro_script = '%s/distro/tools/create-distro.py' % timezone_dir

  tzdata_file = '%s/iana/tzdata' % timezone_output_data_dir
  icu_file = '%s/icu_overlay/icu_tzdata.dat' % timezone_output_data_dir
  tzlookup_file = '%s/android/tzlookup.xml' % timezone_output_data_dir

  distro_file_pattern = '%s/*.zip' % output_dir
  existing_distro_files = glob.glob(distro_file_pattern)

  distro_file_metadata_pattern = '%s/*.txt' % output_dir
  existing_distro_metadata_files = glob.glob(distro_file_metadata_pattern)
  existing_files = existing_distro_files + existing_distro_metadata_files

  print 'Removing %s' % existing_files
  for existing_file in existing_files:
    os.remove(existing_file)

  subprocess.check_call([create_distro_script,
      '-iana_version', iana_version,
      '-tzdata', tzdata_file,
      '-icu', icu_file,
      '-tzlookup', tzlookup_file,
      '-output', output_dir])


# Run with no arguments from any directory, with no special setup required.
# See http://www.iana.org/time-zones/ for more about the source of this data.
def main():
  print 'Found source data file structure in %s ...' % timezone_input_data_dir

  iana_data_dir = '%s/iana' % timezone_input_data_dir
  iana_tar_file = tzdatautil.GetIanaTarFile(iana_data_dir)
  iana_version = ExtractIanaVersion(iana_tar_file)
  print 'Found IANA time zone data release %s in %s ...' % (iana_version, iana_tar_file)

  print 'Found android output dir in %s ...' % timezone_output_data_dir

  icu_dir = icuutil.icuDir()
  print 'Found icu in %s ...' % icu_dir

  BuildIcuData(iana_tar_file)
  BuildTzdata(iana_tar_file)
  BuildTzlookup()

  # Create a distro file from the output from prior stages.
  distro_output_dir = '%s/distro' % timezone_output_data_dir
  CreateDistroFiles(iana_version, distro_output_dir)

  print 'Look in %s and %s for new files' % (timezone_output_data_dir, icu_dir)
  sys.exit(0)


if __name__ == '__main__':
  main()
