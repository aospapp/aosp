/*
 * Copyright 2022 The Android Open Source Project
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

package android.system.virtualization.payload;

/**
 * This interface regroups the tasks that payloads delegate to
 * Microdroid Manager for execution.
 */
interface IVmPayloadService {
    /** Socket name of the service IVmPayloadService. */
    const String VM_PAYLOAD_SERVICE_SOCKET_NAME = "vm_payload_service";

    /** Path to the APK contents path. */
    const String VM_APK_CONTENTS_PATH = "/mnt/apk";

    /**
     * Path to the encrypted storage. Note the path will not exist if encrypted storage
     * is not enabled.
     */
    const String ENCRYPTEDSTORE_MOUNTPOINT = "/mnt/encryptedstore";

    /** Notifies that the payload is ready to serve. */
    void notifyPayloadReady();

    /**
     * Gets a secret that is uniquely bound to this VM instance.
     *
     * @param identifier the identifier of the secret to return.
     * @param size the number of bytes of the secret to return.
     * @return size bytes of the identified secret.
     */
    byte[] getVmInstanceSecret(in byte[] identifier, int size);

    /**
     * Gets the DICE attestation chain for the VM.
     *
     * The DICE chain must not be made available to all VMs as it contains privacy breaking
     * identifiers.
     *
     * @return the VM's raw DICE certificate chain.
     * @throws SecurityException if the use of test APIs is not permitted.
     */
    byte[] getDiceAttestationChain();

    /**
     * Gets the DICE attestation CDI for the VM.
     *
     * The raw attestation CDI isn't very useful but is used for smoke tests. A better API would
     * handle key derivation on behalf of the payload so they can't forget to do it themselves and
     * would also mean the payload doesn't get the raw CDI which reduces the chance of it leaking.
     *
     * @return the VM's raw attestation CDI.
     * @throws SecurityException if the use of test APIs is not permitted.
     */
    byte[] getDiceAttestationCdi();
}
