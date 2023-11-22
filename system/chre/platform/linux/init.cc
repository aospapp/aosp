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

#include "chre/core/event.h"
#include "chre/core/event_loop.h"
#include "chre/core/event_loop_manager.h"
#include "chre/core/init.h"
#include "chre/core/nanoapp.h"
#include "chre/core/static_nanoapps.h"
#include "chre/platform/context.h"
#include "chre/platform/log.h"
#include "chre/platform/shared/platform_log.h"
#include "chre/platform/system_timer.h"
#include "chre/util/time.h"

#include <csignal>
#include <tclap/CmdLine.h>
#include <thread>

using chre::EventLoopManagerSingleton;
using chre::Milliseconds;

//! A description of the simulator.
constexpr char kSimDescription[] = "A simulation environment for the Context "
                                   "Hub Runtime Environment (CHRE)";

//! The version of the simulator. This is not super important but is assigned by
//! rules of semantic versioning.
constexpr char kSimVersion[] = "0.1.0";

namespace {

extern "C" void signalHandler(int sig) {
  (void) sig;
  LOGI("Stop request received");
  EventLoopManagerSingleton::get()->getEventLoop().stop();
}

}

int main(int argc, char **argv) {
  try {
    // Parse command-line arguments.
    TCLAP::CmdLine cmd(kSimDescription, ' ', kSimVersion);
    TCLAP::SwitchArg no_static_nanoapps_arg("", "no_static_nanoapps",
        "disable running static nanoapps", cmd, false);
    TCLAP::MultiArg<std::string> nanoapps_arg("", "nanoapp",
        "a nanoapp shared object to load and execute", false, "path", cmd);
    cmd.parse(argc, argv);

    // Initialize the system.
    chre::PlatformLogSingleton::init();
    chre::init();

    // Register a signal handler.
    std::signal(SIGINT, signalHandler);

    // Load any static nanoapps and start the event loop.
    std::thread chreThread([&]() {
      // Load static nanoapps unless they are disabled by a command-line flag.
      if (!no_static_nanoapps_arg.getValue()) {
        chre::loadStaticNanoapps();
      }

      // Load dynamic nanoapps specified on the command-line.
      chre::DynamicVector<chre::UniquePtr<chre::Nanoapp>> dynamicNanoapps;
      for (const auto& nanoapp : nanoapps_arg.getValue()) {
        dynamicNanoapps.push_back(chre::MakeUnique<chre::Nanoapp>());
        dynamicNanoapps.back()->loadFromFile(nanoapp);
        EventLoopManagerSingleton::get()->getEventLoop()
            .startNanoapp(dynamicNanoapps.back());
      }

      EventLoopManagerSingleton::get()->getEventLoop().run();
    });
    chreThread.join();

    chre::deinit();
    chre::PlatformLogSingleton::deinit();
  } catch (TCLAP::ExitException) {}

  return 0;
}
