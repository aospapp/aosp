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

package android.telephony.ims.cts;

import static android.provider.Telephony.RcsColumns.IS_RCS_TABLE_SCHEMA_CODE_COMPLETE;

import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.net.Uri;
import android.telephony.ims.RcsEvent;
import android.telephony.ims.RcsEventQueryParams;
import android.telephony.ims.RcsEventQueryResult;
import android.telephony.ims.RcsGroupThread;
import android.telephony.ims.RcsGroupThreadEvent;
import android.telephony.ims.RcsGroupThreadIconChangedEvent;
import android.telephony.ims.RcsGroupThreadNameChangedEvent;
import android.telephony.ims.RcsGroupThreadParticipantJoinedEvent;
import android.telephony.ims.RcsGroupThreadParticipantLeftEvent;
import android.telephony.ims.RcsManager;
import android.telephony.ims.RcsMessageStore;
import android.telephony.ims.RcsMessageStoreException;
import android.telephony.ims.RcsParticipant;
import android.telephony.ims.RcsParticipantAliasChangedEvent;

import androidx.test.InstrumentationRegistry;

import com.google.android.collect.Lists;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.function.Predicate;

public class RcsEventTest {
    private RcsMessageStore mRcsMessageStore;

    private long mTimestamp;
    private RcsParticipant mParticipant1;
    private RcsParticipant mParticipant2;
    private RcsGroupThread mGroupThread;

    @BeforeClass
    public static void ensureDefaultSmsApp() {
        DefaultSmsAppHelper.ensureDefaultSmsApp();
    }


    @Before
    public void setupTestEnvironment() throws RcsMessageStoreException {
        // Used to skip tests for production builds without RCS tables, will be removed when
        // IS_RCS_TABLE_SCHEMA_CODE_COMPLETE flag is removed.
        Assume.assumeTrue(IS_RCS_TABLE_SCHEMA_CODE_COMPLETE);

        Context context = InstrumentationRegistry.getTargetContext();
        RcsManager rcsManager = context.getSystemService(RcsManager.class);
        mRcsMessageStore = rcsManager.getRcsMessageStore();

        cleanup();

        mTimestamp = 1234567890;
        mParticipant1 = mRcsMessageStore.createRcsParticipant("403", "p1");
        mParticipant2 = mRcsMessageStore.createRcsParticipant("404", "p2");
        mGroupThread = mRcsMessageStore.createGroupThread(
                Lists.newArrayList(mParticipant1, mParticipant2), "groupName", Uri.EMPTY);

    }

    @AfterClass
    public static void cleanup() {
        // TODO(b/123997749) should clean RCS message store here
    }

    @Test
    public void testCreateRcsEvent_canSaveAndQueryGroupThreadParticipantJoinedEvent()
            throws RcsMessageStoreException {
        RcsGroupThreadParticipantJoinedEvent rcsGroupThreadParticipantJoinedEvent =
                new RcsGroupThreadParticipantJoinedEvent(
                        mTimestamp, mGroupThread, mParticipant1, mParticipant2);

        mRcsMessageStore.persistRcsEvent(rcsGroupThreadParticipantJoinedEvent);

        assertMatchingEventInQuery(
                RcsEventQueryParams.GROUP_THREAD_PARTICIPANT_JOINED_EVENT,
                event -> matches(rcsGroupThreadParticipantJoinedEvent, event));
    }

    @Test
    public void testCreateRcsEvent_canSaveAndQueryGroupThreadNameChangedEvent()
            throws RcsMessageStoreException {
        RcsGroupThreadNameChangedEvent rcsGroupThreadNameChangedEvent =
                new RcsGroupThreadNameChangedEvent(
                        mTimestamp, mGroupThread, mParticipant1, "newName");

        mRcsMessageStore.persistRcsEvent(rcsGroupThreadNameChangedEvent);

        assertMatchingEventInQuery(
                RcsEventQueryParams.GROUP_THREAD_NAME_CHANGED_EVENT,
                event -> matches(rcsGroupThreadNameChangedEvent, event));
    }

    @Test
    public void testCreateRcsEvent_canSaveAndQueryParticipantAliasChangedEvent()
            throws RcsMessageStoreException {
        RcsParticipantAliasChangedEvent rcsParticipantAliasChangedEvent
                = new RcsParticipantAliasChangedEvent(mTimestamp, mParticipant1, "newAlias");

        mRcsMessageStore.persistRcsEvent(rcsParticipantAliasChangedEvent);

        assertMatchingEventInQuery(
                RcsEventQueryParams.PARTICIPANT_ALIAS_CHANGED_EVENT,
                event -> matches(rcsParticipantAliasChangedEvent, event));
    }

    @Test
    public void testCreateRcsEvent_canSaveAndQueryGroupThreadParticipantLeftEvent()
            throws RcsMessageStoreException {
        RcsGroupThreadParticipantLeftEvent rcsGroupThreadParticipantLeftEvent =
                new RcsGroupThreadParticipantLeftEvent(
                        mTimestamp, mGroupThread, mParticipant1, mParticipant2);

        mRcsMessageStore.persistRcsEvent(rcsGroupThreadParticipantLeftEvent);

        assertMatchingEventInQuery(
                RcsEventQueryParams.GROUP_THREAD_PARTICIPANT_LEFT_EVENT,
                event -> matches(rcsGroupThreadParticipantLeftEvent, event));
    }

    @Test
    public void testCreateRcsEvent_canSaveAndQueryGroupThreadIconChangedEvent()
            throws RcsMessageStoreException {
        Uri newIcon = Uri.parse("cool/new/icon");

        RcsGroupThreadIconChangedEvent rcsGroupThreadIconChangedEvent =
                new RcsGroupThreadIconChangedEvent(
                        mTimestamp, mGroupThread, mParticipant1, newIcon);

        mRcsMessageStore.persistRcsEvent(rcsGroupThreadIconChangedEvent);

        assertMatchingEventInQuery(
                RcsEventQueryParams.GROUP_THREAD_ICON_CHANGED_EVENT,
                event -> matches(rcsGroupThreadIconChangedEvent, event));
    }

    private void assertMatchingEventInQuery(int queryMessageType, Predicate<RcsEvent> predicate)
            throws RcsMessageStoreException {
        RcsEventQueryResult queryResult = mRcsMessageStore.getRcsEvents(
                new RcsEventQueryParams.Builder()
                        .setEventType(queryMessageType)
                        .build());

        boolean foundMatch = queryResult.getEvents().stream().anyMatch(predicate);

        assertWithMessage(queryResult.getEvents().toString()).that(foundMatch).isTrue();
    }

    private boolean matches(RcsGroupThreadParticipantJoinedEvent expected, RcsEvent actual) {
        if (!(actual instanceof RcsGroupThreadParticipantJoinedEvent)) {
            return false;
        }
        RcsGroupThreadParticipantJoinedEvent actualParticipantJoinedEvent =
                (RcsGroupThreadParticipantJoinedEvent) actual;

        return matchesGroupThreadEvent(expected, actualParticipantJoinedEvent)
                && actualParticipantJoinedEvent.getJoinedParticipant().getId()
                        == expected.getJoinedParticipant().getId();
    }


    private boolean matches(RcsGroupThreadNameChangedEvent expected, RcsEvent actual) {
        if (!(actual instanceof RcsGroupThreadNameChangedEvent)) {
            return false;
        }
        RcsGroupThreadNameChangedEvent actualGroupThreadNameChangedEvent =
                (RcsGroupThreadNameChangedEvent) actual;

        return matchesGroupThreadEvent(expected, actualGroupThreadNameChangedEvent)
                && actualGroupThreadNameChangedEvent.getNewName().equals(expected.getNewName());
    }

    private boolean matches(RcsGroupThreadParticipantLeftEvent expected, RcsEvent actual) {
        if (!(actual instanceof RcsGroupThreadParticipantLeftEvent)) {
            return false;
        }
        RcsGroupThreadParticipantLeftEvent actualParticipantLeftEvent =
                (RcsGroupThreadParticipantLeftEvent) actual;

        return matchesGroupThreadEvent(expected, actualParticipantLeftEvent)
                && actualParticipantLeftEvent.getLeavingParticipant().getId()
                        == expected.getLeavingParticipant().getId();
    }


    private boolean matches(RcsGroupThreadIconChangedEvent expected, RcsEvent actual) {
        if (!(actual instanceof RcsGroupThreadIconChangedEvent)) {
            return false;
        }
        RcsGroupThreadIconChangedEvent actualIconChangedEvent =
                (RcsGroupThreadIconChangedEvent) actual;

        return matchesGroupThreadEvent(expected, actualIconChangedEvent)
                && actualIconChangedEvent.getNewIcon().equals(expected.getNewIcon());
    }

    private boolean matchesGroupThreadEvent(
            RcsGroupThreadEvent expected, RcsGroupThreadEvent actual) {
        return matchesRcsEventFields(expected, actual)
                && actual.getOriginatingParticipant().getId()
                        == expected.getOriginatingParticipant().getId()
                && actual.getRcsGroupThread().getThreadId()
                        == expected.getRcsGroupThread().getThreadId();
    }

    private boolean matches(RcsParticipantAliasChangedEvent expected, RcsEvent actual) {
        if (!(actual instanceof RcsParticipantAliasChangedEvent)) {
            return false;
        }
        RcsParticipantAliasChangedEvent actualIconChangedEvent =
                (RcsParticipantAliasChangedEvent) actual;

        return matchesRcsEventFields(expected, actual)
                && actualIconChangedEvent.getParticipant().getId()
                        == expected.getParticipant().getId()
                && actualIconChangedEvent.getNewAlias().equals(expected.getNewAlias());
    }

    private boolean matchesRcsEventFields(RcsEvent expected, RcsEvent actual) {
        return actual.getTimestamp() == expected.getTimestamp();
    }
}
