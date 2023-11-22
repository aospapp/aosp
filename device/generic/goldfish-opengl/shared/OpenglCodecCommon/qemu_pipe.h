/*
 * Copyright (C) 2011 The Android Open Source Project
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
#ifndef ANDROID_INCLUDE_HARDWARE_QEMU_PIPE_H
#define ANDROID_INCLUDE_HARDWARE_QEMU_PIPE_H

#ifdef HOST_BUILD

#include <sys/types.h>

typedef void* QEMU_PIPE_HANDLE;

#define QEMU_PIPE_INVALID_HANDLE NULL

QEMU_PIPE_HANDLE qemu_pipe_open(const char* pipeName);
void qemu_pipe_close(QEMU_PIPE_HANDLE pipe);

ssize_t qemu_pipe_read(QEMU_PIPE_HANDLE pipe, void* buffer, size_t len);
ssize_t qemu_pipe_write(QEMU_PIPE_HANDLE pipe, const void* buffer, size_t len);

bool qemu_pipe_try_again();
bool qemu_pipe_valid(QEMU_PIPE_HANDLE pipe);

void qemu_pipe_print_error(QEMU_PIPE_HANDLE pipe);

#else

typedef int QEMU_PIPE_HANDLE;

#define QEMU_PIPE_INVALID_HANDLE (-1)

#ifndef QEMU_PIPE_PATH
#define QEMU_PIPE_PATH "/dev/qemu_pipe"
#endif

#ifndef TEMP_FAILURE_RETRY
#define TEMP_FAILURE_RETRY(exp) ({         \
    __typeof__(exp) _rc;                   \
    do {                                   \
        _rc = (exp);                       \
    } while (_rc == -1 && errno == EINTR); \
    _rc; })
#include <stdint.h>
#endif

#if PLATFORM_SDK_VERSION < 26
#include <cutils/log.h>
#else
#include <log/log.h>
#endif
#ifdef __ANDROID__
#include <sys/cdefs.h>
#endif
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <pthread.h>  /* for pthread_once() */
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>

#ifndef D
#  define  D(...)   do{}while(0)
#endif

static bool WriteFully(QEMU_PIPE_HANDLE fd, const void* data, size_t byte_count) {
  const uint8_t* p = (const uint8_t*)(data);
  size_t remaining = byte_count;
  while (remaining > 0) {
    ssize_t n = TEMP_FAILURE_RETRY(write(fd, p, remaining));
    if (n == -1) return false;
    p += n;
    remaining -= n;
  }
  return true;
}

/* Try to open a new Qemu fast-pipe. This function returns a file descriptor
 * that can be used to communicate with a named service managed by the
 * emulator.
 *
 * This file descriptor can be used as a standard pipe/socket descriptor.
 *
 * 'pipeName' is the name of the emulator service you want to connect to.
 * E.g. 'opengles' or 'camera'.
 *
 * On success, return a valid file descriptor
 * Returns -1 on error, and errno gives the error code, e.g.:
 *
 *    EINVAL  -> unknown/unsupported pipeName
 *    ENOSYS  -> fast pipes not available in this system.
 *
 * ENOSYS should never happen, except if you're trying to run within a
 * misconfigured emulator.
 *
 * You should be able to open several pipes to the same pipe service,
 * except for a few special cases (e.g. GSM modem), where EBUSY will be
 * returned if more than one client tries to connect to it.
 */
static __inline__ QEMU_PIPE_HANDLE
qemu_pipe_open(const char* pipeName) {
    char  buff[256];
    int   buffLen;
    QEMU_PIPE_HANDLE   fd;

    if (pipeName == NULL || pipeName[0] == '\0') {
        errno = EINVAL;
        return -1;
    }

    snprintf(buff, sizeof buff, "pipe:%s", pipeName);

    fd = TEMP_FAILURE_RETRY(open(QEMU_PIPE_PATH, O_RDWR));
    if (fd < 0 && errno == ENOENT)
        fd = TEMP_FAILURE_RETRY(open("/dev/goldfish_pipe", O_RDWR));
    if (fd < 0) {
        D("%s: Could not open " QEMU_PIPE_PATH ": %s", __FUNCTION__, strerror(errno));
        //errno = ENOSYS;
        return -1;
    }

    buffLen = strlen(buff);

    if (!WriteFully(fd, buff, buffLen + 1)) {
        D("%s: Could not connect to %s pipe service: %s", __FUNCTION__, pipeName, strerror(errno));
        return -1;
    }

    return fd;
}

static __inline__ void
qemu_pipe_close(QEMU_PIPE_HANDLE pipe) {
    close(pipe);
}

static __inline__ ssize_t
qemu_pipe_read(QEMU_PIPE_HANDLE pipe, void* buffer, size_t len) {
    return read(pipe, buffer, len);
}

static __inline__ ssize_t
qemu_pipe_write(QEMU_PIPE_HANDLE pipe, const void* buffer, size_t len) {
    return write(pipe, buffer, len);
}

static __inline__ bool
qemu_pipe_try_again() {
    return errno == EINTR;
}

static __inline__ bool
qemu_pipe_valid(QEMU_PIPE_HANDLE pipe) {
    return pipe >= 0;
}

static __inline__ void
qemu_pipe_print_error(QEMU_PIPE_HANDLE pipe) {
    ALOGE("pipe error: fd %d errno %d", pipe, errno);
}

#endif // !HOST_BUILD

#endif /* ANDROID_INCLUDE_HARDWARE_QEMU_PIPE_H */
