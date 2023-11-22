#
# Copyright (C) 2016 The Android Open Source Project
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
    Inferno is a tool to generate flamegraphs for android programs. It was originally written
    to profile surfaceflinger (Android compositor) but it can be used for other C++ program.
    It uses simpleperf to collect data. Programs have to be compiled with frame pointers which
    excludes ART based programs for the time being.

    Here is how it works:

    1/ Data collection is started via simpleperf and pulled locally as "perf.data".
    2/ The raw format is parsed, callstacks are merged to form a flamegraph data structure.
    3/ The data structure is used to generate a SVG embedded into an HTML page.
    4/ Javascript is injected to allow flamegraph navigation, search, coloring model.

"""

from scripts.simpleperf_report_lib import *
import argparse
from data_types import *
from svg_renderer import *
import datetime
import webbrowser
from adb_non_root import AdbNonRoot
from adb_root import AdbRoot

def create_process(adb_client, args):
    """ Retrieves target process pid and create a process contained.

    :param args: Argument as parsed by argparse
    :return: Process objectk
    """
    process_id = adb_client.get_process_pid(args.process_name)
    process = Process(args.process_name, process_id)
    return process


def collect_data(adb_client, process):
    """ Start simpleperf on device and collect data. Pull perf.data into cwd.

    :param process:  Process object
    :return: Populated Process object
    """

    if process.args.dwarf_unwinding:
        unwinding_parameter = "-g"
        print "Unwinding with dwarf."
    else:
        unwinding_parameter = "--call-graph fp"
        print "Unwinding with frame pointers."

    # Check whether sampling will be frequency based or event based.
    sampling_parameter = "-f %s" % process.args.sample_frequency
    if process.args.events:
        tokens = process.args.events.split(" ")
        if len(tokens) == 2:
            num_events = tokens[0]
            event_name = tokens[1]
            sampling_parameter = "-c %s -e '%s'" % (num_events, event_name)
        else:
            print "Event format string not recognized. Expected \"requency event_name\"."
            print "Got : [" + ",".join(tokens) + "]"
            return False
        print "Using event sampling (%s)." % sampling_parameter
    else:
        print "Using frequency sampling (%s)." % sampling_parameter

    process.cmd = "./simpleperf record \
    -o /data/local/tmp/perf.data \
    %s \
    -p %s \
    --duration %s \
    %s" % (
        unwinding_parameter,
        process.pid,
        process.args.capture_duration,
        sampling_parameter)

    print("Process '%s' PID = %d" % (process.name, process.pid))

    if process.args.skip_collection:
       print("Skipping data collection, expecting perf.data in folder")
       return True

    print("Sampling for %s seconds..." % process.args.capture_duration)


    adb_client.delete_previous_data()

    success = adb_client.collect_data(process)
    if not success:
        return False

    err = adb_client.pull_data()
    if err:
        return False

    return True


def parse_samples(process, args):
    """ read record_file, and print each sample"""

    record_file = args.record_file
    symfs_dir = args.symfs
    kallsyms_file = args.kallsyms

    lib = ReportLib()

    lib.ShowIpForUnknownSymbol()
    if symfs_dir is not None:
        lib.SetSymfs(symfs_dir)
    if record_file is not None:
        lib.SetRecordFile(record_file)
    if kallsyms_file is not None:
        lib.SetKallsymsFile(kallsyms_file)

    while True:
        sample = lib.GetNextSample()
        if sample is None:
            lib.Close()
            break
        symbol = lib.GetSymbolOfCurrentSample()
        callchain = lib.GetCallChainOfCurrentSample()
        process.get_thread(sample.tid).add_callchain(callchain, symbol, sample)
        process.num_samples += 1

    print("Parsed %s callchains." % process.num_samples)


def collapse_callgraphs(process):
    """
    For each thread, collapse all callgraph into one flamegraph.
    :param process:  Process object
    :return: None
    """
    for _, thread in process.threads.items():
        thread.collapse_flamegraph()


def get_local_asset_content(local_path):
    """
    Retrieves local package text content
    :param local_path: str, filename of local asset
    :return: str, the content of local_path
    """
    f = open(os.path.join(os.path.dirname(__file__), local_path), 'r')
    content = f.read()
    f.close()
    return content


def output_report(process):
    """
    Generates a HTML report representing the result of simpleperf sampling as flamegraph
    :param process: Process object
    :return: str, absolute path to the file
    """
    f = open('report.html', 'w')
    filepath = os.path.realpath(f.name)
    f.write("<html>")
    f.write("<body style='font-family: Monospace;' onload='init()'>")
    f.write('<style type="text/css"> .s { stroke:black; stroke-width:0.5; cursor:pointer;} </style>')
    f.write('<style type="text/css"> .t:hover { cursor:pointer; } </style>')
    f.write('<img height="180" alt = "Embedded Image" src ="data')
    f.write(get_local_asset_content("inferno.b64"))
    f.write('"/>')
    f.write("<div style='display:inline-block;'> \
    <font size='8'>\
    Inferno Flamegraph Report</font><br/><br/> \
    Process : %s (%d)<br/>\
    Date&nbsp;&nbsp;&nbsp;&nbsp;: %s<br/>\
    Threads : %d <br/>\
    Samples : %d</br>\
    Duration: %s seconds<br/>\
    Machine : %s (%s) by %s<br/>\
    Capture : %s<br/><br/></div>"
            % (
                process.name,process.pid,
                datetime.datetime.now().strftime("%Y-%m-%d (%A) %H:%M:%S"),
                len(process.threads),
                process.num_samples,
                process.args.capture_duration,
                process.props["ro.product.model"], process.props["ro.product.name"],
                process.props["ro.product.manufacturer"],
                process.cmd))
    f.write("<br/><br/><div>Navigate with WASD, zoom in with SPACE, zoom out with BACKSPACE.</div>")
    f.write(get_local_asset_content("script.js"))

    # Output tid == pid Thread first.
    main_thread = [x for _, x in process.threads.items() if x.tid == process.pid]
    for thread in main_thread:
        f.write("<br/><br/><b>Main Thread %d (%d samples):</b><br/>\n\n\n\n" % (thread.tid, thread.num_samples))
        renderSVG(thread.flamegraph, f, process.args.color, process.args.svg_width)

    other_threads = [x for _, x in process.threads.items() if x.tid != process.pid]
    for thread in other_threads:
        f.write("<br/><br/><b>Thread %d (%d samples):</b><br/>\n\n\n\n" % (thread.tid, thread.num_samples))
        renderSVG(thread.flamegraph, f, process.args.color, process.args.svg_width)

    f.write("</body>")
    f.write("</html>")
    f.close()
    return "file://" + filepath

def generate_flamegraph_offsets(flamegraph):
    rover = flamegraph.offset
    for callsite in flamegraph.callsites:
        callsite.offset = rover
        rover += callsite.num_samples
        generate_flamegraph_offsets(callsite)


def generate_threads_offsets(process):
    for _, thread in process.threads.items():
        generate_flamegraph_offsets(thread.flamegraph)


def collect_machine_info(adb_client, process):
    process.props = adb_client.get_props()


def setup_adb():
    err = subprocess.call(["adb", "root"])
    if err == 0:
        return AdbRoot()
    else:
        return AdbNonRoot()

def open_report_in_browser(report_path):
    # Try to open the report with Chrome
    browser_key = ""
    for key, value in webbrowser._browsers.items():
        if key.find("chrome") != -1:
           browser_key = key
    browser = webbrowser.get(browser_key)
    browser.open(report_path, new=0, autoraise=True)



def main():

    parser = argparse.ArgumentParser(description='Report samples in perf.data.')
    parser.add_argument('--symfs', help='Set the path to find binaries with symbols and debug info.')
    parser.add_argument('--kallsyms', help='Set the path to find kernel symbols.')
    parser.add_argument('--record_file', default='perf.data', help='Default is perf.data.')
    parser.add_argument('-t', '--capture_duration', default=10, help='Capture duration in seconds.')
    parser.add_argument('-p', '--process_name', default='surfaceflinger', help='Default is surfaceflinger.')
    parser.add_argument('-c', '--color', default='hot', choices=['hot', 'dso', 'legacy'],
                        help='Color theme: hot=percentage of samples, dso=callsite DSO name, legacy=brendan style')
    parser.add_argument('-sc','--skip_collection', default=False, help='Skip data collection', action="store_true")
    parser.add_argument('-f', '--sample_frequency', default=6000, help='Sample frequency')
    parser.add_argument('-w', '--svg_width', type=int, default=1124)
    parser.add_argument('-sb', '--skip_push_binary', help='Skip pushing simpleperf before profiling',
                        default=False, action="store_true")
    parser.add_argument('-du', '--dwarf_unwinding', help='Perform unwinding using dwarf instead of fp.',
                        default=False, action='store_true')
    parser.add_argument('-e', '--events',
                        help='Sample based on event occurences instead of frequency. '
                             'Format expected is "event_counts event_name". e.g: "10000 cpu-cyles". A few examples of \
                              nmames: cpu-cycles, cache-references, cache-misses, branch-instructions, branch-misses',
                        default="")
    args = parser.parse_args()

    # Since we may attempt to sample privileged process, let's try to be root.
    adb_client = setup_adb()

    # Create a process object
    process = create_process(adb_client, args)
    if process.pid == 0:
        print("Unable to retrive pid for process '%s'. Terminating." % process.name)
        return
    process.args = args


    print("Starting data collection stage for process '%s'." % args.process_name)
    success = collect_data(adb_client, process)
    if not success:
        print "Unable to collect data"
        return

    collect_machine_info(adb_client, process)
    parse_samples(process, args)
    collapse_callgraphs(process)
    generate_threads_offsets(process)
    report_path = output_report(process)
    open_report_in_browser(report_path)

    print "Report generated at '%s'." % report_path

if __name__ == "__main__":
    main()