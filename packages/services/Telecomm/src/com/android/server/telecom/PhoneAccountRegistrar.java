/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.telecom;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Environment;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Xml;

// TODO: Needed for move to system service: import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Integer;
import java.lang.SecurityException;
import java.lang.String;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Handles writing and reading PhoneAccountHandle registration entries. This is a simple verbatim
 * delegate for all the account handling methods on {@link android.telecom.TelecomManager} as implemented in
 * {@link TelecomServiceImpl}, with the notable exception that {@link TelecomServiceImpl} is
 * responsible for security checking to make sure that the caller has proper authority over
 * the {@code ComponentName}s they are declaring in their {@code PhoneAccountHandle}s.
 */
public final class PhoneAccountRegistrar {

    public static final PhoneAccountHandle NO_ACCOUNT_SELECTED =
            new PhoneAccountHandle(new ComponentName("null", "null"), "NO_ACCOUNT_SELECTED");

    public abstract static class Listener {
        public void onAccountsChanged(PhoneAccountRegistrar registrar) {}
        public void onDefaultOutgoingChanged(PhoneAccountRegistrar registrar) {}
        public void onSimCallManagerChanged(PhoneAccountRegistrar registrar) {}
    }

    private static final String FILE_NAME = "phone-account-registrar-state.xml";
    @VisibleForTesting
    public static final int EXPECTED_STATE_VERSION = 3;

    /** Keep in sync with the same in SipSettings.java */
    private static final String SIP_SHARED_PREFERENCES = "SIP_PREFERENCES";

    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final AtomicFile mAtomicFile;
    private final Context mContext;
    private State mState;

    public PhoneAccountRegistrar(Context context) {
        this(context, FILE_NAME);
    }

    @VisibleForTesting
    public PhoneAccountRegistrar(Context context, String fileName) {
        // TODO: This file path is subject to change -- it is storing the phone account registry
        // state file in the path /data/system/users/0/, which is likely not correct in a
        // multi-user setting.
        /** UNCOMMENT_FOR_MOVE_TO_SYSTEM_SERVICE
        String filePath = Environment.getUserSystemDirectory(UserHandle.myUserId()).
                getAbsolutePath();
        mAtomicFile = new AtomicFile(new File(filePath, fileName));
         UNCOMMENT_FOR_MOVE_TO_SYSTEM_SERVICE */
        mAtomicFile = new AtomicFile(new File(context.getFilesDir(), fileName));

        mState = new State();
        mContext = context;
        read();
    }

    /**
     * Retrieves the default outgoing phone account supporting the specified uriScheme.
     * @param uriScheme The URI scheme for the outgoing call.
     * @return The {@link PhoneAccountHandle} to use.
     */
    public PhoneAccountHandle getDefaultOutgoingPhoneAccount(String uriScheme) {
        final PhoneAccountHandle userSelected = getUserSelectedOutgoingPhoneAccount();

        if (userSelected != null) {
            // If there is a default PhoneAccount, ensure it supports calls to handles with the
            // specified uriScheme.
            final PhoneAccount userSelectedAccount = getPhoneAccount(userSelected);
            if (userSelectedAccount.supportsUriScheme(uriScheme)) {
                return userSelected;
            }
        }

        List<PhoneAccountHandle> outgoing = getCallCapablePhoneAccounts(uriScheme);
        switch (outgoing.size()) {
            case 0:
                // There are no accounts, so there can be no default
                return null;
            case 1:
                // There is only one account, which is by definition the default
                return outgoing.get(0);
            default:
                // There are multiple accounts with no selected default
                return null;
        }
    }

    PhoneAccountHandle getUserSelectedOutgoingPhoneAccount() {
        if (mState.defaultOutgoing != null) {
            // Return the registered outgoing default iff it still exists (we keep a sticky
            // default to survive account deletion and re-addition)
            for (int i = 0; i < mState.accounts.size(); i++) {
                if (mState.accounts.get(i).getAccountHandle().equals(mState.defaultOutgoing)) {
                    return mState.defaultOutgoing;
                }
            }
            // At this point, there was a registered default but it has been deleted; proceed
            // as though there were no default
        }
        return null;
    }

    public void setUserSelectedOutgoingPhoneAccount(PhoneAccountHandle accountHandle) {
        if (accountHandle == null) {
            // Asking to clear the default outgoing is a valid request
            mState.defaultOutgoing = null;
        } else {
            boolean found = false;
            for (PhoneAccount m : mState.accounts) {
                if (Objects.equals(accountHandle, m.getAccountHandle())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                Log.w(this, "Trying to set nonexistent default outgoing %s",
                        accountHandle);
                return;
            }

            if (!getPhoneAccount(accountHandle).hasCapabilities(
                    PhoneAccount.CAPABILITY_CALL_PROVIDER)) {
                Log.w(this, "Trying to set non-call-provider default outgoing %s",
                        accountHandle);
                return;
            }

            mState.defaultOutgoing = accountHandle;
        }

        write();
        fireDefaultOutgoingChanged();
    }

    public void setSimCallManager(PhoneAccountHandle callManager) {
        if (callManager != null) {
            PhoneAccount callManagerAccount = getPhoneAccount(callManager);
            if (callManagerAccount == null) {
                Log.d(this, "setSimCallManager: Nonexistent call manager: %s", callManager);
                return;
            } else if (!callManagerAccount.hasCapabilities(
                    PhoneAccount.CAPABILITY_CONNECTION_MANAGER)) {
                Log.d(this, "setSimCallManager: Not a call manager: %s", callManagerAccount);
                return;
            }
        } else {
            callManager = NO_ACCOUNT_SELECTED;
        }
        mState.simCallManager = callManager;

        write();
        fireSimCallManagerChanged();
    }

    public PhoneAccountHandle getSimCallManager() {
        if (mState.simCallManager != null) {
            if (NO_ACCOUNT_SELECTED.equals(mState.simCallManager)) {
                return null;
            }
            // Return the registered sim call manager iff it still exists (we keep a sticky
            // setting to survive account deletion and re-addition)
            for (int i = 0; i < mState.accounts.size(); i++) {
                if (mState.accounts.get(i).getAccountHandle().equals(mState.simCallManager)) {
                    return mState.simCallManager;
                }
            }
        }

        // See if the OEM has specified a default one.
        String defaultConnectionMgr =
                mContext.getResources().getString(R.string.default_connection_manager_component);
        if (!TextUtils.isEmpty(defaultConnectionMgr)) {
            PackageManager pm = mContext.getPackageManager();

            ComponentName componentName = ComponentName.unflattenFromString(defaultConnectionMgr);
            Intent intent = new Intent(ConnectionService.SERVICE_INTERFACE);
            intent.setComponent(componentName);

            // Make sure that the component can be resolved.
            List<ResolveInfo> resolveInfos = pm.queryIntentServices(intent, 0);
            if (!resolveInfos.isEmpty()) {
                // See if there is registered PhoneAccount by this component.
                List<PhoneAccountHandle> handles = getAllPhoneAccountHandles();
                for (PhoneAccountHandle handle : handles) {
                    if (componentName.equals(handle.getComponentName())) {
                        return handle;
                    }
                }
                Log.d(this, "%s does not have a PhoneAccount; not using as default", componentName);
            } else {
                Log.d(this, "%s could not be resolved; not using as default", componentName);
            }
        } else {
            Log.v(this, "No default connection manager specified");
        }

        return null;
    }

    /**
     * Retrieves a list of all {@link PhoneAccountHandle}s registered.
     *
     * @return The list of {@link PhoneAccountHandle}s.
     */
    public List<PhoneAccountHandle> getAllPhoneAccountHandles() {
        List<PhoneAccountHandle> accountHandles = new ArrayList<>();
        for (PhoneAccount m : mState.accounts) {
            accountHandles.add(m.getAccountHandle());
        }
        return accountHandles;
    }

    public List<PhoneAccount> getAllPhoneAccounts() {
        return new ArrayList<>(mState.accounts);
    }

    /**
     * Determines the number of all {@link PhoneAccount}s.
     *
     * @return The total number {@link PhoneAccount}s.
     */
    public int getAllPhoneAccountsCount() {
        return mState.accounts.size();
    }

    /**
     * Retrieves a list of all call provider phone accounts.
     *
     * @return The phone account handles.
     */
    public List<PhoneAccountHandle> getCallCapablePhoneAccounts() {
        return getPhoneAccountHandles(PhoneAccount.CAPABILITY_CALL_PROVIDER);
    }

    /**
     * Retrieves a list of all phone account call provider phone accounts supporting the
     * specified URI scheme.
     *
     * @param uriScheme The URI scheme.
     * @return The phone account handles.
     */
    public List<PhoneAccountHandle> getCallCapablePhoneAccounts(String uriScheme) {
        return getPhoneAccountHandles(PhoneAccount.CAPABILITY_CALL_PROVIDER, uriScheme);
    }

    /**
     * Retrieves a list of all phone accounts registered by a specified package.
     *
     * @param packageName The name of the package that registered the phone accounts.
     * @return The phone account handles.
     */
    public List<PhoneAccountHandle> getPhoneAccountsForPackage(String packageName) {
        List<PhoneAccountHandle> accountHandles = new ArrayList<>();
        for (PhoneAccount m : mState.accounts) {
            if (Objects.equals(
                    packageName,
                    m.getAccountHandle().getComponentName().getPackageName())) {
                accountHandles.add(m.getAccountHandle());
            }
        }
        return accountHandles;
    }

    /**
     * Retrieves a list of all phone account handles with the connection manager capability.
     *
     * @return The phone account handles.
     */
    public List<PhoneAccountHandle> getConnectionManagerPhoneAccounts() {
        return getPhoneAccountHandles(PhoneAccount.CAPABILITY_CONNECTION_MANAGER,
                null /* supportedUriScheme */);
    }

    public PhoneAccount getPhoneAccount(PhoneAccountHandle handle) {
        for (PhoneAccount m : mState.accounts) {
            if (Objects.equals(handle, m.getAccountHandle())) {
                return m;
            }
        }
        return null;
    }

    // TODO: Should we implement an artificial limit for # of accounts associated with a single
    // ComponentName?
    public void registerPhoneAccount(PhoneAccount account) {
        // Enforce the requirement that a connection service for a phone account has the correct
        // permission.
        if (!phoneAccountHasPermission(account.getAccountHandle())) {
            Log.w(this, "Phone account %s does not have BIND_CONNECTION_SERVICE permission.",
                    account.getAccountHandle());
            throw new SecurityException(
                    "PhoneAccount connection service requires BIND_CONNECTION_SERVICE permission.");
        }

        addOrReplacePhoneAccount(account);
    }

    /**
     * Adds a {@code PhoneAccount}, replacing an existing one if found.
     *
     * @param account The {@code PhoneAccount} to add or replace.
     */
    private void addOrReplacePhoneAccount(PhoneAccount account) {
        mState.accounts.add(account);
        // Search for duplicates and remove any that are found.
        for (int i = 0; i < mState.accounts.size() - 1; i++) {
            if (Objects.equals(
                    account.getAccountHandle(), mState.accounts.get(i).getAccountHandle())) {
                // replace existing entry.
                mState.accounts.remove(i);
                break;
            }
        }

        write();
        fireAccountsChanged();
    }

    public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
        for (int i = 0; i < mState.accounts.size(); i++) {
            if (Objects.equals(accountHandle, mState.accounts.get(i).getAccountHandle())) {
                mState.accounts.remove(i);
                break;
            }
        }

        write();
        fireAccountsChanged();
    }

    /**
     * Un-registers all phone accounts associated with a specified package.
     *
     * @param packageName The package for which phone accounts will be removed.
     */
    public void clearAccounts(String packageName) {
        boolean accountsRemoved = false;
        Iterator<PhoneAccount> it = mState.accounts.iterator();
        while (it.hasNext()) {
            PhoneAccount phoneAccount = it.next();
            if (Objects.equals(
                    packageName,
                    phoneAccount.getAccountHandle().getComponentName().getPackageName())) {
                Log.i(this, "Removing phone account " + phoneAccount.getLabel());
                it.remove();
                accountsRemoved = true;
            }
        }

        if (accountsRemoved) {
            write();
            fireAccountsChanged();
        }
    }

    public void addListener(Listener l) {
        mListeners.add(l);
    }

    public void removeListener(Listener l) {
        if (l != null) {
            mListeners.remove(l);
        }
    }

    private void fireAccountsChanged() {
        for (Listener l : mListeners) {
            l.onAccountsChanged(this);
        }
    }

    private void fireDefaultOutgoingChanged() {
        for (Listener l : mListeners) {
            l.onDefaultOutgoingChanged(this);
        }
    }

    private void fireSimCallManagerChanged() {
        for (Listener l : mListeners) {
            l.onSimCallManagerChanged(this);
        }
    }

    /**
     * Determines if the connection service specified by a {@link PhoneAccountHandle} has the
     * {@link Manifest.permission#BIND_CONNECTION_SERVICE} permission.
     *
     * @param phoneAccountHandle The phone account to check.
     * @return {@code True} if the phone account has permission.
     */
    public boolean phoneAccountHasPermission(PhoneAccountHandle phoneAccountHandle) {
        PackageManager packageManager = mContext.getPackageManager();
        try {
            ServiceInfo serviceInfo = packageManager.getServiceInfo(
                    phoneAccountHandle.getComponentName(), 0);

            return serviceInfo.permission != null &&
                    serviceInfo.permission.equals(Manifest.permission.BIND_CONNECTION_SERVICE);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(this, "Name not found %s", e);
            return false;
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns a list of phone account handles with the specified flag.
     *
     * @param flags Flags which the {@code PhoneAccount} must have.
     */
    private List<PhoneAccountHandle> getPhoneAccountHandles(int flags) {
        return getPhoneAccountHandles(flags, null);
    }

    /**
     * Returns a list of phone account handles with the specified flag, supporting the specified
     * URI scheme.
     *
     * @param flags Flags which the {@code PhoneAccount} must have.
     * @param uriScheme URI schemes the PhoneAccount must handle.  {@code Null} bypasses the
     *                  URI scheme check.
     */
    private List<PhoneAccountHandle> getPhoneAccountHandles(int flags, String uriScheme) {
        List<PhoneAccountHandle> accountHandles = new ArrayList<>();
        for (PhoneAccount m : mState.accounts) {
            if (m.hasCapabilities(flags) && (uriScheme == null || m.supportsUriScheme(uriScheme))) {
                accountHandles.add(m.getAccountHandle());
            }
        }
        return accountHandles;
    }

    /**
     * The state of this {@code PhoneAccountRegistrar}.
     */
    @VisibleForTesting
    public static class State {
        /**
         * The account selected by the user to be employed by default for making outgoing calls.
         * If the user has not made such a selection, then this is null.
         */
        public PhoneAccountHandle defaultOutgoing = null;

        /**
         * A {@code PhoneAccount} having {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER} which
         * manages and optimizes a user's PSTN SIM connections.
         */
        public PhoneAccountHandle simCallManager;

        /**
         * The complete list of {@code PhoneAccount}s known to the Telecom subsystem.
         */
        public final List<PhoneAccount> accounts = new ArrayList<>();

        /**
         * The version number of the State data.
         */
        public int versionNumber;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // State management
    //

    private void write() {
        final FileOutputStream os;
        try {
            os = mAtomicFile.startWrite();
            boolean success = false;
            try {
                XmlSerializer serializer = new FastXmlSerializer();
                serializer.setOutput(new BufferedOutputStream(os), "utf-8");
                writeToXml(mState, serializer);
                serializer.flush();
                success = true;
            } finally {
                if (success) {
                    mAtomicFile.finishWrite(os);
                } else {
                    mAtomicFile.failWrite(os);
                }
            }
        } catch (IOException e) {
            Log.e(this, e, "Writing state to XML file");
        }
    }

    private void read() {
        final InputStream is;
        try {
            is = mAtomicFile.openRead();
        } catch (FileNotFoundException ex) {
            return;
        }

        boolean versionChanged = false;

        XmlPullParser parser;
        try {
            parser = Xml.newPullParser();
            parser.setInput(new BufferedInputStream(is), null);
            parser.nextTag();
            mState = readFromXml(parser, mContext);
            versionChanged = mState.versionNumber < EXPECTED_STATE_VERSION;

        } catch (IOException | XmlPullParserException e) {
            Log.e(this, e, "Reading state from XML file");
            mState = new State();
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                Log.e(this, e, "Closing InputStream");
            }
        }

        // If an upgrade occurred, write out the changed data.
        if (versionChanged) {
            write();
        }
    }

    private static void writeToXml(State state, XmlSerializer serializer)
            throws IOException {
        sStateXml.writeToXml(state, serializer);
    }

    private static State readFromXml(XmlPullParser parser, Context context)
            throws IOException, XmlPullParserException {
        State s = sStateXml.readFromXml(parser, 0, context);
        return s != null ? s : new State();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // XML serialization
    //

    @VisibleForTesting
    public abstract static class XmlSerialization<T> {
        private static final String LENGTH_ATTRIBUTE = "length";
        private static final String VALUE_TAG = "value";

        /**
         * Write the supplied object to XML
         */
        public abstract void writeToXml(T o, XmlSerializer serializer)
                throws IOException;

        /**
         * Read from the supplied XML into a new object, returning null in case of an
         * unrecoverable schema mismatch or other data error. 'parser' must be already
         * positioned at the first tag that is expected to have been emitted by this
         * object's writeToXml(). This object tries to fail early without modifying
         * 'parser' if it does not recognize the data it sees.
         */
        public abstract T readFromXml(XmlPullParser parser, int version, Context context)
                throws IOException, XmlPullParserException;

        protected void writeTextSafely(String tagName, Object value, XmlSerializer serializer)
                throws IOException {
            if (value != null) {
                serializer.startTag(null, tagName);
                serializer.text(Objects.toString(value));
                serializer.endTag(null, tagName);
            }
        }

        /**
         * Serializes a string array.
         *
         * @param tagName The tag name for the string array.
         * @param values The string values to serialize.
         * @param serializer The serializer.
         * @throws IOException
         */
        protected void writeStringList(String tagName, List<String> values,
                XmlSerializer serializer)
                throws IOException {

            serializer.startTag(null, tagName);
            if (values != null) {
                serializer.attribute(null, LENGTH_ATTRIBUTE, Objects.toString(values.size()));
                for (String toSerialize : values) {
                    serializer.startTag(null, VALUE_TAG);
                    if (toSerialize != null ){
                        serializer.text(toSerialize);
                    }
                    serializer.endTag(null, VALUE_TAG);
                }
            } else {
                serializer.attribute(null, LENGTH_ATTRIBUTE, "0");
            }
            serializer.endTag(null, tagName);

        }

        /**
         * Reads a string array from the XML parser.
         *
         * @param parser The XML parser.
         * @return String array containing the parsed values.
         * @throws IOException Exception related to IO.
         * @throws XmlPullParserException Exception related to parsing.
         */
        protected List<String> readStringList(XmlPullParser parser)
                throws IOException, XmlPullParserException {

            int length = Integer.parseInt(parser.getAttributeValue(null, LENGTH_ATTRIBUTE));
            List<String> arrayEntries = new ArrayList<String>(length);
            String value = null;

            if (length == 0) {
                return arrayEntries;
            }

            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (parser.getName().equals(VALUE_TAG)) {
                    parser.next();
                    value = parser.getText();
                    arrayEntries.add(value);
                }
            }

            return arrayEntries;
        }
    }

    @VisibleForTesting
    public static final XmlSerialization<State> sStateXml =
            new XmlSerialization<State>() {
        private static final String CLASS_STATE = "phone_account_registrar_state";
        private static final String DEFAULT_OUTGOING = "default_outgoing";
        private static final String SIM_CALL_MANAGER = "sim_call_manager";
        private static final String ACCOUNTS = "accounts";
        private static final String VERSION = "version";

        @Override
        public void writeToXml(State o, XmlSerializer serializer)
                throws IOException {
            if (o != null) {
                serializer.startTag(null, CLASS_STATE);
                serializer.attribute(null, VERSION, Objects.toString(EXPECTED_STATE_VERSION));

                if (o.defaultOutgoing != null) {
                    serializer.startTag(null, DEFAULT_OUTGOING);
                    sPhoneAccountHandleXml.writeToXml(o.defaultOutgoing, serializer);
                    serializer.endTag(null, DEFAULT_OUTGOING);
                }

                if (o.simCallManager != null) {
                    serializer.startTag(null, SIM_CALL_MANAGER);
                    sPhoneAccountHandleXml.writeToXml(o.simCallManager, serializer);
                    serializer.endTag(null, SIM_CALL_MANAGER);
                }

                serializer.startTag(null, ACCOUNTS);
                for (PhoneAccount m : o.accounts) {
                    sPhoneAccountXml.writeToXml(m, serializer);
                }
                serializer.endTag(null, ACCOUNTS);

                serializer.endTag(null, CLASS_STATE);
            }
        }

        @Override
        public State readFromXml(XmlPullParser parser, int version, Context context)
                throws IOException, XmlPullParserException {
            if (parser.getName().equals(CLASS_STATE)) {
                State s = new State();

                String rawVersion = parser.getAttributeValue(null, VERSION);
                s.versionNumber = TextUtils.isEmpty(rawVersion) ? 1 :
                        Integer.parseInt(rawVersion);

                int outerDepth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                    if (parser.getName().equals(DEFAULT_OUTGOING)) {
                        parser.nextTag();
                        s.defaultOutgoing = sPhoneAccountHandleXml.readFromXml(parser,
                                s.versionNumber, context);
                    } else if (parser.getName().equals(SIM_CALL_MANAGER)) {
                        parser.nextTag();
                        s.simCallManager = sPhoneAccountHandleXml.readFromXml(parser,
                                s.versionNumber, context);
                    } else if (parser.getName().equals(ACCOUNTS)) {
                        int accountsDepth = parser.getDepth();
                        while (XmlUtils.nextElementWithin(parser, accountsDepth)) {
                            PhoneAccount account = sPhoneAccountXml.readFromXml(parser,
                                    s.versionNumber, context);

                            if (account != null && s.accounts != null) {
                                s.accounts.add(account);
                            }
                        }
                    }
                }
                return s;
            }
            return null;
        }
    };

    @VisibleForTesting
    public static final XmlSerialization<PhoneAccount> sPhoneAccountXml =
            new XmlSerialization<PhoneAccount>() {
        private static final String CLASS_PHONE_ACCOUNT = "phone_account";
        private static final String ACCOUNT_HANDLE = "account_handle";
        private static final String ADDRESS = "handle";
        private static final String SUBSCRIPTION_ADDRESS = "subscription_number";
        private static final String CAPABILITIES = "capabilities";
        private static final String ICON_RES_ID = "icon_res_id";
        private static final String LABEL = "label";
        private static final String SHORT_DESCRIPTION = "short_description";
        private static final String SUPPORTED_URI_SCHEMES = "supported_uri_schemes";
        private static final String TRUE = "true";
        private static final String FALSE = "false";

        @Override
        public void writeToXml(PhoneAccount o, XmlSerializer serializer)
                throws IOException {
            if (o != null) {
                serializer.startTag(null, CLASS_PHONE_ACCOUNT);

                if (o.getAccountHandle() != null) {
                    serializer.startTag(null, ACCOUNT_HANDLE);
                    sPhoneAccountHandleXml.writeToXml(o.getAccountHandle(), serializer);
                    serializer.endTag(null, ACCOUNT_HANDLE);
                }

                writeTextSafely(ADDRESS, o.getAddress(), serializer);
                writeTextSafely(SUBSCRIPTION_ADDRESS, o.getSubscriptionAddress(), serializer);
                writeTextSafely(CAPABILITIES, Integer.toString(o.getCapabilities()), serializer);
                writeTextSafely(ICON_RES_ID, Integer.toString(o.getIconResId()), serializer);
                writeTextSafely(LABEL, o.getLabel(), serializer);
                writeTextSafely(SHORT_DESCRIPTION, o.getShortDescription(), serializer);
                writeStringList(SUPPORTED_URI_SCHEMES, o.getSupportedUriSchemes(), serializer);

                serializer.endTag(null, CLASS_PHONE_ACCOUNT);
            }
        }

        public PhoneAccount readFromXml(XmlPullParser parser, int version, Context context)
                throws IOException, XmlPullParserException {
            if (parser.getName().equals(CLASS_PHONE_ACCOUNT)) {
                int outerDepth = parser.getDepth();
                PhoneAccountHandle accountHandle = null;
                Uri address = null;
                Uri subscriptionAddress = null;
                int capabilities = 0;
                int iconResId = 0;
                String label = null;
                String shortDescription = null;
                List<String> supportedUriSchemes = null;

                while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                    if (parser.getName().equals(ACCOUNT_HANDLE)) {
                        parser.nextTag();
                        accountHandle = sPhoneAccountHandleXml.readFromXml(parser, version,
                                context);
                    } else if (parser.getName().equals(ADDRESS)) {
                        parser.next();
                        address = Uri.parse(parser.getText());
                    } else if (parser.getName().equals(SUBSCRIPTION_ADDRESS)) {
                        parser.next();
                        String nextText = parser.getText();
                        subscriptionAddress = nextText == null ? null : Uri.parse(nextText);
                    } else if (parser.getName().equals(CAPABILITIES)) {
                        parser.next();
                        capabilities = Integer.parseInt(parser.getText());
                    } else if (parser.getName().equals(ICON_RES_ID)) {
                        parser.next();
                        iconResId = Integer.parseInt(parser.getText());
                    } else if (parser.getName().equals(LABEL)) {
                        parser.next();
                        label = parser.getText();
                    } else if (parser.getName().equals(SHORT_DESCRIPTION)) {
                        parser.next();
                        shortDescription = parser.getText();
                    } else if (parser.getName().equals(SUPPORTED_URI_SCHEMES)) {
                        supportedUriSchemes = readStringList(parser);
                    }
                }

                // Upgrade older phone accounts to specify the supported URI schemes.
                if (version < 2) {
                    ComponentName sipComponentName = new ComponentName("com.android.phone",
                            "com.android.services.telephony.sip.SipConnectionService");

                    supportedUriSchemes = new ArrayList<>();

                    // Handle the SIP connection service.
                    // Check the system settings to see if it also should handle "tel" calls.
                    if (accountHandle.getComponentName().equals(sipComponentName)) {
                        boolean useSipForPstn = useSipForPstnCalls(context);
                        supportedUriSchemes.add(PhoneAccount.SCHEME_SIP);
                        if (useSipForPstn) {
                            supportedUriSchemes.add(PhoneAccount.SCHEME_TEL);
                        }
                    } else {
                        supportedUriSchemes.add(PhoneAccount.SCHEME_TEL);
                        supportedUriSchemes.add(PhoneAccount.SCHEME_VOICEMAIL);
                    }
                }

                return PhoneAccount.builder(accountHandle, label)
                        .setAddress(address)
                        .setSubscriptionAddress(subscriptionAddress)
                        .setCapabilities(capabilities)
                        .setIconResId(iconResId)
                        .setShortDescription(shortDescription)
                        .setSupportedUriSchemes(supportedUriSchemes)
                        .build();
            }
            return null;
        }

        /**
         * Determines if the SIP call settings specify to use SIP for all calls, including PSTN calls.
         *
         * @param context The context.
         * @return {@code True} if SIP should be used for all calls.
         */
        private boolean useSipForPstnCalls(Context context) {
            String option = Settings.System.getString(context.getContentResolver(),
                    Settings.System.SIP_CALL_OPTIONS);
            option = (option != null) ? option : Settings.System.SIP_ADDRESS_ONLY;
            return option.equals(Settings.System.SIP_ALWAYS);
        }
    };

    @VisibleForTesting
    public static final XmlSerialization<PhoneAccountHandle> sPhoneAccountHandleXml =
            new XmlSerialization<PhoneAccountHandle>() {
        private static final String CLASS_PHONE_ACCOUNT_HANDLE = "phone_account_handle";
        private static final String COMPONENT_NAME = "component_name";
        private static final String ID = "id";

        @Override
        public void writeToXml(PhoneAccountHandle o, XmlSerializer serializer)
                throws IOException {
            if (o != null) {
                serializer.startTag(null, CLASS_PHONE_ACCOUNT_HANDLE);

                if (o.getComponentName() != null) {
                    writeTextSafely(
                            COMPONENT_NAME, o.getComponentName().flattenToString(), serializer);
                }

                writeTextSafely(ID, o.getId(), serializer);

                serializer.endTag(null, CLASS_PHONE_ACCOUNT_HANDLE);
            }
        }

        @Override
        public PhoneAccountHandle readFromXml(XmlPullParser parser, int version, Context context)
                throws IOException, XmlPullParserException {
            if (parser.getName().equals(CLASS_PHONE_ACCOUNT_HANDLE)) {
                String componentNameString = null;
                String idString = null;
                int outerDepth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                    if (parser.getName().equals(COMPONENT_NAME)) {
                        parser.next();
                        componentNameString = parser.getText();
                    } else if (parser.getName().equals(ID)) {
                        parser.next();
                        idString = parser.getText();
                    }
                }
                if (componentNameString != null) {
                    return new PhoneAccountHandle(
                            ComponentName.unflattenFromString(componentNameString),
                            idString);
                }
            }
            return null;
        }
    };
}
