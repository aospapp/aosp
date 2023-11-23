/*
 * Copyright (C) 2022 The Android Open Source Project
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
#include "TracingClient.h"
#include "vsockinfo.h"

#include <android-base/logging.h>

#include <getopt.h>
#include <unistd.h>

using ::android::hardware::automotive::utils::VsockConnectionInfo;
using ::android::tools::automotive::tracing::TracingClient;

int main(int argc, char* argv[]) {
    enum class Options {
        OPT_SERVER_ADDR,
        OPT_CMD,
        OPT_HOST_CONFIG,
        OPT_SESSION_ID,
        OPT_FILE_DIR,
    };
    enum class TracingCommand {
        INVALID_CMD_CODE,
        START_TRACING,
        STOP_TRACING,
        GET_TRACING_FILE,
    };
    struct option options[] = {
            {
                    .name = "server_addr",
                    .has_arg = 1,
                    .flag = 0,
                    .val = static_cast<int>(Options::OPT_SERVER_ADDR),
            },
            {
                    .name = "cmd",
                    .has_arg = 1,
                    .flag = 0,
                    .val = static_cast<int>(Options::OPT_CMD),
            },
            {
                    .name = "host_config",
                    .has_arg = 1,
                    .flag = 0,
                    .val = static_cast<int>(Options::OPT_HOST_CONFIG),
            },
            {
                    .name = "session_id",
                    .has_arg = 1,
                    .flag = 0,
                    .val = static_cast<int>(Options::OPT_SESSION_ID),
            },
            {
                    .name = "dir",
                    .has_arg = 1,
                    .flag = 0,
                    .val = static_cast<int>(Options::OPT_FILE_DIR),
            },
            {},
    };

    std::string tracing_service_addr;
    TracingCommand cmd_code = TracingCommand::INVALID_CMD_CODE;
    std::string host_config;
    std::string file_dir;
    uint64_t session_id = 0;

    int opt_value;
    int index = 0;
    while ((opt_value = getopt_long_only(argc, argv, ":", options, &index)) != -1) {
        switch (static_cast<Options>(opt_value)) {
            case Options::OPT_SERVER_ADDR:
                tracing_service_addr = optarg;
                break;
            case Options::OPT_CMD: {
                const auto optarg_str = std::string(optarg);
                if (optarg_str == "start") {
                    cmd_code = TracingCommand::START_TRACING;
                } else if (optarg_str == "stop") {
                    cmd_code = TracingCommand::STOP_TRACING;
                } else if (optarg_str == "get") {
                    cmd_code = TracingCommand::GET_TRACING_FILE;
                } else {
                    std::cerr << "tracing-client doesn't support command: " << optarg << std::endl;
                    return -1;
                }
                break;
            }
            case Options::OPT_HOST_CONFIG:
                host_config = optarg;
                break;
            case Options::OPT_FILE_DIR:
                file_dir = optarg;
                break;
            case Options::OPT_SESSION_ID:
                session_id = std::stoull(optarg);
                break;
            default:
                std::cerr << "tracing-client can't process option: " << argv[optind - 1]
                          << std::endl;
                return -1;
        }
    }

    if (opt_value == -1 && optind < argc) {
        std::cerr << "tracing-client doesn't support option: " << argv[optind - 1] << std::endl;
        return -1;
    }

    if (tracing_service_addr.empty()) {
        const auto si = VsockConnectionInfo::fromRoPropertyStore(
                {
                        "ro.boot.vendor.tracing.server.cid",
                        "ro.vendor.tracing.server.cid",
                },
                {
                        "ro.boot.vendor.tracing.server.port",
                        "ro.vendor.tracing.server.port",
                });
        if (!si) {
            std::cerr << "tracing-client failed to get server connection cid/port." << std::endl;
            return -1;
        }
        tracing_service_addr = si->str();
    }

    TracingClient client(tracing_service_addr);
    switch (cmd_code) {
        case TracingCommand::START_TRACING: {
            return client.StartTracing(host_config, session_id) ? 0 : -1;

            case TracingCommand::STOP_TRACING:
                return client.StopTracing(session_id) ? 0 : -1;

            case TracingCommand::GET_TRACING_FILE:
                return client.GetTracingFile(session_id, file_dir) ? 0 : -1;

            default:
                std::cerr << "tracing-client input has wrong command code "
                          << static_cast<int>(cmd_code) << std::endl;
                return -1;
        }
    }
}
