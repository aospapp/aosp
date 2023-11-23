/*
 * Copyright (C) 2022 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.platform.snippets;

import android.platform.helpers.HelperAccessor;
import android.platform.helpers.IAutoDialContactDetailsHelper;
import android.platform.helpers.IAutoDialHelper;
import android.platform.helpers.IAutoVehicleHardKeysHelper;

import com.google.android.mobly.snippet.Snippet;
import com.google.android.mobly.snippet.rpc.Rpc;

/** Snippet class for exposing Phone/Dial App APIs. */
public class PhoneSnippet implements Snippet {
    private final HelperAccessor<IAutoDialHelper> mDialerHelper;
    private final HelperAccessor<IAutoDialContactDetailsHelper> mContactsDetailsHelper;
    private final HelperAccessor<IAutoVehicleHardKeysHelper> mHardKeysHelper;

    public PhoneSnippet() {
        mDialerHelper = new HelperAccessor<>(IAutoDialHelper.class);
        mContactsDetailsHelper = new HelperAccessor<>(IAutoDialContactDetailsHelper.class);
        mHardKeysHelper = new HelperAccessor<>(IAutoVehicleHardKeysHelper.class);
    }

    @Rpc(description = "Open Phone Application.")
    public void openPhoneApp() {
        mDialerHelper.get().open();
    }

    @Rpc(description = "Open Dial Pad and dial in a number using keypad.")
    public void dialANumber(String phoneNumber) {
        mDialerHelper.get().dialANumber(phoneNumber);
    }

    @Rpc(description = "Make a call.")
    public void makeCall() {
        mDialerHelper.get().makeCall();
    }

    @Rpc(description = "End the call.")
    public void endCall() {
        mDialerHelper.get().endCall();
    }

    @Rpc(description = "Press the hardkey for ending the call.")
    public void endCallWithHardkey() {
        mHardKeysHelper.get().pressEndCallKey();
    }

    @Rpc(description = "Open Call History.")
    public void openCallHistory() {
        mDialerHelper.get().openCallHistory();
    }

    @Rpc(description = "Call Contact From Contact List.")
    public void callContact(String contactName) {
        mDialerHelper.get().callContact(contactName);
    }

    @Rpc(description = "Delete the dialed number on Dial Pad.")
    public void deleteDialedNumber() {
        mDialerHelper.get().deleteDialedNumber();
    }

    @Rpc(description = "Get the dialed number when the call is in progress.")
    public String getDialedNumber() {
        return mDialerHelper.get().getDialedNumber();
    }

    @Rpc(description = "Get the entered on dial pad.")
    public String getDialInNumber() {
        return mDialerHelper.get().getDialInNumber();
    }

    @Rpc(description = "Get the contact name for dialed number when the call is in progress.")
    public String getDialedContactName() {
        return mDialerHelper.get().getDialedContactName();
    }

    @Rpc(description = "Get the recent entry from Call History.")
    public String getRecentCallHistory() {
        return mDialerHelper.get().getRecentCallHistory();
    }

    @Rpc(
            description =
                    "Call contact from list open in foreground e.g. Favorites, Recents, Contacts.")
    public void dialFromList(String contact) {
        mDialerHelper.get().dialFromList(contact);
    }

    @Rpc(description = "Dial a number in call dial pad when call is in progress.")
    public void inCallDialPad(String phoneNumber) {
        mDialerHelper.get().inCallDialPad(phoneNumber);
    }

    @Rpc(description = "Mute Call.")
    public void muteCall() {
        mDialerHelper.get().muteCall();
    }

    @Rpc(description = "Unmute Call.")
    public void unmuteCall() {
        mDialerHelper.get().unmuteCall();
    }

    @Rpc(description = "Change audio source to Phone when the call is in progress.")
    public void changeAudioSourceToPhone() {
        mDialerHelper.get().changeAudioSource(IAutoDialHelper.AudioSource.PHONE);
    }

    @Rpc(description = "Change audio source to Car Speakers when the call is in progress.")
    public void changeAudioSourceToCarSpeakers() {
        mDialerHelper.get().changeAudioSource(IAutoDialHelper.AudioSource.CAR_SPEAKERS);
    }

    @Rpc(description = "Call Most Recent History.")
    public void callMostRecentHistory() {
        mDialerHelper.get().callMostRecentHistory();
    }

    @Rpc(description = "Get contact name while the call is in progress.")
    public String getContactName() {
        return mDialerHelper.get().getContactName();
    }

    @Rpc(description = "Get contact type (Work, Mobile, Home) while the call is in progress.")
    public String getContactType() {
        return mDialerHelper.get().getContactType();
    }

    @Rpc(description = "Search contact by name.")
    public void searchContactsByName(String contact) {
        mDialerHelper.get().searchContactsByName(contact);
    }

    @Rpc(description = "Search contact by number.")
    public void searchContactsByNumber(String number) {
        mDialerHelper.get().searchContactsByNumber(number);
    }

    @Rpc(description = "Get first search result.")
    public String getFirstSearchResult() {
        return mDialerHelper.get().getFirstSearchResult();
    }

    @Rpc(description = "Sort contact list by First Name.")
    public void sortContactListByFirstName() {
        mDialerHelper.get().sortContactListBy(IAutoDialHelper.OrderType.FIRST_NAME);
    }

    @Rpc(description = "Sort contact list by Last Name.")
    public void sortContactListByLastName() {
        mDialerHelper.get().sortContactListBy(IAutoDialHelper.OrderType.LAST_NAME);
    }

    @Rpc(description = "Get first contact from contacts list.")
    public String getFirstContactFromContactList() {
        return mDialerHelper.get().getFirstContactFromContactList();
    }

    @Rpc(description = "Check if given contact is in Favorites.")
    public boolean isContactInFavorites(String contact) {
        return mDialerHelper.get().isContactInFavorites(contact);
    }

    @Rpc(description = "Open details page for given contact.")
    public void openDetailsPage(String contact) {
        mDialerHelper.get().openDetailsPage(contact);
    }

    @Rpc(description = "Open Contacts List.")
    public void openContacts() {
        mDialerHelper.get().openContacts();
    }

    @Rpc(description = "Add and remove contact ( contact details are open ) from favorites.")
    public void addRemoveFavoriteContact() {
        mContactsDetailsHelper.get().addRemoveFavoriteContact();
    }

    @Rpc(description = "Make call to number with type Work from contact details page.")
    public void makeCallFromDetailsPageByTypeWork() {
        mContactsDetailsHelper
                .get()
                .makeCallFromDetailsPageByType(IAutoDialContactDetailsHelper.ContactType.WORK);
    }

    @Rpc(description = "Make call to number with type Home from contact details page.")
    public void makeCallFromDetailsPageByTypeHome() {
        mContactsDetailsHelper
                .get()
                .makeCallFromDetailsPageByType(IAutoDialContactDetailsHelper.ContactType.HOME);
    }

    @Rpc(description = "Make call to number with type Mobile from contact details page.")
    public void makeCallFromDetailsPageByTypeMobile() {
        mContactsDetailsHelper
                .get()
                .makeCallFromDetailsPageByType(IAutoDialContactDetailsHelper.ContactType.MOBILE);
    }

    @Rpc(description = "Close contact details page.")
    public void closeDetailsPage() {
        mContactsDetailsHelper.get().closeDetailsPage();
    }

    @Override
    public void shutdown() {}
}
