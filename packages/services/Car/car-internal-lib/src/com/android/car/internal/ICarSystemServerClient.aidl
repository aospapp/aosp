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

package com.android.car.internal;

import android.content.pm.UserInfo;
import android.os.UserHandle;

import com.android.internal.os.IResultReceiver;

/**
 * API to communicate from CarServiceHelperService to car service.
 */
oneway interface ICarSystemServerClient {
    /**
     * Notify of user lifecycle events.
     *
     * @param eventType - type as defined by CarUserManager.UserLifecycleEventType
     * @param fromUserId - user id of previous user when type is SWITCHING (or UserHandle.USER_NULL)
     * @param toUserId - user id of new user.
     */
    void onUserLifecycleEvent(int eventType, int fromUserId, int toUserId);

    /**
     * Nofity when a user is removed.
     *
     * NOTE: this is different from onUserLifecycleEvent(), whic is used on user switching events.
     *
     * @param user info about the user that was removed.
     */
    void onUserRemoved(in UserInfo user);

    /**
     * Notify to init boot user.
     */
    void initBootUser();

    /**
      * Notify that the device must be factory reset, so CarService can ask user to confirm.
      *
      * @param callback used to trigger the factory reset.
      */
    void onFactoryReset(IResultReceiver callback);

    /**
     * Initial user is decided by HAL and information is saved in CarUserService. It is possible
     * that car service may crash and this information will be lost. To avoid this situation,
     * initial user information is saved in System Service using
     * {@link ICarServiceHelper.sendInitialUser}. If car service reconnects after crash, then this
     * call will set the initial user information in CarUserService.
     */
     void setInitialUser(in UserHandle user);
}
