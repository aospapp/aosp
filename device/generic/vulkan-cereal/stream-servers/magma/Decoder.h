// Copyright (C) 2023 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License") = 0;
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#include "magma_dec.h"

#include <memory>

namespace gfxstream {
namespace magma {

// Generic magma decoder.
class Decoder : public magma_decoder_context_t {
  public:
    static std::unique_ptr<Decoder> Create();

  private:
    virtual magma_status_t magma_device_import(magma_handle_t device_channel, magma_device_t* device_out) = 0;
    virtual void magma_device_release(magma_device_t device) = 0;
    virtual magma_status_t magma_device_query(magma_device_t device, uint64_t id, magma_handle_t* result_buffer_out, uint64_t* result_out) = 0;
    virtual magma_status_t magma_device_create_connection(magma_device_t device, magma_connection_t* connection_out) = 0;
    virtual void magma_connection_release(magma_connection_t connection) = 0;
    virtual magma_status_t magma_connection_create_buffer(magma_connection_t connection, uint64_t size, uint64_t* size_out, magma_buffer_t* buffer_out, magma_buffer_id_t* id_out) = 0;
    virtual void magma_connection_release_buffer(magma_connection_t connection, magma_buffer_t buffer) = 0;
    virtual magma_status_t magma_connection_create_semaphore(magma_connection_t magma_connection, magma_semaphore_t* semaphore_out, magma_semaphore_id_t* id_out) = 0;
    virtual void magma_connection_release_semaphore(magma_connection_t connection, magma_semaphore_t semaphore) = 0;
    virtual magma_status_t magma_buffer_export(magma_buffer_t buffer, magma_handle_t* buffer_handle_out) = 0;
    virtual void magma_semaphore_signal(magma_semaphore_t semaphore) = 0;
    virtual void magma_semaphore_reset(magma_semaphore_t semaphore) = 0;
    virtual magma_status_t magma_poll(magma_poll_item_t* items, uint32_t count, uint64_t timeout_ns) = 0;
    virtual magma_status_t magma_connection_get_error(magma_connection_t connection) = 0;
    virtual magma_status_t magma_connection_create_context(magma_connection_t connection, uint32_t* context_id_out) = 0;
    virtual void magma_connection_release_context(magma_connection_t connection, uint32_t context_id) = 0;
    virtual magma_status_t magma_connection_map_buffer(magma_connection_t connection, uint64_t hw_va, magma_buffer_t buffer, uint64_t offset, uint64_t length, uint64_t map_flags) = 0;
    virtual void magma_connection_unmap_buffer(magma_connection_t connection, uint64_t hw_va, magma_buffer_t buffer) = 0;
};

} // namespace magma
} // namespace gfxstream
