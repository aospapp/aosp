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

#define LOG_TAG "debug-hifi"
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <stdio.h>
#include <cutils/log.h>
#include <cutils/uevent.h>
#include <stdlib.h>
#include <linux/audio_hifi.h>

int main(int argc, char *argv[])
{
    char *buffer;
    int hifi_dsp_fd;
    int ret = -1;
    int i = 0;
    unsigned int memsize = DRV_DSP_UART_TO_MEM_SIZE;
    unsigned int clear = 0;
    struct misc_io_dump_buf_param dump_buf;

    ALOGI("Enter hifi-dsp Audio Framework - sample application\n");
    if (argc > 1)
        memsize = strtoul(argv[1], NULL, 0);
    if (argc > 2)
        clear = 1;
    hifi_dsp_fd = open(HIFI_DSP_MISC_DRIVER, O_RDWR, 0);
    if (hifi_dsp_fd < 0) {
        ALOGE("Error %d opening hifi dsp device", errno);
        return ret;
    }
    buffer = malloc(memsize);
    if (!buffer) {
        ALOGE("Error allocating buffer");
        goto out0;
    }
    dump_buf.user_buf = (uint64_t)buffer;
    dump_buf.buf_size = memsize;
    dump_buf.clear = clear;
    ret = ioctl(hifi_dsp_fd, HIFI_MISC_IOCTL_DISPLAY_MSG, &dump_buf);
    if (ret < 0) { /* This IOCTL returns buffer size */
        ALOGE("Error %d accessing message buffer", errno);
    } else {
        printf("%s\n", ret > 0 ? buffer : "Buffer is empty");
    }
    free(buffer);
out0:
    close(hifi_dsp_fd);
    ALOGI("Exit hifi-dsp Audio Framework - sample application\n");
    return ret;
}
