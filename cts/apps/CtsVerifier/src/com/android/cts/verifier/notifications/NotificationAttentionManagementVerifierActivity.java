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

package com.android.cts.verifier.notifications;

import static com.android.cts.verifier.notifications.MockListener.JSON_AMBIENT;
import static com.android.cts.verifier.notifications.MockListener.JSON_MATCHES_ZEN_FILTER;
import static com.android.cts.verifier.notifications.MockListener.JSON_TAG;

import android.app.Activity;
import android.app.Notification;
import android.content.ContentProviderOperation;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.Settings.Secure;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import com.android.cts.verifier.R;
import com.android.cts.verifier.nfc.TagVerifierActivity;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NotificationAttentionManagementVerifierActivity
        extends NotificationListenerVerifierActivity {
    private static final String TAG = TagVerifierActivity.class.getSimpleName();
    private static final String ALICE = "Alice";
    private static final String ALICE_PHONE = "+16175551212";
    private static final String ALICE_EMAIL = "alice@_foo._bar";
    private static final String BOB = "Bob";
    private static final String BOB_PHONE = "+16505551212";;
    private static final String BOB_EMAIL = "bob@_foo._bar";
    private static final String CHARLIE = "Charlie";
    private static final String CHARLIE_PHONE = "+13305551212";
    private static final String CHARLIE_EMAIL = "charlie@_foo._bar";
    private static final int MODE_NONE = 0;
    private static final int MODE_URI = 1;
    private static final int MODE_PHONE = 2;
    private static final int MODE_EMAIL = 3;
    private static final int DELAYED_SETUP = CLEARED;

    private Uri mAliceUri;
    private Uri mBobUri;
    private Uri mCharlieUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState, R.layout.nls_main);
        setInfoResources(R.string.attention_test, R.string.attention_info, -1);
    }

    // Test Setup

    @Override
    protected void createTestItems() {
        createNlsSettingsItem(R.string.nls_enable_service);
        createAutoItem(R.string.nls_service_started);
        createAutoItem(R.string.attention_create_contacts);
        createRetryItem(R.string.attention_filter_none);
        createAutoItem(R.string.attention_all_are_filtered);
        createRetryItem(R.string.attention_filter_all);
        createAutoItem(R.string.attention_none_are_filtered);
        createAutoItem(R.string.attention_default_order);
        createAutoItem(R.string.attention_interruption_order);
        createAutoItem(R.string.attention_priority_order);
        createAutoItem(R.string.attention_ambient_bit);
        createAutoItem(R.string.attention_lookup_order);
        createAutoItem(R.string.attention_email_order);
        createAutoItem(R.string.attention_phone_order);
        createRetryItem(R.string.attention_filter_priority);
        createAutoItem(R.string.attention_some_are_filtered);
        createAutoItem(R.string.attention_delete_contacts);
    }

    // Test management

    @Override
    protected void updateStateMachine() {
        switch (mState) {
            case 0:
                testIsEnabled(mState);
                break;
            case 1:
                testIsStarted(mState);
                break;
            case 2:
                testInsertContacts(mState);
                break;
            case 3:
                testModeNone(mState);
                break;
            case 4:
                testNoneInterceptsAll(mState);
                break;
            case 5:
                testModeAll(mState);
                break;
            case 6:
                testAllInterceptsNothing(mState);
                break;
            case 7:
                testDefaultOrder(mState);
                break;
            case 8:
                testInterruptionOrder(mState);
                break;
            case 9:
                testPrioritytOrder(mState);
                break;
            case 10:
                testAmbientBits(mState);
                break;
            case 11:
                testLookupUriOrder(mState);
                break;
            case 12:
                testEmailOrder(mState);
                break;
            case 13:
                testPhoneOrder(mState);
                break;
            case 14:
                testModePriority(mState);
                break;
            case 15:
                testPriorityInterceptsSome(mState);
                break;
            case 16:
                testDeleteContacts(mState);
                break;
            case 17:
                getPassButton().setEnabled(true);
                mNm.cancelAll();
                break;
        }
    }

    // usePriorities true: B, C, A
    // usePriorities false:
    //   MODE_NONE: C, B, A
    //   otherwise: A, B ,C
    private void sendNotifications(int annotationMode, boolean usePriorities, boolean noisy) {
        // TODO(cwren) Fixes flakey tests due to bug 17644321. Remove this line when it is fixed.
        int baseId = NOTIFICATION_ID + (noisy ? 3 : 0);

        // C, B, A when sorted by time.  Times must be in the past.
        long whenA = System.currentTimeMillis() - 4000000L;
        long whenB = System.currentTimeMillis() - 2000000L;
        long whenC = System.currentTimeMillis() - 1000000L;

        // B, C, A when sorted by priorities
        int priorityA = usePriorities ? Notification.PRIORITY_MIN : Notification.PRIORITY_DEFAULT;
        int priorityB = usePriorities ? Notification.PRIORITY_MAX : Notification.PRIORITY_DEFAULT;
        int priorityC = usePriorities ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT;

        Notification.Builder alice = new Notification.Builder(mContext)
                .setContentTitle(ALICE)
                .setContentText(ALICE)
                .setSmallIcon(R.drawable.fs_good)
                .setPriority(priorityA)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setWhen(whenA);
        alice.setDefaults(noisy ? Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE : 0);
        addPerson(annotationMode, alice, mAliceUri, ALICE_PHONE, ALICE_EMAIL);
        mNm.notify(ALICE, baseId + 1, alice.build());

        Notification.Builder bob = new Notification.Builder(mContext)
                .setContentTitle(BOB)
                .setContentText(BOB)
                .setSmallIcon(R.drawable.fs_warning)
                .setPriority(priorityB)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setWhen(whenB);
        addPerson(annotationMode, bob, mBobUri, BOB_PHONE, BOB_EMAIL);
        mNm.notify(BOB, baseId + 2, bob.build());

        Notification.Builder charlie = new Notification.Builder(mContext)
                .setContentTitle(CHARLIE)
                .setContentText(CHARLIE)
                .setSmallIcon(R.drawable.fs_error)
                .setPriority(priorityC)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setWhen(whenC);
        addPerson(annotationMode, charlie, mCharlieUri, CHARLIE_PHONE, CHARLIE_EMAIL);
        mNm.notify(CHARLIE, baseId + 3, charlie.build());
    }

    private void addPerson(int mode, Notification.Builder note,
            Uri uri, String phone, String email) {
        if (mode == MODE_URI && uri != null) {
            note.addPerson(uri.toString());
        } else if (mode == MODE_PHONE) {
            note.addPerson(Uri.fromParts("tel", phone, null).toString());
        } else if (mode == MODE_EMAIL) {
            note.addPerson(Uri.fromParts("mailto", email, null).toString());
        }
    }

    // Tests

    private void testIsEnabled(int i) {
        // no setup required
        Intent settings = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
        if (settings.resolveActivity(mPackageManager) == null) {
            logWithStack("failed testIsEnabled: no settings activity");
            mStatus[i] = FAIL;
        } else {
            // TODO: find out why Secure.ENABLED_NOTIFICATION_LISTENERS is hidden
            String listeners = Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            if (listeners != null && listeners.contains(LISTENER_PATH)) {
                mStatus[i] = PASS;
            } else {
                mStatus[i] = WAIT_FOR_USER;
            }
        }
        next();
    }

    private void testIsStarted(final int i) {
        if (mStatus[i] == SETUP) {
            mStatus[i] = READY;
            // wait for the service to start
            delay();
        } else {
            MockListener.probeListenerStatus(mContext,
                    new MockListener.StatusCatcher() {
                        @Override
                        public void accept(int result) {
                            if (result == Activity.RESULT_OK) {
                                mStatus[i] = PASS;
                            } else {
                                logWithStack("failed testIsStarted: " + result);
                                mStatus[i] = FAIL;
                            }
                            next();
                        }
                    });
        }
    }

    private void testModeAll(final int i) {
        if (mStatus[i] == READY || mStatus[i] == SETUP) {
            MockListener.probeFilter(mContext,
                    new MockListener.IntegerResultCatcher() {
                        @Override
                        public void accept(int mode) {
                            if (mode == NotificationListenerService.INTERRUPTION_FILTER_ALL) {
                                mStatus[i] = PASS;
                            } else {
                                logWithStack("waiting testModeAll: " + mode);
                                mStatus[i] = WAIT_FOR_USER;
                            }
                            next();
                        }
                    });
        }
    }

    private void testModePriority(final int i) {
        if (mStatus[i] == READY || mStatus[i] == SETUP) {
            MockListener.probeFilter(mContext,
                    new MockListener.IntegerResultCatcher() {
                        @Override
                        public void accept(int mode) {
                            if (mode == NotificationListenerService.INTERRUPTION_FILTER_PRIORITY) {
                                mStatus[i] = PASS;
                            } else {
                                logWithStack("waiting testModePriority: " + mode);
                                mStatus[i] = WAIT_FOR_USER;
                            }
                            next();
                        }
                    });
        }
    }

    private void testModeNone(final int i) {
        if (mStatus[i] == READY || mStatus[i] == SETUP) {
            MockListener.probeFilter(mContext,
                    new MockListener.IntegerResultCatcher() {
                        @Override
                        public void accept(int mode) {
                            if (mode == NotificationListenerService.INTERRUPTION_FILTER_NONE) {
                                mStatus[i] = PASS;
                            } else {
                                logWithStack("waiting testModeNone: " + mode);
                                mStatus[i] = WAIT_FOR_USER;
                            }
                            next();
                        }
                    });
        }
    }


    private void insertSingleContact(String name, String phone, String email, boolean starred) {
        final ArrayList<ContentProviderOperation> operationList =
                new ArrayList<ContentProviderOperation>();
        ContentProviderOperation.Builder builder =
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI);
        builder.withValue(ContactsContract.RawContacts.STARRED, starred ? 1 : 0);
        operationList.add(builder.build());

        builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
        builder.withValueBackReference(StructuredName.RAW_CONTACT_ID, 0);
        builder.withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
        builder.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name);
        operationList.add(builder.build());

        if (phone != null) {
            builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
            builder.withValueBackReference(Phone.RAW_CONTACT_ID, 0);
            builder.withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
            builder.withValue(Phone.TYPE, Phone.TYPE_MOBILE);
            builder.withValue(Phone.NUMBER, phone);
            builder.withValue(ContactsContract.Data.IS_PRIMARY, 1);
            operationList.add(builder.build());
        }
        if (email != null) {
            builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
            builder.withValueBackReference(Email.RAW_CONTACT_ID, 0);
            builder.withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE);
            builder.withValue(Email.TYPE, Email.TYPE_HOME);
            builder.withValue(Email.DATA, email);
            operationList.add(builder.build());
        }

        try {
            mContext.getContentResolver().applyBatch(ContactsContract.AUTHORITY, operationList);
        } catch (RemoteException e) {
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        } catch (OperationApplicationException e) {
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        }
    }

    private Uri lookupContact(String phone) {
        Cursor c = null;
        try {
            Uri phoneUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phone));
            String[] projection = new String[] { ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.LOOKUP_KEY };
            c = mContext.getContentResolver().query(phoneUri, projection, null, null, null);
            if (c != null && c.getCount() > 0) {
                c.moveToFirst();
                int lookupIdx = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY);
                int idIdx = c.getColumnIndex(ContactsContract.Contacts._ID);
                String lookupKey = c.getString(lookupIdx);
                long contactId = c.getLong(idIdx);
                return ContactsContract.Contacts.getLookupUri(contactId, lookupKey);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Problem getting content resolver or performing contacts query.", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    private void testInsertContacts(final int i) {
        if (mStatus[i] == SETUP) {
            insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
            insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
            // charlie is not in contacts
            mStatus[i] = READY;
            // wait for insertions to move through the system
            delay();
        } else {
            mAliceUri = lookupContact(ALICE_PHONE);
            mBobUri = lookupContact(BOB_PHONE);
            mCharlieUri = lookupContact(CHARLIE_PHONE);

            mStatus[i] = PASS;
            if (mAliceUri == null) { mStatus[i] = FAIL; }
            if (mBobUri == null) { mStatus[i] = FAIL; }
            if (mCharlieUri != null) { mStatus[i] = FAIL; }
            next();
        }
    }

    // ordered by time: C, B, A
    private void testDefaultOrder(final int i) {
        if (mStatus[i] == SETUP) {
            mNm.cancelAll();
            MockListener.resetListenerData(this);
            mStatus[i] = CLEARED;
            // wait for intent to move through the system
            delay();
        } else if (mStatus[i] == CLEARED) {
            sendNotifications(MODE_NONE, false, false);
            mStatus[i] = READY;
            // wait for notifications to move through the system
            delay();
        } else {
            MockListener.probeListenerOrder(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> orderedKeys) {
                            int rankA = findTagInKeys(ALICE, orderedKeys);
                            int rankB = findTagInKeys(BOB, orderedKeys);
                            int rankC = findTagInKeys(CHARLIE, orderedKeys);
                            if (rankC < rankB && rankB < rankA) {
                                mStatus[i] = PASS;
                            } else {
                                logWithStack("failed testDefaultOrder : "
                                        + rankA + ", " + rankB + ", " + rankC);
                                mStatus[i] = FAIL;
                            }
                            next();
                        }
                    });
        }
    }

    // ordered by priority: B, C, A
    private void testPrioritytOrder(final int i) {
        if (mStatus[i] == SETUP) {
            mNm.cancelAll();
            MockListener.resetListenerData(this);
            mStatus[i] = CLEARED;
            // wait for intent to move through the system
            delay();
        } else if (mStatus[i] == CLEARED) {
            sendNotifications(MODE_PHONE, true, false);
            mStatus[i] = READY;
            // wait for notifications to move through the system
            delay();
        } else {
            MockListener.probeListenerOrder(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> orderedKeys) {
                            int rankA = findTagInKeys(ALICE, orderedKeys);
                            int rankB = findTagInKeys(BOB, orderedKeys);
                            int rankC = findTagInKeys(CHARLIE, orderedKeys);
                            if (rankB < rankC && rankC < rankA) {
                                mStatus[i] = PASS;
                            } else {
                                logWithStack("failed testPrioritytOrder : "
                                        + rankA + ", " + rankB + ", " + rankC);
                                mStatus[i] = FAIL;
                            }
                            next();
                        }
                    });
        }
    }

    // B & C above the fold, A below
    private void testAmbientBits(final int i) {
        if (mStatus[i] == SETUP) {
            mNm.cancelAll();
            MockListener.resetListenerData(this);
            mStatus[i] = CLEARED;
            // wait for intent to move through the system
            delay();
        } else if (mStatus[i] == CLEARED) {
            sendNotifications(MODE_PHONE, true, false);
            mStatus[i] = READY;
            // wait for notifications to move through the system
            delay();
        } else {
            MockListener.probeListenerPayloads(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> result) {
                            boolean pass = false;
                            Set<String> found = new HashSet<String>();
                            if (result != null && result.size() > 0) {
                                pass = true;
                                for (String payloadData : result) {
                                    try {
                                        JSONObject payload = new JSONObject(payloadData);
                                        String tag = payload.getString(JSON_TAG);
                                        if (found.contains(tag)) {
                                            // multiple entries for same notification!
                                            pass = false;
                                        } else if (ALICE.equals(tag)) {
                                            found.add(ALICE);
                                            pass &= payload.getBoolean(JSON_AMBIENT);
                                        } else if (BOB.equals(tag)) {
                                            found.add(BOB);
                                            pass &= !payload.getBoolean(JSON_AMBIENT);
                                        } else if (CHARLIE.equals(tag)) {
                                            found.add(CHARLIE);
                                            pass &= !payload.getBoolean(JSON_AMBIENT);
                                        }
                                    } catch (JSONException e) {
                                        pass = false;
                                        Log.e(TAG, "failed to unpack data from mocklistener", e);
                                    }
                                }
                            }
                            pass &= found.size() == 3;
                            mStatus[i] = pass ? PASS : FAIL;
                            next();
                        }
                    });
        }
    }

    // ordered by contact affinity: A, B, C
    private void testLookupUriOrder(final int i) {
        if (mStatus[i] == SETUP) {
            mNm.cancelAll();
            MockListener.resetListenerData(this);
            mStatus[i] = CLEARED;
            // wait for intent to move through the system
            delay();
        } else if (mStatus[i] == CLEARED) {
            sendNotifications(MODE_URI, false, false);
            mStatus[i] = READY;
            // wait for notifications to move through the system
            delay();
        } else {
            MockListener.probeListenerOrder(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> orderedKeys) {
                            int rankA = findTagInKeys(ALICE, orderedKeys);
                            int rankB = findTagInKeys(BOB, orderedKeys);
                            int rankC = findTagInKeys(CHARLIE, orderedKeys);
                            if (rankA < rankB && rankB < rankC) {
                                mStatus[i] = PASS;
                            } else {
                                logWithStack("failed testLookupUriOrder : "
                                        + rankA + ", " + rankB + ", " + rankC);
                                mStatus[i] = FAIL;
                            }
                            next();
                        }
                    });
        }
    }

    // ordered by contact affinity: A, B, C
    private void testEmailOrder(final int i) {
        if (mStatus[i] == SETUP) {
            mNm.cancelAll();
            MockListener.resetListenerData(this);
            mStatus[i] = DELAYED_SETUP;
            // wait for intent to move through the system
            delay();
        } else if (mStatus[i] == DELAYED_SETUP) {
            sendNotifications(MODE_EMAIL, false, false);
            mStatus[i] = READY;
            // wait for notifications to move through the system
            delay();
        } else {
            MockListener.probeListenerOrder(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> orderedKeys) {
                            int rankA = findTagInKeys(ALICE, orderedKeys);
                            int rankB = findTagInKeys(BOB, orderedKeys);
                            int rankC = findTagInKeys(CHARLIE, orderedKeys);
                            if (rankA < rankB && rankB < rankC) {
                                mStatus[i] = PASS;
                            } else {
                                logWithStack("failed testEmailOrder : "
                                        + rankA + ", " + rankB + ", " + rankC);
                                mStatus[i] = FAIL;
                            }
                            next();
                        }
                    });
        }
    }

    // ordered by contact affinity: A, B, C
    private void testPhoneOrder(final int i) {
        if (mStatus[i] == SETUP) {
            mNm.cancelAll();
            MockListener.resetListenerData(this);
            mStatus[i] = CLEARED;
            // wait for intent to move through the system
            delay();
        } else if (mStatus[i] == CLEARED) {
            sendNotifications(MODE_PHONE, false, false);
            mStatus[i] = READY;
            // wait for notifications to move through the system
            delay();
        } else {
            MockListener.probeListenerOrder(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> orderedKeys) {
                            int rankA = findTagInKeys(ALICE, orderedKeys);
                            int rankB = findTagInKeys(BOB, orderedKeys);
                            int rankC = findTagInKeys(CHARLIE, orderedKeys);
                            if (rankA < rankB && rankB < rankC) {
                                mStatus[i] = PASS;
                            } else {
                                logWithStack("failed testPhoneOrder : "
                                        + rankA + ", " + rankB + ", " + rankC);
                                mStatus[i] = FAIL;
                            }
                            next();
                        }
                    });
        }
    }

    // A starts at the top then falls to the bottom
    private void testInterruptionOrder(final int i) {
        if (mStatus[i] == SETUP) {
            mNm.cancelAll();
            MockListener.resetListenerData(this);
            mStatus[i] = CLEARED;
            // wait for intent to move through the system
            delay();
        } else if (mStatus[i] == CLEARED) {
            sendNotifications(MODE_NONE, false, true);
            mStatus[i] = READY;
            // wait for notifications to move through the system
            delay();
        } else if (mStatus[i] == READY) {
            MockListener.probeListenerOrder(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> orderedKeys) {
                            int rankA = findTagInKeys(ALICE, orderedKeys);
                            int rankB = findTagInKeys(BOB, orderedKeys);
                            int rankC = findTagInKeys(CHARLIE, orderedKeys);
                            if (rankA < rankB && rankA < rankC) {
                                mStatus[i] = RETRY;
                                delay(12000);
                            } else {
                                logWithStack("noisy notification did not sort to top.");
                                mStatus[i] = FAIL;
                                next();
                            }
                        }
                    });
        } else if (mStatus[i] == RETRY) {
            MockListener.probeListenerOrder(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> orderedKeys) {
                            int rankA = findTagInKeys(ALICE, orderedKeys);
                            int rankB = findTagInKeys(BOB, orderedKeys);
                            int rankC = findTagInKeys(CHARLIE, orderedKeys);
                            if (rankA > rankB && rankA > rankC) {
                                mStatus[i] = PASS;
                            } else {
                                logWithStack("noisy notification did not fade back into the list.");
                                mStatus[i] = FAIL;
                            }
                            next();
                        }
                    });
        }
    }

    // Nothing should be filtered when mode is ALL
    private void testAllInterceptsNothing(final int i) {
        if (mStatus[i] == SETUP) {
            mNm.cancelAll();
            MockListener.resetListenerData(this);
            mStatus[i] = CLEARED;
            // wait for intent to move through the system
            delay();
        } else if (mStatus[i] == CLEARED) {
            sendNotifications(MODE_URI, false, false);
            mStatus[i] = READY;
            // wait for notifications to move through the system
            delay();
        } else {
            MockListener.probeListenerPayloads(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> result) {
                            boolean pass = false;
                            Set<String> found = new HashSet<String>();
                            if (result != null && result.size() > 0) {
                                pass = true;
                                for (String payloadData : result) {
                                    try {
                                        JSONObject payload = new JSONObject(payloadData);
                                        String tag = payload.getString(JSON_TAG);
                                        if (found.contains(tag)) {
                                            // multiple entries for same notification!
                                            pass = false;
                                        } else if (ALICE.equals(tag)) {
                                            found.add(ALICE);
                                            pass &= payload.getBoolean(JSON_MATCHES_ZEN_FILTER);
                                        } else if (BOB.equals(tag)) {
                                            found.add(BOB);
                                            pass &= payload.getBoolean(JSON_MATCHES_ZEN_FILTER);
                                        } else if (CHARLIE.equals(tag)) {
                                            found.add(CHARLIE);
                                            pass &= payload.getBoolean(JSON_MATCHES_ZEN_FILTER);
                                        }
                                    } catch (JSONException e) {
                                        pass = false;
                                        Log.e(TAG, "failed to unpack data from mocklistener", e);
                                    }
                                }
                            }
                            pass &= found.size() == 3;
                            mStatus[i] = pass ? PASS : FAIL;
                            next();
                        }
                    });
        }
    }

    // A should be filtered when mode is Priority/Starred.
    private void testPriorityInterceptsSome(final int i) {
        if (mStatus[i] == SETUP) {
            mNm.cancelAll();
            MockListener.resetListenerData(this);
            mStatus[i] = CLEARED;
            // wait for intent to move through the system
            delay();
        } else if (mStatus[i] == CLEARED) {
            sendNotifications(MODE_URI, false, false);
            mStatus[i] = READY;
            // wait for notifications to move through the system
            delay();
        } else {
            MockListener.probeListenerPayloads(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> result) {
                            boolean pass = false;
                            Set<String> found = new HashSet<String>();
                            if (result != null && result.size() > 0) {
                                pass = true;
                                for (String payloadData : result) {
                                    try {
                                        JSONObject payload = new JSONObject(payloadData);
                                        String tag = payload.getString(JSON_TAG);
                                        if (found.contains(tag)) {
                                            // multiple entries for same notification!
                                            pass = false;
                                        } else if (ALICE.equals(tag)) {
                                            found.add(ALICE);
                                            pass &= payload.getBoolean(JSON_MATCHES_ZEN_FILTER);
                                        } else if (BOB.equals(tag)) {
                                            found.add(BOB);
                                            pass &= !payload.getBoolean(JSON_MATCHES_ZEN_FILTER);
                                        } else if (CHARLIE.equals(tag)) {
                                            found.add(CHARLIE);
                                            pass &= !payload.getBoolean(JSON_MATCHES_ZEN_FILTER);
                                        }
                                    } catch (JSONException e) {
                                        pass = false;
                                        Log.e(TAG, "failed to unpack data from mocklistener", e);
                                    }
                                }
                            }
                            pass &= found.size() == 3;
                            mStatus[i] = pass ? PASS : FAIL;
                            next();
                        }
                    });
        }
    }

    // Nothing should get through when mode is None.
    private void testNoneInterceptsAll(final int i) {
        if (mStatus[i] == SETUP) {
            mNm.cancelAll();
            MockListener.resetListenerData(this);
            mStatus[i] = CLEARED;
            // wait for intent to move through the system
            delay();
        } else if (mStatus[i] == CLEARED) {
            sendNotifications(MODE_URI, false, false);
            mStatus[i] = READY;
            // wait for notifications to move through the system
            delay();
        } else {
            MockListener.probeListenerPayloads(mContext,
                    new MockListener.StringListResultCatcher() {
                        @Override
                        public void accept(List<String> result) {
                            boolean pass = false;
                            Set<String> found = new HashSet<String>();
                            if (result != null && result.size() > 0) {
                                pass = true;
                                for (String payloadData : result) {
                                    try {
                                        JSONObject payload = new JSONObject(payloadData);
                                        String tag = payload.getString(JSON_TAG);
                                        if (found.contains(tag)) {
                                            // multiple entries for same notification!
                                            pass = false;
                                        } else if (ALICE.equals(tag)) {
                                            found.add(ALICE);
                                            pass &= !payload.getBoolean(JSON_MATCHES_ZEN_FILTER);
                                        } else if (BOB.equals(tag)) {
                                            found.add(BOB);
                                            pass &= !payload.getBoolean(JSON_MATCHES_ZEN_FILTER);
                                        } else if (CHARLIE.equals(tag)) {
                                            found.add(CHARLIE);
                                            pass &= !payload.getBoolean(JSON_MATCHES_ZEN_FILTER);
                                        }
                                    } catch (JSONException e) {
                                        pass = false;
                                        Log.e(TAG, "failed to unpack data from mocklistener", e);
                                    }
                                }
                            }
                            pass &= found.size() == 3;
                            mStatus[i] = pass ? PASS : FAIL;
                            next();
                        }
                    });
        }
    }

    /** Search a list of notification keys for a givcen tag. */
    private int findTagInKeys(String tag, List<String> orderedKeys) {
        for (int i = 0; i < orderedKeys.size(); i++) {
            if (orderedKeys.get(i).contains(tag)) {
                return i;
            }
        }
        return -1;
    }

    private void testDeleteContacts(final int i) {
        if (mStatus[i] == SETUP) {
            final ArrayList<ContentProviderOperation> operationList =
                    new ArrayList<ContentProviderOperation>();
            operationList.add(ContentProviderOperation.newDelete(mAliceUri).build());
            operationList.add(ContentProviderOperation.newDelete(mBobUri).build());
            try {
                mContext.getContentResolver().applyBatch(ContactsContract.AUTHORITY, operationList);
                mStatus[i] = READY;
            } catch (RemoteException e) {
                Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                mStatus[i] = FAIL;
            } catch (OperationApplicationException e) {
                Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                mStatus[i] = FAIL;
            }
            // wait for deletions to move through the system
            delay(3000);
        } else if (mStatus[i] == READY) {
            mAliceUri = lookupContact(ALICE_PHONE);
            mBobUri = lookupContact(BOB_PHONE);
            mCharlieUri = lookupContact(CHARLIE_PHONE);

            mStatus[i] = PASS;
            if (mAliceUri != null) { mStatus[i] = FAIL; }
            if (mBobUri != null) { mStatus[i] = FAIL; }
            if (mCharlieUri != null) { mStatus[i] = FAIL; }
            next();
        }
    }
}
