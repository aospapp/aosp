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

class CallSite:
    def __init__(self, ip, method, dso):
        self.ip = ip
        self.method = method
        self.dso = dso



class Thread:
    def __init__(self, tid):
        self.tid = tid
        self.samples = []
        self.flamegraph = {}
        self.num_samples = 0


    def add_callchain(self, callchain, symbol, sample):
        chain = []
        self.num_samples += 1
        for j in range(callchain.nr):
            entry = callchain.entries[callchain.nr - j - 1]
            if entry.ip == 0:
                continue
            chain.append(CallSite(entry.ip, entry.symbol.symbol_name, entry.symbol.dso_name))

        chain.append(CallSite(sample.ip, symbol.symbol_name, symbol.dso_name))
        self.samples.append(chain)


    def collapse_flamegraph(self):
        flamegraph = FlameGraphCallSite("root", "")
        flamegraph.id = 0 # This is used for wasd navigation, 0 = not a valid target.
        self.flamegraph = flamegraph
        for sample in self.samples:
            flamegraph = self.flamegraph
            for callsite in sample:
               flamegraph = flamegraph.get_callsite(callsite.method, callsite.dso)

        # Populate root note.
        for node in self.flamegraph.callsites:
            self.flamegraph.num_samples += node.num_samples


class Process:
    def __init__(self, name, pid):
        self.name = name
        self.pid = pid
        self.threads = {}
        self.cmd = ""
        self.props = {}
        self.args = None
        self.num_samples = 0

    def get_thread(self, tid):
        if (tid not in self.threads.keys()):
            self.threads[tid] = Thread(tid)
        return self.threads[tid]

CALLSITE_COUNTER = 0
def get_callsite_id():
    global CALLSITE_COUNTER
    CALLSITE_COUNTER += 1
    toReturn = CALLSITE_COUNTER
    return toReturn


class FlameGraphCallSite:

    def __init__(self, method, dso):
        self.callsites = []
        self.method = method
        self.dso = dso
        self.num_samples = 0
        self.offset = 0 # Offset allows position nodes in different branches.
        self.id = get_callsite_id()


    def get_callsite(self, name, dso):
        for c in self.callsites:
            if c.equivalent(name, dso):
                c.num_samples += 1
                return c
        callsite = FlameGraphCallSite(name, dso)
        callsite.num_samples = 1
        self.callsites.append(callsite)
        return callsite

    def equivalent(self, method, dso):
        return self.method == method and self.dso == dso


    def get_max_depth(self):
        max = 0
        for c in self.callsites:
            depth = c.get_max_depth()
            if depth > max:
                max = depth
        return max +1