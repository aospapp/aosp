/*******************************************************************************
 *      Copyright (C) 2012 Google Inc.
 *      Licensed to The Android Open Source Project.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 *******************************************************************************/

package com.android.mail.ui;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.ListFragment;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.IdRes;
import android.support.annotation.LayoutRes;
import android.support.v7.app.ActionBar;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;

import com.android.mail.ConversationListContext;
import com.android.mail.R;
import com.android.mail.providers.Account;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider.ConversationListIcon;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

/**
 * Controller for two-pane Mail activity. Two Pane is used for tablets, where screen real estate
 * abounds.
 */
public final class TwoPaneController extends AbstractActivityController implements
        ConversationViewFrame.DownEventListener {

    private static final String SAVED_MISCELLANEOUS_VIEW = "saved-miscellaneous-view";
    private static final String SAVED_MISCELLANEOUS_VIEW_TRANSACTION_ID =
            "saved-miscellaneous-view-transaction-id";

    private TwoPaneLayout mLayout;
    @Deprecated
    private Conversation mConversationToShow;

    /**
     * 2-pane, in wider configurations, allows peeking at a conversation view without having the
     * conversation marked-as-read as far as read/unread state goes.<br>
     * <br>
     * This flag applies to {@link AbstractActivityController#mCurrentConversation} and indicates
     * that the current conversation, if set, is in a 'peeking' state. If there is no current
     * conversation, peeking is implied (in certain view configurations) and this value is
     * meaningless.
     */
    // TODO: save in instance state
    private boolean mCurrentConversationJustPeeking;

    /**
     * Used to determine whether onViewModeChanged should skip a potential
     * fragment transaction that would remove a miscellaneous view.
     */
    private boolean mSavedMiscellaneousView = false;

    private boolean mIsTabletLandscape;

    public TwoPaneController(MailActivity activity, ViewMode viewMode) {
        super(activity, viewMode);
    }

    public boolean isCurrentConversationJustPeeking() {
        return mCurrentConversationJustPeeking;
    }

    private boolean isConversationOnlyMode() {
        return getCurrentConversation() != null && !isCurrentConversationJustPeeking()
                && !mLayout.shouldShowPreviewPanel();
    }

    /**
     * Display the conversation list fragment.
     */
    private void initializeConversationListFragment() {
        if (Intent.ACTION_SEARCH.equals(mActivity.getIntent().getAction())) {
            if (shouldEnterSearchConvMode()) {
                mViewMode.enterSearchResultsConversationMode();
            } else {
                mViewMode.enterSearchResultsListMode();
            }
        }
        renderConversationList();
    }

    /**
     * Render the conversation list in the correct pane.
     */
    private void renderConversationList() {
        if (mActivity == null) {
            return;
        }
        FragmentTransaction fragmentTransaction = mActivity.getFragmentManager().beginTransaction();
        // Use cross fading animation.
        fragmentTransaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        final ConversationListFragment conversationListFragment =
                ConversationListFragment.newInstance(mConvListContext);
        fragmentTransaction.replace(R.id.conversation_list_pane, conversationListFragment,
                TAG_CONVERSATION_LIST);
        fragmentTransaction.commitAllowingStateLoss();
        // Set default navigation here once the ConversationListFragment is created.
        conversationListFragment.setNextFocusLeftId(
                getClfNextFocusLeftId(getFolderListFragment().isMinimized()));
    }

    @Override
    public boolean doesActionChangeConversationListVisibility(final int action) {
        if (action == R.id.settings
                || action == R.id.compose
                || action == R.id.help_info_menu_item
                || action == R.id.feedback_menu_item) {
            return true;
        }

        return false;
    }

    @Override
    protected boolean isConversationListVisible() {
        return !mLayout.isConversationListCollapsed();
    }

    @Override
    public void showConversationList(ConversationListContext listContext) {
        super.showConversationList(listContext);
        initializeConversationListFragment();
    }

    @Override
    public @LayoutRes int getContentViewResource() {
        return R.layout.two_pane_activity;
    }

    @Override
    public boolean onCreate(Bundle savedState) {
        mLayout = (TwoPaneLayout) mActivity.findViewById(R.id.two_pane_activity);
            if (mLayout == null) {
            // We need the layout for everything. Crash/Return early if it is null.
            LogUtils.wtf(LOG_TAG, "mLayout is null!");
            return false;
        }
        mLayout.setController(this, Intent.ACTION_SEARCH.equals(mActivity.getIntent().getAction()));
        mActivity.getWindow().setBackgroundDrawable(null);
        mIsTabletLandscape = !mActivity.getResources().getBoolean(R.bool.list_collapsible);

        final FolderListFragment flf = getFolderListFragment();
        flf.setMiniDrawerEnabled(true);
        flf.setMinimized(true);

        if (savedState != null) {
            mSavedMiscellaneousView = savedState.getBoolean(SAVED_MISCELLANEOUS_VIEW, false);
            mMiscellaneousViewTransactionId =
                    savedState.getInt(SAVED_MISCELLANEOUS_VIEW_TRANSACTION_ID, -1);
        }

        // 2-pane layout is the main listener of view mode changes, and issues secondary
        // notifications upon animation completion:
        // (onConversationVisibilityChanged, onConversationListVisibilityChanged)
        mViewMode.addListener(mLayout);
        return super.onCreate(savedState);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(SAVED_MISCELLANEOUS_VIEW, mMiscellaneousViewTransactionId >= 0);
        outState.putInt(SAVED_MISCELLANEOUS_VIEW_TRANSACTION_ID, mMiscellaneousViewTransactionId);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus && !mLayout.isConversationListCollapsed()) {
            // The conversation list is visible.
            informCursorVisiblity(true);
        }
    }

    @Override
    public void switchToDefaultInboxOrChangeAccount(Account account) {
        if (mViewMode.isSearchMode()) {
            // We are in an activity on top of the main navigation activity.
            // We need to return to it with a result code that indicates it should navigate to
            // a different folder.
            final Intent intent = new Intent();
            intent.putExtra(AbstractActivityController.EXTRA_ACCOUNT, account);
            mActivity.setResult(Activity.RESULT_OK, intent);
            mActivity.finish();
            return;
        }
        if (mViewMode.getMode() != ViewMode.CONVERSATION_LIST) {
            mViewMode.enterConversationListMode();
        }
        super.switchToDefaultInboxOrChangeAccount(account);
    }

    @Override
    public void onFolderSelected(Folder folder) {
        // It's possible that we are not in conversation list mode
        if (mViewMode.isSearchMode()) {
            // We are in an activity on top of the main navigation activity.
            // We need to return to it with a result code that indicates it should navigate to
            // a different folder.
            final Intent intent = new Intent();
            intent.putExtra(AbstractActivityController.EXTRA_FOLDER, folder);
            mActivity.setResult(Activity.RESULT_OK, intent);
            mActivity.finish();
            return;
        } else if (mViewMode.getMode() != ViewMode.CONVERSATION_LIST) {
            mViewMode.enterConversationListMode();
        }

        setHierarchyFolder(folder);
        super.onFolderSelected(folder);
    }

    public boolean isDrawerOpen() {
        final FolderListFragment flf = getFolderListFragment();
        return flf != null && !flf.isMinimized();
    }

    @Override
    protected void toggleDrawerState() {
        final FolderListFragment flf = getFolderListFragment();
        if (flf == null) {
            LogUtils.w(LOG_TAG, "no drawer to toggle open/closed");
            return;
        }
        flf.setMinimized(!flf.isMinimized());
        mLayout.requestLayout();
        resetActionBarIcon();

        final ConversationListFragment clf = getConversationListFragment();
        if (clf != null) {
            clf.setNextFocusLeftId(getClfNextFocusLeftId(flf.isMinimized()));
        }
    }

    @Override
    public void onViewModeChanged(int newMode) {
        if (!mSavedMiscellaneousView && mMiscellaneousViewTransactionId >= 0) {
            final FragmentManager fragmentManager = mActivity.getFragmentManager();
            fragmentManager.popBackStackImmediate(mMiscellaneousViewTransactionId,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE);
            mMiscellaneousViewTransactionId = -1;
        }
        mSavedMiscellaneousView = false;

        super.onViewModeChanged(newMode);
        if (!isConversationOnlyMode()) {
            mFloatingComposeButton.setVisibility(View.VISIBLE);
        }
        if (newMode != ViewMode.WAITING_FOR_ACCOUNT_INITIALIZATION) {
            // Clear the wait fragment
            hideWaitForInitialization();
        }
        // In conversation mode, if the conversation list is not visible, then the user cannot
        // see the selected conversations. Disable the CAB mode while leaving the selected set
        // untouched.
        // When the conversation list is made visible again, try to enable the CAB
        // mode if any conversations are selected.
        if (newMode == ViewMode.CONVERSATION || newMode == ViewMode.CONVERSATION_LIST
                || ViewMode.isAdMode(newMode)) {
            enableOrDisableCab();
        }
    }

    private @IdRes int getClfNextFocusLeftId(boolean drawerMinimized) {
        return (drawerMinimized) ? R.id.current_account_avatar : android.R.id.list;
    }

    @Override
    public void onConversationVisibilityChanged(boolean visible) {
        super.onConversationVisibilityChanged(visible);
        if (!visible) {
            mPagerController.hide(false /* changeVisibility */);
        } else if (mConversationToShow != null) {
            mPagerController.show(mAccount, mFolder, mConversationToShow,
                    false /* changeVisibility */);
            mConversationToShow = null;
        }
    }

    @Override
    public void onConversationListVisibilityChanged(boolean visible) {
        super.onConversationListVisibilityChanged(visible);
        enableOrDisableCab();
    }

    @Override
    public void resetActionBarIcon() {
        final ActionBar ab = mActivity.getSupportActionBar();
        final boolean isChildFolder = getFolder() != null && !Utils.isEmpty(getFolder().parent);
        if (isConversationOnlyMode() || isChildFolder) {
            ab.setHomeAsUpIndicator(R.drawable.ic_arrow_back_wht_24dp);
            ab.setHomeActionContentDescription(0 /* system default */);
        } else {
            ab.setHomeAsUpIndicator(R.drawable.ic_drawer);
            ab.setHomeActionContentDescription(
                    isDrawerOpen() ? R.string.drawer_close : R.string.drawer_open);
        }
    }

    /**
     * Enable or disable the CAB mode based on the visibility of the conversation list fragment.
     */
    private void enableOrDisableCab() {
        if (mLayout.isConversationListCollapsed()) {
            disableCabMode();
        } else {
            enableCabMode();
        }
    }

    @Override
    public void onSetPopulated(ConversationSelectionSet set) {
        super.onSetPopulated(set);

        boolean showSenderImage =
                (mAccount.settings.convListIcon == ConversationListIcon.SENDER_IMAGE);
        if (!showSenderImage && mViewMode.isListMode()) {
            getConversationListFragment().setChoiceNone();
        }
    }

    @Override
    public void onSetEmpty() {
        super.onSetEmpty();

        boolean showSenderImage =
                (mAccount.settings.convListIcon == ConversationListIcon.SENDER_IMAGE);
        if (!showSenderImage && mViewMode.isListMode()) {
            getConversationListFragment().revertChoiceMode();
        }
    }

    @Override
    protected void showConversation(Conversation conversation, boolean markAsRead) {
        super.showConversation(conversation, markAsRead);

        // 2-pane can ignore inLoaderCallbacks because it doesn't use
        // FragmentManager.popBackStack().

        if (mActivity == null) {
            return;
        }
        if (conversation == null) {
            handleBackPress();
            return;
        }
        // If conversation list is not visible, then the user cannot see the CAB mode, so exit it.
        // This is needed here (in addition to during viewmode changes) because orientation changes
        // while viewing a conversation don't change the viewmode: the mode stays
        // ViewMode.CONVERSATION and yet the conversation list goes in and out of visibility.
        enableOrDisableCab();

        // close the drawer, if open
        if (isDrawerOpen()) {
            toggleDrawerState();
        }

        // When a mode change is required, wait for onConversationVisibilityChanged(), the signal
        // that the mode change animation has finished, before rendering the conversation.
        mConversationToShow = conversation;
        mCurrentConversationJustPeeking = !markAsRead;

        final int mode = mViewMode.getMode();
        LogUtils.i(LOG_TAG, "IN TPC.showConv, oldMode=%s conv=%s", mode, mConversationToShow);
        if (mode == ViewMode.SEARCH_RESULTS_LIST || mode == ViewMode.SEARCH_RESULTS_CONVERSATION) {
            mViewMode.enterSearchResultsConversationMode();
        } else {
            mViewMode.enterConversationMode();
        }
        // load the conversation immediately if we're already in conversation mode
        if (!mLayout.isModeChangePending()) {
            onConversationVisibilityChanged(true);
        } else {
            LogUtils.i(LOG_TAG, "TPC.showConversation will wait for TPL.animationEnd to show!");
        }
    }

    @Override
    public final void onConversationSelected(Conversation conversation, boolean inLoaderCallbacks) {
        super.onConversationSelected(conversation, inLoaderCallbacks);
        // Shift the focus to the conversation in landscape mode
        mPagerController.focusPager();
    }

    @Override
    public void onConversationFocused(Conversation conversation) {
        if (mIsTabletLandscape) {
            showConversation(conversation, false /* markAsRead */);
        }
    }

    @Override
    public void setCurrentConversation(Conversation conversation) {
        // Order is important! We want to calculate different *before* the superclass changes
        // mCurrentConversation, so before super.setCurrentConversation().
        final long oldId = mCurrentConversation != null ? mCurrentConversation.id : -1;
        final long newId = conversation != null ? conversation.id : -1;
        final boolean different = oldId != newId;

        // This call might change mCurrentConversation.
        super.setCurrentConversation(conversation);

        final ConversationListFragment convList = getConversationListFragment();
        if (convList != null && conversation != null) {
            convList.setSelected(conversation.position, different);
        }
    }

    @Override
    public void showWaitForInitialization() {
        super.showWaitForInitialization();

        FragmentTransaction fragmentTransaction = mActivity.getFragmentManager().beginTransaction();
        fragmentTransaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
        fragmentTransaction.replace(R.id.conversation_list_pane, getWaitFragment(), TAG_WAIT);
        fragmentTransaction.commitAllowingStateLoss();
    }

    @Override
    protected void hideWaitForInitialization() {
        final WaitFragment waitFragment = getWaitFragment();
        if (waitFragment == null) {
            // We aren't showing a wait fragment: nothing to do
            return;
        }
        // Remove the existing wait fragment from the back stack.
        final FragmentTransaction fragmentTransaction =
                mActivity.getFragmentManager().beginTransaction();
        fragmentTransaction.remove(waitFragment);
        fragmentTransaction.commitAllowingStateLoss();
        super.hideWaitForInitialization();
        if (mViewMode.isWaitingForSync()) {
            // We should come out of wait mode and display the account inbox.
            loadAccountInbox();
        }
    }

    /**
     * Up works as follows:
     * 1) If the user is in a conversation and:
     *  a) the conversation list is hidden (portrait mode), shows the conv list and
     *  stays in conversation view mode.
     *  b) the conversation list is shown, goes back to conversation list mode.
     * 2) If the user is in search results, up exits search.
     * mode and returns the user to whatever view they were in when they began search.
     * 3) If the user is in conversation list mode, there is no up.
     */
    @Override
    public boolean handleUpPress() {
        if (isConversationOnlyMode()) {
            handleBackPress();
        } else {
            toggleDrawerState();
        }

        return true;
    }

    @Override
    public boolean handleBackPress() {
        // Clear any visible undo bars.
        mToastBar.hide(false, false /* actionClicked */);
        if (isDrawerOpen()) {
            toggleDrawerState();
        } else {
            popView(false);
        }
        return true;
    }

    /**
     * Pops the "view stack" to the last screen the user was viewing.
     *
     * @param preventClose Whether to prevent closing the app if the stack is empty.
     */
    protected void popView(boolean preventClose) {
        // If the user is in search query entry mode, or the user is viewing
        // search results, exit
        // the mode.
        int mode = mViewMode.getMode();
        if (mode == ViewMode.SEARCH_RESULTS_LIST) {
            mActivity.finish();
        } else if (mode == ViewMode.CONVERSATION || mViewMode.isAdMode()) {
            // Go to conversation list.
            mViewMode.enterConversationListMode();
        } else if (mode == ViewMode.SEARCH_RESULTS_CONVERSATION) {
            mViewMode.enterSearchResultsListMode();
        } else {
            // The Folder List fragment can be null for monkeys where we get a back before the
            // folder list has had a chance to initialize.
            final FolderListFragment folderList = getFolderListFragment();
            if (mode == ViewMode.CONVERSATION_LIST && folderList != null
                    && !Folder.isRoot(mFolder)) {
                // If the user navigated via the left folders list into a child folder,
                // back should take the user up to the parent folder's conversation list.
                navigateUpFolderHierarchy();
            // Otherwise, if we are in the conversation list but not in the default
            // inbox and not on expansive layouts, we want to switch back to the default
            // inbox. This fixes b/9006969 so that on smaller tablets where we have this
            // hybrid one and two-pane mode, we will return to the inbox. On larger tablets,
            // we will instead exit the app.
            } else if (!preventClose) {
                // There is nothing else to pop off the stack.
                mActivity.finish();
            }
        }
    }

    @Override
    public void exitSearchMode() {
        final int mode = mViewMode.getMode();
        if (mode == ViewMode.SEARCH_RESULTS_LIST
                || (mode == ViewMode.SEARCH_RESULTS_CONVERSATION
                        && Utils.showTwoPaneSearchResults(mActivity.getApplicationContext()))) {
            mActivity.finish();
        }
    }

    @Override
    public boolean shouldShowFirstConversation() {
        return Intent.ACTION_SEARCH.equals(mActivity.getIntent().getAction())
                && shouldEnterSearchConvMode();
    }

    @Override
    public void onUndoAvailable(ToastBarOperation op) {
        final int mode = mViewMode.getMode();
        final ConversationListFragment convList = getConversationListFragment();

        switch (mode) {
            case ViewMode.SEARCH_RESULTS_LIST:
            case ViewMode.CONVERSATION_LIST:
            case ViewMode.SEARCH_RESULTS_CONVERSATION:
            case ViewMode.CONVERSATION:
                if (convList != null) {
                    mToastBar.show(getUndoClickedListener(convList.getAnimatedAdapter()),
                            Utils.convertHtmlToPlainText
                                (op.getDescription(mActivity.getActivityContext())),
                            R.string.undo,
                            true,  /* replaceVisibleToast */
                            op);
                }
        }
    }

    @Override
    public void onError(final Folder folder, boolean replaceVisibleToast) {
        showErrorToast(folder, replaceVisibleToast);
    }

    @Override
    public boolean isDrawerEnabled() {
        // two-pane has its own drawer-like thing that expands inline from a minimized state.
        return false;
    }

    @Override
    public int getFolderListViewChoiceMode() {
        // By default, we want to allow one item to be selected in the folder list
        return ListView.CHOICE_MODE_SINGLE;
    }

    private int mMiscellaneousViewTransactionId = -1;

    @Override
    public void launchFragment(final Fragment fragment, final int selectPosition) {
        final int containerViewId = TwoPaneLayout.MISCELLANEOUS_VIEW_ID;

        final FragmentManager fragmentManager = mActivity.getFragmentManager();
        if (fragmentManager.findFragmentByTag(TAG_CUSTOM_FRAGMENT) == null) {
            final FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
            fragmentTransaction.addToBackStack(null);
            fragmentTransaction.replace(containerViewId, fragment, TAG_CUSTOM_FRAGMENT);
            mMiscellaneousViewTransactionId = fragmentTransaction.commitAllowingStateLoss();
            fragmentManager.executePendingTransactions();
        }

        if (selectPosition >= 0) {
            getConversationListFragment().setRawSelected(selectPosition, true);
        }
    }

    @Override
    public boolean onInterceptCVDownEvent() {
        // handle a down event on CV by closing the drawer if open
        if (isDrawerOpen()) {
            toggleDrawerState();
            return true;
        }
        return false;
    }

    @Override
    public boolean onInterceptKeyFromCV(int keyCode, KeyEvent keyEvent, boolean navigateAway) {
        // Override left/right key presses in landscape mode.
        if (navigateAway) {
            if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                ConversationListFragment clf = getConversationListFragment();
                if (clf != null) {
                    clf.getListView().requestFocus();
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isTwoPaneLandscape() {
        return mIsTabletLandscape;
    }
}
