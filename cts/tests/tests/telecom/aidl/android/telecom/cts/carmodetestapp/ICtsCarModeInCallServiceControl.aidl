/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telecom.cts.carmodetestapp;
import android.telecom.PhoneAccountHandle;
import android.telecom.PhoneAccount;

interface ICtsCarModeInCallServiceControl {
    boolean isBound();
    boolean isUnbound();
    void reset();
    int getCallCount();
    void enableCarMode(int priority);
    void disableCarMode();
    void disconnectCalls();
    boolean requestAutomotiveProjection();
    void releaseAutomotiveProjection();
    boolean checkBindStatus(boolean bind);
    List<PhoneAccountHandle> getSelfManagedPhoneAccounts();
    List<PhoneAccountHandle> getOwnSelfManagedPhoneAccounts();
    void registerPhoneAccount(in PhoneAccount phoneAccount);
    void unregisterPhoneAccount(in PhoneAccountHandle phoneAccountHandle);
    boolean checkCallAddedStatus();
    int getCallVideoState();
    int getCallState();
    void hold();
    void unhold();
    void disconnect();
    void answerCall(int videoState);
}