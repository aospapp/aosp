/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "execns"
#include <log/log.h>

#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <string>
#include <vector>

static bool isTerminal = false;
// Print errors to stderr if running from a terminal, otherwise print to logcat
// This is useful for debugging from a terminal
#define LOGE(...) do { \
    if (isTerminal) { \
        fprintf(stderr, __VA_ARGS__); \
        fprintf(stderr, "\n"); \
    } else { \
        ALOGE(__VA_ARGS__); \
    } \
} while (0)

static const char kNetNsDir[] = "/data/vendor/var/run/netns";

class FileDescriptor {
public:
    explicit FileDescriptor(int fd) : mFd(fd) { }
    FileDescriptor(const FileDescriptor&) = delete;
    ~FileDescriptor() {
        if (mFd != -1) {
            close(mFd);
            mFd = -1;
        }
    }
    int get() const { return mFd; }
    FileDescriptor& operator=(const FileDescriptor&) = delete;
private:
    int mFd;
};

class File {
public:
    explicit File(FILE* file) : mFile(file) { }
    File(const File&) = delete;
    ~File() {
        if (mFile) {
            ::fclose(mFile);
            mFile = nullptr;
        }
    }

    FILE* get() const { return mFile; }
    File& operator=(const File&) = delete;
private:
    FILE* mFile;
};

static void printUsage(const char* program) {
    LOGE("%s <namespace> <program> [options...]", program);
}

static bool isNumericString(const char* str) {
    while (isdigit(*str)) {
        ++str;
    }
    return *str == '\0';
}

static std::string readNamespacePid(const char* ns) {
    char nsPath[PATH_MAX];
    snprintf(nsPath, sizeof(nsPath), "%s/%s.pid", kNetNsDir, ns);

    File file(::fopen(nsPath, "r"));
    if (file.get() == nullptr) {
        LOGE("Unable to open file %s for namespace %s: %s",
             nsPath, ns, strerror(errno));
        return std::string();
    }

    char buffer[32];
    size_t bytesRead = ::fread(buffer, 1, sizeof(buffer), file.get());
    if (bytesRead < sizeof(buffer) && feof(file.get())) {
        // Reached end-of-file, null-terminate
        buffer[bytesRead] = '\0';
        if (isNumericString(buffer)) {
            // File is valid and contains a number, return it
            return buffer;
        }
        LOGE("File %s does not contain a valid pid '%s'", nsPath, buffer);
    } else if (ferror(file.get())) {
        LOGE("Error reading from file %s: %s", nsPath, strerror(errno));
    } else {
        LOGE("Invalid contents of pid file %s", nsPath);
    }
    return std::string();
}

static bool setNetworkNamespace(const char* ns) {
    // There is a file in the net namespace dir (/data/vendor/var/run/netns) with
    // the name "<namespace>.pid". This file contains the pid of the createns
    // process that created the namespace.
    //
    // To switch network namespace we're going to call setns which requires an
    // open file descriptor to /proc/<pid>/ns/net where <pid> refers to a
    // process already running in that namespace. So using the pid from the file
    // above we can determine which path to use.
    std::string pid = readNamespacePid(ns);
    if (pid.empty()) {
        return false;
    }
    char nsPath[PATH_MAX];
    snprintf(nsPath, sizeof(nsPath), "/proc/%s/ns/net", pid.c_str());

    FileDescriptor nsFd(open(nsPath, O_RDONLY | O_CLOEXEC));
    if (nsFd.get() == -1) {
        LOGE("Cannot open network namespace '%s' at '%s': %s",
             ns, nsPath, strerror(errno));
        return false;
    }

    if (setns(nsFd.get(), CLONE_NEWNET) == -1) {
        LOGE("Cannot set network namespace '%s': %s",
             ns, strerror(errno));
        return false;
    }
    return true;
}

// Append a formatted string to the end of |buffer|. The total size in |buffer|
// is |size|, including any existing string data. The string to append is
// specified by |fmt| and any additional arguments required by the format
// string. If the function fails it returns -1, otherwise it returns the number
// of characters printed (excluding the terminating NULL). On success the
// string is always null-terminated.
static int sncatf(char* buffer, size_t size, const char* fmt, ...) {
    size_t len = strnlen(buffer, size);
    if (len >= size) {
        // The length exceeds the available size, if len == size then there is
        // also a terminating null after len bytes which would then be outside
        // the provided buffer.
        return -1;
    }

    va_list args;
    va_start(args, fmt);
    int printed = vsnprintf(buffer + len, size - len, fmt, args);
    buffer[size - 1] = '\0';
    va_end(args);
    return printed;
}

/**
 * Execute a given |command| with |argc| number of parameters that are located
 * in |argv|. The first parameter in |argv| is the command that should be run
 * followed by its arguments.
 */
static int execCommand( int argc, char** argv) {
    if (argc <= 0 || argv == nullptr || argv[0] == nullptr) {
        LOGE("No command specified");
        return 1;
    }

    std::vector<char*> arguments;
    // Place all the arguments in the vector and the terminating null
    arguments.insert(arguments.begin(), argv, argv + argc);
    arguments.push_back(nullptr);

    char buffer[4096];
    if (execvp(argv[0], arguments.data()) == -1) {
        // Save errno in case it gets changed by printing stuff.
        int error = errno;
        int printed = snprintf(buffer, sizeof(buffer),
                               "Could not execute command '%s", argv[0]);
        if (printed < 0) {
            LOGE("Could not execute command: %s", strerror(error));
            return error;
        }
        for (int i = 1; i < argc; ++i) {
            // Be nice to the user and print quotes if there are spaces to
            // indicate how we saw it. If there are already single quotes in
            // there confusion will ensue.
            if (strchr(argv[i], ' ')) {
                sncatf(buffer, sizeof(buffer), " \"%s\"", argv[i]);
            } else {
                sncatf(buffer, sizeof(buffer), " %s", argv[i]);
            }
        }
        sncatf(buffer, sizeof(buffer), "': %s", strerror(error));
        LOGE("%s", buffer);
        return error;
    }
    // execvp never returns unless it fails so this is just to return something.
    return 0;
}

/**
 * Enter a given network namespace argv[1] and execute command argv[2] with
 * options argv[3..argc-1] in that namespace.
 */
int main(int argc, char* argv[]) {
    isTerminal = isatty(STDOUT_FILENO) != 0;
    if (argc < 3) {
        printUsage(argv[0]);
        return 1;
    }

    // First set the new network namespace for this process
    if (!setNetworkNamespace(argv[1])) {
        return 1;
    }

    // Now run the command with all the remaining parameters
    return execCommand(argc - 2, &argv[2]);
}

