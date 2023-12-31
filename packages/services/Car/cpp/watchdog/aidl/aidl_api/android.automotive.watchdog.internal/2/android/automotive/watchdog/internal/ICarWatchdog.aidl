/*
 * Copyright (C) 2020 The Android Open Source Project
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
///////////////////////////////////////////////////////////////////////////////
// THIS FILE IS IMMUTABLE. DO NOT EDIT IN ANY CASE.                          //
///////////////////////////////////////////////////////////////////////////////

// This file is a snapshot of an AIDL file. Do not edit it manually. There are
// two cases:
// 1). this is a frozen version file - do not edit this in any case.
// 2). this is a 'current' file. If you make a backwards compatible change to
//     the interface (from the latest frozen version), the build system will
//     prompt you to update this file with `m <name>-update-api`.
//
// You must not make a backward incompatible change to any AIDL file built
// with the aidl_interface module type with versions property set. The module
// type is used to build AIDL files in a way that they can be used across
// independently updatable components of the system. If a device is shipped
// with such a backward incompatible change, it has a high risk of breaking
// later when a module using the interface is updated, e.g., Mainline modules.

package android.automotive.watchdog.internal;
interface ICarWatchdog {
  void registerCarWatchdogService(in android.automotive.watchdog.internal.ICarWatchdogServiceForSystem service);
  void unregisterCarWatchdogService(in android.automotive.watchdog.internal.ICarWatchdogServiceForSystem service);
  void registerMonitor(in android.automotive.watchdog.internal.ICarWatchdogMonitor monitor);
  void unregisterMonitor(in android.automotive.watchdog.internal.ICarWatchdogMonitor monitor);
  void tellCarWatchdogServiceAlive(in android.automotive.watchdog.internal.ICarWatchdogServiceForSystem service, in List<android.automotive.watchdog.internal.ProcessIdentifier> processIdentifiers, in int sessionId);
  void tellDumpFinished(in android.automotive.watchdog.internal.ICarWatchdogMonitor monitor, in android.automotive.watchdog.internal.ProcessIdentifier processIdentifier);
  void notifySystemStateChange(in android.automotive.watchdog.internal.StateType type, in int arg1, in int arg2);
  void updateResourceOveruseConfigurations(in List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> configs);
  List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> getResourceOveruseConfigurations();
  void controlProcessHealthCheck(in boolean enable);
  void setThreadPriority(int pid, int tid, int uid, int policy, int priority);
  android.automotive.watchdog.internal.ThreadPolicyWithPriority getThreadPriority(int pid, int tid, int uid);
}
