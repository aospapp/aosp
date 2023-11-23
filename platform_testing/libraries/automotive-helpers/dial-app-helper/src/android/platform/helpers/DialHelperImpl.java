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

package android.platform.helpers;

import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.platform.helpers.ScrollUtility.ScrollActions;
import android.platform.helpers.ScrollUtility.ScrollDirection;
import android.platform.helpers.exceptions.UnknownUiException;
import android.platform.spectatio.exceptions.MissingUiElementException;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiObject2;

public class DialHelperImpl extends AbstractStandardAppHelper implements IAutoDialHelper {
    private static final String LOG_TAG = DialHelperImpl.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;

    private ScrollUtility mScrollUtility;
    private ScrollActions mScrollAction;
    private BySelector mBackwardButtonSelector;
    private BySelector mForwardButtonSelector;
    private BySelector mScrollableElementSelector;
    private ScrollDirection mScrollDirection;

    public DialHelperImpl(Instrumentation instr) {
        super(instr);
        mBluetoothManager =
                (BluetoothManager)
                        mInstrumentation.getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        mScrollUtility = ScrollUtility.getInstance(getSpectatioUiUtil());
        mScrollAction =
                ScrollActions.valueOf(
                        getActionFromConfig(AutomotiveConfigConstants.CONTACT_LIST_SCROLL_ACTION));
        mBackwardButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CONTACT_LIST_SCROLL_BACKWARD);
        mForwardButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CONTACT_LIST_SCROLL_FORWARD);
        mScrollableElementSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CONTACT_LIST_SCROLL_ELEMENT);
        mScrollDirection =
                ScrollDirection.valueOf(
                        getActionFromConfig(
                                AutomotiveConfigConstants.CONTACT_LIST_SCROLL_DIRECTION));
    }

    /** {@inheritDoc} */
    @Override
    public void dismissInitialDialogs() {
        // Nothing to dismiss
    }

    /** {@inheritDoc} */
    public void open() {
        getSpectatioUiUtil().pressHome();
        getSpectatioUiUtil().wait1Second();
        getSpectatioUiUtil()
                .executeShellCommand(
                        getCommandFromConfig(
                                AutomotiveConfigConstants.OPEN_PHONE_ACTIVITY_COMMAND));
        getSpectatioUiUtil().wait1Second();
    }

    /** {@inheritDoc} */
    @Override
    public String getPackage() {
        return getPackageFromConfig(AutomotiveConfigConstants.DIAL_PACKAGE);
    }

    /** {@inheritDoc} */
    @Override
    public String getLauncherName() {
        throw new UnsupportedOperationException("Operation not supported.");
    }

    /** {@inheritDoc} */
    public void makeCall() {
        BySelector dialButtonSelector = getUiElementFromConfig(AutomotiveConfigConstants.MAKE_CALL);
        UiObject2 dialButton = getSpectatioUiUtil().findUiObject(dialButtonSelector);
        validateUiObject(dialButton, AutomotiveConfigConstants.MAKE_CALL);
        getSpectatioUiUtil().clickAndWait(dialButton);
        getSpectatioUiUtil().wait5Seconds(); // Wait for the call to go through
    }

    /** {@inheritDoc} */
    public void endCall() {
        BySelector endButtonSelector = getUiElementFromConfig(AutomotiveConfigConstants.END_CALL);
        UiObject2 endButton = getSpectatioUiUtil().findUiObject(endButtonSelector);
        validateUiObject(endButton, AutomotiveConfigConstants.END_CALL);
        getSpectatioUiUtil().clickAndWait(endButton);
        getSpectatioUiUtil().wait5Seconds();
    }

    /** {@inheritDoc} */
    public void dialANumber(String phoneNumber) {
        enterNumber(phoneNumber);
        getSpectatioUiUtil().wait1Second();
    }

    /** {@inheritDoc} */
    public void openCallHistory() {
        BySelector callHistorySelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CALL_HISTORY_MENU);
        UiObject2 historyMenuButton = getSpectatioUiUtil().findUiObject(callHistorySelector);
        validateUiObject(historyMenuButton, AutomotiveConfigConstants.CALL_HISTORY_MENU);
        getSpectatioUiUtil().clickAndWait(historyMenuButton);
    }

    /** {@inheritDoc} */
    public void callContact(String contactName) {
        openContacts();
        dialFromList(contactName);
        getSpectatioUiUtil().wait5Seconds(); // Wait for the call to go through
    }

    /** {@inheritDoc} */
    public String getRecentCallHistory() {
        UiObject2 recentCallHistory = getCallHistory();
        validateUiObject(recentCallHistory, /* action= */ "Recent Call History");
        return recentCallHistory.getText();
    }

    /** {@inheritDoc} */
    public void callMostRecentHistory() {
        UiObject2 recentCallHistory = getCallHistory();
        validateUiObject(recentCallHistory, /* action= */ "Calling Most Recent Call From History");
        getSpectatioUiUtil().clickAndWait(recentCallHistory);
    }

    /** {@inheritDoc} */
    public void deleteDialedNumber() {
        String phoneNumber = getDialInNumber();
        BySelector deleteButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.DELETE_NUMBER);
        UiObject2 deleteButton = getSpectatioUiUtil().findUiObject(deleteButtonSelector);
        validateUiObject(deleteButton, AutomotiveConfigConstants.DELETE_NUMBER);
        for (int index = 0; index < phoneNumber.length(); ++index) {
            getSpectatioUiUtil().clickAndWait(deleteButton);
        }
    }

    /** {@inheritDoc} */
    public String getDialInNumber() {
        BySelector dialedInNumberSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.DIAL_IN_NUMBER);
        UiObject2 dialInNumber = getSpectatioUiUtil().findUiObject(dialedInNumberSelector);
        validateUiObject(dialInNumber, AutomotiveConfigConstants.DIAL_IN_NUMBER);
        return dialInNumber.getText();
    }

    /** {@inheritDoc} */
    public String getDialedNumber() {
        BySelector dialedNumberSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.DIALED_CONTACT_TITLE);
        UiObject2 dialedNumber = getSpectatioUiUtil().findUiObject(dialedNumberSelector);
        validateUiObject(dialedNumber, AutomotiveConfigConstants.DIALED_CONTACT_TITLE);
        return dialedNumber.getText();
    }

    /** {@inheritDoc} */
    public String getDialedContactName() {
        BySelector dialedContactNameSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.DIALED_CONTACT_TITLE);
        UiObject2 dialedContactName = getSpectatioUiUtil().findUiObject(dialedContactNameSelector);
        validateUiObject(dialedContactName, AutomotiveConfigConstants.DIALED_CONTACT_TITLE);
        return dialedContactName.getText();
    }

    /** {@inheritDoc} */
    public void inCallDialPad(String phoneNumber) {
        BySelector dialPadSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.SWITCH_TO_DIAL_PAD);
        UiObject2 dialPad = getSpectatioUiUtil().findUiObject(dialPadSelector);
        validateUiObject(dialPad, AutomotiveConfigConstants.SWITCH_TO_DIAL_PAD);
        getSpectatioUiUtil().clickAndWait(dialPad);
        enterNumber(phoneNumber);
        getSpectatioUiUtil().wait1Second();
    }

    /** {@inheritDoc} */
    public void muteCall() {
        BySelector muteButtonSelector = getUiElementFromConfig(AutomotiveConfigConstants.MUTE_CALL);
        UiObject2 muteButton = getSpectatioUiUtil().findUiObject(muteButtonSelector);
        validateUiObject(muteButton, AutomotiveConfigConstants.MUTE_CALL);
        getSpectatioUiUtil().clickAndWait(muteButton);
    }

    /** {@inheritDoc} */
    public void unmuteCall() {
        BySelector muteButtonSelector = getUiElementFromConfig(AutomotiveConfigConstants.MUTE_CALL);
        UiObject2 muteButton = getSpectatioUiUtil().findUiObject(muteButtonSelector);
        validateUiObject(muteButton, AutomotiveConfigConstants.MUTE_CALL);
        getSpectatioUiUtil().clickAndWait(muteButton);
    }

    private UiObject2 getContactFromContactList(String contact) {
        BySelector contactSelector = By.text(contact);
        UiObject2 contactFromList =
                mScrollUtility.scrollAndFindUiObject(
                        mScrollAction,
                        mScrollDirection,
                        mForwardButtonSelector,
                        mBackwardButtonSelector,
                        mScrollableElementSelector,
                        contactSelector,
                        String.format("scroll to find %s", contact));
            validateUiObject(contactFromList, String.format("Given Contact %s", contact));
            return contactFromList;
    }

    /** {@inheritDoc} */
    public void dialFromList(String contact) {
        UiObject2 contactToCall = getContactFromContactList(contact);
        getSpectatioUiUtil().clickAndWait(contactToCall);
    }

    /** {@inheritDoc} */
    public void changeAudioSource(AudioSource source) {
        BySelector voiceChannelButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CHANGE_VOICE_CHANNEL);
        UiObject2 voiceChannelButton =
                getSpectatioUiUtil().findUiObject(voiceChannelButtonSelector);
        validateUiObject(voiceChannelButton, AutomotiveConfigConstants.CHANGE_VOICE_CHANNEL);
        getSpectatioUiUtil().clickAndWait(voiceChannelButton);
        BySelector voiceChannelSelector;
        if (source == AudioSource.PHONE) {
            voiceChannelSelector =
                    getUiElementFromConfig(AutomotiveConfigConstants.VOICE_CHANNEL_PHONE);
        } else {
            voiceChannelSelector =
                    getUiElementFromConfig(AutomotiveConfigConstants.VOICE_CHANNEL_CAR);
        }
        UiObject2 channelButton = getSpectatioUiUtil().findUiObject(voiceChannelSelector);
        validateUiObject(channelButton, String.format("Voice Channel %s", source));
        getSpectatioUiUtil().clickAndWait(channelButton);
        getSpectatioUiUtil().wait5Seconds();
    }

    /** {@inheritDoc} */
    public String getContactName() {
        BySelector contactNameSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.DIALED_CONTACT_TITLE);
        UiObject2 contactName = getSpectatioUiUtil().findUiObject(contactNameSelector);
        validateUiObject(contactName, AutomotiveConfigConstants.DIALED_CONTACT_TITLE);
        return contactName.getText();
    }

    /** {@inheritDoc} */
    public String getContactType() {
        // Contact number displayed on screen contains type
        // e.g. Mobile xxx-xxx-xxxx , Work xxx-xxx-xxxx
        BySelector contactTypeSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.DIALED_CONTACT_TYPE);
        UiObject2 contactType = getSpectatioUiUtil().findUiObject(contactTypeSelector);
        validateUiObject(contactType, AutomotiveConfigConstants.DIALED_CONTACT_TYPE);
        return contactType.getText();
    }

    /** {@inheritDoc} */
    public void searchContactsByName(String contact) {
        openSearchContact();
        BySelector searchBoxSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CONTACT_SEARCH_BAR);
        UiObject2 searchBox = getSpectatioUiUtil().findUiObject(searchBoxSelector);
        validateUiObject(searchBox, AutomotiveConfigConstants.CONTACT_SEARCH_BAR);
        searchBox.setText(contact);
    }

    /** {@inheritDoc} */
    public void searchContactsByNumber(String number) {
        openSearchContact();
        BySelector searchBoxSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CONTACT_SEARCH_BAR);
        UiObject2 searchBox = getSpectatioUiUtil().findUiObject(searchBoxSelector);
        validateUiObject(searchBox, AutomotiveConfigConstants.CONTACT_SEARCH_BAR);
        searchBox.setText(number);
    }

    /** {@inheritDoc} */
    public String getFirstSearchResult() {
        BySelector searchResultSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.SEARCH_RESULT);
        UiObject2 searchResult = getSpectatioUiUtil().findUiObject(searchResultSelector);
        validateUiObject(searchResult, AutomotiveConfigConstants.SEARCH_RESULT);
        String result = searchResult.getText();
        exitSearchResultPage();
        return result;
    }

    private void exitSearchResultPage() {
        BySelector searchBackButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.SEARCH_BACK_BUTTON);
        UiObject2 searchBackButton = getSpectatioUiUtil().findUiObject(searchBackButtonSelector);
        validateUiObject(searchBackButton, AutomotiveConfigConstants.SEARCH_BACK_BUTTON);
        getSpectatioUiUtil().clickAndWait(searchBackButton);
    }

    private void openContactOrder() {
        try {
            ScrollActions scrollAction =
                    ScrollActions.valueOf(
                            getActionFromConfig(
                                    AutomotiveConfigConstants.CONTACT_SETTING_SCROLL_ACTION));
            BySelector contactOrderSelector =
                    getUiElementFromConfig(AutomotiveConfigConstants.CONTACT_ORDER);
            UiObject2 contactOrder = null;
            switch (scrollAction) {
                case USE_BUTTON:
                    BySelector forwardButtonSelector =
                            getUiElementFromConfig(
                                    AutomotiveConfigConstants.CONTACT_SETTING_SCROLL_FORWARD);
                    BySelector backwardButtonSelector =
                            getUiElementFromConfig(
                                    AutomotiveConfigConstants.CONTACT_SETTING_SCROLL_BACKWARD);
                    contactOrder =
                            getSpectatioUiUtil()
                                    .scrollAndFindUiObject(
                                            forwardButtonSelector,
                                            backwardButtonSelector,
                                            contactOrderSelector);
                    break;
                case USE_GESTURE:
                    BySelector scrollElementSelector =
                            getUiElementFromConfig(
                                    AutomotiveConfigConstants.CONTACT_SETTING_SCROLL_ELEMENT);
                    ScrollDirection scrollDirection =
                            ScrollDirection.valueOf(
                                    getActionFromConfig(
                                            AutomotiveConfigConstants
                                                    .CONTACT_SETTING_SCROLL_DIRECTION));
                    contactOrder =
                            getSpectatioUiUtil()
                                    .scrollAndFindUiObject(
                                            scrollElementSelector,
                                            contactOrderSelector,
                                            (scrollDirection == ScrollDirection.VERTICAL));
                    break;
                default:
                    throw new IllegalStateException(
                            String.format(
                                    "Cannot scroll through contact settings. Unknown Scroll Action"
                                            + " %s.",
                                    scrollAction));
            }
            validateUiObject(contactOrder, AutomotiveConfigConstants.CONTACT_ORDER);
            getSpectatioUiUtil().clickAndWait(contactOrder);
        } catch (MissingUiElementException ex) {
            throw new RuntimeException("Unable to open contact order menu.", ex);
        }
    }

    /** {@inheritDoc} */
    public void sortContactListBy(OrderType orderType) {
        openContacts();
        openSettings();
        openContactOrder();
        BySelector orderBySelector = null;
        if (orderType == OrderType.FIRST_NAME) {
            orderBySelector = getUiElementFromConfig(AutomotiveConfigConstants.SORT_BY_FIRST_NAME);
        } else {
            orderBySelector = getUiElementFromConfig(AutomotiveConfigConstants.SORT_BY_LAST_NAME);
        }
        UiObject2 orderButton = getSpectatioUiUtil().findUiObject(orderBySelector);
        validateUiObject(orderButton, String.format("sorting by %s", orderType));
        getSpectatioUiUtil().clickAndWait(orderButton);
        BySelector contactsMenuSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CONTACTS_MENU);
        UiObject2 contactsMenu = getSpectatioUiUtil().findUiObject(contactsMenuSelector);
        while (contactsMenu == null) {
            getSpectatioUiUtil().pressBack();
            contactsMenu = getSpectatioUiUtil().findUiObject(contactsMenuSelector);
        }
    }

    private void scrollToTopOfContactList() {
        mScrollUtility.scrollToBeginning(
                mScrollAction,
                mScrollDirection,
                mBackwardButtonSelector,
                mScrollableElementSelector,
                "Scroll to top of Contact list");
    }

    /** {@inheritDoc} */
    public String getFirstContactFromContactList() {
        openContacts();
        scrollToTopOfContactList();
        BySelector contactNameSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CONTACT_NAME);
        UiObject2 firstContact = getSpectatioUiUtil().findUiObject(contactNameSelector);
        validateUiObject(
                firstContact,
                String.format("%s to get first contact", AutomotiveConfigConstants.CONTACT_NAME));
        return firstContact.getText();
    }

    /** {@inheritDoc} */
    public boolean isContactInFavorites(String contact) {
        openFavorites();
        UiObject2 uiObject = getSpectatioUiUtil().findUiObject(contact);
        return uiObject != null;
    }

    /** {@inheritDoc} */
    public void openDetailsPage(String contactName) {
        openContacts();
        UiObject2 contact = getContactFromContactList(contactName);
        UiObject2 contactDetailButton;
        BySelector showContactDetailsSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CONTACT_DETAIL);
        UiObject2 showDetailsButton = getSpectatioUiUtil().findUiObject(showContactDetailsSelector);
        if (showDetailsButton == null) {
            contactDetailButton = contact;
        } else {
            int lastIndex = contact.getParent().getChildren().size() - 1;
            contactDetailButton = contact.getParent().getChildren().get(lastIndex);
        }
        getSpectatioUiUtil().clickAndWait(contactDetailButton);
    }

    /** This method is used to get the first history in the Recents tab. */
    private UiObject2 getCallHistory() {
        BySelector callHistorySelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CALL_HISTORY_INFO);
        UiObject2 callHistory = getSpectatioUiUtil().findUiObject(callHistorySelector);
        validateUiObject(callHistory, AutomotiveConfigConstants.CALL_HISTORY_INFO);
        UiObject2 recentCallHistory = callHistory.getParent().getChildren().get(2);
        return recentCallHistory;
    }

    /** This method is used to open the contacts menu */
    public void openContacts() {
        BySelector contactMenuSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CONTACTS_MENU);
        UiObject2 contactMenuButton = getSpectatioUiUtil().findUiObject(contactMenuSelector);
        validateUiObject(contactMenuButton, AutomotiveConfigConstants.CONTACTS_MENU);
        getSpectatioUiUtil().clickAndWait(contactMenuButton);
    }

    /** This method opens the contact search window. */
    private void openSearchContact() {
        BySelector searchContactSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.SEARCH_CONTACT);
        UiObject2 searchContact = getSpectatioUiUtil().findUiObject(searchContactSelector);
        validateUiObject(searchContact, AutomotiveConfigConstants.SEARCH_CONTACT);
        getSpectatioUiUtil().clickAndWait(searchContact);
    }

    /** This method opens the settings for contact. */
    private void openSettings() {
        BySelector contactSettingSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.CONTACT_SETTINGS);
        UiObject2 settingButton = getSpectatioUiUtil().findUiObject(contactSettingSelector);
        validateUiObject(settingButton, AutomotiveConfigConstants.CONTACT_SETTINGS);
        getSpectatioUiUtil().clickAndWait(settingButton);
    }

    /** This method opens the Favorites tab. */
    private void openFavorites() {
        BySelector favoritesMenuSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.FAVORITES_MENU);
        UiObject2 favoritesMenuButton = getSpectatioUiUtil().findUiObject(favoritesMenuSelector);
        validateUiObject(favoritesMenuButton, AutomotiveConfigConstants.FAVORITES_MENU);
        getSpectatioUiUtil().clickAndWait(favoritesMenuButton);
    }

    public boolean isPhonePaired() {
        return mBluetoothAdapter.getBondedDevices().size() != 0;
    }

    /**
     * This method is used to enter phonenumber from the on-screen numberpad
     *
     * @param phoneNumber number to be dialed
     */
    private void enterNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("No phone number provided");
        }
        getSpectatioUiUtil().pressHome();
        getSpectatioUiUtil().wait1Second();
        getSpectatioUiUtil()
                .executeShellCommand(
                        getCommandFromConfig(AutomotiveConfigConstants.OPEN_DIAL_PAD_COMMAND));
        BySelector dialPadSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.DIAL_PAD_FRAGMENT);
        UiObject2 dialPad = getSpectatioUiUtil().findUiObject(dialPadSelector);
        validateUiObject(dialPad, AutomotiveConfigConstants.DIAL_PAD_FRAGMENT);
        char[] array = phoneNumber.toCharArray();
        for (char ch : array) {
            UiObject2 numberButton =
                    getSpectatioUiUtil()
                            .findUiObject(getUiElementFromConfig(Character.toString(ch)));
            if (numberButton == null) {
                numberButton = getSpectatioUiUtil().findUiObject(Character.toString(ch));
            }
            validateUiObject(numberButton, String.format("Number %s", Character.toString(ch)));
            getSpectatioUiUtil().clickAndWait(numberButton);
        }
    }

    private void validateUiObject(UiObject2 uiObject, String action) {
        if (uiObject == null) {
            throw new UnknownUiException(
                    String.format("Unable to find UI Element for %s.", action));
        }
    }
}
