#!/usr/bin/env python3
#
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
"""A tool to print human-readable metrics information regarding the last build.

By default, the consumed file will be $OUT_DIR/soong_build_metrics.pb. You may
pass in a different file instead using the metrics_file flag.
"""

import argparse
import json
import os
import subprocess
import sys


class Event(object):
  """Contains nested event data.

  Fields:
    name: The short name of this event e.g. the 'b' in an event called a.b.
    children: Nested events
    start_time_relative_ns: Time since the epoch that the event started
    duration_ns: Duration of this event, including time spent in children.
  """

  def __init__(self, name):
    self.name = name
    self.children = list()
    self.start_time_relative_ns = 0
    self.duration_ns = 0

  def get_child(self, name):
    "Get a child called 'name' or return None"
    for child in self.children:
      if child.name == name:
        return child
    return None

  def get_or_add_child(self, name):
    "Get a child called 'name', or if it isn't there, add it and return it."
    child = self.get_child(name)
    if not child:
      child = Event(name)
      self.children.append(child)
    return child


def _get_proto_output_file():
  """Returns the location of the proto file used for analyzing out/soong_build_metrics.pb.

  This corresponds to soong/ui/metrics/metrics_proto/metrics.proto.
  """
  return os.getenv("ANDROID_BUILD_TOP"
                  ) + "/build/soong/ui/metrics/metrics_proto/metrics.proto"


def _get_default_output_file():
  """Returns the filepath for the build output."""
  out_dir = os.getenv("OUT_DIR")
  if not out_dir:
    out_dir = "out"
  build_top = os.getenv("ANDROID_BUILD_TOP")
  if not build_top:
    raise Exception(
        "$ANDROID_BUILD_TOP not found in environment. Have you run lunch?")
  return os.path.join(build_top, out_dir, "soong_build_metrics.pb")


def _make_nested_events(root_event, event):
  """Splits the event into its '.' separated name parts, and adds Event objects for it to the

  synthetic root_event event.
  """
  node = root_event
  for sub_event in event["description"].split("."):
    node = node.get_or_add_child(sub_event)
  node.start_time_relative_ns = event["start_time_relative_ns"]
  node.duration_ns = event["real_time"]


def _write_events(out, events, parent=None):
  """Writes the list of events.

  Args:
    out: The stream to write to
    events: The list of events to write
    parent: Prefix parent's name
  """
  for event in events:
    _write_event(out, event, parent)


def _write_event(out, event, parent=None):
  "Writes an event. See _write_events for args."
  full_event_name = parent + "." + event.name if parent else event.name
  out.write(
      "%(start)9s  %(duration)9s  %(name)s\n" % {
          "start": _format_ns(event.start_time_relative_ns),
          "duration": _format_ns(event.duration_ns),
          "name": full_event_name,
      })
  _write_events(out, event.children, full_event_name)


def _format_ns(duration_ns):
  "Pretty print duration in nanoseconds"
  return "%.02fs" % (duration_ns / 1_000_000_000)


def _save_file(data, file):
  f = open(file, "wb")
  f.write(data)
  f.close()


def main():
  # Parse args
  parser = argparse.ArgumentParser(description="")
  parser.add_argument(
      "metrics_file",
      nargs="?",
      default=_get_default_output_file(),
      help="The soong_metrics file created as part of the last build. " +
      "Defaults to out/soong_build_metrics.pb")
  parser.add_argument(
      "--save-proto-output-file",
      nargs="?",
      default="",
      help="(Optional) The file to save the output of the printproto command to."
  )
  args = parser.parse_args()

  # Check the metrics file
  metrics_file = args.metrics_file
  if not os.path.exists(metrics_file):
    raise Exception("File " + metrics_file + " not found. Did you run a build?")

  # Check the proto definition file
  proto_file = _get_proto_output_file()
  if not os.path.exists(proto_file):
    raise Exception(
        "$ANDROID_BUILD_TOP not found in environment. Have you run lunch?")

  # Load the metrics file from the out dir
  cmd = r"""printproto --proto2 --raw_protocol_buffer --json \
              --json_accuracy_loss_reaction=ignore \
              --message=soong_build_metrics.SoongBuildMetrics --multiline \
              --proto=""" + proto_file + " " + metrics_file
  json_out = subprocess.check_output(cmd, shell=True)

  if args.save_proto_output_file != "":
    _save_file(json_out, args.save_proto_output_file)

  build_output = json.loads(json_out)

  # Bail if there are no events
  raw_events = build_output.get("events")
  if not raw_events:
    print("No events to display")
    return

  # Update the start times to be based on the first event
  first_time_ns = min([event["start_time"] for event in raw_events])
  for event in raw_events:
    event["start_time_relative_ns"] = event["start_time"] - first_time_ns

  # Sort by start time so the nesting also is sorted by time
  raw_events.sort(key=lambda x: x["start_time_relative_ns"])

  # We don't show this event, so that there doesn't have to be a single top level event
  fake_root_event = Event("<root>")

  # Convert the flat event list into the tree
  for event in raw_events:
    _make_nested_events(fake_root_event, event)

  # Output the results
  print("    start   duration")

  _write_events(sys.stdout, fake_root_event.children)


if __name__ == "__main__":
  main()
