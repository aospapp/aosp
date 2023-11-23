#define LOG_TAG "castkey-main"

#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include "CreatePluginFactories.h"

using ::android::base::InitLogging;
using ::android::base::LogdLogger;
using ::aidl::android::hardware::drm::castkey::createDrmFactory;
using ::aidl::android::hardware::drm::castkey::DrmFactory;

int main(int /*argc*/, char* argv[]) {
    InitLogging(argv, LogdLogger());
    ABinderProcess_setThreadPoolMaxThreadCount(8);
    std::shared_ptr<DrmFactory> drmFactory = createDrmFactory();
    const std::string drmInstance = std::string() + DrmFactory::descriptor + "/castkey";
    binder_status_t status =
            AServiceManager_addService(drmFactory->asBinder().get(), drmInstance.c_str());
    CHECK(status == STATUS_OK);
    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}