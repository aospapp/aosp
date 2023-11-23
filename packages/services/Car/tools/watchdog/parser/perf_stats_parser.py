#!/usr/bin/python3

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
#
"""
Tool to parse CarWatchdog's performance stats dump.

To build the parser script run:
  m perf_stats_parser

To parse a carwatchdog dump text file run:
  perf_stats_parser -f <cw-dump>.txt -o cw_proto_out.pb

To read a carwatchdog proto file as a json run:
  pers_stats_parser -r <cw-proto-out>.pb -j
"""
import argparse
import json
import os
import re
import sys

from parser import performancestats_pb2
from parser import deviceperformancestats_pb2
from datetime import datetime

BOOT_TIME_REPORT_HEADER = "Boot-time performance report:"
CUSTOM_COLLECTION_REPORT_HEADER = "Custom performance data report:"
TOP_N_CPU_TIME_HEADER = "Top N CPU Times:"

DUMP_DATETIME_FORMAT = "%a %b %d %H:%M:%S %Y %Z"
DATETIME_FORMAT = "%Y-%m-%d %H:%M:%S"

STATS_COLLECTION_PATTERN = "Collection (\d+): <(.+)>"
PACKAGE_CPU_STATS_PATTERN = "(\d+), (.+), (\d+), (\d+).(\d+)%(, (\d+))?"
PROCESS_CPU_STATS_PATTERN = "\s+(.+), (\d+), (\d+).(\d+)%(, (\d+))?"
TOTAL_CPU_TIME_PATTERN = "Total CPU time \\(ms\\): (\d+)"
TOTAL_IDLE_CPU_TIME_PATTERN = "Total idle CPU time \\(ms\\)/percent: (\d+) / .+"
CPU_IO_WAIT_TIME_PATTERN = "CPU I/O wait time \\(ms\\)/percent: (\d+) / .+"
CONTEXT_SWITCHES_PATTERN = "Number of context switches: (\d+)"
IO_BLOCKED_PROCESSES_PATTERN = "Number of I/O blocked processes/percent: (\d+) / .+"


class BuildInformation:
  def __init__(self):
    self.fingerprint = None
    self.brand = None
    self.product = None
    self.device = None
    self.version_release = None
    self.id = None
    self.version_incremental = None
    self.type = None
    self.tags = None
    self.sdk = None
    self.platform_minor = None
    self.codename = None

  def __repr__(self):
    return "BuildInformation (fingerprint={}, brand={}, product={}, device={}, " \
           "version_release={}, id={}, version_incremental={}, type={}, tags={}, " \
           "sdk={}, platform_minor={}, codename={})"\
      .format(self.fingerprint, self.brand, self.product, self.device, self.version_release,
              self.id, self.version_incremental, self.type, self.tags, self.sdk,
              self.platform_minor, self.codename)


class ProcessCpuStats:
  def __init__(self, command, cpu_time_ms, package_cpu_time_percent, cpu_cycles):
    self.command = command
    self.cpu_time_ms = cpu_time_ms
    self.package_cpu_time_percent = package_cpu_time_percent
    self.cpu_cycles = cpu_cycles

  def __repr__(self):
    return "ProcessCpuStats (command={}, CPU time={}ms, percent of " \
           "package's CPU time={}%, CPU cycles={})"\
      .format(self.command, self.cpu_time_ms, self.package_cpu_time_percent,
              self.cpu_cycles)


class PackageCpuStats:
  def __init__(self, user_id, package_name, cpu_time_ms,
      total_cpu_time_percent, cpu_cycles):
    self.user_id = user_id
    self.package_name = package_name
    self.cpu_time_ms = cpu_time_ms
    self.total_cpu_time_percent = total_cpu_time_percent
    self.cpu_cycles = cpu_cycles
    self.process_cpu_stats = []

  def to_dict(self):
    return {
        "user_id": self.user_id,
        "package_name": self.package_name,
        "cpu_time_ms": self.cpu_time_ms,
        "total_cpu_time_percent": self.total_cpu_time_percent,
        "cpu_cycles": self.cpu_cycles,
        "process_cpu_stats": [vars(p) for p in self.process_cpu_stats]
    }

  def __repr__(self):
    process_cpu_stats_str = "[])"
    if len(self.process_cpu_stats) > 0:
      process_list_str = "\n      ".join(list(map(repr, self.process_cpu_stats)))
      process_cpu_stats_str = "\n      {}\n    )".format(process_list_str)
    return "PackageCpuStats (user id={}, package name={}, CPU time={}ms, " \
           "percent of total CPU time={}%, CPU cycles={}, process CPU stats={}" \
      .format(self.user_id, self.package_name, self.cpu_time_ms,
              self.total_cpu_time_percent, self.cpu_cycles, process_cpu_stats_str)


class StatsCollection:
  def __init__(self):
    self.id = -1
    self.date = None
    self.total_cpu_time_ms = 0
    self.idle_cpu_time_ms = 0
    self.io_wait_time_ms = 0
    self.context_switches = 0
    self.io_blocked_processes = 0
    self.package_cpu_stats = []

  def is_empty(self):
    val = self.total_cpu_time_ms + self.idle_cpu_time_ms + self.io_wait_time_ms + \
          self.context_switches + self.io_blocked_processes
    return self.id == -1 and not self.date and val == 0 and len(self.package_cpu_stats) == 0

  def to_dict(self):
    return {
        "id": self.id,
        "date": self.date.strftime(DATETIME_FORMAT) if self.date else "",
        "total_cpu_time_ms": self.total_cpu_time_ms,
        "idle_cpu_time_ms": self.idle_cpu_time_ms,
        "io_wait_time_ms": self.io_wait_time_ms,
        "context_switches": self.context_switches,
        "io_blocked_processes": self.io_blocked_processes,
        "packages_cpu_stats": [p.to_dict() for p in self.package_cpu_stats]
    }

  def __repr__(self):
    date = self.date.strftime(DATETIME_FORMAT) if self.date else ""
    pcs_str = "\n    ".join(list(map(repr, self.package_cpu_stats)))
    return "StatsCollection (id={}, date={}, total CPU time={}ms, " \
           "idle CPU time={}ms, I/O wait time={}ms, total context switches={}, " \
           "total I/O blocked processes={}, package CPU stats=\n    {}\n  )" \
      .format(self.id, date, self.total_cpu_time_ms, self.idle_cpu_time_ms,
              self.io_wait_time_ms, self.context_switches,
              self.io_blocked_processes, pcs_str)


class SystemEventStats:
  def __init__(self):
    self.collections = []

  def add(self, collection):
    self.collections.append(collection)

  def is_empty(self):
    return not any(map(lambda c: not c.is_empty(), self.collections))

  def to_list(self):
    return [c.to_dict() for c in self.collections]

  def __repr__(self):
    collections_str = "\n  ".join(list(map(repr, self.collections)))
    return "SystemEventStats (\n" \
           "  {}\n)".format(collections_str)


class PerformanceStats:
  def __init__(self):
    self.boot_time_stats = None
    self.user_switch_stats = []
    self.custom_collection_stats = None

  def has_boot_time(self):
    return self.boot_time_stats and not self.boot_time_stats.is_empty()

  def has_custom_collection(self):
    return self.custom_collection_stats \
           and not self.custom_collection_stats.is_empty()

  def is_empty(self):
    return not self.has_boot_time() and not self.has_custom_collection() \
           and not any(map(lambda u: not u.is_empty(), self.user_switch_stats))

  def to_dict(self):
    return {
        "boot_time_stats": self.boot_time_stats.to_list() if self.boot_time_stats else None,
        "user_switch_stats": [u.to_list() for u in self.user_switch_stats],
        "custom_collection_stats": self.custom_collection_stats.to_list() if self.custom_collection_stats else None,
    }

  def __repr__(self):
    return "PerformanceStats (\n" \
          "boot-time stats={}\n" \
          "\nuser-switch stats={}\n" \
          "\ncustom-collection stats={}\n)" \
      .format(self.boot_time_stats, self.user_switch_stats,
              self.custom_collection_stats)

class DevicePerformanceStats:
  def __init__(self):
    self.build_info = None
    self.perf_stats = []

  def to_dict(self):
    return {
        "build_info": vars(self.build_info),
        "perf_stats": [s.to_dict() for s in self.perf_stats]
    }

  def __repr__(self):
    return "DevicePerformanceStats (\n" \
            "build_info={}\n" \
            "\nperf_stats={}\n)"\
      .format(self.build_info, self.perf_stats)

def parse_build_info(build_info_file):
  build_info = BuildInformation()

  def get_value(line):
    if ':' not in line:
      return ""
    return line.split(':')[1].strip()

  with open(build_info_file, 'r') as f:
    for line in f.readlines():
      value = get_value(line)
      if line.startswith("fingerprint"):
        build_info.fingerprint = value
      elif line.startswith("brand"):
        build_info.brand = value
      elif line.startswith("product"):
        build_info.product = value
      elif line.startswith("device"):
        build_info.device = value
      elif line.startswith("version.release"):
        build_info.version_release = value
      elif line.startswith("id"):
        build_info.id = value
      elif line.startswith("version.incremental"):
        build_info.version_incremental = value
      elif line.startswith("type"):
        build_info.type = value
      elif line.startswith("tags"):
        build_info.tags = value
      elif line.startswith("sdk"):
        build_info.sdk = value
      elif line.startswith("platform minor version"):
        build_info.platform_minor = value
      elif line.startswith("codename"):
        build_info.codename = value

    return build_info

def parse_cpu_times(lines, idx):
  package_cpu_stats = []
  package_cpu_stat = None

  while not (line := lines[idx].rstrip()).startswith("Top N") \
      and not re.match(STATS_COLLECTION_PATTERN, line) \
      and not line.startswith('-' * 50):
    if match := re.match(PACKAGE_CPU_STATS_PATTERN, line):
      user_id = int(match.group(1))
      package_name = match.group(2)
      cpu_time_ms = int(match.group(3))
      total_cpu_time_percent = float("{}.{}".format(match.group(4),
                                                    match.group(5)))
      cpu_cycles = int(match.group(7)) if match.group(7) is not None else -1

      package_cpu_stat = PackageCpuStats(user_id, package_name,
                                         cpu_time_ms,
                                         total_cpu_time_percent,
                                         cpu_cycles)
      package_cpu_stats.append(package_cpu_stat)
    elif match := re.match(PROCESS_CPU_STATS_PATTERN, line):
      command = match.group(1)
      cpu_time_ms = int(match.group(2))
      package_cpu_time_percent = float("{}.{}".format(match.group(3),
                                                      match.group(4)))
      cpu_cycles = int(match.group(6)) if match.group(6) is not None else -1
      if package_cpu_stat:
        package_cpu_stat.process_cpu_stats.append(
          ProcessCpuStats(command, cpu_time_ms, package_cpu_time_percent, cpu_cycles))
      else:
        print("No package CPU stats parsed for process:", command, file=sys.stderr)

    idx += 1

  return package_cpu_stats, idx


def parse_collection(lines, idx, match):
  collection = StatsCollection()
  collection.id = int(match.group(1))
  collection.date = datetime.strptime(match.group(2), DUMP_DATETIME_FORMAT)

  while not re.match(STATS_COLLECTION_PATTERN,
                     (line := lines[idx].strip())) and not line.startswith('-' * 50):
    if match := re.match(TOTAL_CPU_TIME_PATTERN, line):
      collection.total_cpu_time_ms = int(match.group(1))
    elif match := re.match(TOTAL_IDLE_CPU_TIME_PATTERN, line):
      collection.idle_cpu_time_ms = int(match.group(1))
    elif match := re.match(CPU_IO_WAIT_TIME_PATTERN, line):
      collection.io_wait_time_ms = int(match.group(1))
    elif match := re.match(CONTEXT_SWITCHES_PATTERN, line):
      collection.context_switches = int(match.group(1))
    elif match := re.match(IO_BLOCKED_PROCESSES_PATTERN, line):
      collection.io_blocked_processes = int(match.group(1))
    elif line == TOP_N_CPU_TIME_HEADER:
      idx += 1  # Skip subsection header
      package_cpu_stats, idx = parse_cpu_times(lines, idx)
      collection.package_cpu_stats = package_cpu_stats
      continue

    idx += 1

  return collection, idx


def parse_stats_collections(lines, idx):
  system_event_stats = SystemEventStats()
  while not (line := lines[idx].strip()).startswith('-' * 50):
    if match := re.match(STATS_COLLECTION_PATTERN, line):
      idx += 1  # Skip the collection header
      collection, idx = parse_collection(lines, idx, match)
      if not collection.is_empty():
        system_event_stats.add(collection)
    else:
      idx += 1
  return system_event_stats, idx


def parse_dump(dump):
  lines = dump.split("\n")
  performance_stats = PerformanceStats()
  idx = 0
  while idx < len(lines):
    line = lines[idx].strip()
    if line == BOOT_TIME_REPORT_HEADER:
      boot_time_stats, idx = parse_stats_collections(lines, idx)
      if not boot_time_stats.is_empty():
        performance_stats.boot_time_stats = boot_time_stats
    if line == CUSTOM_COLLECTION_REPORT_HEADER:
      idx += 2  # Skip the dashed-line after the custom collection header
      custom_collection_stats, idx = parse_stats_collections(lines, idx)
      if not custom_collection_stats.is_empty():
        performance_stats.custom_collection_stats = custom_collection_stats
    else:
      idx += 1

  return performance_stats


def create_date_pb(date):
  date_pb = performancestats_pb2.Date()
  date_pb.year = date.year
  date_pb.month = date.month
  date_pb.day = date.day
  return date_pb


def create_timeofday_pb(date):
  timeofday_pb = performancestats_pb2.TimeOfDay()
  timeofday_pb.hours = date.hour
  timeofday_pb.minutes = date.minute
  timeofday_pb.seconds = date.second
  return timeofday_pb

def add_system_event_pb(system_event_stats, system_event_pb):
  for collection in system_event_stats.collections:
    stats_collection_pb = system_event_pb.collections.add()
    stats_collection_pb.id = collection.id
    stats_collection_pb.date.CopyFrom(create_date_pb(collection.date))
    stats_collection_pb.time.CopyFrom(create_timeofday_pb(collection.date))
    stats_collection_pb.total_cpu_time_ms = collection.total_cpu_time_ms
    stats_collection_pb.idle_cpu_time_ms = collection.idle_cpu_time_ms
    stats_collection_pb.io_wait_time_ms = collection.io_wait_time_ms
    stats_collection_pb.context_switches = collection.context_switches
    stats_collection_pb.io_blocked_processes = collection.io_blocked_processes

    for package_cpu_stats in collection.package_cpu_stats:
      package_cpu_stats_pb = stats_collection_pb.package_cpu_stats.add()
      package_cpu_stats_pb.user_id = package_cpu_stats.user_id
      package_cpu_stats_pb.package_name = package_cpu_stats.package_name
      package_cpu_stats_pb.cpu_time_ms = package_cpu_stats.cpu_time_ms
      package_cpu_stats_pb.total_cpu_time_percent = package_cpu_stats.total_cpu_time_percent
      package_cpu_stats_pb.cpu_cycles = package_cpu_stats.cpu_cycles

      for process_cpu_stats in package_cpu_stats.process_cpu_stats:
        process_cpu_stats_pb = package_cpu_stats_pb.process_cpu_stats.add()
        process_cpu_stats_pb.command = process_cpu_stats.command
        process_cpu_stats_pb.cpu_time_ms = process_cpu_stats.cpu_time_ms
        process_cpu_stats_pb.package_cpu_time_percent = process_cpu_stats.package_cpu_time_percent
        process_cpu_stats_pb.cpu_cycles = process_cpu_stats.cpu_cycles

def get_system_event(system_event_pb):
  system_event_stats = SystemEventStats()
  for stats_collection_pb in system_event_pb.collections:
    stats_collection = StatsCollection()
    stats_collection.id = stats_collection_pb.id
    date_pb = stats_collection_pb.date
    time_pb = stats_collection_pb.time
    stats_collection.date = datetime(date_pb.year, date_pb.month, date_pb.day,
                                     time_pb.hours, time_pb.minutes, time_pb.seconds)
    stats_collection.total_cpu_time_ms = stats_collection_pb.total_cpu_time_ms
    stats_collection.idle_cpu_time_ms = stats_collection_pb.idle_cpu_time_ms
    stats_collection.io_wait_time_ms = stats_collection_pb.io_wait_time_ms
    stats_collection.context_switches = stats_collection_pb.context_switches
    stats_collection.io_blocked_processes = stats_collection_pb.io_blocked_processes

    for package_cpu_stats_pb in stats_collection_pb.package_cpu_stats:
      package_cpu_stats = \
        PackageCpuStats(package_cpu_stats_pb.user_id,
                        package_cpu_stats_pb.package_name,
                        package_cpu_stats_pb.cpu_time_ms,
                        round(package_cpu_stats_pb.total_cpu_time_percent, 2),
                        package_cpu_stats_pb.cpu_cycles)

      for process_cpu_stats_pb in package_cpu_stats_pb.process_cpu_stats:
        process_cpu_stats = \
          ProcessCpuStats(process_cpu_stats_pb.command,
                          process_cpu_stats_pb.cpu_time_ms,
                          round(process_cpu_stats_pb.package_cpu_time_percent,
                                2),
                          process_cpu_stats_pb.cpu_cycles)

        package_cpu_stats.process_cpu_stats.append(process_cpu_stats)
      stats_collection.package_cpu_stats.append(package_cpu_stats)
    system_event_stats.add(stats_collection)

  return system_event_stats

def get_perf_stats(perf_stats_pb):
  perf_stats = PerformanceStats()
  perf_stats.boot_time_stats = get_system_event(perf_stats_pb.boot_time_stats)
  perf_stats.custom_collection_stats = get_system_event(perf_stats_pb.custom_collection_stats)
  return perf_stats

def get_build_info(build_info_pb):
  build_info = BuildInformation()
  build_info.fingerprint = build_info_pb.fingerprint
  build_info.brand = build_info_pb.brand
  build_info.product = build_info_pb.product
  build_info.device = build_info_pb.device
  build_info.version_release = build_info_pb.version_release
  build_info.id = build_info_pb.id
  build_info.version_incremental = build_info_pb.version_incremental
  build_info.type = build_info_pb.type
  build_info.tags = build_info_pb.tags
  build_info.sdk = build_info_pb.sdk
  build_info.platform_minor = build_info_pb.platform_minor
  build_info.codename = build_info_pb.codename
  return build_info

def write_pb(perf_stats, out_file, build_info=None, out_build_file=None):
  if perf_stats.is_empty():
    print("Cannot write proto since performance stats are empty")
    return False

  perf_stats_pb = performancestats_pb2.PerformanceStats()

  # Boot time proto
  if perf_stats.has_boot_time():
    boot_time_stats_pb = performancestats_pb2.SystemEventStats()
    add_system_event_pb(perf_stats.boot_time_stats, boot_time_stats_pb)
    perf_stats_pb.boot_time_stats.CopyFrom(boot_time_stats_pb)

  # TODO(b/256654082): Add user switch events to proto

  # Custom collection proto
  if perf_stats.has_custom_collection():
    custom_collection_stats_pb = performancestats_pb2.SystemEventStats()
    add_system_event_pb(perf_stats.custom_collection_stats,
                        custom_collection_stats_pb)
    perf_stats_pb.custom_collection_stats.CopyFrom(custom_collection_stats_pb)

  # Write pb binary to disk
  if out_file:
    with open(out_file, "wb") as f:
      f.write(perf_stats_pb.SerializeToString())

  if build_info is not None:
    build_info_pb = deviceperformancestats_pb2.BuildInformation()
    build_info_pb.fingerprint = build_info.fingerprint
    build_info_pb.brand = build_info.brand
    build_info_pb.product = build_info.product
    build_info_pb.device = build_info.device
    build_info_pb.version_release = build_info.version_release
    build_info_pb.id = build_info.id
    build_info_pb.version_incremental = build_info.version_incremental
    build_info_pb.type = build_info.type
    build_info_pb.tags = build_info.tags
    build_info_pb.sdk = build_info.sdk
    build_info_pb.platform_minor = build_info.platform_minor
    build_info_pb.codename = build_info.codename

    device_run_perf_stats_pb = deviceperformancestats_pb2.DevicePerformanceStats()
    device_run_perf_stats_pb.build_info.CopyFrom(build_info_pb)
    device_run_perf_stats_pb.perf_stats.add().CopyFrom(perf_stats_pb)

    with open(out_build_file, "wb") as f:
      f.write(device_run_perf_stats_pb.SerializeToString())

  return True


def read_pb(pb_file, is_device_run=False):
  perf_stats_pb = deviceperformancestats_pb2.DevicePerformanceStats() if \
    is_device_run else performancestats_pb2.PerformanceStats()

  with open(pb_file, "rb") as f:
    try:
      perf_stats_pb.ParseFromString(f.read())
      perf_stats_pb.DiscardUnknownFields()
    except UnicodeDecodeError:
      proto_type = "DevicePerformanceStats" if is_device_run else "PerformanceStats"
      print(f"Error: Proto in {pb_file} probably is not '{proto_type}'")
      return None

  if not perf_stats_pb:
    print(f"Error: Proto stored in {pb_file} has incorrect format.")
    return None

  if not is_device_run:
    return get_perf_stats(perf_stats_pb)

  device_run_perf_stats = DevicePerformanceStats()
  device_run_perf_stats.build_info = get_build_info(perf_stats_pb.build_info)

  for perf_stat in perf_stats_pb.perf_stats:
    device_run_perf_stats.perf_stats.append(get_perf_stats(perf_stat))

  return device_run_perf_stats

def init_arguments():
  parser = argparse.ArgumentParser(description="Parses CarWatchdog's dump.")
  parser.add_argument("-f", "--file", dest="file",
                      default="dump.txt",
                      help="File with the CarWatchdog dump")
  parser.add_argument("-o", "--out", dest="out",
                      help="protobuf binary with parsed performance stats")
  parser.add_argument("-b", "--build", dest="build",
                      help="File with Android device build information")
  parser.add_argument("-d", "--device-out", dest="device_out",
                      default="device_perf_stats.pb",
                      help="protobuf binary with build information")
  parser.add_argument("-p", "--print", dest="print", action="store_true",
                      help="prints the parsed performance data to the console "
                           "when out proto defined")
  parser.add_argument("-r", "--read", dest="read_proto",
                      help="Protobuf binary to be printed in console. If this "
                           "flag is set no other process is executed.")
  parser.add_argument("-D", "--device-run", dest="device_run",
                      action="store_true",
                      help="Specifies that the proto to be read is a "
                           "DevicePerformanceStats proto. (Only checked if "
                           "-r is set)")
  parser.add_argument("-j", "--json", dest="json",
                      action="store_true",
                      help="Generate a JSON file from the protobuf binary read.")

  return parser.parse_args()

if __name__ == "__main__":
  args = init_arguments()

  if args.read_proto:
    if not os.path.isfile(args.read_proto):
      print("Error: Proto binary '%s' does not exist" % args.read_proto)
      sys.exit(1)
    performance_stats = read_pb(args.read_proto, args.device_run)
    if performance_stats is None:
      print(f"Error: Could not read '{args.read_proto}'")
      sys.exit(1)
    if args.json:
      print(json.dumps(performance_stats.to_dict()))
    else:
      print("Reading performance stats proto:")
      print(performance_stats)
    sys.exit()

  if not os.path.isfile(args.file):
    print("Error: File '%s' does not exist" % args.file)
    sys.exit(1)

  with open(args.file, 'r', encoding="UTF-8", errors="ignore") as f:
    performance_stats = parse_dump(f.read())

    build_info = None
    if args.build:
      build_info = parse_build_info(args.build)
      print(build_info)

    if performance_stats.is_empty():
      print("Error: No performance stats were parsed. Make sure dump file contains carwatchdog's "
            "dump text.")
      sys.exit(1)

    if (args.out or args.build) and write_pb(performance_stats, args.out,
                                             build_info, args.device_out):
      out_file = args.out if args.out else args.device_out
      print("Output protobuf binary in:", out_file)

    if args.print or not (args.out or args.build):
      if args.json:
          print(json.dumps(performance_stats.to_dict()))
          sys.exit()
      print(performance_stats)
