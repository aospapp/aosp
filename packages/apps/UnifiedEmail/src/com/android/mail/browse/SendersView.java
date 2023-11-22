/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
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

package com.android.mail.browse;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.support.v4.text.BidiFormatter;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.TextAppearanceSpan;

import com.android.mail.R;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.ConversationInfo;
import com.android.mail.providers.ParticipantInfo;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.DividedImageCanvas;
import com.android.mail.utils.ObjectCache;
import com.google.common.base.Objects;
import com.google.common.collect.Maps;

import java.util.ArrayList;
import java.util.Map;

public class SendersView {
    private static final Integer DOES_NOT_EXIST = -5;
    // FIXME(ath): make all of these statics instance variables, and have callers hold onto this
    // instance as long as appropriate (e.g. activity lifetime).
    // no need to listen for configuration changes.
    private static String sSendersSplitToken;
    private static CharSequence sDraftSingularString;
    private static CharSequence sDraftPluralString;
    private static CharSequence sSendingString;
    private static CharSequence sRetryingString;
    private static CharSequence sFailedString;
    private static String sDraftCountFormatString;
    private static CharacterStyle sDraftsStyleSpan;
    private static CharacterStyle sSendingStyleSpan;
    private static CharacterStyle sRetryingStyleSpan;
    private static CharacterStyle sFailedStyleSpan;
    private static TextAppearanceSpan sUnreadStyleSpan;
    private static CharacterStyle sReadStyleSpan;
    private static String sMeSubjectString;
    private static String sMeObjectString;
    private static String sToHeaderString;
    private static String sMessageCountSpacerString;
    public static CharSequence sElidedString;
    private static BroadcastReceiver sConfigurationChangedReceiver;
    private static TextAppearanceSpan sMessageInfoReadStyleSpan;
    private static TextAppearanceSpan sMessageInfoUnreadStyleSpan;
    private static BidiFormatter sBidiFormatter;

    // We only want to have at most 2 Priority to length maps.  This will handle the case where
    // there is a widget installed on the launcher while the user is scrolling in the app
    private static final int MAX_PRIORITY_LENGTH_MAP_LIST = 2;

    // Cache of priority to length maps.  We can't just use a single instance as it may be
    // modified from different threads
    private static final ObjectCache<Map<Integer, Integer>> PRIORITY_LENGTH_MAP_CACHE =
            new ObjectCache<Map<Integer, Integer>>(
                    new ObjectCache.Callback<Map<Integer, Integer>>() {
                        @Override
                        public Map<Integer, Integer> newInstance() {
                            return Maps.newHashMap();
                        }
                        @Override
                        public void onObjectReleased(Map<Integer, Integer> object) {
                            object.clear();
                        }
                    }, MAX_PRIORITY_LENGTH_MAP_LIST);

    public static Typeface getTypeface(boolean isUnread) {
        return isUnread ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    private static synchronized void getSenderResources(
            Context context, final boolean resourceCachingRequired) {
        if (sConfigurationChangedReceiver == null && resourceCachingRequired) {
            sConfigurationChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    sDraftSingularString = null;
                    getSenderResources(context, true);
                }
            };
            context.registerReceiver(sConfigurationChangedReceiver, new IntentFilter(
                    Intent.ACTION_CONFIGURATION_CHANGED));
        }
        if (sDraftSingularString == null) {
            Resources res = context.getResources();
            sSendersSplitToken = res.getString(R.string.senders_split_token);
            sElidedString = res.getString(R.string.senders_elided);
            sDraftSingularString = res.getQuantityText(R.plurals.draft, 1);
            sDraftPluralString = res.getQuantityText(R.plurals.draft, 2);
            sDraftCountFormatString = res.getString(R.string.draft_count_format);
            sMeSubjectString = res.getString(R.string.me_subject_pronoun);
            sMeObjectString = res.getString(R.string.me_object_pronoun);
            sToHeaderString = res.getString(R.string.to_heading);
            sMessageInfoUnreadStyleSpan = new TextAppearanceSpan(context,
                    R.style.MessageInfoUnreadTextAppearance);
            sMessageInfoReadStyleSpan = new TextAppearanceSpan(context,
                    R.style.MessageInfoReadTextAppearance);
            sDraftsStyleSpan = new TextAppearanceSpan(context, R.style.DraftTextAppearance);
            sUnreadStyleSpan = new TextAppearanceSpan(context, R.style.SendersAppearanceUnreadStyle);
            sSendingStyleSpan = new TextAppearanceSpan(context, R.style.SendingTextAppearance);
            sRetryingStyleSpan = new TextAppearanceSpan(context, R.style.RetryingTextAppearance);
            sFailedStyleSpan = new TextAppearanceSpan(context, R.style.FailedTextAppearance);
            sReadStyleSpan = new TextAppearanceSpan(context, R.style.SendersAppearanceReadStyle);
            sMessageCountSpacerString = res.getString(R.string.message_count_spacer);
            sSendingString = res.getString(R.string.sending);
            sRetryingString = res.getString(R.string.message_retrying);
            sFailedString = res.getString(R.string.message_failed);
            sBidiFormatter = BidiFormatter.getInstance();
        }
    }

    public static SpannableStringBuilder createMessageInfo(Context context, Conversation conv,
            final boolean resourceCachingRequired) {
        SpannableStringBuilder messageInfo = new SpannableStringBuilder();

        try {
            ConversationInfo conversationInfo = conv.conversationInfo;
            int sendingStatus = conv.sendingState;
            boolean hasSenders = false;
            // This covers the case where the sender is "me" and this is a draft
            // message, which means this will only run once most of the time.
            for (ParticipantInfo p : conversationInfo.participantInfos) {
                if (!TextUtils.isEmpty(p.name)) {
                    hasSenders = true;
                    break;
                }
            }
            getSenderResources(context, resourceCachingRequired);
            int count = conversationInfo.messageCount;
            int draftCount = conversationInfo.draftCount;
            if (count > 1) {
                messageInfo.append(count + "");
            }
            messageInfo.setSpan(CharacterStyle.wrap(
                    conv.read ? sMessageInfoReadStyleSpan : sMessageInfoUnreadStyleSpan),
                    0, messageInfo.length(), 0);
            if (draftCount > 0) {
                // If we are showing a message count or any draft text and there
                // is at least 1 sender, prepend the sending state text with a
                // comma.
                if (hasSenders || count > 1) {
                    messageInfo.append(sSendersSplitToken);
                }
                SpannableStringBuilder draftString = new SpannableStringBuilder();
                if (draftCount == 1) {
                    draftString.append(sDraftSingularString);
                } else {
                    draftString.append(sDraftPluralString).append(
                            String.format(sDraftCountFormatString, draftCount));
                }
                draftString.setSpan(CharacterStyle.wrap(sDraftsStyleSpan), 0,
                        draftString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                messageInfo.append(draftString);
            }

            boolean showState = sendingStatus == UIProvider.ConversationSendingState.SENDING ||
                    sendingStatus == UIProvider.ConversationSendingState.RETRYING ||
                    sendingStatus == UIProvider.ConversationSendingState.SEND_ERROR;
            if (showState) {
                // If we are showing a message count or any draft text, prepend
                // the sending state text with a comma.
                if (count > 1 || draftCount > 0) {
                    messageInfo.append(sSendersSplitToken);
                }

                SpannableStringBuilder stateSpan = new SpannableStringBuilder();

                if (sendingStatus == UIProvider.ConversationSendingState.SENDING) {
                    stateSpan.append(sSendingString);
                    stateSpan.setSpan(sSendingStyleSpan, 0, stateSpan.length(), 0);
                } else if (sendingStatus == UIProvider.ConversationSendingState.RETRYING) {
                    stateSpan.append(sRetryingString);
                    stateSpan.setSpan(sRetryingStyleSpan, 0, stateSpan.length(), 0);
                } else if (sendingStatus == UIProvider.ConversationSendingState.SEND_ERROR) {
                    stateSpan.append(sFailedString);
                    stateSpan.setSpan(sFailedStyleSpan, 0, stateSpan.length(), 0);
                }
                messageInfo.append(stateSpan);
            }

            // Prepend a space if we are showing other message info text.
            if (count > 1 || (draftCount > 0 && hasSenders) || showState) {
                messageInfo.insert(0, sMessageCountSpacerString);
            }
        } finally {
            if (!resourceCachingRequired) {
                clearResourceCache();
            }
        }

        return messageInfo;
    }

    public static void format(Context context, ConversationInfo conversationInfo,
            String messageInfo, int maxChars, ArrayList<SpannableString> styledSenders,
            ArrayList<String> displayableSenderNames, ArrayList<String> displayableSenderEmails,
            String account, final boolean showToHeader, final boolean resourceCachingRequired) {
        try {
            getSenderResources(context, resourceCachingRequired);
            format(context, conversationInfo, messageInfo, maxChars, styledSenders,
                    displayableSenderNames, displayableSenderEmails, account,
                    sUnreadStyleSpan, sReadStyleSpan, showToHeader, resourceCachingRequired);
        } finally {
            if (!resourceCachingRequired) {
                clearResourceCache();
            }
        }
    }

    public static void format(Context context, ConversationInfo conversationInfo,
            String messageInfo, int maxChars, ArrayList<SpannableString> styledSenders,
            ArrayList<String> displayableSenderNames, ArrayList<String> displayableSenderEmails,
            String account, final TextAppearanceSpan notificationUnreadStyleSpan,
            final CharacterStyle notificationReadStyleSpan, final boolean showToHeader,
            final boolean resourceCachingRequired) {
        try {
            getSenderResources(context, resourceCachingRequired);
            handlePriority(maxChars, messageInfo, conversationInfo, styledSenders,
                    displayableSenderNames, displayableSenderEmails, account,
                    notificationUnreadStyleSpan, notificationReadStyleSpan, showToHeader);
        } finally {
            if (!resourceCachingRequired) {
                clearResourceCache();
            }
        }
    }

    private static void handlePriority(int maxChars, String messageInfoString,
            ConversationInfo conversationInfo, ArrayList<SpannableString> styledSenders,
            ArrayList<String> displayableSenderNames, ArrayList<String> displayableSenderEmails,
            String account, final TextAppearanceSpan unreadStyleSpan,
            final CharacterStyle readStyleSpan, final boolean showToHeader) {
        boolean shouldAddPhotos = displayableSenderEmails != null;
        int maxPriorityToInclude = -1; // inclusive
        int numCharsUsed = messageInfoString.length(); // draft, number drafts,
                                                       // count
        int numSendersUsed = 0;
        int numCharsToRemovePerWord = 0;
        int maxFoundPriority = 0;
        if (numCharsUsed > maxChars) {
            numCharsToRemovePerWord = numCharsUsed - maxChars;
        }

        final Map<Integer, Integer> priorityToLength = PRIORITY_LENGTH_MAP_CACHE.get();
        try {
            priorityToLength.clear();
            int senderLength;
            for (ParticipantInfo info : conversationInfo.participantInfos) {
                final String senderName = info.name;
                senderLength = !TextUtils.isEmpty(senderName) ? senderName.length() : 0;
                priorityToLength.put(info.priority, senderLength);
                maxFoundPriority = Math.max(maxFoundPriority, info.priority);
            }
            while (maxPriorityToInclude < maxFoundPriority) {
                if (priorityToLength.containsKey(maxPriorityToInclude + 1)) {
                    int length = numCharsUsed + priorityToLength.get(maxPriorityToInclude + 1);
                    if (numCharsUsed > 0)
                        length += 2;
                    // We must show at least two senders if they exist. If we don't
                    // have space for both
                    // then we will truncate names.
                    if (length > maxChars && numSendersUsed >= 2) {
                        break;
                    }
                    numCharsUsed = length;
                    numSendersUsed++;
                }
                maxPriorityToInclude++;
            }
        } finally {
            PRIORITY_LENGTH_MAP_CACHE.release(priorityToLength);
        }
        // We want to include this entry if
        // 1) The onlyShowUnread flags is not set
        // 2) The above flag is set, and the message is unread
        ParticipantInfo currentParticipant;
        SpannableString spannableDisplay;
        CharacterStyle style;
        boolean appendedElided = false;
        Map<String, Integer> displayHash = Maps.newHashMap();
        String firstDisplayableSenderEmail = null;
        String firstDisplayableSender = null;
        for (int i = 0; i < conversationInfo.participantInfos.size(); i++) {
            currentParticipant = conversationInfo.participantInfos.get(i);
            final String currentEmail = currentParticipant.email;

            final String currentName = currentParticipant.name;
            String nameString = !TextUtils.isEmpty(currentName) ? currentName : "";
            if (nameString.length() == 0) {
                // if we're showing the To: header, show the object version of me.
                nameString = getMe(showToHeader /* useObjectMe */);
            }
            if (numCharsToRemovePerWord != 0) {
                nameString = nameString.substring(0,
                        Math.max(nameString.length() - numCharsToRemovePerWord, 0));
            }

            final int priority = currentParticipant.priority;
            style = CharacterStyle.wrap(currentParticipant.readConversation ? readStyleSpan :
                    unreadStyleSpan);
            if (priority <= maxPriorityToInclude) {
                spannableDisplay = new SpannableString(sBidiFormatter.unicodeWrap(nameString));
                // Don't duplicate senders; leave the first instance, unless the
                // current instance is also unread.
                int oldPos = displayHash.containsKey(currentName) ? displayHash
                        .get(currentName) : DOES_NOT_EXIST;
                // If this sender doesn't exist OR the current message is
                // unread, add the sender.
                if (oldPos == DOES_NOT_EXIST || !currentParticipant.readConversation) {
                    // If the sender entry already existed, and is right next to the
                    // current sender, remove the old entry.
                    if (oldPos != DOES_NOT_EXIST && i > 0 && oldPos == i - 1
                            && oldPos < styledSenders.size()) {
                        // Remove the old one!
                        styledSenders.set(oldPos, null);
                        if (shouldAddPhotos && !TextUtils.isEmpty(currentEmail)) {
                            displayableSenderEmails.remove(currentEmail);
                            displayableSenderNames.remove(currentName);
                        }
                    }
                    displayHash.put(currentName, i);
                    spannableDisplay.setSpan(style, 0, spannableDisplay.length(), 0);
                    styledSenders.add(spannableDisplay);
                }
            } else {
                if (!appendedElided) {
                    spannableDisplay = new SpannableString(sElidedString);
                    spannableDisplay.setSpan(style, 0, spannableDisplay.length(), 0);
                    appendedElided = true;
                    styledSenders.add(spannableDisplay);
                }
            }
            if (shouldAddPhotos) {
                String senderEmail = TextUtils.isEmpty(currentName) ?
                        account :
                            TextUtils.isEmpty(currentEmail) ? currentName : currentEmail;
                if (i == 0) {
                    // Always add the first sender!
                    firstDisplayableSenderEmail = senderEmail;
                    firstDisplayableSender = currentName;
                } else {
                    if (!Objects.equal(firstDisplayableSenderEmail, senderEmail)) {
                        int indexOf = displayableSenderEmails.indexOf(senderEmail);
                        if (indexOf > -1) {
                            displayableSenderEmails.remove(indexOf);
                            displayableSenderNames.remove(indexOf);
                        }
                        displayableSenderEmails.add(senderEmail);
                        displayableSenderNames.add(currentName);
                        if (displayableSenderEmails.size() > DividedImageCanvas.MAX_DIVISIONS) {
                            displayableSenderEmails.remove(0);
                            displayableSenderNames.remove(0);
                        }
                    }
                }
            }
        }
        if (shouldAddPhotos && !TextUtils.isEmpty(firstDisplayableSenderEmail)) {
            if (displayableSenderEmails.size() < DividedImageCanvas.MAX_DIVISIONS) {
                displayableSenderEmails.add(0, firstDisplayableSenderEmail);
                displayableSenderNames.add(0, firstDisplayableSender);
            } else {
                displayableSenderEmails.set(0, firstDisplayableSenderEmail);
                displayableSenderNames.set(0, firstDisplayableSender);
            }
        }
    }

    static String getMe(boolean useObjectMe) {
        return useObjectMe ? sMeObjectString : sMeSubjectString;
    }

    public static SpannableString getFormattedToHeader() {
        final SpannableString formattedToHeader = new SpannableString(sToHeaderString);
        final CharacterStyle readStyle = CharacterStyle.wrap(sReadStyleSpan);
        formattedToHeader.setSpan(readStyle, 0, formattedToHeader.length(), 0);
        return formattedToHeader;
    }

    public static SpannableString getSingularDraftString(Context context) {
        getSenderResources(context, true /* resourceCachingRequired */);
        final SpannableString formattedDraftString = new SpannableString(sDraftSingularString);
        final CharacterStyle readStyle = CharacterStyle.wrap(sDraftsStyleSpan);
        formattedDraftString.setSpan(readStyle, 0, formattedDraftString.length(), 0);
        return formattedDraftString;
    }

    private static void clearResourceCache() {
        sDraftSingularString = null;
    }
}
