/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define LOG_TAG     "pkvm_enabler"

#include <errno.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <log/log.h>

#define KVM_DEVICE  "/dev/kvm"
#define MISC_WRITER "/vendor/bin/misc_writer"

int main() {
    char *newargv[] = { MISC_WRITER, "--set-enable-pkvm", NULL };
    char *newenvp[] = { NULL };
    pid_t pid;
    int ret, wstatus;

    /* Check whether KVM device exists. */
    ret = access(KVM_DEVICE, F_OK);

    /* If KVM device exists, return SUCCESS to continue booting. */
    if (ret == 0) {
        exit(EXIT_SUCCESS);
    }

    if (ret != -ENOENT) {
        ALOGW("Unexpected error from access(): %d", ret);
    }

    /*
     * If KVM device does not exist, run misc_writer and return FAILURE
     * to force a reboot.
     */
    pid = fork();
    if (pid == -1) {
        ALOGE("Could not fork: %d", errno);
        exit(EXIT_FAILURE);
    }

    if (pid == 0) {
        execve(MISC_WRITER, newargv, newenvp);
        ALOGE("Could not execute " MISC_WRITER ": %d", errno);
        _exit(EXIT_FAILURE);
    }

    waitpid(pid, &wstatus, 0);
    if (WIFEXITED(wstatus)) {
        ret = WEXITSTATUS(wstatus);
        if (ret) {
            ALOGE(MISC_WRITER " exit status: %d", ret);
        }
    } else {
        ALOGE(MISC_WRITER " terminated unexpectedly: %d", wstatus);
    }

    exit(EXIT_FAILURE);
}
