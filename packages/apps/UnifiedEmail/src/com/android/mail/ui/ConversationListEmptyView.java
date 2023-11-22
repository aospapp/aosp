/*
 * Copyright (C) 2014 Google Inc.
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

package com.android.mail.ui;

import android.content.Context;
import android.support.v4.text.BidiFormatter;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.mail.R;
import com.android.mail.providers.Folder;

/**
 * Empty view for {@link ConversationListFragment}.
 */
public class ConversationListEmptyView extends LinearLayout {

    private ImageView mIcon;
    private TextView mText;

    public ConversationListEmptyView(Context context) {
        this(context, null);
    }

    public ConversationListEmptyView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mIcon = (ImageView) findViewById(R.id.empty_icon);
        mText = (TextView) findViewById(R.id.empty_text);
    }

    /**
     * Initializes the empty view to use the proper icon and text
     * based on the type of folder that will be visible.
     */
    public void setupEmptyView(final Folder folder, final String searchQuery,
            final BidiFormatter bidiFormatter) {
        if (folder == null) {
            setupIconAndText(R.drawable.empty_folders, R.string.empty_folder);
            return;
        }

        if (folder.isInbox()) {
            setupIconAndText(R.drawable.empty_inbox, R.string.empty_inbox);
        } else if (folder.isSearch()) {
            setupIconAndText(R.drawable.empty_search, R.string.empty_search,
                    bidiFormatter.unicodeWrap(searchQuery));
        } else if (folder.isSpam()) {
            setupIconAndText(R.drawable.empty_spam, R.string.empty_spam_folder);
        } else if (folder.isTrash()) {
            setupIconAndText(R.drawable.empty_trash, R.string.empty_trash_folder);
        } else {
            setupIconAndText(R.drawable.empty_folders, R.string.empty_folder);
        }
    }

    private void setupIconAndText(int iconId, int stringId) {
        mIcon.setImageResource(iconId);
        mText.setText(stringId);
    }

    private void setupIconAndText(int iconId, int stringId, String extra) {
        mIcon.setImageResource(iconId);

        final String text = getResources().getString(R.string.empty_search, extra);
        mText.setText(text);
    }
}
