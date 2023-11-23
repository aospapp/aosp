/*
 *
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */


#define GRPC_MSG_BUF_MAX 1024

// Do not use_zero, the type of the message queue should be positive value.
enum wmediumd_grpc_type {
    GRPC_REQUEST = 1,
    GRPC_RESPONSE,
};

// Do not use zero, writing zero to eventfd doesn't throw an event.
enum wmediumd_grpc_request_type {
    REQUEST_SET_POSITION = 1,
};

// Do not use zero, writing zero to eventfd doesn't throw an event.
enum wmediumd_grpc_response_type {
    RESPONSE_INVALID = 1,
    RESPONSE_ACK,
};

struct wmediumd_grpc_message {
    long type;
    char data[GRPC_MSG_BUF_MAX];
};