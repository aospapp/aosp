/**
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
import android.support.annotation.LayoutRes;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.mail.R;
import com.android.mail.bitmap.AccountAvatarDrawable;
import com.android.mail.content.ObjectCursor;
import com.android.mail.providers.Account;
import com.android.mail.providers.Folder;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * A smaller version of the account- and folder-switching drawer view for tablet UIs.
 */
public class MiniDrawerView extends LinearLayout {

    private FolderListFragment mController;
    private final int mDrawWidth;
    // use the same dimen as AccountItemView to participate in recycling
    // TODO: but Material account switcher doesn't recycle...
    private final int mAvatarDecodeSize;

    private View mDotdotdot;
    private View mSpacer;

    private AccountItem mCurrentAccount;
    private final List<AccountItem> mRecentAccounts = Lists.newArrayList();

    private final LayoutInflater mInflater;

    private static final int NUM_RECENT_ACCOUNTS = 2;

    public MiniDrawerView(Context context) {
        this(context, null);
    }

    public MiniDrawerView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mDrawWidth = getResources().getDimensionPixelSize(R.dimen.two_pane_drawer_width_mini);
        mAvatarDecodeSize = getResources().getDimensionPixelSize(R.dimen.account_avatar_dimension);

        mInflater = LayoutInflater.from(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mCurrentAccount = new AccountItem((ImageView) findViewById(R.id.current_account_avatar));
        mSpacer = findViewById(R.id.spacer);
        mDotdotdot = findViewById(R.id.dotdotdot);
        mDotdotdot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mController.toggleDrawerState();
            }
        });
    }

    public void setController(FolderListFragment flf) {
        mController = flf;

        if (!mController.isMiniDrawerEnabled()) {
            return;
        }

        // wait for the controller to set these up
        mCurrentAccount.setupDrawable();
    }

    public void refresh() {
        if (mController == null) {
            return;
        }

        final Account currentAccount = mController.getCurrentAccount();
        if (currentAccount != null) {
            mCurrentAccount.setAccount(currentAccount);
        }

        View child;

        // TODO: figure out the N most recent accounts, don't just take the first few
        final int removePos = indexOfChild(mSpacer) + 1;
        if (getChildCount() > removePos) {
            removeViews(removePos, getChildCount() - removePos);
        }
        final Account[] accounts = mController.getAllAccounts();
        int count = 0;
        for (Account a : accounts) {
            if (count >= NUM_RECENT_ACCOUNTS) {
                break;
            }
            if (currentAccount.uri.equals(a.uri)) {
                continue;
            }
            final ImageView iv = (ImageView) mInflater.inflate(
                    R.layout.mini_drawer_recent_account_item, this, false /* attachToRoot */);
            final AccountItem item = new AccountItem(iv);
            item.setupDrawable();
            item.setAccount(a);
            iv.setTag(item);
            addView(iv);
            count++;
        }

        // reset the inbox views for this account
        while ((child=getChildAt(1)) != mDotdotdot) {
            removeView(child);
        }
        final ObjectCursor<Folder> folderCursor = mController.getFoldersCursor();
        if (folderCursor != null && !folderCursor.isClosed()) {
            int pos = -1;
            int numInboxes = 0;
            while (folderCursor.moveToPosition(++pos)) {
                final Folder f = folderCursor.getModel();
                if (f.isInbox()) {
                    final ImageView iv = (ImageView) mInflater.inflate(
                            R.layout.mini_drawer_folder_item, this, false /* attachToRoot */);
                    iv.setTag(new FolderItem(f, iv));
                    addView(iv, 1 + numInboxes);
                    numInboxes++;
                }
            }
        }
    }

    private class FolderItem implements View.OnClickListener {
        public final Folder folder;
        public final ImageView view;

        public FolderItem(Folder f, ImageView iv) {
            folder = f;
            view = iv;
            Folder.setIcon(folder, view);
            view.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            mController.onFolderSelected(folder, "mini-drawer");
        }
    }

    private class AccountItem implements View.OnClickListener {
        private Account mAccount;
        // FIXME: this codepath doesn't use GMS Core, resulting in inconsistent avatars
        // vs. ownerslib. switch to a generic photo getter+listener interface on FLF
        // so these drawables are obtainable regardless of how they are loaded.
        private AccountAvatarDrawable mDrawable;
        public final ImageView view;

        public AccountItem(ImageView iv) {
            view = iv;
            view.setOnClickListener(this);
        }

        public void setupDrawable() {
            mDrawable = new AccountAvatarDrawable(getResources(),
                    mController.getBitmapCache(), mController.getContactResolver());
            mDrawable.setDecodeDimensions(mAvatarDecodeSize, mAvatarDecodeSize);
            view.setImageDrawable(mDrawable);
        }

        public void setAccount(Account acct) {
            mAccount = acct;
            mDrawable.bind(mAccount.getSenderName(), mAccount.getEmailAddress());
        }

        @Override
        public void onClick(View v) {
            mController.onAccountSelected(mAccount);
        }

    }

}
