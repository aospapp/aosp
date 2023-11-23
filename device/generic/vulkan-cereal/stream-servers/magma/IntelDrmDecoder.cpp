// Copyright (C) 2023 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
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

#include "stream-servers/magma/IntelDrmDecoder.h"

#include "RenderThreadInfoMagma.h"

#include <sys/ioctl.h>
#include <unistd.h>

namespace gfxstream {
namespace magma {

std::unique_ptr<IntelDrmDecoder> IntelDrmDecoder::Create() {
    std::unique_ptr<IntelDrmDecoder> decoder(new IntelDrmDecoder());
    return decoder;
}

#define MAGMA_DECODER_BIND_METHOD(method) \
    magma_server_context_t::method = [](auto ...args) { \
      auto decoder = RenderThreadInfoMagma::get()->m_magmaDec.get(); \
      return static_cast<IntelDrmDecoder*>(decoder)->method(args...); \
    }

IntelDrmDecoder::IntelDrmDecoder() : Decoder() {
    MAGMA_DECODER_BIND_METHOD(magma_device_import);
    MAGMA_DECODER_BIND_METHOD(magma_device_release);
    MAGMA_DECODER_BIND_METHOD(magma_device_query);
    MAGMA_DECODER_BIND_METHOD(magma_device_create_connection);
    MAGMA_DECODER_BIND_METHOD(magma_connection_release);
    MAGMA_DECODER_BIND_METHOD(magma_connection_create_buffer);
    MAGMA_DECODER_BIND_METHOD(magma_connection_release_buffer);
    MAGMA_DECODER_BIND_METHOD(magma_connection_create_semaphore);
    MAGMA_DECODER_BIND_METHOD(magma_connection_release_semaphore);
    MAGMA_DECODER_BIND_METHOD(magma_buffer_export);
    MAGMA_DECODER_BIND_METHOD(magma_semaphore_signal);
    MAGMA_DECODER_BIND_METHOD(magma_semaphore_reset);
    MAGMA_DECODER_BIND_METHOD(magma_poll);
    MAGMA_DECODER_BIND_METHOD(magma_connection_get_error);
    MAGMA_DECODER_BIND_METHOD(magma_connection_create_context);
    MAGMA_DECODER_BIND_METHOD(magma_connection_release_context);
    MAGMA_DECODER_BIND_METHOD(magma_connection_map_buffer);
    MAGMA_DECODER_BIND_METHOD(magma_connection_unmap_buffer);
}

magma_status_t IntelDrmDecoder::magma_device_import(
    magma_handle_t device_channel,
    magma_device_t* device_out) {
    return MAGMA_STATUS_UNIMPLEMENTED;
}

void IntelDrmDecoder::magma_device_release(
    magma_device_t device) {
}

magma_status_t IntelDrmDecoder::magma_device_query(
    magma_device_t device,
    uint64_t id,
    magma_handle_t* result_buffer_out,
    uint64_t* result_out) {
    *result_buffer_out = MAGMA_INVALID_OBJECT_ID;
    *result_out = 0;
    return MAGMA_STATUS_UNIMPLEMENTED;
}

magma_status_t IntelDrmDecoder::magma_device_create_connection(
    magma_device_t device,
    magma_connection_t* connection_out) {
    *connection_out = MAGMA_INVALID_OBJECT_ID;
    return MAGMA_STATUS_UNIMPLEMENTED;
}

void IntelDrmDecoder::magma_connection_release(
    magma_connection_t connection) {
}

magma_status_t IntelDrmDecoder::magma_connection_create_buffer(
    magma_connection_t connection,
    uint64_t size,
    uint64_t* size_out,
    magma_buffer_t* buffer_out,
    magma_buffer_id_t* id_out) {
    *size_out = 0;
    *buffer_out = MAGMA_INVALID_OBJECT_ID;
    *id_out = MAGMA_INVALID_OBJECT_ID;
    return MAGMA_STATUS_UNIMPLEMENTED;
}

void IntelDrmDecoder::magma_connection_release_buffer(
    magma_connection_t connection,
    magma_buffer_t buffer) {
}

magma_status_t IntelDrmDecoder::magma_connection_create_semaphore(
    magma_connection_t magma_connection,
    magma_semaphore_t* semaphore_out,
    magma_semaphore_id_t* id_out) {
    *semaphore_out = MAGMA_INVALID_OBJECT_ID;
    *id_out = MAGMA_INVALID_OBJECT_ID;
    return MAGMA_STATUS_UNIMPLEMENTED;
}

void IntelDrmDecoder::magma_connection_release_semaphore(
    magma_connection_t connection,
    magma_semaphore_t semaphore) {
}

magma_status_t IntelDrmDecoder::magma_buffer_export(
    magma_buffer_t buffer,
    magma_handle_t* buffer_handle_out) {
    *buffer_handle_out = MAGMA_INVALID_OBJECT_ID;
    return MAGMA_STATUS_UNIMPLEMENTED;
}

void IntelDrmDecoder::magma_semaphore_signal(
    magma_semaphore_t semaphore) {
}

void IntelDrmDecoder::magma_semaphore_reset(
    magma_semaphore_t semaphore) {
}

magma_status_t IntelDrmDecoder::magma_poll(
    magma_poll_item_t* items,
    uint32_t count,
    uint64_t timeout_ns) {
    return MAGMA_STATUS_UNIMPLEMENTED;
}

magma_status_t IntelDrmDecoder::magma_connection_get_error(
    magma_connection_t connection) {
    return MAGMA_STATUS_UNIMPLEMENTED;
}

magma_status_t IntelDrmDecoder::magma_connection_create_context(
    magma_connection_t connection,
    uint32_t* context_id_out) {
    *context_id_out = MAGMA_INVALID_OBJECT_ID;
    return MAGMA_STATUS_UNIMPLEMENTED;
}

void IntelDrmDecoder::magma_connection_release_context(
    magma_connection_t connection,
    uint32_t context_id) {
}

magma_status_t IntelDrmDecoder::magma_connection_map_buffer(
    magma_connection_t connection,
    uint64_t hw_va,
    magma_buffer_t buffer,
    uint64_t offset,
    uint64_t length,
    uint64_t map_flags) {
    return MAGMA_STATUS_UNIMPLEMENTED;
}

void IntelDrmDecoder::magma_connection_unmap_buffer(
    magma_connection_t connection,
    uint64_t hw_va,
    magma_buffer_t buffer) {
}

} // namespace magma
} // namespace gfxstream
