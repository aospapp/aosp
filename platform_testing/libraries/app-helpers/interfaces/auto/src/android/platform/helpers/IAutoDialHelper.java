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

package android.platform.helpers;

public interface IAutoDialHelper extends IAppHelper {
    /**
     * Setup expectations: The app is open and the dialpad is open
     *
     * <p>This method is used to dial the phonenumber on dialpad
     *
     * @param phoneNumber phone number to dial.
     */
    void dialANumber(String phoneNumber);

    /**
     * Setup expectations: The app is open and there is an ongoing call.
     *
     * <p>This method is used to end call using softkey.
     */
    void endCall();

    /**
     * Setup expectations: The app is open.
     *
     * <p>This method is used to open call history details.
     */
    void openCallHistory();

    /**
     * Setup expectations: The app is open.
     *
     * <p>This method dials a contact from the contact list.
     *
     * @param contactName to dial.
     */
    void callContact(String contactName);

    /**
     * Setup expectations: The app is open and in Dialpad.
     *
     * <p>This method is used to delete the number entered on dialpad using backspace
     */
    void deleteDialedNumber();

    /**
     * Setup expectations: The app is open and in Dialpad
     *
     * <p>This method is used to get the number entered on dialing screen.
     */
    String getDialedNumber();

    /**
     * Setup expectations: The app is open and in Dialpad
     *
     * <p>This method is used to get the number entered on dialpad
     */
    String getDialInNumber();

    /**
     * Setup expectations: The app is open and there is an ongoing call.
     *
     * <p>This method is used to get the name of the contact for the ongoing call
     */
    String getDialedContactName();

    /**
     * Setup expectations: The app is open and Call History is open.
     *
     * <p>This method is used to get the most recent call history.
     */
    String getRecentCallHistory();

    /**
     * Setup expectations: The app is open and phonenumber is entered on the dialpad
     *
     * <p>This method is used to make/receive a call using softkey
     */
    void makeCall();

    /**
     * Setup expectations: The app is open
     *
     * <p>This method is used to dial a number from a list (Favorites, Call History, Contact)
     *
     * @param contact (number or name) dial.
     */
    void dialFromList(String contact);

    /**
     * Setup expectations: The app is open and there is an ongoing call
     *
     * <p>This method is used to enter number on the in-call dialpad
     */
    void inCallDialPad(String phoneNumber);

    /**
     * Setup expectations: The app is open and there is an ongoing call
     *
     * <p>This method is used to mute the ongoing call
     */
    void muteCall();

    /**
     * Setup expectations: The app is open and there is an ongoing call
     *
     * <p>This method is used to unmute the ongoing call
     */
    void unmuteCall();
}
