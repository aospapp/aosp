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
#include <inttypes.h>
#include <stdio.h>
#include <string>
#include <android-base/file.h>
#include <android-base/stringprintf.h>

struct abl_log_header {
    uint64_t i;
    uint64_t size;
    char buf[];
} __attribute__((packed));

// Gzip binary data and dump in base64 format. Cmd to decode is also attached.
void dumpGzippedFileInBase64(const char* title, const char* file_path) {
    auto cmd = android::base::StringPrintf("gzip < %s | base64", file_path);
    printf("------ %s, %s\n", title, cmd.c_str());
    printf("base64 -d <<EOF | gunzip\n");
    system(cmd.c_str());
    printf("EOF\n");
    return;
}

// Dump items related to ramdump.
int main() {
    setbuf(stdout, NULL);
    std::string abl_log;
    if (android::base::ReadFileToString("/mnt/vendor/ramdump/abl.log", &abl_log)) {
        const struct abl_log_header *header = (const struct abl_log_header*) abl_log.c_str();
        printf("------ Ramdump misc file: abl.log (i:0x%" PRIx64 " size:0x%" PRIx64 ") ------\n%s\n",
               header->i, header->size, std::string(header->buf, header->i).c_str());
    } else {
        printf("*** Ramdump misc file: abl.log: File not found\n");
    }

    dumpGzippedFileInBase64("Ramdump misc file: acpm.lst (gzipped in base64)", "/mnt/vendor/ramdump/acpm.lst");
    dumpGzippedFileInBase64("Ramdump misc file: s2d.lst (gzipped in base64)", "/mnt/vendor/ramdump/s2d.lst");
    return 0;
}
