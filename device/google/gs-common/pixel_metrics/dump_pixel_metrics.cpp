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
#include <dump/pixel_dump.h>
#include <android-base/file.h>


int main() {
    setbuf(stdout, NULL);
    dumpFileContent("Long running IRQ metrics", "/sys/kernel/metrics/irq/long_irq_metrics");
    dumpFileContent("Storm IRQ metrics", "/sys/kernel/metrics/irq/storm_irq_metrics");
    dumpFileContent("Resume latency metrics", "/sys/kernel/metrics/resume_latency/resume_latency_metrics");
    return 0;
}

