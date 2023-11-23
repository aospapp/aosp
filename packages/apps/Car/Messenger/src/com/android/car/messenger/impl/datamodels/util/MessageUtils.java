/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.messenger.impl.datamodels.util;

import static com.android.car.messenger.common.Conversation.Message.MessageStatus.MESSAGE_STATUS_NONE;
import static com.android.car.messenger.common.Conversation.Message.MessageStatus.MESSAGE_STATUS_READ;
import static com.android.car.messenger.common.Conversation.Message.MessageStatus.MESSAGE_STATUS_UNREAD;

import static java.lang.Math.min;
import static java.util.Comparator.comparingLong;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony.TextBasedSmsColumns;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.Person;

import com.android.car.messenger.common.Conversation;
import com.android.car.messenger.common.Conversation.Message;
import com.android.car.messenger.common.Conversation.Message.MessageStatus;
import com.android.car.messenger.common.Conversation.Message.MessageType;
import com.android.car.messenger.core.interfaces.AppFactory;
import com.android.car.messenger.core.util.L;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Message Parser that provides useful static methods to parse 1-1 and Group MMS messages. */
public final class MessageUtils {

    /**
     * Returns all messages in the given cursors.
     *
     * @param limit The maximum number of messages
     * @param messageCursors The messageCursors of messages in descending order
     */
    @NonNull
    public static List<Message> getMessages(int limit, @Nullable Cursor... messageCursors) {
        List<Message> messages = new ArrayList<>();
        for (Cursor cursor : messageCursors) {
            MessageUtils.forEachDesc(
                    cursor,
                    message -> {
                        messages.add(message);
                        return true;
                    });
        }
        messages.sort(comparingLong(Message::getTimestamp).reversed());
        return messages.subList(0, min(limit, messages.size()));
    }

    /**
     * Returns unread messages from a conversation, in ascending order.
     *
     * @param messages The messages in descending order
     */
    @NonNull
    public static List<Message> getUnreadMessages(@NonNull List<Message> messages) {
        int i = 0;
        for (Conversation.Message message : messages) {
            if (message.getMessageStatus() != MessageStatus.MESSAGE_STATUS_UNREAD) {
                break;
            }
            i++;
        }
        List<Message> unreadMessages = messages.subList(0, i);
        unreadMessages.sort(comparingLong(Conversation.Message::getTimestamp));
        return unreadMessages;
    }


    /**
     * Gets Read Messages and Last Reply
     *
     * @param messages List of messages in descending order
     */
    @NonNull
    public static Pair<List<Message>, Message> getReadMessagesAndReplyTimestamp(
            @Nullable List<Message> messages) {
        List<Message> readMessages = new ArrayList<>();
        AtomicReference<Message> replyMessage = new AtomicReference<>();
        AtomicReference<Long> lastReply = new AtomicReference<>(0L);

        for (Message message : messages) {
            // Desired impact: 4. Reply -> 3. Messages -> 2. Reply -> 1 Messages (stop
            // parsing at 2.)
            // lastReply references 4., messages references 3.
            // Desired impact: 3. Messages -> 2. Reply -> 1. Messages (stop parsing at 2.)
            // lastReply references 2., messages references 3.
            int messageStatus = message.getMessageStatus();
            if (message.getMessageType() == MessageType.MESSAGE_TYPE_SENT) {
                if (lastReply.get() < message.getTimestamp()) {
                    lastReply.set(message.getTimestamp());
                    replyMessage.set(message);
                }
                if (!readMessages.isEmpty()) {
                    break;
                }
            } else if (messageStatus == MessageStatus.MESSAGE_STATUS_READ
                    || messageStatus == MessageStatus.MESSAGE_STATUS_NONE) {
                readMessages.add(message);
            } else {
                break;
            }
        }
        readMessages.sort(comparingLong(Message::getTimestamp));
        return new Pair<>(readMessages, replyMessage.get());
    }

    /**
     * Parses each message in the cursor and returns the item for further processing
     *
     * @param messageCursor The message cursor to be parsed for SMS and MMS messages
     * @param processor A consumer that takes in the {@link Message} and returns true for the method
     *     to continue parsing the cursor or false to return.
     */
    private static void forEachDesc(
            @Nullable Cursor messageCursor, @NonNull Function<Message, Boolean> processor) {
        if (messageCursor == null || !messageCursor.moveToFirst()) {
            return;
        }
        Context context = AppFactory.get().getContext();
        boolean moveToNext = true;
        boolean hasBeenRepliedTo = false;
        do {
            Message message;
            try {
                message = parseMessageAtPoint(context, messageCursor, hasBeenRepliedTo);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
                L.d("Message was not able to be parsed. Skipping.");
                continue;
            }
            if (message.getText().trim().isEmpty()) {
                // There are occasions where a user may send
                // a text message plus an image or audio and
                // bluetooth will post two messages to the database (b/182834412),
                // one with a text and one blank
                // This leads to boomerang notifications, one with text and one that is empty.
                // Validating or removing messages when blank is a mitigation on our end.
                L.d("Message is blank. Skipped. ");
                continue;
            }
            if (message.getMessageType() == MessageType.MESSAGE_TYPE_SENT) {
                hasBeenRepliedTo = true;
            }
            moveToNext = processor.apply(message);
        } while (messageCursor.moveToNext() && moveToNext);
    }

    /**
     * Parses message at the point in cursor.
     *
     * @throws IllegalArgumentException if desired columns are missing.
     * @see CursorUtils#CONTENT_CONVERSATION_PROJECTION
     */
    @NonNull
    private static Conversation.Message parseMessageAtPoint(
            @NonNull Context context, @NonNull Cursor cursor, boolean userHasReplied) {
        MmsSmsMessage msg =
                MmsUtils.isMms(cursor)
                        ? MmsUtils.parseMms(context, cursor)
                        : SmsUtils.parseSms(cursor);
        Person person =
                ContactUtils.getPerson(context, msg.mPhoneNumber, /* processParticipant= */ null);
        Conversation.Message message =
                new Conversation.Message(msg.mBody, msg.mDate.toEpochMilli(), person);
        if (msg.mType == TextBasedSmsColumns.MESSAGE_TYPE_SENT) {
            message.setMessageType(MessageType.MESSAGE_TYPE_SENT);
            message.setMessageStatus(MESSAGE_STATUS_NONE);
        } else {
            int status =
                    (msg.mRead || userHasReplied) ? MESSAGE_STATUS_READ : MESSAGE_STATUS_UNREAD;
            message.setMessageType(MessageType.MESSAGE_TYPE_INBOX);
            message.setMessageStatus(status);
        }
        return message;
    }

    private MessageUtils() {}
}
