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
#include <string>
#include <android-base/file.h>
#include <fstream>
#include <dump/pixel_dump.h>

// Format title and content output.
void dumpFileContent(const char* title, const char* file_path) {
    std::string content;
    printf("------ %s (%s) ------\n", title, file_path);
    if (android::base::ReadFileToString(file_path, &content)) {
        printf("%s\n", content.c_str());
    } else {
        printf("Unable to read %s\n", file_path);
    }
    return;
}

// Format title and command output.
void runCommand(const char* title, const char* cmd) {
    printf("------ %s (%s)------\n", title, cmd);
    system(cmd);
    return;
}

std::string concatenatePath(const char* folder, const char* file){
    std::string path = folder;
    if(folder[strlen(folder)-1] == '/'){
        path = path + file;
    } else {
        path = path + "/" + file;
    }

    printf("folder:%s, result:%s\n", folder, path.c_str());
    return path;
}

// Copy stored log from individual folder to our dumpstate folder for
// compressing.
void dumpLogs(const char* SrcDir, const char* DestDir, int limit, const char* prefix) {

    struct dirent **dirent_list = NULL;
    int num_entries = scandir(SrcDir, &dirent_list, 0, (int (*)(const struct dirent **, const struct dirent **)) alphasort);
    if (!dirent_list) {
        printf("Unable to scan dir: %s.\n", SrcDir);
        return;
    } else if (num_entries <= 0) {
        printf("No file is found.\n");
        return;
    }

    if (access(DestDir, R_OK)) {
        printf("Unable to find folder: %s\n", DestDir);
        return;
    }

    int copiedFiles = 0;

    for (int i = num_entries - 1; i >= 0; i--) {

        if (0 != strncmp(dirent_list[i]->d_name, prefix, strlen(prefix))) {
            continue;
        }

        if ((copiedFiles >= limit) && (limit != -1)) {
            printf("Skipped %s\n", dirent_list[i]->d_name);
            continue;
        }

        copiedFiles++;
        copyFile(concatenatePath(SrcDir, dirent_list[i]->d_name).c_str(), concatenatePath(DestDir, dirent_list[i]->d_name).c_str());
    }

    while (num_entries--) {
        free(dirent_list[num_entries]);
    }

    free(dirent_list);
    return;
}

void copyFile(const char* SrcDir, const char* DestDir) {
    std::ifstream src(SrcDir, std::ios::binary);
    std::ofstream dst(DestDir, std::ios::binary);
    dst << src.rdbuf();
    src.close();
    dst.close();
    return;
}

