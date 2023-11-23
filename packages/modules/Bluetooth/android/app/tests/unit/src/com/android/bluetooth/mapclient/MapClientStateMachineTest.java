/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.bluetooth.mapclient;

import static android.Manifest.permission.BLUETOOTH_CONNECT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import android.annotation.Nullable;
import android.app.BroadcastOptions;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothMapClient;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.SdpMasRecord;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Mms;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.R;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.obex.HeaderSet;
import com.android.vcard.VCardConstants;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardProperty;

import com.google.common.truth.Correspondence;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class MapClientStateMachineTest {

    private static final String TAG = "MapStateMachineTest";
    private static final String FOLDER_SENT = "sent";

    private static final int ASYNC_CALL_TIMEOUT_MILLIS = 100;
    private static final int DISCONNECT_TIMEOUT = 3000;

    private Bmessage mTestIncomingSmsBmessage;
    private Bmessage mTestIncomingMmsBmessage;
    private String mTestMessageSmsHandle = "0001";
    private String mTestMessageMmsHandle = "0002";

    private static final boolean MESSAGE_SEEN = true;
    private static final boolean MESSAGE_NOT_SEEN = false;

    private VCardEntry mOriginator;

    @Rule
    public final ServiceTestRule mServiceRule = new ServiceTestRule();
    private BluetoothAdapter mAdapter;
    private MceStateMachine mMceStateMachine = null;
    private BluetoothDevice mTestDevice;
    private Context mTargetContext;
    private Handler mHandler;
    private ArgumentCaptor<Intent> mIntentArgument = ArgumentCaptor.forClass(Intent.class);
    @Mock
    private AdapterService mAdapterService;
    @Mock
    private DatabaseManager mDatabaseManager;
    @Mock
    private MapClientService mMockMapClientService;
    @Mock
    private MapClientContent mMockDatabase;
    private MockContentResolver mMockContentResolver;
    private MockSmsContentProvider mMockContentProvider;

    @Mock
    private TelephonyManager mMockTelephonyManager;

    @Mock
    private MasClient mMockMasClient;

    @Mock
    private RequestPushMessage mMockRequestPushMessage;

    @Mock
    private SubscriptionManager mMockSubscriptionManager;

    private static final String TEST_OWN_PHONE_NUMBER = "555-1234";
    @Mock
    private RequestGetMessagesListingForOwnNumber mMockRequestOwnNumberCompletedWithNumber;
    @Mock
    private RequestGetMessagesListingForOwnNumber mMockRequestOwnNumberIncompleteSearch;
    @Mock
    private RequestGetMessage mMockRequestGetMessage;
    @Mock
    private RequestGetMessagesListing mMockRequestGetMessagesListing;

    private static final Correspondence<Request, String> GET_FOLDER_NAME =
            Correspondence.transforming(
            MapClientStateMachineTest::getFolderNameFromRequestGetMessagesListing,
            "has folder name of");

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getTargetContext();
        MockitoAnnotations.initMocks(this);
        mMockContentProvider = new MockSmsContentProvider();
        mMockContentResolver = new MockContentResolver();
        TestUtils.setAdapterService(mAdapterService);
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        doReturn(true, false).when(mAdapterService).isStartedProfile(anyString());
        TestUtils.startService(mServiceRule, MapClientService.class);
        mMockContentResolver.addProvider("sms", mMockContentProvider);
        mMockContentResolver.addProvider("mms", mMockContentProvider);
        mMockContentResolver.addProvider("mms-sms", mMockContentProvider);

        when(mMockMapClientService.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockMapClientService.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE))
                .thenReturn(mMockSubscriptionManager);
        when(mMockMapClientService.getSystemServiceName(SubscriptionManager.class))
                .thenReturn(Context.TELEPHONY_SUBSCRIPTION_SERVICE);

        doReturn(mTargetContext.getResources()).when(mMockMapClientService).getResources();

        // This line must be called to make sure relevant objects are initialized properly
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        // Get a device for testing
        mTestDevice = mAdapter.getRemoteDevice("00:01:02:03:04:05");

        when(mMockMasClient.makeRequest(any(Request.class))).thenReturn(true);
        mMceStateMachine = new MceStateMachine(mMockMapClientService, mTestDevice, mMockMasClient,
                mMockDatabase);
        TestUtils.waitForLooperToFinishScheduledTask(mMceStateMachine.getHandler().getLooper());
        Assert.assertNotNull(mMceStateMachine);
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mHandler = new Handler();

        when(mMockRequestOwnNumberCompletedWithNumber.isSearchCompleted()).thenReturn(true);
        when(mMockRequestOwnNumberCompletedWithNumber.getOwnNumber()).thenReturn(
                TEST_OWN_PHONE_NUMBER);
        when(mMockRequestOwnNumberIncompleteSearch.isSearchCompleted()).thenReturn(false);
        when(mMockRequestOwnNumberIncompleteSearch.getOwnNumber()).thenReturn(null);

        createTestMessages();

        when(mMockRequestGetMessage.getMessage()).thenReturn(mTestIncomingSmsBmessage);
        when(mMockRequestGetMessage.getHandle()).thenReturn(mTestMessageSmsHandle);

        when(mMockMapClientService.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(
                mMockTelephonyManager);
        when(mMockTelephonyManager.isSmsCapable()).thenReturn(false);

    }

    @After
    public void tearDown() throws Exception {
        if (mMceStateMachine != null) {
            mMceStateMachine.doQuit();
        }
        TestUtils.stopService(mServiceRule, MapClientService.class);
        TestUtils.clearAdapterService(mAdapterService);
    }

    /**
     * Test that default state is STATE_CONNECTING
     */
    @Test
    public void testDefaultConnectingState() {
        Log.i(TAG, "in testDefaultConnectingState");
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING, mMceStateMachine.getState());
    }

    /**
     * Test transition from STATE_CONNECTING --> (receive MSG_MAS_DISCONNECTED) -->
     * STATE_DISCONNECTED
     */
    @Test
    public void testStateTransitionFromConnectingToDisconnected() {
        Log.i(TAG, "in testStateTransitionFromConnectingToDisconnected");
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_DISCONNECTED);
        mMceStateMachine.sendMessage(msg);

        // Wait until the message is processed and a broadcast request is sent to
        // to MapClientService to change
        // state from STATE_CONNECTING to STATE_DISCONNECTED
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED, mMceStateMachine.getState());
    }

    /**
     * Test transition from STATE_CONNECTING --> (receive MSG_MAS_CONNECTED) --> STATE_CONNECTED
     */
    @Test
    public void testStateTransitionFromConnectingToConnected() {
        Log.i(TAG, "in testStateTransitionFromConnectingToConnected");

        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        // Wait until the message is processed and a broadcast request is sent to
        // to MapClientService to change
        // state from STATE_CONNECTING to STATE_CONNECTED
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mMceStateMachine.getState());
    }

    /**
     * Test transition from STATE_CONNECTING --> (receive MSG_MAS_CONNECTED) --> STATE_CONNECTED -->
     * (receive MSG_MAS_DISCONNECTED) --> STATE_DISCONNECTED
     */
    @Test
    public void testStateTransitionFromConnectedWithMasDisconnected() {
        Log.i(TAG, "in testStateTransitionFromConnectedWithMasDisconnected");

        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        // Wait until the message is processed and a broadcast request is sent to
        // to MapClientService to change
        // state from STATE_CONNECTING to STATE_CONNECTED
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mMceStateMachine.getState());

        msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_DISCONNECTED);
        mMceStateMachine.sendMessage(msg);
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(4)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));

        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED, mMceStateMachine.getState());
    }

    /**
     * Test receiving an empty event report
     */
    @Test
    public void testReceiveEmptyEvent() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        // Wait until the message is processed and a broadcast request is sent to
        // to MapClientService to change
        // state from STATE_CONNECTING to STATE_CONNECTED
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mMceStateMachine.getState());

        // Send an empty notification event, verify the mMceStateMachine is still connected
        Message notification = Message.obtain(mHandler, MceStateMachine.MSG_NOTIFICATION);
        mMceStateMachine.getCurrentState().processMessage(msg);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mMceStateMachine.getState());
    }

    /**
     * Test set message status
     */
    @Test
    public void testSetMessageStatus() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        // Wait until the message is processed and a broadcast request is sent to
        // to MapClientService to change
        // state from STATE_CONNECTING to STATE_CONNECTED
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mMceStateMachine.getState());
        Assert.assertTrue(
                mMceStateMachine.setMessageStatus("123456789AB", BluetoothMapClient.READ));
    }

    /**
     * Test disconnect
     */
    @Test
    public void testDisconnect() {
        setupSdpRecordReceipt();
        doAnswer(invocation -> {
            mMceStateMachine.sendMessage(MceStateMachine.MSG_MAS_DISCONNECTED);
            return null;
        }).when(mMockMasClient).shutdown();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        // Wait until the message is processed and a broadcast request is sent to
        // to MapClientService to change
        // state from STATE_CONNECTING to STATE_CONNECTED
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mMceStateMachine.getState());

        mMceStateMachine.disconnect();
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(4)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED, mMceStateMachine.getState());
    }

    /**
     * Test disconnect timeout
     */
    @Test
    public void testDisconnectTimeout() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        // Wait until the message is processed and a broadcast request is sent to
        // to MapClientService to change
        // state from STATE_CONNECTING to STATE_CONNECTED
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mMceStateMachine.getState());

        mMceStateMachine.disconnect();
        verify(mMockMapClientService,
                after(DISCONNECT_TIMEOUT / 2).times(3)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTING, mMceStateMachine.getState());

        verify(mMockMapClientService,
                timeout(DISCONNECT_TIMEOUT).times(4)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED, mMceStateMachine.getState());
    }

    /**
     * Test sending a message to a phone
     */
    @Test
    public void testSendSMSMessageToPhone() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);
        TestUtils.waitForLooperToFinishScheduledTask(mMceStateMachine.getHandler().getLooper());
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mMceStateMachine.getState());

        String testMessage = "Hello World!";
        Uri[] contacts = new Uri[] {Uri.parse("tel://5551212")};

        verify(mMockMasClient, times(0)).makeRequest(any(RequestPushMessage.class));
        mMceStateMachine.sendMapMessage(contacts, testMessage, null, null);
        verify(mMockMasClient, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .makeRequest(any(RequestPushMessage.class));
    }

    /**
     * Test sending a message to an email
     */
    @Test
    public void testSendSMSMessageToEmail() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);
        TestUtils.waitForLooperToFinishScheduledTask(mMceStateMachine.getHandler().getLooper());
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mMceStateMachine.getState());

        String testMessage = "Hello World!";
        Uri[] contacts = new Uri[] {Uri.parse("mailto://sms-test@google.com")};

        verify(mMockMasClient, times(0)).makeRequest(any(RequestPushMessage.class));
        mMceStateMachine.sendMapMessage(contacts, testMessage, null, null);
        verify(mMockMasClient, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .makeRequest(any(RequestPushMessage.class));
    }

    /**
     * Test message sent successfully
     */
    @Test
    public void testSMSMessageSent() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);
        TestUtils.waitForLooperToFinishScheduledTask(mMceStateMachine.getHandler().getLooper());
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mMceStateMachine.getState());

        RequestPushMessage testRequest =
                new RequestPushMessage(FOLDER_SENT, mTestIncomingSmsBmessage, null, false, false);
        when(mMockRequestPushMessage.getMsgHandle()).thenReturn(mTestMessageSmsHandle);
        when(mMockRequestPushMessage.getBMsg()).thenReturn(mTestIncomingSmsBmessage);
        Message msgSent = Message.obtain(mHandler, MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestPushMessage);

        mMceStateMachine.sendMessage(msgSent);

        TestUtils.waitForLooperToFinishScheduledTask(mMceStateMachine.getHandler().getLooper());
        verify(mMockDatabase,  times(1)).storeMessage(eq(mTestIncomingSmsBmessage),
                eq(mTestMessageSmsHandle), any(), eq(MESSAGE_SEEN));
    }

    /**
     * Preconditions:
     * - In {@code STATE_CONNECTED}.
     * - {@code MSG_SEARCH_OWN_NUMBER_TIMEOUT} has been set.
     * - Next stage of connection process has NOT begun, i.e.:
     *   - Request for Notification Registration not sent
     *   - Request for MessageListing of SENT folder not sent
     *   - Request for MessageListing of INBOX folder not sent
     */
    private void testGetOwnNumber_setup() {
        testStateTransitionFromConnectingToConnected();
        verify(mMockMasClient, after(ASYNC_CALL_TIMEOUT_MILLIS).never()).makeRequest(
                any(RequestSetNotificationRegistration.class));
        verify(mMockMasClient, never()).makeRequest(any(RequestGetMessagesListing.class));
        assertThat(mMceStateMachine.getHandler().hasMessages(
                MceStateMachine.MSG_SEARCH_OWN_NUMBER_TIMEOUT)).isTrue();
    }

    /**
     * Assert whether the next stage of connection process has begun, i.e., whether the following
     * {@link Request} are sent or not:
     * - Request for Notification Registration,
     * - Request for MessageListing of SENT folder (to start downloading),
     * - Request for MessageListing of INBOX folder (to start downloading).
     */
    private void testGetOwnNumber_assertNextStageStarted(boolean hasStarted) {
        if (hasStarted) {
            verify(mMockMasClient).makeRequest(any(RequestSetNotificationRegistration.class));
            verify(mMockMasClient, times(2)).makeRequest(any(RequestGetMessagesListing.class));

            ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
            verify(mMockMasClient, atLeastOnce()).makeRequest(requestCaptor.capture());
            // There will be multiple calls to {@link MasClient#makeRequest} with different
            // {@link Request} subtypes; not all of them will be {@link
            // RequestGetMessagesListing}.
            List<Request> capturedRequests = requestCaptor.getAllValues();
            assertThat(capturedRequests).comparingElementsUsing(GET_FOLDER_NAME).contains(
                    MceStateMachine.FOLDER_INBOX);
            assertThat(capturedRequests).comparingElementsUsing(GET_FOLDER_NAME).contains(
                    MceStateMachine.FOLDER_SENT);
        } else {
            verify(mMockMasClient, never()).makeRequest(
                    any(RequestSetNotificationRegistration.class));
            verify(mMockMasClient, never()).makeRequest(any(RequestGetMessagesListing.class));
        }
    }

    /**
     * Preconditions:
     * - See {@link testGetOwnNumber_setup}.
     *
     * Actions:
     * - Send a {@code MSG_MAS_REQUEST_COMPLETED} with a {@link
     *   RequestGetMessagesListingForOwnNumber} object that has completed its search.
     *
     * Outcome:
     * - {@code MSG_SEARCH_OWN_NUMBER_TIMEOUT} has been cancelled.
     * - Next stage of connection process has begun, i.e.:
     *   - Request for Notification Registration is made.
     *   - Request for MessageListing of SENT folder is made (to start downloading).
     *   - Request for MessageListing of INBOX folder is made (to start downloading).
     */
    @Test
    public void testGetOwnNumberCompleted() {
        testGetOwnNumber_setup();

        Message requestCompletedMsg = Message.obtain(mHandler,
                MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestOwnNumberCompletedWithNumber);
        mMceStateMachine.sendMessage(requestCompletedMsg);

        verify(mMockMasClient, after(ASYNC_CALL_TIMEOUT_MILLIS).never()).makeRequest(
                eq(mMockRequestOwnNumberCompletedWithNumber));
        assertThat(mMceStateMachine.getHandler().hasMessages(
                MceStateMachine.MSG_SEARCH_OWN_NUMBER_TIMEOUT)).isFalse();
        testGetOwnNumber_assertNextStageStarted(true);
    }

    /**
     * Preconditions:
     * - See {@link testGetOwnNumber_setup}.
     *
     * Actions:
     * - Send a {@code MSG_SEARCH_OWN_NUMBER_TIMEOUT}.
     *
     * Outcome:
     * - {@link MasClient#abortRequest} invoked on a {@link RequestGetMessagesListingForOwnNumber}.
     * - Any existing {@code MSG_MAS_REQUEST_COMPLETED} (corresponding to a
     *   {@link RequestGetMessagesListingForOwnNumber}) has been dropped.
     * - Next stage of connection process has begun, i.e.:
     *   - Request for Notification Registration is made.
     *   - Request for MessageListing of SENT folder is made (to start downloading).
     *   - Request for MessageListing of INBOX folder is made (to start downloading).
     */
    @Test
    public void testGetOwnNumberTimedOut() {
        testGetOwnNumber_setup();
        Message requestIncompleteMsg = Message.obtain(mHandler,
                MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestOwnNumberIncompleteSearch);
        mMceStateMachine.sendMessage(requestIncompleteMsg);
        assertThat(mMceStateMachine.getHandler().hasMessages(
                MceStateMachine.MSG_MAS_REQUEST_COMPLETED)).isTrue();

        Message timeoutMsg = Message.obtain(mHandler,
                MceStateMachine.MSG_SEARCH_OWN_NUMBER_TIMEOUT,
                mMockRequestOwnNumberIncompleteSearch);
        mMceStateMachine.sendMessage(timeoutMsg);

        verify(mMockMasClient, after(ASYNC_CALL_TIMEOUT_MILLIS)).abortRequest(
                mMockRequestOwnNumberIncompleteSearch);
        assertThat(mMceStateMachine.getHandler().hasMessages(
                MceStateMachine.MSG_MAS_REQUEST_COMPLETED)).isFalse();
        testGetOwnNumber_assertNextStageStarted(true);
    }

    /**
     * Preconditions:
     * - See {@link testGetOwnNumber_setup}.
     *
     * Actions:
     * - Send a {@code MSG_MAS_REQUEST_COMPLETED} with a {@link
     *   RequestGetMessagesListingForOwnNumber} object that has not completed its search.
     *
     * Outcome:
     * - {@link Request} made to continue searching for own number (using existing/same
     *   {@link Request}).
     * - {@code MSG_SEARCH_OWN_NUMBER_TIMEOUT} has not been cancelled.
     * - Next stage of connection process has not begun, i.e.:
     *   - No Request for Notification Registration,
     *   - No Request for MessageListing of SENT folder is made (to start downloading),
     *   - No Request for MessageListing of INBOX folder is made (to start downloading).
     */
    @Test
    public void testGetOwnNumberIncomplete() {
        testGetOwnNumber_setup();

        Message requestIncompleteMsg = Message.obtain(mHandler,
                MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestOwnNumberIncompleteSearch);
        mMceStateMachine.sendMessage(requestIncompleteMsg);

        verify(mMockMasClient, after(ASYNC_CALL_TIMEOUT_MILLIS)).makeRequest(
                eq(mMockRequestOwnNumberIncompleteSearch));
        assertThat(mMceStateMachine.getHandler().hasMessages(
                MceStateMachine.MSG_SEARCH_OWN_NUMBER_TIMEOUT)).isTrue();
        testGetOwnNumber_assertNextStageStarted(false);
    }

    /**
     * Test seen status set for new SMS
     */
     @Test
     public void testReceivedNewSms_messageStoredAsUnseen() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        //verifying that state machine is in the Connected state
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        assertThat(mMceStateMachine.getState()).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        String dateTime = new ObexTime(Instant.now()).toString();
        EventReport event = createNewEventReport("NewMessage", dateTime, mTestMessageSmsHandle,
                "telecom/msg/inbox", null, "SMS_GSM");

        mMceStateMachine.receiveEvent(event);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockMasClient, times(1)).makeRequest
                (any(RequestGetMessage.class));

        msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestGetMessage);
        mMceStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockDatabase, times(1)).storeMessage(eq(mTestIncomingSmsBmessage),
                eq(mTestMessageSmsHandle), any(), eq(MESSAGE_NOT_SEEN));
     }

     /**
     * Test seen status set for new MMS
     */
     @Test
     public void testReceivedNewMms_messageStoredAsUnseen() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        //verifying that state machine is in the Connected state
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        assertThat(mMceStateMachine.getState()).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        String dateTime = new ObexTime(Instant.now()).toString();
        EventReport event = createNewEventReport("NewMessage", dateTime, mTestMessageMmsHandle,
                "telecom/msg/inbox", null, "MMS");

        when(mMockRequestGetMessage.getMessage()).thenReturn(mTestIncomingMmsBmessage);
        when(mMockRequestGetMessage.getHandle()).thenReturn(mTestMessageMmsHandle);

        mMceStateMachine.receiveEvent(event);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockMasClient, times(1)).makeRequest
               (any(RequestGetMessage.class));

        msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestGetMessage);
        mMceStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockDatabase, times(1)).storeMessage(eq(mTestIncomingMmsBmessage),
                eq(mTestMessageMmsHandle), any(), eq(MESSAGE_NOT_SEEN));
     }

     /**
     * Test seen status set in database on initial download
     */
     @Test
     public void testDownloadExistingSms_messageStoredAsSeen() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        assertThat(mMceStateMachine.getState()).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        com.android.bluetooth.mapclient.Message testMessageListingSms = createNewMessage("SMS_GSM",
                mTestMessageSmsHandle);
        ArrayList<com.android.bluetooth.mapclient.Message> messageListSms = new ArrayList<>();
        messageListSms.add(testMessageListingSms);
        when(mMockRequestGetMessagesListing.getList()).thenReturn(messageListSms);

        msg = Message.obtain(mHandler, MceStateMachine.MSG_GET_MESSAGE_LISTING,
                MceStateMachine.FOLDER_INBOX);
        mMceStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockMasClient, times(1)).makeRequest(any(
                RequestGetMessagesListing.class));

        msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestGetMessagesListing);
        mMceStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockMasClient, times(1)).makeRequest(any(
                RequestGetMessage.class));

        msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestGetMessage);
        mMceStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockDatabase, times(1)).storeMessage(any(), any(),
                any(), eq(MESSAGE_SEEN));
     }

     /**
     * Test seen status set in database on initial download
     */
     @Test
     public void testDownloadExistingMms_messageStoredAsSeen() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        assertThat(mMceStateMachine.getState()).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        com.android.bluetooth.mapclient.Message testMessageListingMms = createNewMessage("MMS",
                mTestMessageMmsHandle);
        ArrayList<com.android.bluetooth.mapclient.Message> messageListMms = new ArrayList<>();
        messageListMms.add(testMessageListingMms);

        when(mMockRequestGetMessage.getMessage()).thenReturn(mTestIncomingMmsBmessage);
        when(mMockRequestGetMessage.getHandle()).thenReturn(mTestMessageMmsHandle);
        when(mMockRequestGetMessagesListing.getList()).thenReturn(messageListMms);

        msg = Message.obtain(mHandler, MceStateMachine.MSG_GET_MESSAGE_LISTING,
                MceStateMachine.FOLDER_INBOX);
        mMceStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockMasClient, times(1)).makeRequest(any(
                RequestGetMessagesListing.class));

        msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestGetMessagesListing);
        mMceStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockMasClient, times(1)).makeRequest(any(
                RequestGetMessage.class));

        msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestGetMessage);
        mMceStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockDatabase, times(1)).storeMessage(any(), any(),
                any(), eq(MESSAGE_SEEN));
     }

    /**
     * Test receiving a new message notification.
     */
    @Test
    public void testReceiveNewMessageNotification() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        assertThat(mMceStateMachine.getState()).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        // Receive a new message notification.
        String dateTime = new ObexTime(Instant.now()).toString();
        EventReport event = createNewEventReport("NewMessage", dateTime, mTestMessageSmsHandle,
                "telecom/msg/inbox", null, "SMS_GSM");

        Message notificationMessage =
                Message.obtain(mHandler, MceStateMachine.MSG_NOTIFICATION, (Object)event);

        mMceStateMachine.getCurrentState().processMessage(notificationMessage);

        verify(mMockMasClient,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                        .makeRequest(any(RequestGetMessage.class));

        MceStateMachine.MessageMetadata messageMetadata =
                mMceStateMachine.mMessages.get(mTestMessageSmsHandle);
        Assert.assertEquals(messageMetadata.getHandle(), mTestMessageSmsHandle);
        Assert.assertEquals(
                new ObexTime(Instant.ofEpochMilli(messageMetadata.getTimestamp())).toString(),
                dateTime);
    }

    @Test
    public void testReceivedNewMmsNoSMSDefaultPackage_broadcastToSMSReplyPackage() {
        setupSdpRecordReceipt();
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_CONNECTED);
        mMceStateMachine.sendMessage(msg);

        //verifying that state machine is in the Connected state
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        assertThat(mMceStateMachine.getState()).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        String dateTime = new ObexTime(Instant.now()).toString();
        EventReport event = createNewEventReport("NewMessage", dateTime, mTestMessageSmsHandle,
                "telecom/msg/inbox", null, "SMS_GSM");

        mMceStateMachine.receiveEvent(event);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockMasClient, times(1)).makeRequest
                (any(RequestGetMessage.class));

        msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_REQUEST_COMPLETED,
                mMockRequestGetMessage);
        mMceStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToBeIdle(mMceStateMachine.getHandler().getLooper());
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendBroadcast(
                mIntentArgument.capture(),
                eq(android.Manifest.permission.RECEIVE_SMS));
        Assert.assertNull(mIntentArgument.getValue().getPackage());
    }

    private void setupSdpRecordReceipt() {
        // Perform first part of MAP connection logic.
        verify(mMockMapClientService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING, mMceStateMachine.getState());

        // Setup receipt of SDP record
        SdpMasRecord record = new SdpMasRecord(1, 1, 1, 1, 1, 1, "MasRecord");
        Message msg = Message.obtain(mHandler, MceStateMachine.MSG_MAS_SDP_DONE, record);
        mMceStateMachine.sendMessage(msg);
    }

    private class MockSmsContentProvider extends MockContentProvider {
        Map<Uri, ContentValues> mContentValues = new HashMap<>();
        int mInsertOperationCount = 0;

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            mInsertOperationCount++;
            return Uri.withAppendedPath(Sms.CONTENT_URI, String.valueOf(mInsertOperationCount));
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            Cursor cursor = Mockito.mock(Cursor.class);

            when(cursor.moveToFirst()).thenReturn(true);
            when(cursor.moveToNext()).thenReturn(true).thenReturn(false);

            when(cursor.getLong(anyInt())).thenReturn((long) mContentValues.size());
            when(cursor.getString(anyInt())).thenReturn(String.valueOf(mContentValues.size()));
            return cursor;
        }
    }

    private static String getFolderNameFromRequestGetMessagesListing(
            Request request) {
        Log.d(TAG, "getFolderName, Request type=" + request);
        String folderName = null;
        if (request instanceof RequestGetMessagesListing) {
            try {
                folderName = (String) request.mHeaderSet.getHeader(HeaderSet.NAME);
            } catch (Exception e) {
                Log.e(TAG, "in getFolderNameFromRequestGetMessagesListing", e);
            }
        }
        Log.d(TAG, "getFolderName, name=" + folderName);
        return folderName;
    }

    // create new Messages from given input
    com.android.bluetooth.mapclient.Message createNewMessage(String mType, String mHandle){
        HashMap<String, String> attrs = new HashMap<String, String>();

        attrs.put("type", mType);
        attrs.put("handle", mHandle);
        attrs.put("datetime", "20230223T160000");

        com.android.bluetooth.mapclient.Message message = new com.android.bluetooth.mapclient.
                Message(attrs);

        return message;
    }

    EventReport createNewEventReport(String mType, String mDateTime, String mHandle, String mFolder,
            String mOldFolder, String mMsgType){

        HashMap<String, String> attrs = new HashMap<String, String>();

        attrs.put("type", mType);
        attrs.put("datetime", mDateTime);
        attrs.put("handle", mHandle);
        attrs.put("folder", mFolder);
        attrs.put("old_folder", mOldFolder);
        attrs.put("msg_type", mMsgType);

        EventReport event = new EventReport(attrs);

        return event;

    }

    //create new Bmessages for testing
    void createTestMessages() {
        mOriginator = new VCardEntry();
        VCardProperty property = new VCardProperty();
        property.setName(VCardConstants.PROPERTY_TEL);
        property.addValues("555-1212");
        mOriginator.addProperty(property);

        mTestIncomingSmsBmessage = new Bmessage();
        mTestIncomingSmsBmessage.setBodyContent("HelloWorld");
        mTestIncomingSmsBmessage.setType(Bmessage.Type.SMS_GSM);
        mTestIncomingSmsBmessage.setFolder("telecom/msg/inbox");
        mTestIncomingSmsBmessage.addOriginator(mOriginator);
        mTestIncomingSmsBmessage.addRecipient(mOriginator);

        mTestIncomingMmsBmessage = new Bmessage();
        mTestIncomingMmsBmessage.setBodyContent("HelloWorld");
        mTestIncomingMmsBmessage.setType(Bmessage.Type.MMS);
        mTestIncomingMmsBmessage.setFolder("telecom/msg/inbox");
        mTestIncomingMmsBmessage.addOriginator(mOriginator);
        mTestIncomingMmsBmessage.addRecipient(mOriginator);
    }
}
