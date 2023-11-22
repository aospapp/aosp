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

package com.android.exchange.service;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.TextUtils;

import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.EmailServiceVersion;
import com.android.emailcommon.service.HostAuthCompat;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.service.ServiceProxy;
import com.android.exchange.Eas;
import com.android.exchange.eas.EasAutoDiscover;
import com.android.exchange.eas.EasFolderSync;
import com.android.exchange.eas.EasFullSyncOperation;
import com.android.exchange.eas.EasLoadAttachment;
import com.android.exchange.eas.EasOperation;
import com.android.exchange.eas.EasSearch;
import com.android.exchange.eas.EasSearchGal;
import com.android.exchange.eas.EasSendMeetingResponse;
import com.android.exchange.eas.EasSyncCalendar;
import com.android.exchange.eas.EasSyncContacts;
import com.android.exchange.provider.GalResult;
import com.android.mail.utils.LogUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * Service to handle all communication with the EAS server. Note that this is completely decoupled
 * from the sync adapters; sync adapters should make blocking calls on this service to actually
 * perform any operations.
 */
public class EasService extends Service {

    private static final String TAG = Eas.LOG_TAG;

    /**
     * The content authorities that can be synced for EAS accounts. Initialization must wait until
     * after we have a chance to call {@link EmailContent#init} (and, for future content types,
     * possibly other initializations) because that's how we can know what the email authority is.
     */
    private static String[] AUTHORITIES_TO_SYNC;

    /** Bookkeeping for ping tasks & sync threads management. */
    private final PingSyncSynchronizer mSynchronizer;

    /**
     * Implementation of the IEmailService interface.
     * For the most part these calls should consist of creating the correct {@link EasOperation}
     * class and calling {@link #doOperation} with it.
     */
    private final IEmailService.Stub mBinder = new IEmailService.Stub() {
        @Override
        public void loadAttachment(final IEmailServiceCallback callback, final long accountId,
                final long attachmentId, final boolean background) {
            LogUtils.d(TAG, "IEmailService.loadAttachment: %d", attachmentId);
            final EasLoadAttachment operation = new EasLoadAttachment(EasService.this, accountId,
                    attachmentId, callback);
            doOperation(operation, "IEmailService.loadAttachment");
        }

        @Override
        public void updateFolderList(final long accountId) {
            final EasFolderSync operation = new EasFolderSync(EasService.this, accountId);
            doOperation(operation, "IEmailService.updateFolderList");
        }

        public void sendMail(final long accountId) {
            // TODO: We should get rid of sendMail, and this is done in sync.
            LogUtils.wtf(TAG, "unexpected call to EasService.sendMail");
        }

        public int sync(final long accountId, Bundle syncExtras) {
            EasFullSyncOperation op = new EasFullSyncOperation(EasService.this, accountId, syncExtras);
            return convertToEmailServiceStatus(doOperation(op, "IEmailService.sync"));
        }

        @Override
        public void pushModify(final long accountId) {
            LogUtils.d(TAG, "IEmailService.pushModify: %d", accountId);
            final Account account = Account.restoreAccountWithId(EasService.this, accountId);
            if (pingNeededForAccount(account)) {
                mSynchronizer.pushModify(account);
            } else {
                mSynchronizer.pushStop(accountId);
            }
        }

        @Override
        public Bundle validate(final HostAuthCompat hostAuthCom) {
            final HostAuth hostAuth = hostAuthCom.toHostAuth();
            final EasFolderSync operation = new EasFolderSync(EasService.this, hostAuth);
            doOperation(operation, "IEmailService.validate");
            return operation.getValidationResult();
        }

        @Override
        public int searchMessages(final long accountId, final SearchParams searchParams,
                final long destMailboxId) {
            final EasSearch operation = new EasSearch(EasService.this, accountId, searchParams,
                    destMailboxId);
            doOperation(operation, "IEmailService.searchMessages");
            return operation.getTotalResults();
        }

        @Override
        public void sendMeetingResponse(final long messageId, final int response) {
            EmailContent.Message msg = EmailContent.Message.restoreMessageWithId(EasService.this,
                    messageId);
            if (msg == null) {
                LogUtils.e(TAG, "Could not load message %d in sendMeetingResponse", messageId);
                return;
            }

            final EasSendMeetingResponse operation = new EasSendMeetingResponse(EasService.this,
                    msg.mAccountKey, msg, response);
            doOperation(operation, "IEmailService.sendMeetingResponse");
        }

        @Override
        public Bundle autoDiscover(final String username, final String password) {
            final String domain = EasAutoDiscover.getDomain(username);
            for (int attempt = 0; attempt <= EasAutoDiscover.ATTEMPT_MAX; attempt++) {
                LogUtils.d(TAG, "autodiscover attempt %d", attempt);
                final String uri = EasAutoDiscover.genUri(domain, attempt);
                Bundle result = autoDiscoverInternal(uri, attempt, username, password, true);
                int resultCode = result.getInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE);
                if (resultCode != EasAutoDiscover.RESULT_BAD_RESPONSE) {
                    return result;
                } else {
                    LogUtils.d(TAG, "got BAD_RESPONSE");
                }
            }
            return null;
        }

        private Bundle autoDiscoverInternal(final String uri, final int attempt,
                                            final String username, final String password,
                                            final boolean canRetry) {
            final EasAutoDiscover op = new EasAutoDiscover(EasService.this, uri, attempt,
                    username, password);
            final int result = op.performOperation();
            if (result == EasAutoDiscover.RESULT_REDIRECT) {
                // Try again recursively with the new uri. TODO we should limit the number of redirects.
                final String redirectUri = op.getRedirectUri();
                return autoDiscoverInternal(redirectUri, attempt, username, password, canRetry);
            } else if (result == EasAutoDiscover.RESULT_SC_UNAUTHORIZED) {
                if (canRetry && username.contains("@")) {
                    // Try again using the bare user name
                    final int atSignIndex = username.indexOf('@');
                    final String bareUsername = username.substring(0, atSignIndex);
                    LogUtils.d(TAG, "%d received; trying username: %s", result, atSignIndex);
                    // Try again recursively, but this time don't allow retries for username.
                    return autoDiscoverInternal(uri, attempt, bareUsername, password, false);
                } else {
                    // Either we're already on our second try or the username didn't have an "@"
                    // to begin with. Either way, failure.
                    final Bundle bundle = new Bundle(1);
                    bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                            EasAutoDiscover.RESULT_OTHER_FAILURE);
                    return bundle;
                }
            } else if (result != EasAutoDiscover.RESULT_OK) {
                // Return failure, we'll try again with an alternate address
                final Bundle bundle = new Bundle(1);
                bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                        EasAutoDiscover.RESULT_BAD_RESPONSE);
                return bundle;
            }
            // Success.
            return op.getResultBundle();
        }

        @Override
        public void setLogging(final int flags) {
            LogUtils.d(TAG, "IEmailService.setLogging");
        }

        @Override
        public void deleteExternalAccountPIMData(final String emailAddress) {
            LogUtils.d(TAG, "IEmailService.deleteAccountPIMData");
            if (emailAddress != null) {
                // TODO: stop pings
                final Context context = EasService.this;
                EasSyncContacts.wipeAccountFromContentProvider(context, emailAddress);
                EasSyncCalendar.wipeAccountFromContentProvider(context, emailAddress);
            }
        }

        public int getApiVersion() {
            return EmailServiceVersion.CURRENT;
        }
    };

    /**
     * Content selection string for getting all accounts that are configured for push.
     * TODO: Add protocol check so that we don't get e.g. IMAP accounts here.
     * (Not currently necessary but eventually will be.)
     */
    private static final String PUSH_ACCOUNTS_SELECTION =
            EmailContent.AccountColumns.SYNC_INTERVAL +
                    "=" + Integer.toString(Account.CHECK_INTERVAL_PUSH);

    /** {@link AsyncTask} to restart pings for all accounts that need it. */
    private class RestartPingsTask extends AsyncTask<Void, Void, Void> {
        private boolean mHasRestartedPing = false;

        @Override
        protected Void doInBackground(Void... params) {
            final Cursor c = EasService.this.getContentResolver().query(Account.CONTENT_URI,
                    Account.CONTENT_PROJECTION, PUSH_ACCOUNTS_SELECTION, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        final Account account = new Account();
                        LogUtils.d(TAG, "RestartPingsTask starting ping for %s", account);
                        account.restore(c);
                        if (EasService.this.pingNeededForAccount(account)) {
                            mHasRestartedPing = true;
                            EasService.this.mSynchronizer.pushModify(account);
                        }
                    }
                } finally {
                    c.close();
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (!mHasRestartedPing) {
                LogUtils.d(TAG, "RestartPingsTask did not start any pings.");
                EasService.this.mSynchronizer.stopServiceIfIdle();
            }
        }
    }

    public EasService() {
        super();
        mSynchronizer = new PingSyncSynchronizer(this);
    }

    @Override
    public void onCreate() {
        LogUtils.d(TAG, "EasService.onCreate");
        super.onCreate();
        TempDirectory.setTempDirectory(this);
        EmailContent.init(this);
        AUTHORITIES_TO_SYNC = new String[] {
                EmailContent.AUTHORITY,
                CalendarContract.AUTHORITY,
                ContactsContract.AUTHORITY
        };

        // Restart push for all accounts that need it. Because this requires DB loads, we do it in
        // an AsyncTask, and we startService to ensure that we stick around long enough for the
        // task to complete. The task will stop the service if necessary after it's done.
        startService(new Intent(this, EasService.class));
        new RestartPingsTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void onDestroy() {
        mSynchronizer.stopAllPings();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null &&
                TextUtils.equals(Eas.EXCHANGE_SERVICE_INTENT_ACTION, intent.getAction())) {
            if (intent.getBooleanExtra(ServiceProxy.EXTRA_FORCE_SHUTDOWN, false)) {
                // We've been asked to forcibly shutdown. This happens if email accounts are
                // deleted, otherwise we can get errors if services are still running for
                // accounts that are now gone.
                // TODO: This is kind of a hack, it would be nicer if we could handle it correctly
                // if accounts disappear out from under us.
                LogUtils.d(TAG, "Forced shutdown, killing process");
                System.exit(-1);
            }
        }
        return START_STICKY;
    }

    public int doOperation(final EasOperation operation, final String loggingName) {
        LogUtils.d(TAG, "%s: %d", loggingName, operation.getAccountId());
        mSynchronizer.syncStart(operation.getAccountId());
        int result = EasOperation.RESULT_MIN_OK_RESULT;
        // TODO: Do we need a wakelock here? For RPC coming from sync adapters, no -- the SA
        // already has one. But for others, maybe? Not sure what's guaranteed for AIDL calls.
        // If we add a wakelock (or anything else for that matter) here, must remember to undo
        // it in the finally block below.
        // On the other hand, even for SAs, it doesn't hurt to get a wakelock here.
        try {
            result = operation.performOperation();
            LogUtils.d(TAG, "Operation result %d", result);
            return result;
        } finally {
            mSynchronizer.syncEnd(result >= EasOperation.RESULT_MIN_OK_RESULT,
                    operation.getAccount());
        }
    }

    /**
     * Determine whether this account is configured with folders that are ready for push
     * notifications.
     * @param account The {@link Account} that we're interested in.
     * @return Whether this account needs to ping.
     */
    public boolean pingNeededForAccount(final Account account) {
        // Check account existence.
        if (account == null || account.mId == Account.NO_ACCOUNT) {
            LogUtils.d(TAG, "Do not ping: Account not found or not valid");
            return false;
        }

        // Check if account is configured for a push sync interval.
        if (account.mSyncInterval != Account.CHECK_INTERVAL_PUSH) {
            LogUtils.d(TAG, "Do not ping: Account %d not configured for push", account.mId);
            return false;
        }

        // Check security hold status of the account.
        if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
            LogUtils.d(TAG, "Do not ping: Account %d is on security hold", account.mId);
            return false;
        }

        // Check if the account has performed at least one sync so far (accounts must perform
        // the initial sync before push is possible).
        if (EmailContent.isInitialSyncKey(account.mSyncKey)) {
            LogUtils.d(TAG, "Do not ping: Account %d has not done initial sync", account.mId);
            return false;
        }

        // Check that there's at least one mailbox that is both configured for push notifications,
        // and whose content type is enabled for sync in the account manager.
        final android.accounts.Account amAccount = new android.accounts.Account(
                        account.mEmailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);

        final Set<String> authsToSync = getAuthoritiesToSync(amAccount, AUTHORITIES_TO_SYNC);
        // If we have at least one sync-enabled content type, check for syncing mailboxes.
        if (!authsToSync.isEmpty()) {
            final Cursor c = Mailbox.getMailboxesForPush(getContentResolver(), account.mId);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        final int mailboxType = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
                        if (authsToSync.contains(Mailbox.getAuthority(mailboxType))) {
                            return true;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }
        LogUtils.d(TAG, "Do not ping: Account %d has no folders configured for push", account.mId);
        return false;
    }

    static public GalResult searchGal(final Context context, final long accountId,
                                      final String filter, final int limit) {
        final EasSearchGal operation = new EasSearchGal(context, accountId, filter, limit);
        // We don't use doOperation() here for two reasons:
        // 1. This is a static function, doOperation is not, and we don't have an instance of
        // EasService.
        // 2. All doOperation() does besides this is stop the ping and then restart it. This is
        // required during syncs, but not for GalSearches.
        final int result = operation.performOperation();
        if (result == EasSearchGal.RESULT_OK) {
            return operation.getResult();
        } else {
            return null;
        }
    }

    /**
     * Converts from an EasOperation status to a status code defined in EmailServiceStatus.
     * This is used to communicate the status of a sync operation to the caller.
     * @param easStatus result returned from an EasOperation
     * @return EmailServiceStatus
     */
    private int convertToEmailServiceStatus(int easStatus) {
        if (easStatus >= EasOperation.RESULT_MIN_OK_RESULT) {
            return EmailServiceStatus.SUCCESS;
        }
        switch (easStatus) {
            case EasOperation.RESULT_ABORT:
            case EasOperation.RESULT_RESTART:
                // This should only happen if a ping is interruped for some reason. We would not
                // expect see that here, since this should only be called for a sync.
                LogUtils.e(TAG, "Abort or Restart easStatus");
                return EmailServiceStatus.SUCCESS;

            case EasOperation.RESULT_TOO_MANY_REDIRECTS:
                return EmailServiceStatus.INTERNAL_ERROR;

            case EasOperation.RESULT_NETWORK_PROBLEM:
                // This is due to an IO error, we need the caller to know about this so that it
                // can let the syncManager know.
                return EmailServiceStatus.IO_ERROR;

            case EasOperation.RESULT_FORBIDDEN:
            case EasOperation.RESULT_AUTHENTICATION_ERROR:
                return EmailServiceStatus.LOGIN_FAILED;

            case EasOperation.RESULT_PROVISIONING_ERROR:
                return EmailServiceStatus.PROVISIONING_ERROR;

            case EasOperation.RESULT_CLIENT_CERTIFICATE_REQUIRED:
                return EmailServiceStatus.CLIENT_CERTIFICATE_ERROR;

            case EasOperation.RESULT_PROTOCOL_VERSION_UNSUPPORTED:
                return EmailServiceStatus.PROTOCOL_ERROR;

            case EasOperation.RESULT_INITIALIZATION_FAILURE:
            case EasOperation.RESULT_HARD_DATA_FAILURE:
            case EasOperation.RESULT_OTHER_FAILURE:
                return EmailServiceStatus.INTERNAL_ERROR;

            case EasOperation.RESULT_NON_FATAL_ERROR:
                // We do not expect to see this error here: This should be consumed in
                // EasFullSyncOperation. The only case this occurs in is when we try to send
                // a message in the outbox, and there's some problem with the message locally
                // that prevents it from being sent. We return a
                LogUtils.e(TAG, "Other non-fatal error easStatus %d", easStatus);
                return EmailServiceStatus.SUCCESS;
        }
        LogUtils.e(TAG, "Unexpected easStatus %d", easStatus);
        return EmailServiceStatus.INTERNAL_ERROR;
    }


    /**
     * Determine which content types are set to sync for an account.
     * @param account The account whose sync settings we're looking for.
     * @param authorities All possible authorities we could care about.
     * @return The authorities for the content types we want to sync for account.
     */
    public static Set<String> getAuthoritiesToSync(final android.accounts.Account account,
                                                    final String[] authorities) {
        final HashSet<String> authsToSync = new HashSet();
        for (final String authority : authorities) {
            if (ContentResolver.getSyncAutomatically(account, authority)) {
                authsToSync.add(authority);
            }
        }
        return authsToSync;
    }
}
