#include <stdint.h>
#include <stdio.h>
#include <log/log.h>
#include "fp_test.h"
#include <cutils/properties.h>

#define TAG "[FP_TEST] "
#define LOGI(format,...) ALOGI(TAG format,##__VA_ARGS__)
#define LOGD(format,...) ALOGD(TAG format,##__VA_ARGS__)
#define LOGE(format,...) ALOGE(TAG format,##__VA_ARGS__)
#define CLOGI(format,...) printf(TAG format,##__VA_ARGS__)
#define CLOGD(format,...) printf(TAG format,##__VA_ARGS__)
#define CLOGE(format,...) printf(TAG format,##__VA_ARGS__)

#define LOGI_BOTH(format,...) {        \
    ALOGI(TAG format,##__VA_ARGS__)       \
    prinft(TAG format, ##__VA_ARGS__) \
}                                     \

#define ARRAY_SIZE(arr)  (sizeof(arr) / sizeof(arr[0]))
#define STRING_SIZE 32

#define FPS_SRV_PROP "fps_hal"
#define FPS_SRV_FULL_PROP "init.svc.fps_hal"
#define FPS_SRV_STATUS_PROP "vendor.fp.status"

enum ErrorType {
    OK,
    ERROR
};

static const char* const cmdUsage[] = {
    "-------fp_test tool usage--------",
    "fp_test -e: Enable FPS service",
    "fp_test -d: Disable FPS service",
    "fp_test -i: Idle Mode",
    "fp_test -n: Navigation Mode",
    "fp_test -a: Authentication Mode",
    "---------------------------------",
};

void toolUsage(void) {
    int numCmdUsage = ARRAY_SIZE(cmdUsage);
    for(int i = 0; i< numCmdUsage; i++)
        CLOGI("%s\n",cmdUsage[i]);
}

int checkParameter(int num, char **strArray)
{
    int ret = 0;
    char parameter[STRING_SIZE] = {0,};
    if (num != 2 || (strlen(strArray[1]) > STRING_SIZE)) {
        return -ERROR;
    }
    strcpy(parameter, strArray[1]);
    if (!strncmp(parameter, "-a", sizeof("-a"))) {
        CLOGI("Start Authentication Mode!\n");
        LOGI("Start Authentication Mode!\n");
        ret = 'a';
    } else if (!strncmp(parameter, "-n", sizeof("-n"))) {
        CLOGI("Start Navigation Mode!\n");
        LOGI("Start Navigation Mode!\n");
        ret = 'n';
    } else if (!strncmp(parameter, "-i", sizeof("-i"))) {
        CLOGI("Start Idle Mode!\n");
        LOGI("Start Idle Mode!\n");
        ret = 'n';
    } else if (!strncmp(parameter, "-e", sizeof("-e"))) {
        CLOGI("Start enabling FPS service!\n");
        LOGI("Start enabling FPS service!\n");
        ret = 'e';
    } else if (!strncmp(parameter, "-d", sizeof("-d"))) {
        CLOGI("Start disabling FPS service!\n");
        LOGI("Start disabling FPS service!\n");
        ret = 'd';
    } else {
        ret = -ERROR;
    }
    return ret;
}

int enable_disable_fps(bool set)
{
    int ret  = 0;
    // Set property to enable/disable fingerprint service
    if (set == true) {
       ret = property_set("ctl.start", FPS_SRV_PROP);
    } else {
       ret = property_set("ctl.stop", FPS_SRV_PROP);
    }

    if (ret != 0) {
        CLOGE("Failed to %s FPS service\n", set? "enable" : "disable");
        LOGE("Failed to %s FPS service\n", set? "enable" : "disable");
        return -ERROR;
    }

    return ret;
}

int run_auth_cmd() {
    RequestStatus hidlRet;
    uint64_t operationId = 0;
    uint32_t gid = 0;
    char tempbuf[PROPERTY_VALUE_MAX];

    property_get(FPS_SRV_FULL_PROP, tempbuf, 0);
    LOGE("%s : current fp service status is %s!\n",__func__, tempbuf);
    if (!strncmp(tempbuf, "stopped", strlen("stopped"))) {
        return -ERROR;
    }

    sp<IBiometricsFingerprint> service = IBiometricsFingerprint::getService();
    if (service == nullptr) {
        CLOGE("%s : Fail to get FingerprintService!\n",__func__);
        LOGE("%s : Fail to get FingerprintService!\n",__func__);
        return -ERROR;
    }

    hidlRet = service->authenticate(operationId, gid);
    if (hidlRet == RequestStatus::SYS_OK) {
        return OK;
    } else {
        return -ERROR;
    }
}

int run_cancel_cmd() {

    RequestStatus hidlRet;
    char tempbuf[PROPERTY_VALUE_MAX];

    property_get(FPS_SRV_FULL_PROP, tempbuf, 0);
    LOGE("%s : current fp service status is %s!\n",__func__, tempbuf);
    if (!strncmp(tempbuf, "stopped", strlen("stopped"))) {
        return -ERROR;
    }

    sp<IBiometricsFingerprint> service = IBiometricsFingerprint::getService();
    if (service == nullptr) {
        CLOGE("%s : Fail to get FingerprintService!\n",__func__);
        LOGE("%s : Fail to get FingerprintService!\n",__func__);
        return -ERROR;
    }

    hidlRet = service->cancel();
    if (hidlRet == RequestStatus::SYS_OK) {
        return OK;
    } else {
        return -ERROR;
    }
}

int main(int argc, char *argv[])
{
    int input=0;
    int32_t ret = 0;
    LOGI("%s",__func__);
    input = checkParameter(argc, argv);
    if (input == -ERROR){
        LOGE("Invalid Parameter\n");
        CLOGE("Invalid Parameter\n");
        toolUsage();
        return -ERROR;
    }

    switch (input) {
        case 'e':
            CLOGI("%s: Enable fingerprint service\n",__func__);
            LOGI("%s: Enable fingerprint service\n",__func__);
            ret = enable_disable_fps(true);
            break;
        case 'd':
            CLOGI("%s: Disable fingerprint service\n",__func__);
            LOGI("%s: Disable fingerprint service\n",__func__);
            ret = enable_disable_fps(false);
            break;
        case 'a':
            ret = run_auth_cmd();
            break;
        // For the rear fingerprint module, calling cancel() will go to the
        // navigation mode by default.
        // For other device not enabling naivgation feature, default mode will
        // be "Idle" by invoking cancel().
        case 'n':
        case 'i':
        default:
            ret = run_cancel_cmd();
            break;
    }

    if (ret != OK)
        CLOGE("FP HIDL fail to excute cmd '%c'\n", input);
    else
        CLOGI("FP HIDL excute cmd '%c' successfully\n", input);

    return ret;

}

