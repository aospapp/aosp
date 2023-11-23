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
package android.system.virtualizationservice_internal;

import android.system.virtualizationservice.VirtualMachineDebugInfo;
import android.system.virtualizationservice_internal.AtomVmBooted;
import android.system.virtualizationservice_internal.AtomVmCreationRequested;
import android.system.virtualizationservice_internal.AtomVmExited;
import android.system.virtualizationservice_internal.IGlobalVmContext;

interface IVirtualizationServiceInternal {
    /**
     * Removes the memlock rlimit of the calling process.
     *
     * The SELinux policy only allows this to succeed for virtmgr callers.
     */
    void removeMemlockRlimit();

    /**
     * Allocates global context for a new VM.
     *
     * This allocates VM's globally unique resources such as the CID.
     * The resources will not be recycled as long as there is a strong reference
     * to the returned object.
     */
    IGlobalVmContext allocateGlobalVmContext(int requesterDebugPid);

    /** Forwards a VmBooted atom to statsd. */
    void atomVmBooted(in AtomVmBooted atom);

    /** Forwards a VmCreationRequested atom to statsd. */
    void atomVmCreationRequested(in AtomVmCreationRequested atom);

    /** Forwards a VmExited atom to statsd. */
    void atomVmExited(in AtomVmExited atom);

    /** Get a list of all currently running VMs. */
    VirtualMachineDebugInfo[] debugListVms();
}
