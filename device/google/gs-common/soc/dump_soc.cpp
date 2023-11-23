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
#include <string.h>
#include <stdio.h>
#include <log/log.h>
#include <regex>

std::string readFile(const std::string& file_path) {
    std::string content;
    if(android::base::ReadFileToString(file_path.c_str(), &content)) {
        return std::regex_replace(content, std::regex("\\r\\n|\\r|\\n"),"");
    }
    return content;
}

// Dump chip ID.
int main() {
    dumpFileContent("AP HW TUNE", "/sys/devices/system/chip-id/ap_hw_tune_str");
    dumpFileContent("EVT VERSION", "/sys/devices/system/chip-id/evt_ver");
    dumpFileContent("LOT ID", "/sys/devices/system/chip-id/lot_id");
    dumpFileContent("PRODUCT ID", "/sys/devices/system/chip-id/product_id");
    dumpFileContent("REVISION", "/sys/devices/system/chip-id/revision");
    dumpFileContent("RAW STR", "/sys/devices/system/chip-id/raw_str");
    dumpFileContent("CPU present", "/sys/devices/system/cpu/present");
    dumpFileContent("CPU online", "/sys/devices/system/cpu/online");

    printf("------ CPU time-in-state ------\n");
    std::string states;
    std::unique_ptr<DIR, decltype(&closedir)> cpudir(opendir("/sys/devices/system/cpu/"), closedir);
    if (!cpudir) {
        ALOGE("Fail To Open Dir /sys/devices/system/cpu/");
        return 0;
    }
    dirent *entry;
    while ((entry = readdir(cpudir.get())) != nullptr) {
        std::string core(entry->d_name);
        if (core.find("cpu") != std::string::npos) {
            std::string path("/sys/devices/system/cpu/" + core + "/cpufreq/stats/time_in_state");
            if(!access(path.c_str(), R_OK)){
                dumpFileContent(path.c_str(), path.c_str());
            }
        }
        std::string cpu_idle_path("/sys/devices/system/cpu/" + core + "/cpuidle");
        std::unique_ptr<DIR, decltype(&closedir)> statedir(opendir(cpu_idle_path.c_str()), closedir);
        if (!statedir) {
            continue;
        }
        dirent *state_entry;
        while ((state_entry = readdir(statedir.get())) != nullptr) {
            std::string cpu_idle_state_path(state_entry->d_name);
            std::string full_state_path;
            full_state_path += cpu_idle_path;
            full_state_path += "/";
            full_state_path += cpu_idle_state_path;
            if (cpu_idle_state_path.find("state") != std::string::npos) {
                std::string name(full_state_path + "/name");
                std::string desc(full_state_path + "/desc");
                std::string time(full_state_path + "/time");
                std::string usage(full_state_path + "/usage");
                states += full_state_path+": "+readFile(name)+" "+readFile(desc)+" "+readFile(time)+" "+readFile(usage)+"\n";
            }
        }
    }
    printf("------ CPU cpuidle ------\n%s\n", states.c_str());

    dumpFileContent("INTERRUPTS", "/proc/interrupts");
    return 0;
}
