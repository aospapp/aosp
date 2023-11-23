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
"""Tests for bp2build-progress."""

import bp2build_progress
import collections
import dependency_analysis
import queryview_xml
import soong_module_json
import unittest
import unittest.mock

_queryview_graph = queryview_xml.make_graph([
    queryview_xml.make_module(
        '//pkg:a', 'a', 'type1', dep_names=['//pkg:b', '//other:c']),
    queryview_xml.make_module('//pkg:b', 'b', 'type2', dep_names=['//pkg:d']),
    queryview_xml.make_module('//pkg:d', 'd', 'type2'),
    queryview_xml.make_module(
        '//other:c', 'c', 'type2', dep_names=['//other:e']),
    queryview_xml.make_module('//other:e', 'e', 'type3'),
    queryview_xml.make_module('//pkg2:f', 'f', 'type4'),
    queryview_xml.make_module('//pkg3:g', 'g', 'type5'),
])

_soong_module_graph = [
    soong_module_json.make_module(
        'a',
        'type1',
        blueprint='pkg/Android.bp',
        deps=[soong_module_json.make_dep('b'),
              soong_module_json.make_dep('c')]),
    soong_module_json.make_module(
        'b',
        'type2',
        blueprint='pkg/Android.bp',
        deps=[soong_module_json.make_dep('d')]),
    soong_module_json.make_module('d', 'type2', blueprint='pkg/Android.bp'),
    soong_module_json.make_module(
        'c',
        'type2',
        blueprint='other/Android.bp',
        deps=[soong_module_json.make_dep('e')]),
    soong_module_json.make_module('e', 'type3', blueprint='other/Android.bp'),
    soong_module_json.make_module('f', 'type4', blueprint='pkg2/Android.bp'),
    soong_module_json.make_module('g', 'type5', blueprint='pkg3/Android.bp'),
]

_soong_module_graph_created_by_no_loop = [
    soong_module_json.make_module(
        'a', 'type1', blueprint='pkg/Android.bp', created_by='b'),
    soong_module_json.make_module('b', 'type2', blueprint='pkg/Android.bp'),
]

_soong_module_graph_created_by_loop = [
    soong_module_json.make_module(
        'a',
        'type1',
        deps=[soong_module_json.make_dep('b')],
        blueprint='pkg/Android.bp'),
    soong_module_json.make_module(
        'b', 'type2', blueprint='pkg/Android.bp', created_by='a'),
]


class Bp2BuildProgressTest(unittest.TestCase):

  @unittest.mock.patch(
      'dependency_analysis.get_queryview_module_info',
      autospec=True,
      return_value=_queryview_graph)
  def test_get_module_adjacency_list_queryview_transitive_deps(self, _):
    adjacency_dict = bp2build_progress.get_module_adjacency_list(
        ['a', 'f'], True, set(), False, True, False
    )

    a = bp2build_progress.ModuleInfo(
        name='a', kind='type1', dirname='pkg', num_deps=2, created_by=None)
    b = bp2build_progress.ModuleInfo(
        name='b', kind='type2', dirname='pkg', num_deps=1, created_by=None)
    c = bp2build_progress.ModuleInfo(
        name='c', kind='type2', dirname='other', num_deps=1, created_by=None)
    d = bp2build_progress.ModuleInfo(
        name='d', kind='type2', dirname='pkg', num_deps=0, created_by=None)
    e = bp2build_progress.ModuleInfo(
        name='e', kind='type3', dirname='other', num_deps=0, created_by=None)
    f = bp2build_progress.ModuleInfo(
        name='f', kind='type4', dirname='pkg2', num_deps=0, created_by=None)
    expected_adjacency_dict = collections.defaultdict(set)
    expected_adjacency_dict[a] = set([b, c, d, e])
    expected_adjacency_dict[b] = set([d])
    expected_adjacency_dict[c] = set([e])
    expected_adjacency_dict[d].update(set())
    expected_adjacency_dict[e].update(set())
    expected_adjacency_dict[f].update(set())
    self.assertDictEqual(adjacency_dict, expected_adjacency_dict)

  @unittest.mock.patch(
      'dependency_analysis.get_queryview_module_info',
      autospec=True,
      return_value=_queryview_graph)
  def test_get_module_adjacency_list_queryview_direct_deps(self, _):
    adjacency_dict = bp2build_progress.get_module_adjacency_list(
        ['a', 'f'], True, set(), False, False
    )

    a = bp2build_progress.ModuleInfo(
        name='a', kind='type1', dirname='pkg', num_deps=2, created_by=None)
    b = bp2build_progress.ModuleInfo(
        name='b', kind='type2', dirname='pkg', num_deps=1, created_by=None)
    c = bp2build_progress.ModuleInfo(
        name='c', kind='type2', dirname='other', num_deps=1, created_by=None)
    d = bp2build_progress.ModuleInfo(
        name='d', kind='type2', dirname='pkg', num_deps=0, created_by=None)
    e = bp2build_progress.ModuleInfo(
        name='e', kind='type3', dirname='other', num_deps=0, created_by=None)
    f = bp2build_progress.ModuleInfo(
        name='f', kind='type4', dirname='pkg2', num_deps=0, created_by=None)

    expected_adjacency_dict = collections.defaultdict(set)
    expected_adjacency_dict[a] = set([b, c])
    expected_adjacency_dict[b] = set([d])
    expected_adjacency_dict[c] = set([e])
    expected_adjacency_dict[d].update(set())
    expected_adjacency_dict[e].update(set())
    expected_adjacency_dict[f].update(set())
    self.assertDictEqual(adjacency_dict, expected_adjacency_dict)

  @unittest.mock.patch(
      'dependency_analysis.get_json_module_info',
      autospec=True,
      return_value=_soong_module_graph)
  def test_get_module_adjacency_list_soong_module_transitive_deps(self, _):
    adjacency_dict = bp2build_progress.get_module_adjacency_list(
        ['a', 'f'], False, set(), False, True, False
    )

    a = bp2build_progress.ModuleInfo(
        name='a', kind='type1', dirname='pkg', num_deps=2, created_by='')
    b = bp2build_progress.ModuleInfo(
        name='b', kind='type2', dirname='pkg', num_deps=1, created_by='')
    c = bp2build_progress.ModuleInfo(
        name='c', kind='type2', dirname='other', num_deps=1, created_by='')
    d = bp2build_progress.ModuleInfo(
        name='d', kind='type2', dirname='pkg', num_deps=0, created_by='')
    e = bp2build_progress.ModuleInfo(
        name='e', kind='type3', dirname='other', num_deps=0, created_by='')
    f = bp2build_progress.ModuleInfo(
        name='f', kind='type4', dirname='pkg2', num_deps=0, created_by='')

    expected_adjacency_dict = collections.defaultdict(set)
    expected_adjacency_dict[a] = set([b, c, d, e])
    expected_adjacency_dict[b] = set([d])
    expected_adjacency_dict[c] = set([e])
    expected_adjacency_dict[d].update(set())
    expected_adjacency_dict[e].update(set())
    expected_adjacency_dict[f].update(set())
    self.assertDictEqual(adjacency_dict, expected_adjacency_dict)

  @unittest.mock.patch(
      'dependency_analysis.get_json_module_info',
      autospec=True,
      return_value=_soong_module_graph)
  def test_get_module_adjacency_list_soong_module_direct_deps(self, _):
    adjacency_dict = bp2build_progress.get_module_adjacency_list(['a', 'f'],
                                                                 False, set(),
                                                                 False, False)

    a = bp2build_progress.ModuleInfo(
        name='a', kind='type1', dirname='pkg', num_deps=2, created_by='')
    b = bp2build_progress.ModuleInfo(
        name='b', kind='type2', dirname='pkg', num_deps=1, created_by='')
    c = bp2build_progress.ModuleInfo(
        name='c', kind='type2', dirname='other', num_deps=1, created_by='')
    d = bp2build_progress.ModuleInfo(
        name='d', kind='type2', dirname='pkg', num_deps=0, created_by='')
    e = bp2build_progress.ModuleInfo(
        name='e', kind='type3', dirname='other', num_deps=0, created_by='')
    f = bp2build_progress.ModuleInfo(
        name='f', kind='type4', dirname='pkg2', num_deps=0, created_by='')

    expected_adjacency_dict = collections.defaultdict(set)
    expected_adjacency_dict[a] = set([b, c])
    expected_adjacency_dict[b] = set([d])
    expected_adjacency_dict[c] = set([e])
    expected_adjacency_dict[d].update(set())
    expected_adjacency_dict[e].update(set())
    expected_adjacency_dict[f].update(set())
    self.assertDictEqual(adjacency_dict, expected_adjacency_dict)

  @unittest.mock.patch(
      'dependency_analysis.get_json_module_info',
      autospec=True,
      return_value=_soong_module_graph_created_by_no_loop)
  def test_get_module_adjacency_list_soong_module_created_by(self, _):
    adjacency_dict = bp2build_progress.get_module_adjacency_list(['a', 'f'],
                                                                 False, set(),
                                                                 True, False)

    a = bp2build_progress.ModuleInfo(
        name='a', kind='type1', dirname='pkg', num_deps=1, created_by='b')
    b = bp2build_progress.ModuleInfo(
        name='b', kind='type2', dirname='pkg', num_deps=0, created_by='')

    expected_adjacency_dict = collections.defaultdict(set)
    expected_adjacency_dict[a].update(set([b]))
    expected_adjacency_dict[b].update(set())
    self.assertDictEqual(adjacency_dict, expected_adjacency_dict)

  @unittest.mock.patch(
      'dependency_analysis.get_json_module_info',
      autospec=True,
      return_value=_soong_module_graph_created_by_loop)
  def test_get_module_adjacency_list_soong_module_created_by_loop(self, _):
    adjacency_dict = bp2build_progress.get_module_adjacency_list(['a', 'f'],
                                                                 False, set(),
                                                                 True, False)

    a = bp2build_progress.ModuleInfo(
        name='a', kind='type1', dirname='pkg', num_deps=1, created_by='')
    b = bp2build_progress.ModuleInfo(
        name='b', kind='type2', dirname='pkg', num_deps=1, created_by='a')

    expected_adjacency_dict = collections.defaultdict(set)
    expected_adjacency_dict[a].update(set([b]))
    expected_adjacency_dict[b].update(set())
    self.assertDictEqual(adjacency_dict, expected_adjacency_dict)

  def test_generate_report_data(self):
    a = bp2build_progress.ModuleInfo(
        name='a', kind='type1', dirname='pkg', num_deps=4, created_by=None)
    b = bp2build_progress.ModuleInfo(
        name='b', kind='type2', dirname='pkg', num_deps=1, created_by=None)
    c = bp2build_progress.ModuleInfo(
        name='c', kind='type2', dirname='other', num_deps=1, created_by=None)
    d = bp2build_progress.ModuleInfo(
        name='d', kind='type2', dirname='pkg', num_deps=0, created_by=None)
    e = bp2build_progress.ModuleInfo(
        name='e', kind='type3', dirname='other', num_deps=0, created_by=None)
    f = bp2build_progress.ModuleInfo(
        name='f', kind='type4', dirname='pkg2', num_deps=2, created_by=None)
    g = bp2build_progress.ModuleInfo(
        name='g', kind='type4', dirname='pkg2', num_deps=2, created_by=None)

    module_graph = collections.defaultdict(set)
    module_graph[a] = set([b, c, d, e])
    module_graph[b] = set([d])
    module_graph[c] = set([e])
    module_graph[d].update(set())
    module_graph[e].update(set())
    module_graph[f].update(set([b, g]))
    module_graph[g].update(set())

    report_data = bp2build_progress.generate_report_data(
        module_graph, {'d', 'e', 'g'}, {'a', 'f'})

    all_unconverted_modules = collections.defaultdict(set)
    all_unconverted_modules['b'].update({a, f})
    all_unconverted_modules['c'].update({a})

    blocked_modules = collections.defaultdict(set)
    blocked_modules[a].update({'b', 'c'})
    blocked_modules[b].update(set())
    blocked_modules[c].update(set())
    blocked_modules[f].update(set({'b'}))

    expected_report_data = bp2build_progress.ReportData(
        input_modules={
            bp2build_progress.InputModule(a, 4, 2),
            bp2build_progress.InputModule(f, 2, 1)
        },
        total_deps={b, c, d, e, g},
        unconverted_deps={'b', 'c'},
        all_unconverted_modules=all_unconverted_modules,
        blocked_modules=blocked_modules,
        dirs_with_unconverted_modules={'pkg', 'other', 'pkg2'},
        kind_of_unconverted_modules={'type1', 'type2', 'type4'},
        converted={'d', 'e', 'g'},
        show_converted=False,
    )

    self.assertEqual(report_data, expected_report_data)

  def test_generate_report_data_show_converted(self):
    a = bp2build_progress.ModuleInfo(
        name='a', kind='type1', dirname='pkg', num_deps=2, created_by=None)
    b = bp2build_progress.ModuleInfo(
        name='b', kind='type2', dirname='pkg2', num_deps=0, created_by=None, converted=True)
    c = bp2build_progress.ModuleInfo(
        name='c', kind='type3', dirname='other', num_deps=0, created_by=None)

    module_graph = collections.defaultdict(set)
    module_graph[a] = set([b, c])
    module_graph[b].update(set())
    module_graph[c].update(set())

    report_data = bp2build_progress.generate_report_data(
        module_graph, {'b'}, {'a'}, show_converted=True)

    all_unconverted_modules = collections.defaultdict(set)
    all_unconverted_modules['c'].update({a})

    blocked_modules = collections.defaultdict(set)
    blocked_modules[a].update({'b (c)', 'c'})
    blocked_modules[b].update(set())
    blocked_modules[c].update(set())

    expected_report_data = bp2build_progress.ReportData(
        input_modules={
            bp2build_progress.InputModule(a, 2, 1),
        },
        total_deps={b, c},
        unconverted_deps={'c'},
        all_unconverted_modules=all_unconverted_modules,
        blocked_modules=blocked_modules,
        dirs_with_unconverted_modules={'pkg', 'other'},
        kind_of_unconverted_modules={'type1', 'type3'},
        converted={'b'},
        show_converted=True,
    )

    self.assertEqual(report_data, expected_report_data)

  def test_generate_dot_file(self):
    self.maxDiff = None
    a = bp2build_progress.ModuleInfo(
        name='a', kind='type1', dirname='pkg', num_deps=2, created_by=None)
    b = bp2build_progress.ModuleInfo(
        name='b', kind='type2', dirname='pkg', num_deps=1, created_by=None)
    c = bp2build_progress.ModuleInfo(
        name='c', kind='type2', dirname='other', num_deps=1, created_by=None)
    d = bp2build_progress.ModuleInfo(
        name='d', kind='type2', dirname='pkg', num_deps=0, created_by=None)
    e = bp2build_progress.ModuleInfo(
        name='e', kind='type2', dirname='other', num_deps=0, created_by=None)

    module_graph = collections.defaultdict(set)
    module_graph[a] = set([b, c])
    module_graph[b] = set([d])
    module_graph[c] = set([e])
    module_graph[d] = set([])
    module_graph[e] = set([])

    dot_graph = bp2build_progress.generate_dot_file(module_graph, {'e'}, False)

    expected_dot_graph = """
digraph mygraph {{
  node [shape=box];

  "a" [label="a\\ntype1" color=black, style=filled, fillcolor=tomato]
  "a" -> "b"
  "a" -> "c"
  "b" [label="b\\ntype2" color=black, style=filled, fillcolor=tomato]
  "b" -> "d"
  "c" [label="c\\ntype2" color=black, style=filled, fillcolor=yellow]
  "d" [label="d\\ntype2" color=black, style=filled, fillcolor=yellow]
}}
"""
    self.assertEqual(dot_graph, expected_dot_graph)

  def test_generate_dot_file_show_converted(self):
    self.maxDiff = None
    a = bp2build_progress.ModuleInfo(
        name='a', kind='type1', dirname='pkg', num_deps=2, created_by=None)
    b = bp2build_progress.ModuleInfo(
        name='b', kind='type2', dirname='pkg', num_deps=1, created_by=None)
    c = bp2build_progress.ModuleInfo(
        name='c', kind='type2', dirname='other', num_deps=1, created_by=None)
    d = bp2build_progress.ModuleInfo(
        name='d', kind='type2', dirname='pkg', num_deps=0, created_by=None)
    e = bp2build_progress.ModuleInfo(
        name='e', kind='type2', dirname='other', num_deps=0, created_by=None)

    module_graph = collections.defaultdict(set)
    module_graph[a] = set([b, c])
    module_graph[b] = set([d])
    module_graph[c] = set([e])
    module_graph[d] = set([])
    module_graph[e] = set([])

    dot_graph = bp2build_progress.generate_dot_file(module_graph, {'e'}, True)

    expected_dot_graph = """
digraph mygraph {{
  node [shape=box];

  "a" [label="a\\ntype1" color=black, style=filled, fillcolor=tomato]
  "a" -> "b"
  "a" -> "c"
  "b" [label="b\\ntype2" color=black, style=filled, fillcolor=tomato]
  "b" -> "d"
  "c" [label="c\\ntype2" color=black, style=filled, fillcolor=yellow]
  "c" -> "e"
  "d" [label="d\\ntype2" color=black, style=filled, fillcolor=yellow]
  "e" [label="e\\ntype2" color=black, style=filled, fillcolor=dodgerblue]
}}
"""
    self.assertEqual(dot_graph, expected_dot_graph)


if __name__ == '__main__':
  unittest.main()
