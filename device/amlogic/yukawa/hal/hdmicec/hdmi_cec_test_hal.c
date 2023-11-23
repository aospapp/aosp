/*
 * Copyright (C) 2014 ASUSTek COMPUTER INC.
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

#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <log/log.h>
#include <hardware/hdmi_cec.h>

extern struct hw_module_t HAL_MODULE_INFO_SYM;
static int (*hdmicec_open)(const struct hw_module_t *, const char *, struct hw_device_t **);

static struct hw_module_t *device;
static struct hw_module_t **device2 = &device;

int main(int argc, char* argv[])
{
    struct hdmi_cec_device *cec_device;
    int err, type = -1;

    if (argc > 1)
        type = atoi(argv[1]);

    ALOGI("open hdmicec");
    hdmicec_open = HAL_MODULE_INFO_SYM.methods->open;
    err = (*hdmicec_open)(&HAL_MODULE_INFO_SYM, NULL, (struct hw_device_t **)device2);
    if (!err) {
        ALOGI("open success");
    } else {
        ALOGE("open fail");
    }

    cec_device = (struct hdmi_cec_device *)(*device2);
#if 0
    if (type != -1) {
        int fd;

        ALOGI("0. test type change: %d\n", type);
        fd = open("/dev/cec0", O_RDWR);
        if (fd < 0) {
            ALOGE("fail to open CEC device\n");
        } else {
            err = ioctl(fd, CEC_IOC_SET_DEV_TYPE, type);
            if (!err) {
                ALOGI("type change success\n");
            } else {
                ALOGE("type change fail\n");
            }
            close(fd);
        }
    }
#endif

    ALOGI("1. test add logical address\n");
    err = cec_device->add_logical_address(cec_device, CEC_ADDR_PLAYBACK_1);
    if (!err) {
        ALOGI("add logical address success\n");
    } else {
        ALOGE("add logical address fail\n");
    }

    ALOGI("2. test send cec message\n");
    cec_message_t send_msg;

    ALOGI("2.a header\n");
    send_msg.initiator = CEC_ADDR_PLAYBACK_1;
    send_msg.destination = CEC_ADDR_PLAYBACK_1;
    send_msg.length = 0;

    err = cec_device->send_message(cec_device, &send_msg);
    ALOGI("send_message result= %d", err);

    ALOGI("2.b header + opcode\n");
    send_msg.initiator = CEC_ADDR_PLAYBACK_1;
    send_msg.destination = CEC_ADDR_TV;
    send_msg.length = 1;
    send_msg.body[0] = CEC_MESSAGE_IMAGE_VIEW_ON;

    err = cec_device->send_message(cec_device, &send_msg);
    ALOGI("send_message result= %d", err);

    ALOGI("2.c header + opcode + operands\n");
    send_msg.initiator = CEC_ADDR_PLAYBACK_1;
    send_msg.destination = CEC_ADDR_TV;
    send_msg.length = 15; //max opcode + operands should be 15
    send_msg.body[0] = CEC_MESSAGE_VENDOR_COMMAND;
    for (size_t i = 1; i < send_msg.length; i++)
        send_msg.body[i] = i;

    err = cec_device->send_message(cec_device, &send_msg);
    ALOGI("send_message result= %d", err);

    ALOGI("3 check hdmi connect status\n");
    err = cec_device->is_connected(cec_device, 1);
    ALOGI("is connected= %d", err);

    ALOGI("4 get hdmi physical address\n");
    uint16_t addr;
    err = cec_device->get_physical_address(cec_device, &addr);
    ALOGI("addr= %d, err= %d", addr, err);

    sleep(3);
    ALOGI("close hdmicec");
    cec_device->common.close((struct hw_device_t *)cec_device);

    return 0;
}
