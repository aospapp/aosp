#/usr/bin/env python3
#
# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.

#
# Copyright 2009 Google Inc. All Rights Reserved.
"""Lightweight Sponge client, supporting upload via the HTTP Redirector.

Does not depend on protobufs, Stubby, works on Windows, builds without blaze.
"""

__author__ = 'klm@google.com (Michael Klepikov)'

import collections
import os
import re
import socket
import time

try:
    import httpclient as httplib
except ImportError:
    import httplib

try:
    import StringIO
except ImportError:
    from io import StringIO

try:
    import google3  # pylint: disable=g-import-not-at-top
    from google3.testing.coverage.util import bitfield  # pylint: disable=g-import-not-at-top
except ImportError:
    pass  # Running outside of google3

import SimpleXMLWriter  # pylint: disable=g-import-not-at-top


class Entity(object):
    """Base class for all Sponge client entities. Provides XML s11n basics."""

    def WriteXmlToStream(self, ostream, encoding='UTF-8'):
        """Writes out all attributes with string/numeric value to supplied ostream.

    Args:
      ostream: A file or file-like object. This object must implement a write
               method.
      encoding: Optionally specify encoding to be used.
    """
        xml_writer = SimpleXMLWriter.XMLWriter(ostream, encoding)
        self.WriteXml(xml_writer)

    def WriteXml(self, xml_writer):
        """Writes out all attributes that have a string or numeric value.

    Args:
      xml_writer: google3.third_party.python.elementtree.SimpleXMLWriter.
    """
        for attr_name in dir(self):  # Guaranteed sorted alphabetically
            assert attr_name
            if attr_name.startswith(
                    '_') or attr_name[0].upper() == attr_name[0]:
                continue  # Skip non-public attributes and public constants
            if hasattr(self, '_permitted_attributes'):
                assert attr_name in self._permitted_attributes
            if (hasattr(self, '_custom_write_attributes')
                    and attr_name in self._custom_write_attributes):
                # An attribute that has custom serialization code
                continue
            value = self.__getattribute__(attr_name)
            if callable(value):
                continue  # Skip methods
            Entity._WriteValue(xml_writer, attr_name, value)

    def GetXmlString(self):
        """Returns a string with XML produced by WriteXml()."""
        xml_out = StringIO.StringIO()
        self.WriteXmlToStream(xml_out)
        xml_str = xml_out.getvalue()
        xml_out.close()
        return xml_str

    @staticmethod
    def _WriteValue(xml_writer, name, value):
        if value is None:
            return  # Do not serialize None (but do serialize 0 or empty string)
        elif isinstance(value, unicode):
            xml_writer.element(name, value)  # Will write out as UTF-8
        elif isinstance(value, str):
            # A non-Unicode string. By default the encoding is 'ascii',
            # where 8-bit characters cause an encoding exception
            # when a protobuf encodes itself on the HTTP Redirector side.
            # Force 'latin' encoding, which allows 8-bit chars.
            # Still it's only a guess which could be wrong, so use errors='replace'
            # to produce an 'invalid character' Unicode placeholder in such cases.
            # For the caller, the cleanest thing to do is pass a proper
            # Unicode string if it may contain international characters.
            xml_writer.element(
                name, unicode(value, encoding='latin', errors='replace'))
        elif isinstance(value, bool):
            # Careful! Check for this before isinstance(int) -- true for bools
            xml_writer.element(name, str(value).lower())
        elif (isinstance(value, int) or isinstance(value, long)
              or isinstance(value, float)):
            xml_writer.element(name, str(value))
        elif hasattr(value, 'WriteXml'):
            # An object that knows how to write itself
            xml_writer.start(name)
            value.WriteXml(xml_writer)
            xml_writer.end()
        elif isinstance(value, list) or isinstance(value, tuple):
            # Sequence names are often plural, but the element name must be single
            if name.endswith('s'):
                value_element_name = name[0:len(name) - 1]
            else:
                value_element_name = name
            for sequence_value in value:
                Entity._WriteValue(xml_writer, value_element_name,
                                   sequence_value)
        elif hasattr(value, 'iteritems'):  # A mapping type
            # Map names are often plural, but the element name must be single
            if name.endswith('s'):
                map_element_name = name[0:len(name) - 1]
            else:
                map_element_name = name
            Entity._WriteNameValuesXml(xml_writer, map_element_name, value,
                                       'name', 'value')

    @staticmethod
    def _WriteNameValuesXml(xml_writer, element_name, name_value_dict,
                            name_elem, value_elem):
        """Writes a dict as XML elements with children as keys (names) and values.

    Args:
      xml_writer: google3.third_party.python.elementtree.SimpleXMLWriter.
      element_name: name of enclosing element for the name-value pair elements.
      name_value_dict: the dict to write.
      name_elem: name of the "name" element.
      value_elem: name of the "value" element.
    """
        if name_value_dict:
            for name in sorted(
                    name_value_dict):  # Guarantee order for testability
                value = name_value_dict[name]
                xml_writer.start(element_name)
                Entity._WriteValue(xml_writer, name_elem, name)
                Entity._WriteValue(xml_writer, value_elem, value)
                xml_writer.end()


class LcovUtils(object):
    """Just groups Lcov handling."""

    @staticmethod
    def GetFilename(lcov_section):
        return lcov_section.split('\n', 1)[0].strip()[3:]

    @staticmethod
    def LcovSectionToBitFields(lcov_section):
        """Fill in bit fields that represent covered and instrumented lines.

    Note that lcov line numbers start from 1 while sponge expects line numbers
    to start from 0, hence the line_num-1 is required.

    Args:
      lcov_section: string, relevant section of lcov

    Returns:
      Tuple of google3.testing.coverage.util.bitfield objects. First bitfield
      represents lines covered. Second bitfield represents total lines
      instrumented.
    """
        covered_bf = bitfield.BitField()
        instrumented_bf = bitfield.BitField()
        for line in lcov_section.split('\n'):
            if line.startswith('DA:'):
                line_num, times_hit = line.strip()[3:].split(',')
                instrumented_bf.SetBit(int(line_num) - 1)
                if times_hit != '0':
                    covered_bf.SetBit(int(line_num) - 1)
            elif line.startswith('FN:'):
                pass  # Function coverage will be supported soon.
        return covered_bf, instrumented_bf

    @staticmethod
    def UrlEncode(bit_field):
        """Convert bit field into url-encoded string of hex representation."""
        if not bit_field.CountBitsSet():
            return '%00'
        else:
            ret_str = ''
            for c in bit_field.Get():
                ret_str += '%%%02x' % ord(c)
        return ret_str.upper()

    @staticmethod
    def WriteBitfieldXml(xml_writer, name, value):
        encoded_value = LcovUtils.UrlEncode(value)
        xml_writer.element(
            name, unicode(encoded_value, encoding='latin', errors='replace'))


class FileCoverage(Entity):
    """Represents Sponge FileCoverage.

  instrumented_lines and executed_lines are bit fields with following format:
  Divide line number by 8 to get index into string.
  Mod line number by 8 to get bit number (0 = LSB, 7 = MSB).

  Attributes:
    file_name: name of the file this entry represents.
    location: the location of the file: PERFORCE, MONDRIAN, UNKNOWN.
    revision: stores the revision number of the file when location is PERFORCE.
    instrumented_lines: bitfield of line numbers that have been instrumented
    executed_lines: bitfield of line numbers that have been executed
    md5: string. Hex representation of the md5 checksum for the file
         "file_name". This should only be set if file_name is open in the
         client.
    pending_cl: string. CL containing the file "file_name" if it is checked out
                at the time this invocation is sent out. Should only be set if
                location is MONDRIAN.
    sourcerer_depot: string. [optional] The sourcerer depot to use in coverage
        tab. Only required if your code is stored in one of the PerforceN
        servers and therefore has it's own Sourcerer instance. For example,
        Perforce11 code should set sourcerer_depot to "s11".
  """

    # location
    PERFORCE = 0
    MONDRIAN = 1
    UNKNOWN = 2

    def __init__(self):
        super(FileCoverage, self).__init__()
        self.file_name = None
        self.location = None
        self.revision = None
        self.md5 = None
        self.pending_cl = None
        self.executed_lines = None
        self.instrumented_lines = None
        self.sourcerer_depot = None
        self._custom_write_attributes = [
            'executed_lines', 'instrumented_lines'
        ]

    def WriteXml(self, xml_writer):
        """Writes this object as XML suitable for Sponge HTTP Redirector.

    Args:
      xml_writer: google3.third_party.python.elementtree.SimpleXMLWriter.
    """
        super(FileCoverage, self).WriteXml(xml_writer)
        for attr_name in self._custom_write_attributes:
            value = self.__getattribute__(attr_name)
            if value:
                LcovUtils.WriteBitfieldXml(xml_writer, attr_name, value)

    def Combine(self, other_file_coverage):
        """Combines 2 FileCoverage objects.

    This method expects all fields of the 2 FileCoverage objects to be identical
    except for the executed_lines and instrumented_lines fields which it will
    combine into 1 by performing logical OR operation on executed_lines and
    instrumented_lines bitfields. All other fields are copied directly from
    source.

    Args:
      other_file_coverage: FileCoverage object to combine with

    Returns:
      The combined FileCoverage object
    """
        assert self.file_name == other_file_coverage.file_name
        assert self.location == other_file_coverage.location
        assert self.revision == other_file_coverage.revision
        assert self.md5 == other_file_coverage.md5
        assert self.pending_cl == other_file_coverage.pending_cl

        result_file_coverage = FileCoverage()
        result_file_coverage.file_name = self.file_name
        result_file_coverage.location = self.location
        result_file_coverage.revision = self.revision
        result_file_coverage.md5 = self.md5
        result_file_coverage.pending_cl = self.pending_cl

        result_file_coverage.executed_lines = self.executed_lines.Or(
            other_file_coverage.executed_lines)
        result_file_coverage.instrumented_lines = self.instrumented_lines.Or(
            other_file_coverage.instrumented_lines)

        return result_file_coverage

    def FromLcovSection(self, lcov_section):
        """Fill in coverage from relevant lcov section.

    An lcov section starts with a line starting with 'SF:' followed by filename
    of covered file and is followed by 1 or more lines of coverage data starting
    with 'DA:' or 'FN:'.

    'DA:'lines have the format:
      'DA: line_num, times_covered'

    line_num is the line number of source file starting from 1.
    times_covered is the number of times the line was covered, starting from 0.

    'FN:' is for function coverage and is not supported yet.

    An example section would look like this:
      SF:/Volumes/BuildData/PulseData/data/googleclient/picasa4/yt/safe_str.h
      DA:1412,12
      DA:1413,12
      DA:1414,0
      DA:1415,0

    Args:
      lcov_section: string, relevant section of lcov file.
    """
        if lcov_section:
            assert lcov_section.startswith('SF:')

            self.file_name = LcovUtils.GetFilename(lcov_section)
            self.executed_lines, self.instrumented_lines = (
                LcovUtils.LcovSectionToBitFields(lcov_section))


class TargetCodeCoverage(Entity):
    """Represents Sponge TargetCodeCoverage.

  Attributes:
    file_coverage: list of FileCoverage object.
    instrumentation: method of instrumentation: ONTHEFLY, OFFLINE, UNKNOWN
  """

    # instrumentation
    ONTHEFLY = 0
    OFFLINE = 1
    UNKNOWN = 2

    def __init__(self):
        super(TargetCodeCoverage, self).__init__()
        self.file_coverage = []
        self.instrumentation = None

        # Warning: *DO NOT* switch to Python 2.7 OrderedDict. This code needs to
        # run on Windows and other environments where Python 2.7 may not be
        # available.
        self._file_coverage_map = collections.OrderedDict()

    def FromLcovString(self, lcov_str):
        """Fill in coverage from lcov-formatted string.

    Args:
      lcov_str: contents of lcov file as string
    """
        for entry in lcov_str.split('end_of_record\n'):
            file_coverage = FileCoverage()
            file_coverage.FromLcovSection(entry.strip())

            if not file_coverage.file_name:
                continue

            prev_file_coverage = self._file_coverage_map.get(
                file_coverage.file_name)
            if prev_file_coverage:
                self._file_coverage_map[file_coverage.file_name] = (
                    prev_file_coverage.Combine(file_coverage))
            else:
                self._file_coverage_map[
                    file_coverage.file_name] = file_coverage

        self.file_coverage = self._file_coverage_map.values()

    def IndexOf(self, filename):
        """Index of filename in the FileCoverage map. Must exist!"""
        return self._file_coverage_map.keys().index(filename)


class Sample(Entity):
    """Represents a single data sample within a Metric object.

  Attributes:
    value: the data value of this sample -- the thing that we measured.
    timestamp_in_millis: the time when this particular sample was taken.
       Milliseconds since the Epoch. Not required, but highly recommended for
       a proper single-CL view in LoadViz that shows all samples of one run.
    outcome: SUCCESSFUL_OUTCOME or FAILED_OUTCOME.
    metadata: a dict of arbitrary user defined name-value pairs.
      For example, when measuring page load times, one can store the page URL
      under the key "url" in the metadata.
  """

    SUCCESSFUL_OUTCOME = 0
    FAILED_OUTCOME = 1

    def __init__(self):
        super(Sample, self).__init__()
        self.value = None
        self.timestamp_in_millis = None
        self.outcome = None
        self.metadata = {}


class Percentile(Entity):
    """Represents a percentile within an Aggregation object.

  Percentile objects only give enough info to filter samples by percentiles,
  Sponge doesn't store per-percentile means etc.

  Attributes:
    percentage: upper bracket of the percentile: integer number of percent.
       Lower bracket is always zero.
    value: maximum value for the this percentile.
  """

    def __init__(self):
        super(Percentile, self).__init__()
        self.percentage = None
        self.value = None


class Aggregation(Entity):
    """Represents aggregated values from samples in a Metric object.

  As also noted in Metric, Sponge would compute a default Aggregation
  if it's not supplied explicitly with a Metric. Sponge currently computes
  the following percentiles: 50, 80, 90, 95, 99, with no way to control it.
  If you want other percentiles, you need to provide the Aggregatioin yourself.

  Attributes:
    count: the number of samples represented by this aggregation.
    min: minimum sample value.
    max: maximum sample value.
    mean: mean of all sample values.
    standard_deviation: standard deviation of all sample values.
    percentiles: a sequence of Percentile objects.
    error_count: the number of samples with error outcomes.
  """

    def __init__(self):
        super(Aggregation, self).__init__()
        self.count = None
        self.min = None
        self.max = None
        self.mean = None
        self.standard_deviation = None
        self.error_count = None
        self.percentiles = []


class Metric(Entity):
    """Represents a single metric under PerformanceData.

  See the comment in PerformanceData about the mapping to sponge.proto.

  Attributes:
    name: the metric name.
    time_series: if True, this is a time series, otherwise not a time series.
    unit: string name of the unit of measure for sample values in this metric.
    machine_name: hostname where the test was run.
        If None, use Invocation.hostname.
    aggregation: an Aggregation object.
        If None, Sponge will compute it from samples.
    samples: a sequence of Sample objects.
  """

    def __init__(self):
        super(Metric, self).__init__()
        self.name = None
        self.time_series = True
        self.unit = None
        self.machine_name = None
        self.aggregation = None
        self.samples = []


class PerformanceData(Entity):
    """Represents Sponge PerformanceData, only moved under a TargetResult.

  Currently sponge.proto defines PerformanceData as a top level object,
  stored in a separate table from Invocations. There is an idea to move it
  under a TargetResult, allowing it to have labels and generally play
  by the same rules as all other test runs -- coverage etc.

  So far the interim solution is to try to have PerformanceData under
  a TargetResult only in sponge_client_lite, and do an on the fly
  conversion to sponge.proto structures in the HTTP Redirector.
  If all goes well there, then a similar conversion in the other direction
  (top level PerformanceData -> PerformanceData under a TargetResult)
  can be implemented in Sponge Java upload code, together with a data model
  change, allowing backward compatibility with older performance test clients.

  The mapping of the PerformanceData fields missing here is as follows:
  id -> Invocation.id
  timestamp_in_millis -> TargetResult.run_date
  cl -> Invocation.cl
  config -> TargetResult.configuration_values
  user -> Invocation.user
  description, project_name, project_id -- not mapped, if necessary should
      be added to Invocation and/or TargetResult, as they are not
      performance-specific. TODO(klm): discuss use cases with havardb@.

  For LoadViz to work properly, Invocation.cl must be supplied even though
  it's formally optional in the Invocation. It doesn't have to be an actual
  Perforce CL number, could be an arbitrary string, but these strings must
  sort in the chronological order -- e.g. may represent a date and time,
  for example may use an ISO date+time string notation of the run_date.

  Attributes:
    benchmark: benchmark name -- the most important ID in LoadViz.
        Must not be None for results to be usable in LoadViz.
    experiment: experiment name.
    thread_count: for load tests, the number of concurrent threads.
    aggregator_strategy: NONE or V1 or V1_NO_DOWNSAMPLE.
    metrics: a sequence of Metric objects.
  """

    NONE = 0
    V1 = 1
    V1_NO_DOWNSAMPLE = 2

    def __init__(self):
        super(PerformanceData, self).__init__()
        self.benchmark = None
        self.experiment = None
        self.thread_count = None
        self.aggregator_strategy = None
        self.metrics = []


class TestFault(Entity):
    """Test failure/error data.

  Attributes:
    message: message for the failure/error.
    exception_type: the type of failure/error.
    detail: details of the failure/error.
  """

    def __init__(self):
        super(TestFault, self).__init__()

        self._permitted_attributes = set(
            ['message', 'exception_type', 'detail'])
        self.message = None
        self.exception_type = None
        self.detail = None


class TestResult(Entity):
    """Test case data.

  Attributes:
    child: List of TestResult representing test suites or test cases
    name: Test result name
    class_name: Required for test cases, otherwise not
    was_run: true/false, default true, optional
    run_duration_millis: -
    property: List of TestProperty entities.
    test_case_count: number of test cases
    failure_count: number of failures
    error_count: number of errors
    disabled_count: number of disabled tests
    test_file_coverage: List of TestCaseFileCoverage
    test_failure: List of TestFault objects describing test failures
    test_error: List of TestFault objects describing test errors
    result: The result of running a test case: COMPLETED, INTERRUPTED, etc
  """

    # result
    COMPLETED = 0
    INTERRUPTED = 1
    CANCELLED = 2
    FILTERED = 3
    SKIPPED = 4
    SUPPRESSED = 5

    # Match DA lines claiming nonzero execution count.
    _lcov_executed_re = re.compile(r'^DA:\d+,[1-9][0-9]*', re.MULTILINE)

    def __init__(self):
        super(TestResult, self).__init__()

        self._permitted_attributes = set([
            'child', 'name', 'class_name', 'was_run', 'run_duration_millis',
            'property', 'test_case_count', 'failure_count', 'error_count',
            'disabled_count', 'test_file_coverage', 'test_failure',
            'test_error', 'result'
        ])
        self.child = []
        self.name = None
        self.class_name = None
        self.was_run = True
        self.run_duration_millis = None
        self.property = []
        self.test_case_count = None
        self.failure_count = None
        self.error_count = None
        self.disabled_count = None
        self.test_file_coverage = []
        self.test_error = []
        self.test_failure = []
        self.result = None

    def FromLcovString(self, lcov_str, target_code_coverage):
        """Fill in hit coverage from lcov-formatted string and target_code_coverage.

    Ignores files with zero hit bitmaps; presumes target_code_coverage is final
    for the purposes of determining the index of filenames.

    Args:
      lcov_str: contents of lcov file as string
      target_code_coverage: TargetCodeCoverage for filename indexing
    """
        for entry in lcov_str.split('end_of_record\n'):

            if not TestResult._lcov_executed_re.search(entry):
                continue

            test_file_coverage = TestCaseFileCoverage()
            test_file_coverage.FromLcovSection(entry.strip(),
                                               target_code_coverage)

            self.test_file_coverage.append(test_file_coverage)


class TestProperty(Entity):
    """Test property data.

  Attributes:
    key: A string representing the property key.
    value: A string representing the property value.
  """

    def __init__(self):
        super(TestProperty, self).__init__()
        self._permitted_attributes = set(['key', 'value'])
        self.key = None
        self.value = None


class TestCaseFileCoverage(Entity):
    """Test case file coverage data.

  Attributes:
    file_coverage_index: index into associated test target's file coverage.
    executed_lines: bitfield representing executed lines, as for FileCoverage.
    zipped_executed_lines: zip of executed_lines data, if smaller.
  """

    def __init__(self):
        super(TestCaseFileCoverage, self).__init__()

        self._permitted_attributes = set(
            ['file_coverage_index', 'executed_lines', 'zipped_executed_lines'])

        self.file_coverage_index = None
        self.executed_lines = 0
        self.zipped_executed_lines = 0
        self._custom_write_attributes = [
            'executed_lines', 'zipped_executed_lines'
        ]

    def WriteXml(self, xml_writer):
        """Writes this object as XML suitable for Sponge HTTP Redirector.

    Args:
      xml_writer: google3.third_party.python.elementtree.SimpleXMLWriter.
    """
        super(TestCaseFileCoverage, self).WriteXml(xml_writer)
        for attr_name in self._custom_write_attributes:
            value = self.__getattribute__(attr_name)
            if value:
                LcovUtils.WriteBitfieldXml(xml_writer, attr_name, value)
                # TODO(weasel): Mmmaybe lift bitfield handling to the base class.

    def FromLcovSection(self, lcov_section, tcc):
        if lcov_section:
            assert lcov_section.startswith('SF:')

            file_name = LcovUtils.GetFilename(lcov_section)
            self.file_coverage_index = tcc.IndexOf(file_name)
            self.executed_lines, unused_instrumented_lines = (
                LcovUtils.LcovSectionToBitFields(lcov_section))
            # TODO(weasel): compress executed_lines to zipped_* if smaller.


class GoogleFilePointer(Entity):
    """Represents a Google File system path.

  Attributes:
    name: str name for use by Sponge
    path: str containing the target Google File.
    length: integer size of the file; used purely for display purposes.
  """

    def __init__(self, name, path, length):
        super(GoogleFilePointer, self).__init__()
        self.name = name
        self.path = path
        self.length = length

    def WriteXml(self, xml_writer):
        """Writes this object as XML suitable for Sponge HTTP Redirector.

    Args:
      xml_writer: google3.third_party.python.elementtree.SimpleXMLWriter.
    """
        Entity._WriteValue(xml_writer, 'name', self.name)
        xml_writer.start('google_file_pointer')
        Entity._WriteValue(xml_writer, 'path', self.path)
        Entity._WriteValue(xml_writer, 'length', self.length)
        xml_writer.end()


class TargetResult(Entity):
    """Represents Sponge TargetResult.

  Attributes:
    index: index of the target result within its parent Invocation.
        Needed only for update requests, not for initial creation.
    run_date: execution start timestamp in milliseconds.
    build_target: the name of the build target that was executed.
    size: one of size constants: SMALL, MEDIUM, LARGE, OTHER_SIZE, ENORMOUS.
    environment: how we ran: FORGE, LOCAL_*, OTHER_*, UNKNOWN_*.
    status: test outcome: PASSED, FAILED, etc.
    test_result: tree of TestResults representing test suites and test cases.
    language: programming language of the source code: CC, JAVA, etc.
    run_duration_millis: execution duration in milliseconds.
    status_details: a string explaining the status in more detail.
    attempt_number: for flaky reruns, the number of the run attempt. Start at 1.
    total_attempts: for flaky reruns, the total number of run attempts.
    coverage: a TargetCodeCoverage object.
    performance_data: a PerformanceData object.
    configuration_values: a dict of test configuration parameters.
    type: the type of target: TEST, BINARY, LIBRARY, APPLICATION.
    large_texts: a dict of logs associated with this run. A magic key 'XML Log'
      allows to upload GUnit/JUnit XML and auto-convert it to TestResults.
    large_text_pointers: a list of GoogleFilePointers - distinction for
      formatting only, these are conceptually the same as large_texts.
  """

    # size - if you update these values ensure to also update the appropriate
    # enum list in uploader_recommended_options.py
    SMALL = 0
    MEDIUM = 1
    LARGE = 2
    OTHER_SIZE = 3
    ENORMOUS = 4

    # environment
    FORGE = 0
    LOCAL_PARALLEL = 1
    LOCAL_SEQUENTIAL = 2
    OTHER_ENVIRONMENT = 3
    UNKNOWN_ENVIRONMENT = 4

    # status - if you update these values ensure to also update the appropriate
    # enum list in uploader_optional_options.py
    PASSED = 0
    FAILED = 1
    CANCELLED_BY_USER = 2
    ABORTED_BY_TOOL = 3
    FAILED_TO_BUILD = 4
    BUILT = 5
    PENDING = 6
    UNKNOWN_STATUS = 7
    INTERNAL_ERROR = 8

    # language - if you update these values ensure to also update the appropriate
    # enum list in uploader_recommended_options.py
    UNSPECIFIED_LANGUAGE = 0
    BORGCFG = 1
    CC = 2
    GWT = 3
    HASKELL = 4
    JAVA = 5
    JS = 6
    PY = 7
    SH = 8
    SZL = 9

    # type
    UNSPECIFIED_TYPE = 0
    TEST = 1
    BINARY = 2
    LIBRARY = 3
    APPLICATION = 4

    def __init__(self):
        super(TargetResult, self).__init__()
        self.index = None
        self.run_date = long(round(time.time() * 1000))
        self.build_target = None
        self.size = None
        self.environment = None
        self.status = None
        self.test_result = None
        self.language = None
        self.run_duration_millis = None
        self.status_details = None
        self.attempt_number = None
        self.total_attempts = None
        self.coverage = None
        self.performance_data = None
        self.configuration_values = {}
        self.type = None
        self.large_texts = {}
        self.large_text_pointers = []
        self._custom_write_attributes = ['large_text_pointers']

    def MarkRunDuration(self):
        """Assigns run_duration_millis to the current time minus run_date."""
        assert self.run_date
        self.run_duration_millis = long(round(
            time.time() * 1000)) - self.run_date
        assert self.run_duration_millis > 0

    def WriteXml(self, xml_writer):
        """Writes this object as XML suitable for Sponge HTTP Redirector.

    Args:
      xml_writer: google3.third_party.python.elementtree.SimpleXMLWriter.
    """
        super(TargetResult, self).WriteXml(xml_writer)
        # Write out GoogleFilePointers as large_text fields
        for google_file_pointer in self.large_text_pointers:
            Entity._WriteValue(xml_writer, 'large_text', google_file_pointer)


class Invocation(Entity):
    """Represents a Sponge Invocation.

  Attributes:
    id: the ID of an invocation to update.
        Needed only for update requests, not for initial creation.
    run_date: execution start timestamp in milliseconds
    user: username.
    client: P4 client name.
    cl: P4 changelist ID.
    hostname: the host where the tests ran.
    working_dir: the dir where the tests ran.
    args: command line arguments of the test command.
    environment_variables: a dict of notable OS environment variables.
    configuration_values: a dict of test configuration parameters.
    large_texts: a dict of logs associated with the entire set of target runs.
    labels: a list of labels associated with this invocation.
    target_results: a list of TargetResult objects.
    large_text_pointers: a list of GoogleFilePointers - distinction for
      formatting only, these are conceptually the same as large_texts.
  """

    def __init__(self):
        super(Invocation, self).__init__()
        self.id = None
        self.run_date = long(round(time.time() * 1000))
        self.user = None
        self.target_results = []
        self.client = None
        self.cl = None
        self.hostname = socket.gethostname().lower()
        self.working_dir = os.path.abspath(os.curdir)
        self.args = None
        self.environment_variables = {}
        self.configuration_values = {}
        self.large_texts = {}
        self.large_text_pointers = []
        self.labels = []
        self._custom_write_attributes = [
            'environment_variables',
            'large_text_pointers',
        ]

    def WriteXml(self, xml_writer):
        """Writes this object as XML suitable for Sponge HTTP Redirector.

    Args:
      xml_writer: google3.third_party.python.elementtree.SimpleXMLWriter.
    """
        super(Invocation, self).WriteXml(xml_writer)
        Entity._WriteNameValuesXml(
            xml_writer,
            'environment_variable',
            self.environment_variables,
            name_elem='variable',
            value_elem='value')
        # Write out GoogleFilePointers as large_text fields
        for google_file_pointer in self.large_text_pointers:
            Entity._WriteValue(xml_writer, 'large_text', google_file_pointer)


# Constants for Uploader.server
SERVER_PROD = 'backend'
SERVER_QA = 'backend-qa'


class Uploader(Entity):
    """Uploads Sponge Invocations to the Sponge HTTP Redirector service."""

    def __init__(self,
                 url_host='sponge-http.appspot.com',
                 upload_url_path='/create_invocation',
                 update_url_path='/update_target_result',
                 server=None):
        """Initializes the object.

    Args:
      url_host: host or host:port for the Sponge HTTP Redirector server.
      upload_url_path: the path after url_host.
      update_url_path: the path after update_url_host.
      server: name of the Sponge backend, if None use SERVER_QA.
    """
        super(Uploader, self).__init__()
        self.server = server or SERVER_QA
        self.invocations = []
        self._url_host = url_host
        self._upload_url_path = upload_url_path
        self._update_url_path = update_url_path
        self._proxy = None
        self._https_connection_factory = httplib.HTTPSConnection

    def WriteXml(self, xml_writer):
        """Writes this object as XML suitable for Sponge HTTP Redirector.

    Args:
      xml_writer: google3.third_party.python.elementtree.SimpleXMLWriter.
    """
        xml_writer.start('xml')
        super(Uploader, self).WriteXml(xml_writer)
        xml_writer.end()

    def UseProxy(self, proxy):
        """Forward requests through a given HTTP proxy.

    Args:
      proxy: the proxy address as '<host>' or '<host>:<port>'
    """
        self._proxy = proxy

    def UseHTTPSConnectionFactory(self, https_connection_factory):
        """Use the given function to create HTTPS connections.

    This is helpful for clients on later version of Python (2.7.9+) that wish to
    do client-side SSL authentication via ssl.SSLContext.

    Args:
      https_connection_factory: A function that takes a string url and returns
                                an httplib.HTTPSConnection.
    """
        self._https_connection_factory = https_connection_factory

    def Upload(self):
        """Uploads Sponge invocations to the Sponge HTTP Redirector service.

    Returns:
      A string with Sponge invocation IDs, as returned by the HTTP Redirector.

    Raises:
      ValueError: when at least one invocation id is not None.
    """
        for invocation in self.invocations:
            if invocation.id:
                raise ValueError(
                    'Invocation id must be None for new invocation.')
        return self._UploadHelper(self._url_host, self._upload_url_path)

    def UploadUpdatedResults(self):
        """Uploads updated Sponge invocations to the Sponge HTTP Redirector service.

    Returns:
      A string with Sponge invocation IDs, as returned by the HTTP Redirector.

    Raises:
      ValueError: when at least one invocation id is None or at least one
        target result has index of None.
    """
        for invocation in self.invocations:
            if invocation.id is None:
                raise ValueError('Invocation id must not be None for update.')
            for target_result in invocation.target_results:
                if target_result.index is None:
                    raise ValueError(
                        'Target result index can not be None for update.')
        return self._UploadHelper(self._url_host, self._update_url_path)

    def _UploadHelper(self, host, url):
        """A helper function to perform actual upload of Sponge invocations.

    Args:
      host: host server to connect to.
      url: url for Sponge end point.

    Returns:
      A string represent Sponge invocation IDs.
    """
        if self._proxy:
            # A simple HTTP proxy request is the same as a regular HTTP request
            # via the proxy host:port, except the path after the method (GET or POST)
            # is the full actual request URL.
            url = 'https://%s%s' % (host, url)
            # Assume proxy does not support HTTPS.
            http_connect = httplib.HTTPConnection(self._proxy)
        else:
            http_connect = self._https_connection_factory(host)
        xml_str = self.GetXmlString()
        http_connect.connect()
        http_connect.request('PUT', url, body=xml_str)
        response = http_connect.getresponse()
        response_str = response.read().strip()
        if response_str.startswith('id: "'):
            response_str = response_str[5:-1]
        return response_str


def GetInvocationUrl(server, invocation_id):
    if server == 'backend-qa':
        return 'http://sponge-qa/%s' % invocation_id
    else:
        return 'http://tests/%s' % invocation_id
