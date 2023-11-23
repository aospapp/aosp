#!/usr/bin/env python3
#
# Copyright (C) 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""A json-module-graph postprocessing script to generate a bp2build progress tracker.

Usage:
  b run :bp2build_progress [report|graph] -m <module name>

Example:

  To generate a report on the `adbd` module, run:
    b run //build/bazel/scripts/bp2build_progress:bp2build_progress \
      -- report -m <module-name>

  To generate a graph on the `adbd` module, run:
    b run //build/bazel/scripts/bp2build_progress:bp2build_progress \
      -- graph -m adbd > /tmp/graph.in && \
      dot -Tpng -o /tmp/graph.png /tmp/graph.in
"""

import argparse
import collections
import dataclasses
import datetime
import os.path
import subprocess
import sys
import xml
from typing import Dict, List, Set, Optional

import bp2build_pb2
import dependency_analysis


@dataclasses.dataclass(frozen=True, order=True)
class ModuleInfo:
  name: str
  kind: str
  dirname: str
  created_by: Optional[str]
  num_deps: int = 0
  converted: bool = False

  def __str__(self):
    converted = " (converted)" if self.converted else ""
    return f"{self.name} [{self.kind}] [{self.dirname}]{converted}"

  def short_string(self, converted: Set[str]):
    converted = " (c)" if self.is_converted(converted) else ""
    return f"{self.name}{converted}"

  def is_converted(self, converted: Set[str]):
    return self.name in converted

  def is_converted_or_skipped(self, converted: Set[str]):
    if self.is_converted(converted):
      return True
    # these are implementation details of another module type that can never be
    # created in a BUILD file
    return ".go_android/soong" in self.kind and (
        self.kind.endswith("__loadHookModule") or
        self.kind.endswith("__topDownMutatorModule"))


@dataclasses.dataclass(frozen=True, order=True)
class InputModule:
  module: ModuleInfo
  num_deps: int = 0
  num_unconverted_deps: int = 0

  def __str__(self):
    total = self.num_deps
    converted = self.num_deps - self.num_unconverted_deps
    percent = 1
    if self.num_deps > 0:
      percent = converted / self.num_deps * 100
    return f"{self.module.name}: {percent:.1f}% ({converted}/{total}) converted"


@dataclasses.dataclass(frozen=True)
class ReportData:
  input_modules: Set[InputModule]
  total_deps: Set[ModuleInfo]
  unconverted_deps: Set[str]
  all_unconverted_modules: Dict[str, Set[ModuleInfo]]
  blocked_modules: Dict[ModuleInfo, Set[str]]
  dirs_with_unconverted_modules: Set[str]
  kind_of_unconverted_modules: Set[str]
  converted: Set[str]
  show_converted: bool


# Generate a dot file containing the transitive closure of the module.
def generate_dot_file(modules: Dict[ModuleInfo, Set[ModuleInfo]],
                      converted: Set[str], show_converted: bool):
  # Check that all modules in the argument are in the list of converted modules
  all_converted = lambda modules: all(
      m.is_converted(converted) for m in modules)

  dot_entries = []

  for module, deps in sorted(modules.items()):

    if module.is_converted(converted):
      if show_converted:
        color = "dodgerblue"
      else:
        continue
    elif all_converted(deps):
      color = "yellow"
    else:
      color = "tomato"

    dot_entries.append(
        f'"{module.name}" [label="{module.name}\\n{module.kind}" color=black, style=filled, '
        f"fillcolor={color}]")
    dot_entries.extend(
        f'"{module.name}" -> "{dep.name}"' for dep in sorted(deps)
        if show_converted or not dep.is_converted(converted))

  return """
digraph mygraph {{
  node [shape=box];

  %s
}}
""" % "\n  ".join(dot_entries)


# Generate a report for each module in the transitive closure, and the blockers for each module
def generate_report_data(modules: Dict[ModuleInfo, Set[ModuleInfo]],
                         converted: Set[str],
                         input_modules_names: Set[str],
                         show_converted: bool = False) -> ReportData:
  # Map of [number of unconverted deps] to list of entries,
  # with each entry being the string: "<module>: <comma separated list of unconverted modules>"
  blocked_modules = collections.defaultdict(set)

  # Map of unconverted modules to the modules they're blocking
  # (i.e. reverse deps)
  all_unconverted_modules = collections.defaultdict(set)

  dirs_with_unconverted_modules = set()
  kind_of_unconverted_modules = set()

  input_all_deps = set()
  input_unconverted_deps = set()
  input_modules = set()

  for module, deps in sorted(modules.items()):
    unconverted_deps = set(
        dep.name for dep in deps if not dep.is_converted_or_skipped(converted))

    # replace deps count with transitive deps rather than direct deps count
    module = ModuleInfo(
        module.name,
        module.kind,
        module.dirname,
        module.created_by,
        len(deps),
        module.is_converted(converted),
    )

    for dep in unconverted_deps:
      all_unconverted_modules[dep].add(module)

    if not module.is_converted_or_skipped(converted) or (
        show_converted and not module.is_converted_or_skipped(set())):
      if show_converted:
        full_deps = set(f"{dep.short_string(converted)}" for dep in deps)
        blocked_modules[module].update(full_deps)
      else:
        blocked_modules[module].update(unconverted_deps)

    if not module.is_converted_or_skipped(converted):
      dirs_with_unconverted_modules.add(module.dirname)
      kind_of_unconverted_modules.add(module.kind)

    if module.name in input_modules_names:
      input_modules.add(InputModule(module, len(deps), len(unconverted_deps)))
      input_all_deps.update(deps)
      input_unconverted_deps.update(unconverted_deps)

  return ReportData(
      input_modules=input_modules,
      total_deps=input_all_deps,
      unconverted_deps=input_unconverted_deps,
      all_unconverted_modules=all_unconverted_modules,
      blocked_modules=blocked_modules,
      dirs_with_unconverted_modules=dirs_with_unconverted_modules,
      kind_of_unconverted_modules=kind_of_unconverted_modules,
      converted=converted,
      show_converted=show_converted,
  )


def generate_proto(report_data, file_name):
  message = bp2build_pb2.Bp2buildConversionProgress(
      root_modules=[m.module.name for m in report_data.input_modules],
      num_deps=len(report_data.total_deps),
  )
  for module, unconverted_deps in report_data.blocked_modules.items():
    message.unconverted.add(
        name=module.name,
        directory=module.dirname,
        type=module.kind,
        unconverted_deps=unconverted_deps,
        num_deps=module.num_deps,
    )

  with open(file_name, "wb") as f:
    f.write(message.SerializeToString())


def generate_report(report_data):
  report_lines = []
  input_module_str = ", ".join(
      str(i) for i in sorted(report_data.input_modules))

  report_lines.append("# bp2build progress report for: %s\n" % input_module_str)

  if report_data.show_converted:
    report_lines.append(
        "# progress report includes data both for converted and unconverted modules"
    )

  total = len(report_data.total_deps)
  unconverted = len(report_data.unconverted_deps)
  converted = total - unconverted
  percent = converted / total * 100
  report_lines.append(f"Percent converted: {percent:.2f} ({converted}/{total})")
  report_lines.append(f"Total unique unconverted dependencies: {unconverted}")

  report_lines.append("Ignored module types: %s\n" %
                      sorted(dependency_analysis.IGNORED_KINDS))
  report_lines.append("# Transitive dependency closure:")

  current_count = -1
  for module, unconverted_deps in sorted(
      report_data.blocked_modules.items(), key=lambda x: len(x[1])):
    count = len(unconverted_deps)
    if current_count != count:
      report_lines.append(f"\n{count} unconverted deps remaining:")
      current_count = count
    report_lines.append("{module}: {deps}".format(
        module=module, deps=", ".join(sorted(unconverted_deps))))

  report_lines.append("\n")
  report_lines.append("# Unconverted deps of {}:\n".format(input_module_str))
  for count, dep in sorted(
      ((len(unconverted), dep)
       for dep, unconverted in report_data.all_unconverted_modules.items()),
      reverse=True):
    report_lines.append("%s: blocking %d modules" % (dep, count))

  report_lines.append("\n")
  report_lines.append("# Dirs with unconverted modules:\n\n{}".format("\n".join(
      sorted(report_data.dirs_with_unconverted_modules))))

  report_lines.append("\n")
  report_lines.append("# Kinds with unconverted modules:\n\n{}".format(
      "\n".join(sorted(report_data.kind_of_unconverted_modules))))

  report_lines.append("\n")
  report_lines.append("# Converted modules:\n\n%s" %
                      "\n".join(sorted(report_data.converted)))

  report_lines.append("\n")
  report_lines.append(
      "Generated by: https://cs.android.com/android/platform/superproject/+/master:build/bazel/scripts/bp2build_progress/bp2build_progress.py"
  )
  report_lines.append("Generated at: %s" %
                      datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S %z"))

  return "\n".join(report_lines)


def adjacency_list_from_json(
    module_graph: ...,
    ignore_by_name: List[str],
    ignore_java_auto_deps: bool,
    top_level_modules: List[str],
    collect_transitive_dependencies: bool = True,
) -> Dict[ModuleInfo, Set[ModuleInfo]]:
  def filter_by_name(json):
    return json["Name"] in top_level_modules

  module_adjacency_list = collections.defaultdict(set)
  name_to_info = {}

  def collect_dependencies(module, deps_names):
    module_info = None
    name = module["Name"]
    name_to_info.setdefault(
        name,
        ModuleInfo(
            name=name,
            created_by=module["CreatedBy"],
            kind=module["Type"],
            dirname=os.path.dirname(module["Blueprint"]),
            num_deps=len(deps_names),
        ))

    module_info = name_to_info[name]

    # ensure module_info added to adjacency list even with no deps
    module_adjacency_list[module_info].update(set())
    for dep in deps_names:
      # this may occur if there is a cycle between a module and created_by
      # module
      if not dep in name_to_info:
        continue
      dep_module_info = name_to_info[dep]
      module_adjacency_list[module_info].add(dep_module_info)
      if collect_transitive_dependencies:
        module_adjacency_list[module_info].update(
            module_adjacency_list.get(dep_module_info, set()))

  dependency_analysis.visit_json_module_graph_post_order(
      module_graph, ignore_by_name, ignore_java_auto_deps, filter_by_name, collect_dependencies)

  return module_adjacency_list


def adjacency_list_from_queryview_xml(
    module_graph: xml.etree.ElementTree,
    ignore_by_name: List[str],
    top_level_modules: List[str],
    collect_transitive_dependencies: bool = True
) -> Dict[ModuleInfo, Set[ModuleInfo]]:

  def filter_by_name(module):
    return module.name in top_level_modules

  module_adjacency_list = collections.defaultdict(set)
  name_to_info = {}

  def collect_dependencies(module, deps_names):
    module_info = None
    name_to_info.setdefault(
        module.name,
        ModuleInfo(
            name=module.name,
            kind=module.kind,
            dirname=module.dirname,
            # required so that it cannot be forgotten when updating num_deps
            created_by=None,
            num_deps=len(deps_names),
        ))
    module_info = name_to_info[module.name]

    # ensure module_info added to adjacency list even with no deps
    module_adjacency_list[module_info].update(set())
    for dep in deps_names:
      dep_module_info = name_to_info[dep]
      module_adjacency_list[module_info].add(dep_module_info)
      if collect_transitive_dependencies:
        module_adjacency_list[module_info].update(
            module_adjacency_list.get(dep_module_info, set()))

  dependency_analysis.visit_queryview_xml_module_graph_post_order(
      module_graph, ignore_by_name, filter_by_name, collect_dependencies)

  return module_adjacency_list


def get_module_adjacency_list(
    top_level_modules: List[str],
    use_queryview: bool,
    ignore_by_name: List[str],
    ignore_java_auto_deps: bool = False,
    collect_transitive_dependencies: bool = True,
    banchan_mode: bool = False) -> Dict[ModuleInfo, Set[ModuleInfo]]:
  # The main module graph containing _all_ modules in the Soong build,
  # and the list of converted modules.
  try:
    if use_queryview:
      module_graph = dependency_analysis.get_queryview_module_info(
          top_level_modules, banchan_mode)
      module_adjacency_list = adjacency_list_from_queryview_xml(
          module_graph, ignore_by_name, top_level_modules,
          collect_transitive_dependencies)
    else:
      module_graph = dependency_analysis.get_json_module_info(banchan_mode)
      module_adjacency_list = adjacency_list_from_json(
          module_graph,
          ignore_by_name,
          ignore_java_auto_deps,
          top_level_modules,
          collect_transitive_dependencies,
      )
  except subprocess.CalledProcessError as err:
    sys.exit(f"""Error running: '{' '.join(err.cmd)}':"
Stdout:
{err.stdout.decode('utf-8') if err.stdout else ''}
Stderr:
{err.stderr.decode('utf-8') if err.stderr else ''}""")

  return module_adjacency_list


def add_created_by_to_converted(
    converted: Set[str],
    module_adjacency_list: Dict[ModuleInfo, Set[ModuleInfo]]) -> Set[str]:
  modules_by_name = {m.name: m for m in module_adjacency_list.keys()}

  converted_modules = set()
  converted_modules.update(converted)

  def _update_converted(module_name):
    if module_name in converted_modules:
      return True
    if module_name not in modules_by_name:
      return False
    module = modules_by_name[module_name]
    if module.created_by and _update_converted(module.created_by):
      converted_modules.add(module_name)
      return True
    return False

  for module in modules_by_name.keys():
    _update_converted(module)

  return converted_modules


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("mode", help="mode: graph or report")
  parser.add_argument(
      "--module",
      "-m",
      action="append",
      required=True,
      help="name(s) of Soong module(s). Multiple modules only supported for report"
  )
  parser.add_argument(
      "--use-queryview",
      action="store_true",
      help="whether to use queryview or module_info")
  parser.add_argument(
      "--ignore-by-name",
      default="",
      help=(
          "Comma-separated list. When building the tree of transitive"
          " dependencies, will not follow dependency edges pointing to module"
          " names listed by this flag."
      ),
  )
  parser.add_argument(
      "--ignore-java-auto-deps",
      action="store_true",
      help="whether to ignore automatically added java deps",
  )
  parser.add_argument(
      "--banchan",
      action="store_true",
      help="whether to run Soong in a banchan configuration rather than lunch",
  )
  parser.add_argument(
      "--proto-file",
      help="Path to write proto output",
  )
  parser.add_argument(
      "--out-file",
      "-o",
      type=argparse.FileType("w"),
      default="-",
      help="Path to write output, if omitted, writes to stdout",
  )
  parser.add_argument(
      "--show-converted",
      "-s",
      action="store_true",
      help="Show bp2build-converted modules in addition to the unconverted dependencies to see full dependencies post-migration. By default converted dependencies are not shown",
  )
  args = parser.parse_args()

  if len(args.module) > 1 and args.mode == "graph":
    sys.exit(f"Can only support one module with mode {args.mode}")
  if args.proto_file and args.mode == "graph":
    sys.exit(f"Proto file only supported for report mode, not {args.mode}")

  mode = args.mode
  use_queryview = args.use_queryview
  ignore_by_name = args.ignore_by_name.split(",")
  banchan_mode = args.banchan
  modules = set(args.module)

  converted = dependency_analysis.get_bp2build_converted_modules()

  module_adjacency_list = get_module_adjacency_list(
      modules,
      use_queryview,
      ignore_by_name,
      collect_transitive_dependencies=mode != "graph",
      banchan_mode=banchan_mode)

  converted = add_created_by_to_converted(converted, module_adjacency_list)

  output_file = args.out_file
  if mode == "graph":
    dot_file = generate_dot_file(module_adjacency_list, converted,
                                 args.show_converted)
    output_file.write(dot_file)
  elif mode == "report":
    report_data = generate_report_data(module_adjacency_list, converted,
                                       modules, args.show_converted)
    report = generate_report(report_data)
    output_file.write(report)
    if args.proto_file:
      generate_proto(report_data, args.proto_file)
  else:
    raise RuntimeError("unknown mode: %s" % mode)


if __name__ == "__main__":
  main()
