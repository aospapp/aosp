/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "ServiceManagement"

#include <android/dlext.h>
#include <condition_variable>
#include <dlfcn.h>
#include <dirent.h>
#include <fstream>
#include <pthread.h>
#include <unistd.h>

#include <mutex>
#include <regex>
#include <set>

#include <hidl/HidlBinderSupport.h>
#include <hidl/ServiceManagement.h>
#include <hidl/Status.h>

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <hwbinder/IPCThreadState.h>
#include <hwbinder/Parcel.h>
#include <vndksupport/linker.h>

#include <android/hidl/manager/1.1/IServiceManager.h>
#include <android/hidl/manager/1.1/BpHwServiceManager.h>
#include <android/hidl/manager/1.1/BnHwServiceManager.h>

#define RE_COMPONENT    "[a-zA-Z_][a-zA-Z_0-9]*"
#define RE_PATH         RE_COMPONENT "(?:[.]" RE_COMPONENT ")*"
static const std::regex gLibraryFileNamePattern("(" RE_PATH "@[0-9]+[.][0-9]+)-impl(.*?).so");

using android::base::WaitForProperty;

using IServiceManager1_0 = android::hidl::manager::V1_0::IServiceManager;
using IServiceManager1_1 = android::hidl::manager::V1_1::IServiceManager;
using android::hidl::manager::V1_0::IServiceNotification;
using android::hidl::manager::V1_1::BpHwServiceManager;
using android::hidl::manager::V1_1::BnHwServiceManager;

namespace android {
namespace hardware {

namespace details {
extern Mutex gDefaultServiceManagerLock;
extern sp<android::hidl::manager::V1_1::IServiceManager> gDefaultServiceManager;
}  // namespace details

static const char* kHwServicemanagerReadyProperty = "hwservicemanager.ready";

void waitForHwServiceManager() {
    using std::literals::chrono_literals::operator""s;

    while (!WaitForProperty(kHwServicemanagerReadyProperty, "true", 1s)) {
        LOG(WARNING) << "Waited for hwservicemanager.ready for a second, waiting another...";
    }
}

bool endsWith(const std::string &in, const std::string &suffix) {
    return in.size() >= suffix.size() &&
           in.substr(in.size() - suffix.size()) == suffix;
}

bool startsWith(const std::string &in, const std::string &prefix) {
    return in.size() >= prefix.size() &&
           in.substr(0, prefix.size()) == prefix;
}

std::string binaryName() {
    std::ifstream ifs("/proc/self/cmdline");
    std::string cmdline;
    if (!ifs.is_open()) {
        return "";
    }
    ifs >> cmdline;

    size_t idx = cmdline.rfind("/");
    if (idx != std::string::npos) {
        cmdline = cmdline.substr(idx + 1);
    }

    return cmdline;
}

void tryShortenProcessName(const std::string &packageName) {
    std::string processName = binaryName();

    if (!startsWith(processName, packageName)) {
        return;
    }

    // e.x. android.hardware.module.foo@1.0 -> foo@1.0
    size_t lastDot = packageName.rfind('.');
    size_t secondDot = packageName.rfind('.', lastDot - 1);

    if (secondDot == std::string::npos) {
        return;
    }

    std::string newName = processName.substr(secondDot + 1,
            16 /* TASK_COMM_LEN */ - 1);
    ALOGI("Removing namespace from process name %s to %s.",
            processName.c_str(), newName.c_str());

    int rc = pthread_setname_np(pthread_self(), newName.c_str());
    ALOGI_IF(rc != 0, "Removing namespace from process name %s failed.",
            processName.c_str());
}

namespace details {

void onRegistration(const std::string &packageName,
                    const std::string& /* interfaceName */,
                    const std::string& /* instanceName */) {
    tryShortenProcessName(packageName);
}

}  // details

sp<IServiceManager1_0> defaultServiceManager() {
    return defaultServiceManager1_1();
}
sp<IServiceManager1_1> defaultServiceManager1_1() {
    {
        AutoMutex _l(details::gDefaultServiceManagerLock);
        if (details::gDefaultServiceManager != NULL) {
            return details::gDefaultServiceManager;
        }

        if (access("/dev/hwbinder", F_OK|R_OK|W_OK) != 0) {
            // HwBinder not available on this device or not accessible to
            // this process.
            return nullptr;
        }

        waitForHwServiceManager();

        while (details::gDefaultServiceManager == NULL) {
            details::gDefaultServiceManager =
                    fromBinder<IServiceManager1_1, BpHwServiceManager, BnHwServiceManager>(
                        ProcessState::self()->getContextObject(NULL));
            if (details::gDefaultServiceManager == NULL) {
                LOG(ERROR) << "Waited for hwservicemanager, but got nullptr.";
                sleep(1);
            }
        }
    }

    return details::gDefaultServiceManager;
}

std::vector<std::string> search(const std::string &path,
                              const std::string &prefix,
                              const std::string &suffix) {
    std::unique_ptr<DIR, decltype(&closedir)> dir(opendir(path.c_str()), closedir);
    if (!dir) return {};

    std::vector<std::string> results{};

    dirent* dp;
    while ((dp = readdir(dir.get())) != nullptr) {
        std::string name = dp->d_name;

        if (startsWith(name, prefix) &&
                endsWith(name, suffix)) {
            results.push_back(name);
        }
    }

    return results;
}

bool matchPackageName(const std::string& lib, std::string* matchedName, std::string* implName) {
    std::smatch match;
    if (std::regex_match(lib, match, gLibraryFileNamePattern)) {
        *matchedName = match.str(1) + "::I*";
        *implName = match.str(2);
        return true;
    }
    return false;
}

static void registerReference(const hidl_string &interfaceName, const hidl_string &instanceName) {
    sp<IServiceManager1_0> binderizedManager = defaultServiceManager();
    if (binderizedManager == nullptr) {
        LOG(WARNING) << "Could not registerReference for "
                     << interfaceName << "/" << instanceName
                     << ": null binderized manager.";
        return;
    }
    auto ret = binderizedManager->registerPassthroughClient(interfaceName, instanceName);
    if (!ret.isOk()) {
        LOG(WARNING) << "Could not registerReference for "
                     << interfaceName << "/" << instanceName
                     << ": " << ret.description();
        return;
    }
    LOG(VERBOSE) << "Successfully registerReference for "
                 << interfaceName << "/" << instanceName;
}

using InstanceDebugInfo = hidl::manager::V1_0::IServiceManager::InstanceDebugInfo;
static inline void fetchPidsForPassthroughLibraries(
    std::map<std::string, InstanceDebugInfo>* infos) {
    static const std::string proc = "/proc/";

    std::map<std::string, std::set<pid_t>> pids;
    std::unique_ptr<DIR, decltype(&closedir)> dir(opendir(proc.c_str()), closedir);
    if (!dir) return;
    dirent* dp;
    while ((dp = readdir(dir.get())) != nullptr) {
        pid_t pid = strtoll(dp->d_name, NULL, 0);
        if (pid == 0) continue;
        std::string mapsPath = proc + dp->d_name + "/maps";
        std::ifstream ifs{mapsPath};
        if (!ifs.is_open()) continue;

        for (std::string line; std::getline(ifs, line);) {
            // The last token of line should look like
            // vendor/lib64/hw/android.hardware.foo@1.0-impl-extra.so
            // Use some simple filters to ignore bad lines before extracting libFileName
            // and checking the key in info to make parsing faster.
            if (line.back() != 'o') continue;
            if (line.rfind('@') == std::string::npos) continue;

            auto spacePos = line.rfind(' ');
            if (spacePos == std::string::npos) continue;
            auto libFileName = line.substr(spacePos + 1);
            auto it = infos->find(libFileName);
            if (it == infos->end()) continue;
            pids[libFileName].insert(pid);
        }
    }
    for (auto& pair : *infos) {
        pair.second.clientPids =
            std::vector<pid_t>{pids[pair.first].begin(), pids[pair.first].end()};
    }
}

struct PassthroughServiceManager : IServiceManager1_1 {
    static void openLibs(const std::string& fqName,
            std::function<bool /* continue */(void* /* handle */,
                const std::string& /* lib */, const std::string& /* sym */)> eachLib) {
        //fqName looks like android.hardware.foo@1.0::IFoo
        size_t idx = fqName.find("::");

        if (idx == std::string::npos ||
                idx + strlen("::") + 1 >= fqName.size()) {
            LOG(ERROR) << "Invalid interface name passthrough lookup: " << fqName;
            return;
        }

        std::string packageAndVersion = fqName.substr(0, idx);
        std::string ifaceName = fqName.substr(idx + strlen("::"));

        const std::string prefix = packageAndVersion + "-impl";
        const std::string sym = "HIDL_FETCH_" + ifaceName;

        const int dlMode = RTLD_LAZY;
        void *handle = nullptr;

        dlerror(); // clear

        std::vector<std::string> paths = {HAL_LIBRARY_PATH_ODM, HAL_LIBRARY_PATH_VENDOR,
                                          HAL_LIBRARY_PATH_VNDK_SP, HAL_LIBRARY_PATH_SYSTEM};
#ifdef LIBHIDL_TARGET_DEBUGGABLE
        const char* env = std::getenv("TREBLE_TESTING_OVERRIDE");
        const bool trebleTestingOverride = env && !strcmp(env, "true");
        if (trebleTestingOverride) {
            const char* vtsRootPath = std::getenv("VTS_ROOT_PATH");
            if (vtsRootPath && strlen(vtsRootPath) > 0) {
                const std::string halLibraryPathVtsOverride =
                    std::string(vtsRootPath) + HAL_LIBRARY_PATH_SYSTEM;
                paths.push_back(halLibraryPathVtsOverride);
            }
        }
#endif
        for (const std::string& path : paths) {
            std::vector<std::string> libs = search(path, prefix, ".so");

            for (const std::string &lib : libs) {
                const std::string fullPath = path + lib;

                if (path != HAL_LIBRARY_PATH_SYSTEM) {
                    handle = android_load_sphal_library(fullPath.c_str(), dlMode);
                } else {
                    handle = dlopen(fullPath.c_str(), dlMode);
                }

                if (handle == nullptr) {
                    const char* error = dlerror();
                    LOG(ERROR) << "Failed to dlopen " << lib << ": "
                               << (error == nullptr ? "unknown error" : error);
                    continue;
                }

                if (!eachLib(handle, lib, sym)) {
                    return;
                }
            }
        }
    }

    Return<sp<IBase>> get(const hidl_string& fqName,
                          const hidl_string& name) override {
        sp<IBase> ret = nullptr;

        openLibs(fqName, [&](void* handle, const std::string &lib, const std::string &sym) {
            IBase* (*generator)(const char* name);
            *(void **)(&generator) = dlsym(handle, sym.c_str());
            if(!generator) {
                const char* error = dlerror();
                LOG(ERROR) << "Passthrough lookup opened " << lib
                           << " but could not find symbol " << sym << ": "
                           << (error == nullptr ? "unknown error" : error);
                dlclose(handle);
                return true;
            }

            ret = (*generator)(name.c_str());

            if (ret == nullptr) {
                dlclose(handle);
                return true; // this module doesn't provide this instance name
            }

            registerReference(fqName, name);
            return false;
        });

        return ret;
    }

    Return<bool> add(const hidl_string& /* name */,
                     const sp<IBase>& /* service */) override {
        LOG(FATAL) << "Cannot register services with passthrough service manager.";
        return false;
    }

    Return<Transport> getTransport(const hidl_string& /* fqName */,
                                   const hidl_string& /* name */) {
        LOG(FATAL) << "Cannot getTransport with passthrough service manager.";
        return Transport::EMPTY;
    }

    Return<void> list(list_cb /* _hidl_cb */) override {
        LOG(FATAL) << "Cannot list services with passthrough service manager.";
        return Void();
    }
    Return<void> listByInterface(const hidl_string& /* fqInstanceName */,
                                 listByInterface_cb /* _hidl_cb */) override {
        // TODO: add this functionality
        LOG(FATAL) << "Cannot list services with passthrough service manager.";
        return Void();
    }

    Return<bool> registerForNotifications(const hidl_string& /* fqName */,
                                          const hidl_string& /* name */,
                                          const sp<IServiceNotification>& /* callback */) override {
        // This makes no sense.
        LOG(FATAL) << "Cannot register for notifications with passthrough service manager.";
        return false;
    }

    Return<void> debugDump(debugDump_cb _hidl_cb) override {
        using Arch = ::android::hidl::base::V1_0::DebugInfo::Architecture;
        using std::literals::string_literals::operator""s;
        static std::vector<std::pair<Arch, std::vector<const char*>>> sAllPaths{
            {Arch::IS_64BIT,
             {HAL_LIBRARY_PATH_ODM_64BIT, HAL_LIBRARY_PATH_VENDOR_64BIT,
              HAL_LIBRARY_PATH_VNDK_SP_64BIT, HAL_LIBRARY_PATH_SYSTEM_64BIT}},
            {Arch::IS_32BIT,
             {HAL_LIBRARY_PATH_ODM_32BIT, HAL_LIBRARY_PATH_VENDOR_32BIT,
              HAL_LIBRARY_PATH_VNDK_SP_32BIT, HAL_LIBRARY_PATH_SYSTEM_32BIT}}};
        std::map<std::string, InstanceDebugInfo> map;
        for (const auto &pair : sAllPaths) {
            Arch arch = pair.first;
            for (const auto &path : pair.second) {
                std::vector<std::string> libs = search(path, "", ".so");
                for (const std::string &lib : libs) {
                    std::string matchedName;
                    std::string implName;
                    if (matchPackageName(lib, &matchedName, &implName)) {
                        std::string instanceName{"* ("s + path + ")"s};
                        if (!implName.empty()) instanceName += " ("s + implName + ")"s;
                        map.emplace(path + lib, InstanceDebugInfo{.interfaceName = matchedName,
                                                                  .instanceName = instanceName,
                                                                  .clientPids = {},
                                                                  .arch = arch});
                    }
                }
            }
        }
        fetchPidsForPassthroughLibraries(&map);
        hidl_vec<InstanceDebugInfo> vec;
        vec.resize(map.size());
        size_t idx = 0;
        for (auto&& pair : map) {
            vec[idx++] = std::move(pair.second);
        }
        _hidl_cb(vec);
        return Void();
    }

    Return<void> registerPassthroughClient(const hidl_string &, const hidl_string &) override {
        // This makes no sense.
        LOG(FATAL) << "Cannot call registerPassthroughClient on passthrough service manager. "
                   << "Call it on defaultServiceManager() instead.";
        return Void();
    }

    Return<bool> unregisterForNotifications(const hidl_string& /* fqName */,
                                            const hidl_string& /* name */,
                                            const sp<IServiceNotification>& /* callback */) override {
        // This makes no sense.
        LOG(FATAL) << "Cannot unregister for notifications with passthrough service manager.";
        return false;
    }

};

sp<IServiceManager1_0> getPassthroughServiceManager() {
    return getPassthroughServiceManager1_1();
}
sp<IServiceManager1_1> getPassthroughServiceManager1_1() {
    static sp<PassthroughServiceManager> manager(new PassthroughServiceManager());
    return manager;
}

namespace details {

void preloadPassthroughService(const std::string &descriptor) {
    PassthroughServiceManager::openLibs(descriptor,
        [&](void* /* handle */, const std::string& /* lib */, const std::string& /* sym */) {
            // do nothing
            return true; // open all libs
        });
}

struct Waiter : IServiceNotification {
    Return<void> onRegistration(const hidl_string& /* fqName */,
                                const hidl_string& /* name */,
                                bool /* preexisting */) override {
        std::unique_lock<std::mutex> lock(mMutex);
        if (mRegistered) {
            return Void();
        }
        mRegistered = true;
        lock.unlock();

        mCondition.notify_one();
        return Void();
    }

    void wait(const std::string &interface, const std::string &instanceName) {
        using std::literals::chrono_literals::operator""s;

        std::unique_lock<std::mutex> lock(mMutex);
        while(true) {
            mCondition.wait_for(lock, 1s, [this]{
                return mRegistered;
            });

            if (mRegistered) {
                break;
            }

            LOG(WARNING) << "Waited one second for "
                         << interface << "/" << instanceName
                         << ". Waiting another...";
        }
    }

private:
    std::mutex mMutex;
    std::condition_variable mCondition;
    bool mRegistered = false;
};

void waitForHwService(
        const std::string &interface, const std::string &instanceName) {
    const sp<IServiceManager1_1> manager = defaultServiceManager1_1();

    if (manager == nullptr) {
        LOG(ERROR) << "Could not get default service manager.";
        return;
    }

    sp<Waiter> waiter = new Waiter();
    Return<bool> ret = manager->registerForNotifications(interface, instanceName, waiter);

    if (!ret.isOk()) {
        LOG(ERROR) << "Transport error, " << ret.description()
            << ", during notification registration for "
            << interface << "/" << instanceName << ".";
        return;
    }

    if (!ret) {
        LOG(ERROR) << "Could not register for notifications for "
            << interface << "/" << instanceName << ".";
        return;
    }

    waiter->wait(interface, instanceName);

    if (!manager->unregisterForNotifications(interface, instanceName, waiter).withDefault(false)) {
        LOG(ERROR) << "Could not unregister service notification for "
            << interface << "/" << instanceName << ".";
    }
}

}; // namespace details

}; // namespace hardware
}; // namespace android
