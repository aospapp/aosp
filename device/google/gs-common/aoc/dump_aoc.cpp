/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <stdio.h>
#include <dump/pixel_dump.h>

// Dump Aoc.
int main() {
    setbuf(stdout, NULL);
    dumpFileContent("AoC Service Status", "/sys/devices/platform/19000000.aoc/services");
    dumpFileContent("AoC Restarts", "/sys/devices/platform/19000000.aoc/restart_count");
    dumpFileContent("AoC Coredumps", "/sys/devices/platform/19000000.aoc/coredump_count");
    dumpFileContent("AoC ring buf wake", "/sys/devices/platform/19000000.aoc/control/ring_buffer_wakeup");
    dumpFileContent("AoC host ipc wake", "/sys/devices/platform/19000000.aoc/control/host_ipc_wakeup");
    dumpFileContent("AoC usf wake", "/sys/devices/platform/19000000.aoc/control/usf_wakeup");
    dumpFileContent("AoC audio wake", "/sys/devices/platform/19000000.aoc/control/audio_wakeup");
    dumpFileContent("AoC logging wake", "/sys/devices/platform/19000000.aoc/control/logging_wakeup");
    dumpFileContent("AoC hotword wake", "/sys/devices/platform/19000000.aoc/control/hotword_wakeup");
    dumpFileContent("AoC memory exception wake", "/sys/devices/platform/19000000.aoc/control/memory_exception");
    dumpFileContent("AoC memory votes", "/sys/devices/platform/19000000.aoc/control/memory_votes_a32");
    dumpFileContent("AoC memory votes", "/sys/devices/platform/19000000.aoc/control/memory_votes_ff1");
    runCommand("clean AoC buffer","echo ' ' > /dev/acd-debug; timeout 0.1 cat /dev/acd-debug");
    runCommand("AoC Heap Stats (A32)", "echo 'dbg heap -c 1' > /dev/acd-debug; timeout 0.1 cat /dev/acd-debug");
    runCommand("AoC Heap Stats (F1)", "echo 'dbg heap -c 2' > /dev/acd-debug; timeout 0.1 cat /dev/acd-debug");
    runCommand("AoC Heap Stats (HF0)", "echo 'dbg heap -c 3' > /dev/acd-debug; timeout 0.1 cat /dev/acd-debug");
    runCommand("AoC Heap Stats (HF1)", "echo 'dbg heap -c 4' > /dev/acd-debug; timeout 0.1 cat /dev/acd-debug");
    return 0;
}
