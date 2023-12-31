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

package android.media.bettertogether.cts;

import static androidx.test.ext.truth.os.BundleSubject.assertThat;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaController2;
import android.media.MediaSession2;
import android.media.Session2Command;
import android.media.Session2CommandGroup;
import android.media.Session2Token;
import android.media.cts.TestUtils;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Tests {@link android.media.MediaSession2}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class MediaSession2Test {
    private static final long WAIT_TIME_MS = 300L;

    private static final String TEST_KEY = "test_key";
    private static final String TEST_VALUE = "test_value";

    static Handler sHandler;
    static Executor sHandlerExecutor;
    static final Object sTestLock = new Object();

    private Context mContext;

    @BeforeClass
    public static void setUpThread() {
        synchronized (MediaSession2Test.class) {
            if (sHandler != null) {
                return;
            }
            HandlerThread handlerThread = new HandlerThread("MediaSessionTestBase");
            handlerThread.start();
            sHandler = new Handler(handlerThread.getLooper());
            sHandlerExecutor = (runnable) -> {
                Handler handler;
                synchronized (MediaSession2Test.class) {
                    handler = sHandler;
                }
                if (handler != null) {
                    handler.post(() -> {
                        synchronized (sTestLock) {
                            runnable.run();
                        }
                    });
                }
            };
        }
    }

    @AfterClass
    public static void cleanUpThread() {
        synchronized (MediaSession2Test.class) {
            if (sHandler == null) {
                return;
            }
            sHandler.getLooper().quitSafely();
            sHandler = null;
            sHandlerExecutor = null;
        }
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
    }

    @Test
    public void testBuilder_setIllegalArguments() {
        assertThrows("null context shouldn't be allowed",
                IllegalArgumentException.class,
                () -> new MediaSession2.Builder(null));

        assertThrows("null id shouldn't be allowed",
                IllegalArgumentException.class, () -> {
                    final MediaSession2.Builder builder = new MediaSession2.Builder(mContext);
                    builder.setId(null);
                });
    }

    @Test
    public void testBuilder_setSessionActivity() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0 /* requestCode */,
                intent, PendingIntent.FLAG_MUTABLE_UNAUDITED /* flags */);
        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionActivity(pendingIntent)
                .build()) {
            // Note: The pendingIntent is set but is never used inside of MediaSession2.
            // TODO: If getter is created, put assertEquals() here.
        }
    }

    @Test
    public void testBuilder_createSessionWithoutId() {
        try (MediaSession2 session = new MediaSession2.Builder(mContext).build()) {
            assertThat(session.getId()).isEqualTo("");
        }
    }

    @Test
    public void testBuilder_createSessionWithDupId() {
        final String dupSessionId = "TEST_SESSION_DUP_ID";
        MediaSession2.Builder builder = new MediaSession2.Builder(mContext).setId(dupSessionId);
        assertThrows("Duplicated id shouldn't be allowed",
                IllegalStateException.class, () -> {
                    MediaSession2 session1 = builder.build();
                    MediaSession2 session2 = builder.build();
                });
    }

    @Test
    public void testBuilder_setExtras_withFrameworkParcelable() {
        final String testKey = "test_key";
        final Session2Token frameworkParcelable = new Session2Token(mContext,
                new ComponentName(mContext, this.getClass()));

        Bundle extras = new Bundle();
        extras.putParcelable(testKey, frameworkParcelable);

        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setExtras(extras)
                .build()) {
            Bundle extrasOut = session.getToken().getExtras();
            assertThat(extrasOut).isNotNull();
            assertThat(extrasOut).containsKey(testKey);
            assertThat(extrasOut).parcelable(testKey).isEqualTo(frameworkParcelable);
        }
    }

    @Test
    public void testBuilder_setExtras_withCustomParcelable() {
        final String testKey = "test_key";
        final CustomParcelable customParcelable = new CustomParcelable(1);

        Bundle extras = new Bundle();
        extras.putParcelable(testKey, customParcelable);

        assertThrows("Custom Parcelables shouldn't be accepted!",
                IllegalArgumentException.class,
                () -> new MediaSession2.Builder(mContext)
                        .setExtras(extras)
                        .build());
    }

    @Test
    public void testSession2Token() {
        final Bundle extras = new Bundle();
        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setExtras(extras)
                .build()) {
            Session2Token token = session.getToken();
            assertThat(token.getUid()).isEqualTo(Process.myUid());
            assertThat(token.getPackageName()).isEqualTo(mContext.getPackageName());
            assertThat(token.getServiceName()).isNull();
            assertThat(token.getType()).isEqualTo(Session2Token.TYPE_SESSION);
            assertThat(token.describeContents()).isEqualTo(0);
            assertThat(token.getExtras().isEmpty()).isTrue();
        }
    }

    @Test
    public void testSession2Token_extrasNotSet() {
        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .build()) {
            Session2Token token = session.getToken();
            assertThat(token.getExtras().isEmpty()).isTrue();
        }
    }

    @Test
    public void testGetConnectedControllers_newController() throws Exception {
        Session2Callback sessionCallback = new Session2Callback();
        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionCallback(sHandlerExecutor, sessionCallback)
                .build()) {
            Controller2Callback callback = new Controller2Callback();
            MediaController2 controller =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setControllerCallback(sHandlerExecutor, callback)
                            .build();
            assertThat(callback.awaitOnConnected(WAIT_TIME_MS)).isTrue();

            List<MediaSession2.ControllerInfo> controllers = session.getConnectedControllers();
            boolean found = false;
            for (MediaSession2.ControllerInfo controllerInfo : controllers) {
                if (Objects.equals(sessionCallback.mController, controllerInfo)) {
                    assertThat(controllerInfo.getUid()).isEqualTo(Process.myUid());
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    public void testGetConnectedControllers_closedController() throws Exception {
        Session2Callback sessionCallback = new Session2Callback();
        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionCallback(sHandlerExecutor, sessionCallback)
                .build()) {
            Controller2Callback callback = new Controller2Callback();
            MediaController2 controller =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setControllerCallback(sHandlerExecutor, callback)
                            .build();
            assertThat(callback.awaitOnConnected(WAIT_TIME_MS)).isTrue();
            controller.close();
            assertThat(sessionCallback.awaitOnDisconnect(WAIT_TIME_MS)).isTrue();

            List<MediaSession2.ControllerInfo> controllers = session.getConnectedControllers();
            assertThat(controllers).doesNotContain(sessionCallback.mController);
        }
    }

    @Test
    public void testSession2Token_writeToParcel() {
        final Bundle extras = new Bundle();
        extras.putString(TEST_KEY, TEST_VALUE);

        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setExtras(extras)
                .build()) {
            Session2Token token = session.getToken();

            Parcel parcel = Parcel.obtain();
            token.writeToParcel(parcel, 0 /* flags */);
            parcel.setDataPosition(0);
            Session2Token tokenOut = Session2Token.CREATOR.createFromParcel(parcel);
            parcel.recycle();

            assertThat(tokenOut.getUid()).isEqualTo(Process.myUid());
            assertThat(tokenOut.getPackageName()).isEqualTo(mContext.getPackageName());
            assertThat(tokenOut.getServiceName()).isNull();
            assertThat(tokenOut.getType()).isEqualTo(Session2Token.TYPE_SESSION);

            Bundle extrasOut = tokenOut.getExtras();
            assertThat(extrasOut).isNotNull();
            assertThat(extrasOut.getString(TEST_KEY)).isEqualTo(TEST_VALUE);
        }
    }

    @Test
    public void testBroadcastSessionCommand() throws Exception {
        Session2Callback sessionCallback = new Session2Callback();

        String commandStr = "test_command";
        Session2Command command = new Session2Command(commandStr, null);

        int resultCode = 100;
        Session2Command.Result commandResult = new Session2Command.Result(resultCode, null);

        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionCallback(sHandlerExecutor, sessionCallback)
                .build()) {

            // 1. Create two controllers with each latch.
            final CountDownLatch latch1 = new CountDownLatch(1);
            Controller2Callback callback1 = new Controller2Callback() {
                @Override
                public Session2Command.Result onSessionCommand(MediaController2 controller,
                        Session2Command command, Bundle args) {
                    if (commandStr.equals(command.getCustomAction())
                            && command.getCustomExtras() == null) {
                        latch1.countDown();
                    }
                    return commandResult;
                }
            };

            MediaController2 controller1 =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setControllerCallback(sHandlerExecutor, callback1)
                            .build();

            final CountDownLatch latch2 = new CountDownLatch(1);
            Controller2Callback callback2 = new Controller2Callback() {
                @Override
                public Session2Command.Result onSessionCommand(MediaController2 controller,
                        Session2Command command, Bundle args) {
                    if (commandStr.equals(command.getCustomAction())
                            && command.getCustomExtras() == null) {
                        latch2.countDown();
                    }
                    return commandResult;
                }
            };
            MediaController2 controller2 =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setControllerCallback(sHandlerExecutor, callback2)
                            .build();

            // 2. Wait until all the controllers are connected.
            assertThat(callback1.awaitOnConnected(WAIT_TIME_MS)).isTrue();
            assertThat(callback2.awaitOnConnected(WAIT_TIME_MS)).isTrue();

            // 3. Call MediaSession2#broadcastSessionCommand() and check both controller's
            // onSessionCommand is called.
            session.broadcastSessionCommand(command, null);
            assertThat(latch1.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(latch2.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    @Test
    public void testCallback_onConnect_onDisconnect() throws Exception {
        Session2Callback sessionCallback = new Session2Callback();
        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionCallback(sHandlerExecutor, sessionCallback)
                .build()) {
            // Test onConnect
            Controller2Callback controllerCallback = new Controller2Callback();
            Bundle testConnectionHints = new Bundle();
            testConnectionHints.putString("test_key", "test_value");

            MediaController2 controller =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setConnectionHints(testConnectionHints)
                            .setControllerCallback(sHandlerExecutor, controllerCallback)
                            .build();
            assertThat(controllerCallback.awaitOnConnected(WAIT_TIME_MS)).isTrue();
            assertThat(sessionCallback.awaitOnConnect(WAIT_TIME_MS)).isTrue();
            assertThat(sessionCallback.mSession).isEqualTo(session);
            MediaSession2.ControllerInfo controllerInfo = sessionCallback.mController;

            // Check whether the controllerInfo is the right one.
            assertThat(controllerInfo.getPackageName()).isEqualTo(mContext.getPackageName());
            MediaSessionManager.RemoteUserInfo remoteUserInfo = controllerInfo.getRemoteUserInfo();
            assertThat(remoteUserInfo.getPid()).isEqualTo(Process.myPid());
            assertThat(remoteUserInfo.getUid()).isEqualTo(Process.myUid());
            assertThat(remoteUserInfo.getPackageName()).isEqualTo(mContext.getPackageName());
            assertThat(TestUtils.equals(testConnectionHints, controllerInfo.getConnectionHints()))
                    .isTrue();

            // Test onDisconnect
            controller.close();
            assertThat(controllerCallback.awaitOnDisconnected(WAIT_TIME_MS)).isTrue();
            assertThat(sessionCallback.awaitOnDisconnect(WAIT_TIME_MS)).isTrue();
            assertThat(sessionCallback.mSession).isEqualTo(session);
            assertThat(sessionCallback.mController).isEqualTo(controllerInfo);
        }
    }

    @Test
    public void testCallback_onPostConnect_connected() throws Exception {
        Session2Callback sessionCallback = new Session2Callback();
        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionCallback(sHandlerExecutor, sessionCallback)
                .build()) {
            Controller2Callback controllerCallback = new Controller2Callback();
            MediaController2 controller =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setControllerCallback(sHandlerExecutor, controllerCallback)
                            .build();
            assertThat(controllerCallback.awaitOnConnected(WAIT_TIME_MS)).isTrue();
            assertThat(sessionCallback.awaitOnPostConnect(WAIT_TIME_MS)).isTrue();
            assertThat(sessionCallback.mController.getUid()).isEqualTo(Process.myUid());
        }
    }

    @Test
    public void testCallback_onPostConnect_rejected() throws Exception {
        Session2Callback sessionCallback = new Session2Callback() {
            @Override
            public Session2CommandGroup onConnect(MediaSession2 session,
                    MediaSession2.ControllerInfo controller) {
                // Reject all
                return null;
            }
        };
        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionCallback(sHandlerExecutor, sessionCallback)
                .build()) {
            Controller2Callback callback = new Controller2Callback();

            MediaController2 controller =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setControllerCallback(sHandlerExecutor, callback)
                            .build();
            assertThat(sessionCallback.awaitOnPostConnect(WAIT_TIME_MS)).isFalse();
        }
    }

    @Test
    public void testCallback_onSessionCommand() {
        Session2Callback sessionCallback = new Session2Callback();

        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionCallback(sHandlerExecutor, sessionCallback)
                .build()) {
            Controller2Callback controllerCallback = new Controller2Callback();
            MediaController2 controller =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setControllerCallback(sHandlerExecutor, controllerCallback)
                            .build();
            // Wait for connection
            assertThat(controllerCallback.awaitOnConnected(WAIT_TIME_MS)).isTrue();
            assertThat(sessionCallback.awaitOnConnect(WAIT_TIME_MS)).isTrue();
            MediaSession2.ControllerInfo controllerInfo = sessionCallback.mController;

            // Test onSessionCommand
            String commandStr = "test_command";
            String commandExtraKey = "test_extra_key";
            String commandExtraValue = "test_extra_value";
            Bundle commandExtra = new Bundle();
            commandExtra.putString(commandExtraKey, commandExtraValue);
            Session2Command command = new Session2Command(commandStr, commandExtra);

            String commandArgKey = "test_arg_key";
            String commandArgValue = "test_arg_value";
            Bundle commandArg = new Bundle();
            commandArg.putString(commandArgKey, commandArgValue);
            controller.sendSessionCommand(command, commandArg);

            assertThat(sessionCallback.awaitOnSessionCommand(WAIT_TIME_MS)).isTrue();
            assertThat(sessionCallback.mSession).isEqualTo(session);
            assertThat(sessionCallback.mController).isEqualTo(controllerInfo);
            assertThat(sessionCallback.mCommand.getCustomAction()).isEqualTo(commandStr);
            assertThat(sessionCallback.mCommand.getCustomExtras().getString(commandExtraKey))
                    .isEqualTo(commandExtraValue);
            assertThat(sessionCallback.mCommandArgs.getString(commandArgKey))
                    .isEqualTo(commandArgValue);

            controller.close();
            assertThat(controllerCallback.awaitOnDisconnected(WAIT_TIME_MS)).isTrue();
        }
    }

    @Test
    public void testCallback_onCommandResult() {
        Session2Callback sessionCallback = new Session2Callback();

        int resultCode = 100;
        String commandResultKey = "test_result_key";
        String commandResultValue = "test_result_value";
        Bundle resultData = new Bundle();
        resultData.putString(commandResultKey, commandResultValue);
        Session2Command.Result commandResult = new Session2Command.Result(resultCode, resultData);

        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionCallback(sHandlerExecutor, sessionCallback)
                .build()) {
            Controller2Callback controllerCallback = new Controller2Callback() {
                @Override
                public Session2Command.Result onSessionCommand(MediaController2 controller,
                        Session2Command command, Bundle args) {
                    return commandResult;
                }
            };
            MediaController2 controller =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setControllerCallback(sHandlerExecutor, controllerCallback)
                            .build();
            // Wait for connection
            assertThat(sessionCallback.awaitOnConnect(WAIT_TIME_MS)).isTrue();
            MediaSession2.ControllerInfo controllerInfo = sessionCallback.mController;

            // Test onCommandResult
            String commandStr = "test_command";
            String commandExtraKey = "test_extra_key";
            String commandExtraValue = "test_extra_value";
            Bundle commandExtra = new Bundle();
            commandExtra.putString(commandExtraKey, commandExtraValue);
            Session2Command command = new Session2Command(commandStr, commandExtra);

            String commandArgKey = "test_arg_key";
            String commandArgValue = "test_arg_value";
            Bundle commandArg = new Bundle();
            commandArg.putString(commandArgKey, commandArgValue);
            session.sendSessionCommand(controllerInfo, command, commandArg);

            assertThat(sessionCallback.awaitOnCommandResult(WAIT_TIME_MS)).isTrue();
            assertThat(sessionCallback.mSession).isEqualTo(session);
            assertThat(sessionCallback.mController).isEqualTo(controllerInfo);
            assertThat(sessionCallback.mCommandResult.getResultCode()).isEqualTo(resultCode);
            assertThat(sessionCallback.mCommandResult.getResultData().getString(commandResultKey))
                    .isEqualTo(commandResultValue);

            controller.close();
            assertThat(controllerCallback.awaitOnDisconnected(WAIT_TIME_MS)).isTrue();
        }
    }

    @Test
    public void testSetPlaybackActive() {
        final boolean testInitialPlaybackActive = true;
        final boolean testPlaybackActive = false;
        Session2Callback sessionCallback = new Session2Callback();
        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionCallback(sHandlerExecutor, sessionCallback)
                .build()) {
            session.setPlaybackActive(testInitialPlaybackActive);
            assertThat(session.isPlaybackActive()).isEqualTo(testInitialPlaybackActive);

            Controller2Callback controllerCallback = new Controller2Callback();
            MediaController2 controller =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setControllerCallback(sHandlerExecutor, controllerCallback)
                            .build();
            // Wait for connection
            assertThat(controllerCallback.awaitOnConnected(WAIT_TIME_MS)).isTrue();

            // Check initial value
            assertThat(controller.isPlaybackActive()).isEqualTo(testInitialPlaybackActive);

            // Change playback active change and wait for changes
            session.setPlaybackActive(testPlaybackActive);
            assertThat(session.isPlaybackActive()).isEqualTo(testPlaybackActive);
            assertThat(controllerCallback.awaitOnPlaybackActiveChanged(WAIT_TIME_MS)).isTrue();

            assertThat(controllerCallback.getNotifiedPlaybackActive())
                    .isEqualTo(testPlaybackActive);
            assertThat(controller.isPlaybackActive()).isEqualTo(testPlaybackActive);

            controller.close();
            assertThat(controllerCallback.awaitOnDisconnected(WAIT_TIME_MS)).isTrue();
        }
    }

    @Test
    public void testCancelSessionCommand() {
        Session2Callback sessionCallback = new Session2Callback();
        try (MediaSession2 session = new MediaSession2.Builder(mContext)
                .setSessionCallback(sHandlerExecutor, sessionCallback)
                .build()) {
            Controller2Callback controllerCallback = new Controller2Callback();
            MediaController2 controller =
                    new MediaController2.Builder(mContext, session.getToken())
                            .setControllerCallback(sHandlerExecutor, controllerCallback)
                            .build();
            // Wait for connection
            assertThat(sessionCallback.awaitOnConnect(WAIT_TIME_MS)).isTrue();
            MediaSession2.ControllerInfo controllerInfo = sessionCallback.mController;

            String commandStr = "test_command_";
            String commandExtraKey = "test_extra_key_";
            String commandExtraValue = "test_extra_value_";
            Bundle commandExtra = new Bundle();
            commandExtra.putString(commandExtraKey, commandExtraValue);
            Session2Command command = new Session2Command(commandStr, commandExtra);

            String commandArgKey = "test_arg_key_";
            String commandArgValue = "test_arg_value_";
            Bundle commandArg = new Bundle();
            commandArg.putString(commandArgKey, commandArgValue);
            synchronized (sTestLock) {
                Object token = session.sendSessionCommand(controllerInfo, command, commandArg);
                session.cancelSessionCommand(controllerInfo, token);
            }
            assertThat(sessionCallback.awaitOnCommandResult(WAIT_TIME_MS)).isTrue();
            assertThat(sessionCallback.mCommandResult.getResultCode())
                    .isEqualTo(Session2Command.Result.RESULT_INFO_SKIPPED);

            controller.close();
            assertThat(controllerCallback.awaitOnDisconnected(WAIT_TIME_MS)).isTrue();
        }
    }

    class Controller2Callback extends MediaController2.ControllerCallback {
        private final CountDownLatch mOnConnectedLatch = new CountDownLatch(1);
        private final CountDownLatch mOnDisconnectedLatch = new CountDownLatch(1);
        private final CountDownLatch mOnPlaybackActiveChangedLatch = new CountDownLatch(1);

        private boolean mPlaybackActive;

        @Override
        public void onConnected(MediaController2 controller,
                Session2CommandGroup allowedCommands) {
            mOnConnectedLatch.countDown();
        }

        @Override
        public void onDisconnected(MediaController2 controller) {
            mOnDisconnectedLatch.countDown();
        }

        @Override
        public void onPlaybackActiveChanged(MediaController2 controller, boolean playbackActive) {
            mPlaybackActive = playbackActive;
            mOnPlaybackActiveChangedLatch.countDown();
        }

        public boolean awaitOnConnected(long waitTimeMs) {
            try {
                return mOnConnectedLatch.await(waitTimeMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        public boolean awaitOnDisconnected(long waitTimeMs) {
            try {
                return mOnDisconnectedLatch.await(waitTimeMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        public boolean awaitOnPlaybackActiveChanged(long waitTimeMs) {
            try {
                return mOnPlaybackActiveChangedLatch.await(waitTimeMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        public boolean getNotifiedPlaybackActive() {
            return mPlaybackActive;
        }
    }

    class Session2Callback extends MediaSession2.SessionCallback {
        private final CountDownLatch mOnConnectLatch = new CountDownLatch(1);
        private final CountDownLatch mOnPostConnectLatch = new CountDownLatch(1);
        private final CountDownLatch mOnDisconnectLatch = new CountDownLatch(1);
        private final CountDownLatch mOnSessionCommandLatch = new CountDownLatch(1);
        private final CountDownLatch mOnCommandResultLatch = new CountDownLatch(1);

        MediaSession2 mSession;
        MediaSession2.ControllerInfo mController;
        Session2Command mCommand;
        Bundle mCommandArgs;
        Session2Command.Result mCommandResult;

        @Override
        public Session2CommandGroup onConnect(MediaSession2 session,
                MediaSession2.ControllerInfo controller) {
            super.onConnect(session, controller);
            if (controller.getUid() != Process.myUid()) {
                return null;
            }
            mSession = session;
            mController = controller;
            mOnConnectLatch.countDown();
            return new Session2CommandGroup.Builder().build();
        }

        @Override
        public void onPostConnect(MediaSession2 session, MediaSession2.ControllerInfo controller) {
            super.onPostConnect(session, controller);
            if (controller.getUid() != Process.myUid()) {
                return;
            }
            mSession = session;
            mController = controller;
            mOnPostConnectLatch.countDown();
        }

        @Override
        public void onDisconnected(MediaSession2 session, MediaSession2.ControllerInfo controller) {
            super.onDisconnected(session, controller);
            if (controller.getUid() != Process.myUid()) {
                return;
            }
            mSession = session;
            mController = controller;
            mOnDisconnectLatch.countDown();
        }

        @Override
        public Session2Command.Result onSessionCommand(MediaSession2 session,
                MediaSession2.ControllerInfo controller, Session2Command command, Bundle args) {
            super.onSessionCommand(session, controller, command, args);
            if (controller.getUid() != Process.myUid()) {
                return null;
            }
            mSession = session;
            mController = controller;
            mCommand = command;
            mCommandArgs = args;
            mOnSessionCommandLatch.countDown();

            int resultCode = 100;
            String commandResultKey = "test_result_key";
            String commandResultValue = "test_result_value";
            Bundle resultData = new Bundle();
            resultData.putString(commandResultKey, commandResultValue);
            Session2Command.Result commandResult =
                    new Session2Command.Result(resultCode, resultData);
            return commandResult;
        }

        @Override
        public void onCommandResult(MediaSession2 session, MediaSession2.ControllerInfo controller,
                Object token, Session2Command command, Session2Command.Result result) {
            super.onCommandResult(session, controller, token, command, result);
            if (controller.getUid() != Process.myUid()) {
                return;
            }
            mSession = session;
            mController = controller;
            mCommand = command;
            mCommandResult = result;
            mOnCommandResultLatch.countDown();
        }

        public boolean awaitOnConnect(long waitTimeMs) {
            try {
                return mOnConnectLatch.await(waitTimeMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        public boolean awaitOnPostConnect(long waitTimeMs) {
            try {
                return mOnPostConnectLatch.await(waitTimeMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        public boolean awaitOnDisconnect(long waitTimeMs) {
            try {
                return mOnDisconnectLatch.await(waitTimeMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        public boolean awaitOnSessionCommand(long waitTimeMs) {
            try {
                return mOnSessionCommandLatch.await(waitTimeMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        public boolean awaitOnCommandResult(long waitTimeMs) {
            try {
                return mOnCommandResultLatch.await(waitTimeMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    /* package */ static class CustomParcelable implements Parcelable {
        public int mValue;

        /* package */ CustomParcelable(int value) {
            mValue = value;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mValue);
        }

        public static final Parcelable.Creator<CustomParcelable> CREATOR =
                new Parcelable.Creator<CustomParcelable>() {
            @Override
            public CustomParcelable createFromParcel(Parcel in) {
                int value = in.readInt();
                return new CustomParcelable(value);
            }

            @Override
            public CustomParcelable[] newArray(int size) {
                return new CustomParcelable[size];
            }
        };
    }
}
