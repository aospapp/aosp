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

package com.android.car.messenger.core.ui.conversationlist;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import com.android.car.messenger.R;
import com.android.car.messenger.common.Conversation;
import com.android.car.messenger.core.interfaces.AppFactory;
import com.android.car.messenger.core.util.ConversationUtil;

import java.util.Objects;

/** Util class that converts Conversation Item to UIConversationItem */
public class UIConversationItemConverter {

    private UIConversationItemConverter() {}

    /** Converts Conversation Item to UIConversationItem */
    public static UIConversationItem convertToUIConversationItem(
            Conversation conversation, CarUxRestrictions carUxRestrictions) {
        Context context = AppFactory.get().getContext();
        boolean isUnread = conversation.getUnreadCount() > 0;
        long timestamp = ConversationUtil.getConversationTimestamp(conversation);
        boolean isReplied = ConversationUtil.isReplied(conversation);

        Drawable subtitleIcon =
                isReplied
                        ? context.getDrawable(R.drawable.car_ui_icon_reply)
                        : context.getDrawable(R.drawable.ic_subtitle_play);

        boolean showTextPreview =
                (carUxRestrictions.getActiveRestrictions()
                                & CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE)
                        == 0;
        String textPreview = "";
        String textMetadata = "";

        // show a preview when parked
        if (showTextPreview) {
            textPreview = ConversationUtil.getLastMessagePreview(conversation);
            if (isUnread) {
                textMetadata = getNumberOfMoreMessages(context, conversation.getUnreadCount());
            }
        } else {
            if (isUnread) {
                // in place of text preview, we show "tap to read aloud" when unread
                textPreview = context.getString(R.string.tap_to_read_aloud);
                textMetadata = getNumberOfUnreadMessages(context, conversation.getUnreadCount());
            } else if (isReplied) {
                textMetadata = context.getString(R.string.replied);
            } else {
                textMetadata = getNumberOfMessages(context, conversation.getMessages().size());
            }
        }

        return new UIConversationItem(
                conversation.getId(),
                Objects.requireNonNull(conversation.getConversationTitle()),
                textPreview,
                subtitleIcon,
                textMetadata,
                timestamp,
                getConversationAvatar(context, conversation),
                /* showMuteIcon= */ false,
                /* showReplyIcon= */ true,
                /* showPlayIcon= */ false,
                isUnread,
                conversation.isMuted(),
                conversation);
    }

    /**
     * For the text "More Unread Messages", indicates the number of messages remaining after the
     * preview.
     */
    @NonNull
    private static String getNumberOfMoreMessages(
            @NonNull Context context, int noOfUnreadMessages) {
        int remainingMessagesAfterPreview = noOfUnreadMessages - 1;
        if (remainingMessagesAfterPreview == 0) {
            return "";
        }
        if (remainingMessagesAfterPreview == 1) {
            return context.getResources().getQuantityString(R.plurals.more_message, 1);
        }
        return context.getResources()
                .getQuantityString(
                        R.plurals.more_message,
                        remainingMessagesAfterPreview,
                        remainingMessagesAfterPreview);
    }

    @NonNull
    private static String getNumberOfUnreadMessages(
            @NonNull Context context, int noOfUnreadMessages) {
        if (noOfUnreadMessages == 1) {
            return context.getResources().getQuantityString(R.plurals.new_message, 1);
        }
        return context.getResources()
                .getQuantityString(R.plurals.new_message, noOfUnreadMessages, noOfUnreadMessages);
    }

    @NonNull
    private static String getNumberOfMessages(@NonNull Context context, int noOfMessages) {
        if (noOfMessages < 2) {
            return context.getResources().getQuantityString(R.plurals.no_of_message, noOfMessages);
        }
        return context.getResources()
                .getQuantityString(R.plurals.no_of_message, noOfMessages, noOfMessages);
    }

    private static Drawable getConversationAvatar(
            @NonNull Context context, @NonNull Conversation conversation) {
        return (conversation.getConversationIcon() != null)
                ? conversation.getConversationIcon().loadDrawable(context)
                : null;
    }
}
