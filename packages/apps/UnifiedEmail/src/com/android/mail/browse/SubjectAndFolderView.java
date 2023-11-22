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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.support.v4.text.BidiFormatter;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.mail.R;
import com.android.mail.browse.ConversationViewHeader.ConversationViewHeaderCallbacks;
import com.android.mail.providers.Account;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Folder;
import com.android.mail.providers.Settings;
import com.android.mail.text.ChangeLabelsSpan;
import com.android.mail.ui.FolderDisplayer;
import com.android.mail.utils.ViewUtils;

/**
 * A TextView that displays the conversation subject and list of folders for the message.
 * The view knows the widest that any of its containing {@link FolderSpan}s can be.
 * They cannot exceed the TextView line width, or else {@link Layout}
 * will split up the spans in strange places.
 */
public class SubjectAndFolderView extends TextView
        implements FolderSpan.FolderSpanDimensions {

    private final int mFolderPadding;
    private final int mFolderPaddingExtraWidth;
    private final int mFolderPaddingAfter;
    private final int mRoundedCornerRadius;
    private final float mFolderSpanTextSize;
    private final int mFolderMarginTop;

    private int mMaxSpanWidth;

    private ConversationFolderDisplayer mFolderDisplayer;

    private String mSubject;

    private boolean mVisibleFolders;

    private ConversationViewAdapter.ConversationHeaderItem mHeaderItem;

    private BidiFormatter mBidiFormatter;

    public SubjectAndFolderView(Context context) {
        this(context, null);
    }

    public SubjectAndFolderView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mVisibleFolders = false;
        mFolderDisplayer = new ConversationFolderDisplayer(getContext(), this);

        final Resources r = getResources();
        mFolderPadding = r.getDimensionPixelOffset(R.dimen.conversation_folder_padding);
        mFolderPaddingExtraWidth = r.getDimensionPixelOffset(
                R.dimen.conversation_folder_padding_extra_width);
        mFolderPaddingAfter = r.getDimensionPixelOffset(
                R.dimen.conversation_folder_padding_after);
        mRoundedCornerRadius = r.getDimensionPixelOffset(
                R.dimen.folder_rounded_corner_radius);
        mFolderSpanTextSize = r.getDimension(R.dimen.conversation_folder_font_size);
        mFolderMarginTop = r.getDimensionPixelOffset(R.dimen.conversation_folder_margin_top);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mMaxSpanWidth = MeasureSpec.getSize(widthMeasureSpec) - getTotalPaddingLeft()
                - getTotalPaddingRight();

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public int getPadding() {
        return mFolderPadding;
    }

    @Override
    public int getPaddingExtraWidth() {
        return mFolderPaddingExtraWidth;
    }

    @Override
    public int getPaddingAfter() {
        return mFolderPaddingAfter;
    }

    @Override
    public int getMaxWidth() {
        return mMaxSpanWidth;
    }

    @Override
    public float getRoundedCornerRadius() {
        return mRoundedCornerRadius;
    }

    @Override
    public float getFolderSpanTextSize() {
        return mFolderSpanTextSize;
    }

    @Override
    public int getMarginTop() {
        return mFolderMarginTop;
    }

    @Override
    public boolean isRtl() {
        return ViewUtils.isViewRtl(this);
    }

    public void setSubject(String subject) {
        mSubject = Conversation.getSubjectForDisplay(getContext(), null /* badgeText */, subject);

        if (!mVisibleFolders) {
            setText(mSubject);
        }
    }

    public void setFolders(
            ConversationViewHeaderCallbacks callbacks, Account account, Conversation conv) {
        mVisibleFolders = true;
        final BidiFormatter bidiFormatter = getBidiFormatter();
        final SpannableStringBuilder sb =
                new SpannableStringBuilder(bidiFormatter.unicodeWrap(mSubject));
        sb.append('\u0020');
        final Settings settings = account.settings;
        final int start = sb.length();
        if (settings.importanceMarkersEnabled && conv.isImportant()) {
            sb.append(".\u0020");
            sb.setSpan(new DynamicDrawableSpan(DynamicDrawableSpan.ALIGN_BASELINE) {
                           @Override
                           public Drawable getDrawable() {
                               Drawable d = getContext().getResources().getDrawable(
                                       R.drawable.ic_email_caret_none_important_unread);
                               d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
                               return d;
                           }
                       },
                    start, start + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        }

        mFolderDisplayer.loadConversationFolders(conv, null /* ignoreFolder */,
                -1 /* ignoreFolderType */);
        mFolderDisplayer.appendFolderSpans(sb, bidiFormatter);

        final int end = sb.length();
        sb.setSpan(new ChangeLabelsSpan(callbacks), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        setText(sb);
        setMovementMethod(LinkMovementMethod.getInstance());
    }

    public void bind(ConversationViewAdapter.ConversationHeaderItem headerItem) {
        mHeaderItem = headerItem;
    }

    private BidiFormatter getBidiFormatter() {
        if (mBidiFormatter == null) {
            final ConversationViewAdapter adapter = mHeaderItem != null
                    ? mHeaderItem.getAdapter() : null;
            if (adapter == null) {
                mBidiFormatter = BidiFormatter.getInstance();
            } else {
                mBidiFormatter = adapter.getBidiFormatter();
            }
        }
        return mBidiFormatter;
    }

    private static class ConversationFolderDisplayer extends FolderDisplayer {

        private final FolderSpan.FolderSpanDimensions mDims;

        public ConversationFolderDisplayer(Context context, FolderSpan.FolderSpanDimensions dims) {
            super(context);
            mDims = dims;
        }

        public void appendFolderSpans(SpannableStringBuilder sb, BidiFormatter bidiFormatter) {
            for (final Folder f : mFoldersSortedSet) {
                final int bgColor = f.getBackgroundColor(mDefaultBgColor);
                final int fgColor = f.getForegroundColor(mDefaultFgColor);
                addSpan(sb, f.name, bgColor, fgColor, bidiFormatter);
            }

            if (mFoldersSortedSet.isEmpty()) {
                final Resources r = mContext.getResources();
                final String name = r.getString(R.string.add_label);
                final int bgColor = r.getColor(R.color.conv_header_add_label_background);
                final int fgColor = r.getColor(R.color.conv_header_add_label_text);
                addSpan(sb, name, bgColor, fgColor, bidiFormatter);
            }
        }

        private void addSpan(SpannableStringBuilder sb, String name,
                int bgColor, int fgColor, BidiFormatter bidiFormatter) {
            final int start = sb.length();
            sb.append(bidiFormatter.unicodeWrap(name));
            final int end = sb.length();

            sb.setSpan(new BackgroundColorSpan(bgColor), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(fgColor), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new FolderSpan(sb, mDims), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
