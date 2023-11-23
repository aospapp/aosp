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

#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdnoreturn.h>
#include <sys/cdefs.h>

#include "vm_main.h"

__BEGIN_DECLS

struct AIBinder;
typedef struct AIBinder AIBinder;

/**
 * Notifies the host that the payload is ready.
 *
 * If the host app has set a `VirtualMachineCallback` for the VM, its
 * `onPayloadReady` method will be called.
 *
 * Note that subsequent calls to this function after the first have no effect;
 * `onPayloadReady` is never called more than once.
 */
void AVmPayload_notifyPayloadReady(void);

/**
 * Runs a binder RPC server, serving the supplied binder service implementation on the given vsock
 * port.
 *
 * If and when the server is ready for connections (it is listening on the port), `on_ready` is
 * called to allow appropriate action to be taken - e.g. to notify clients that they may now
 * attempt to connect with `AVmPayload_notifyPayloadReady`.
 *
 * Note that this function does not return. The calling thread joins the binder
 * thread pool to handle incoming messages.
 *
 * \param service the service to bind to the given port.
 * \param port vsock port.
 * \param on_ready the callback to execute once the server is ready for connections. If not null the
 * callback will be called at most once.
 * \param param parameter to be passed to the `on_ready` callback.
 */
noreturn void AVmPayload_runVsockRpcServer(AIBinder* _Nonnull service, uint32_t port,
                                           void (*_Nullable on_ready)(void* _Nullable param),
                                           void* _Nullable param);

/**
 * Returns all or part of a 32-byte secret that is bound to this unique VM
 * instance and the supplied identifier. The secret can be used e.g. as an
 * encryption key.
 *
 * Every VM has a secret that is derived from a device-specific value known to
 * the hypervisor, the code that runs in the VM and its non-modifiable
 * configuration; it is not made available to the host OS.
 *
 * This function performs a further derivation from the VM secret and the
 * supplied identifier. As long as the VM identity doesn't change the same value
 * will be returned for the same identifier, even if the VM is stopped &
 * restarted or the device rebooted.
 *
 * If multiple secrets are required for different purposes, a different
 * identifier should be used for each. The identifiers otherwise are arbitrary
 * byte sequences and do not need to be kept secret; typically they are
 * hardcoded in the calling code.
 *
 * \param identifier identifier of the secret to return.
 * \param identifier_size size of the secret identifier.
 * \param secret pointer to size bytes where the secret is written.
 * \param size number of bytes of the secret to get, <= 32.
 */
void AVmPayload_getVmInstanceSecret(const void* _Nonnull identifier, size_t identifier_size,
                                    void* _Nonnull secret, size_t size);

/**
 * Gets the path to the APK contents. It is a directory, under which are
 * the unzipped contents of the APK containing the payload, all read-only
 * but accessible to the payload.
 *
 * \return the path to the APK contents. The returned string should not be
 * deleted or freed by the application. The string remains valid for the
 * lifetime of the VM.
 */
const char* _Nonnull AVmPayload_getApkContentsPath(void);

/**
 * Gets the path to the encrypted persistent storage for the VM, if any. This is
 * a directory under which any files or directories created will be stored on
 * behalf of the VM by the host app. All data is encrypted using a key known
 * only to the VM, so the host cannot decrypt it, but may delete it.
 *
 * \return the path to the APK contents, or NULL if no encrypted storage was
 * requested in the VM configuration. If non-null the returned string should not
 * be deleted or freed by the application and remains valid for the lifetime of
 * the VM.
 */
const char* _Nullable AVmPayload_getEncryptedStoragePath(void);

__END_DECLS
