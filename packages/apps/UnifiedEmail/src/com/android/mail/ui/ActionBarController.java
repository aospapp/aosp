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

package com.android.mail.ui;

import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.SearchView.OnQueryTextListener;
import android.support.v7.widget.SearchView.OnSuggestionListener;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;

import com.android.mail.ConversationListContext;
import com.android.mail.R;
import com.android.mail.providers.Account;
import com.android.mail.providers.AccountObserver;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Folder;
import com.android.mail.providers.FolderObserver;
import com.android.mail.providers.SearchRecentSuggestionsProvider;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AccountCapabilities;
import com.android.mail.providers.UIProvider.FolderCapabilities;
import com.android.mail.providers.UIProvider.FolderType;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

/**
 * Controller to manage the various states of the {@link android.app.ActionBar}.
 */
public class ActionBarController implements ViewMode.ModeChangeListener,
        OnQueryTextListener, OnSuggestionListener, MenuItemCompat.OnActionExpandListener {

    private final Context mContext;

    protected ActionBar mActionBar;
    protected ControllableActivity mActivity;
    protected ActivityController mController;
    /**
     * The current mode of the ActionBar and Activity
     */
    private ViewMode mViewModeController;

    /**
     * The account currently being shown
     */
    private Account mAccount;
    /**
     * The folder currently being shown
     */
    private Folder mFolder;

    private SearchView mSearchWidget;
    private MenuItem mSearch;
    private MenuItem mEmptyTrashItem;
    private MenuItem mEmptySpamItem;

    /** True if the current device is a tablet, false otherwise. */
    protected final boolean mIsOnTablet;
    private Conversation mCurrentConversation;

    public static final String LOG_TAG = LogTag.getLogTag();

    private FolderObserver mFolderObserver;

    /** Updates the resolver and tells it the most recent account. */
    private final class UpdateProvider extends AsyncTask<Bundle, Void, Void> {
        final Uri mAccount;
        final ContentResolver mResolver;
        public UpdateProvider(Uri account, ContentResolver resolver) {
            mAccount = account;
            mResolver = resolver;
        }

        @Override
        protected Void doInBackground(Bundle... params) {
            mResolver.call(mAccount, UIProvider.AccountCallMethods.SET_CURRENT_ACCOUNT,
                    mAccount.toString(), params[0]);
            return null;
        }
    }

    private final AccountObserver mAccountObserver = new AccountObserver() {
        @Override
        public void onChanged(Account newAccount) {
            updateAccount(newAccount);
        }
    };

    public ActionBarController(Context context) {
        mContext = context;
        mIsOnTablet = Utils.useTabletUI(context.getResources());
    }

    public void expandSearch() {
        if (mSearch != null) {
            MenuItemCompat.expandActionView(mSearch);
        }
    }

    /**
     * Close the search view if it is expanded.
     */
    public void collapseSearch() {
        if (mSearch != null) {
            MenuItemCompat.collapseActionView(mSearch);
        }
    }

    /**
     * Get the search menu item.
     */
    protected MenuItem getSearch() {
        return mSearch;
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        mEmptyTrashItem = menu.findItem(R.id.empty_trash);
        mEmptySpamItem = menu.findItem(R.id.empty_spam);
        mSearch = menu.findItem(R.id.search);

        if (mSearch != null) {
            mSearchWidget = (SearchView) MenuItemCompat.getActionView(mSearch);
            MenuItemCompat.setOnActionExpandListener(mSearch, this);
            SearchManager searchManager = (SearchManager) mActivity.getActivityContext()
                    .getSystemService(Context.SEARCH_SERVICE);
            if (searchManager != null && mSearchWidget != null) {
                SearchableInfo info = searchManager.getSearchableInfo(mActivity.getComponentName());
                mSearchWidget.setSearchableInfo(info);
                mSearchWidget.setOnQueryTextListener(this);
                mSearchWidget.setOnSuggestionListener(this);
                mSearchWidget.setIconifiedByDefault(true);
            }
        }

        // the menu should be displayed if the mode is known
        return getMode() != ViewMode.UNKNOWN;
    }

    public int getOptionsMenuId() {
        switch (getMode()) {
            case ViewMode.UNKNOWN:
                return R.menu.conversation_list_menu;
            case ViewMode.CONVERSATION:
                return R.menu.conversation_actions;
            case ViewMode.CONVERSATION_LIST:
                return R.menu.conversation_list_menu;
            case ViewMode.SEARCH_RESULTS_LIST:
                return R.menu.conversation_list_search_results_actions;
            case ViewMode.SEARCH_RESULTS_CONVERSATION:
                return R.menu.conversation_actions;
            case ViewMode.WAITING_FOR_ACCOUNT_INITIALIZATION:
                return R.menu.wait_mode_actions;
        }
        LogUtils.wtf(LOG_TAG, "Menu requested for unknown view mode");
        return R.menu.conversation_list_menu;
    }

    public void initialize(ControllableActivity activity, ActivityController callback,
            ActionBar actionBar) {
        mActionBar = actionBar;
        mController = callback;
        mActivity = activity;

        mFolderObserver = new FolderObserver() {
            @Override
            public void onChanged(Folder newFolder) {
                onFolderUpdated(newFolder);
            }
        };
        // Return values are purposely discarded. Initialization happens quite early, and we don't
        // have a valid folder, or a valid list of accounts.
        mFolderObserver.initialize(mController);
        updateAccount(mAccountObserver.initialize(activity.getAccountController()));
    }

    private void updateAccount(Account account) {
        final boolean accountChanged = mAccount == null || !mAccount.uri.equals(account.uri);
        mAccount = account;
        if (mAccount != null && accountChanged) {
            final ContentResolver resolver = mActivity.getActivityContext().getContentResolver();
            final Bundle bundle = new Bundle(1);
            bundle.putParcelable(UIProvider.SetCurrentAccountColumns.ACCOUNT, account);
            final UpdateProvider updater = new UpdateProvider(mAccount.uri, resolver);
            updater.execute(bundle);
            setFolderAndAccount();
        }
    }

    /**
     * Called by the owner of the ActionBar to change the current folder.
     */
    public void setFolder(Folder folder) {
        mFolder = folder;
        setFolderAndAccount();
    }

    public void onDestroy() {
        if (mFolderObserver != null) {
            mFolderObserver.unregisterAndDestroy();
            mFolderObserver = null;
        }
        mAccountObserver.unregisterAndDestroy();
    }

    @Override
    public void onViewModeChanged(int newMode) {
        mActivity.supportInvalidateOptionsMenu();
        // Check if we are either on a phone, or in Conversation mode on tablet. For these, the
        // recent folders is enabled.
        switch (getMode()) {
            case ViewMode.UNKNOWN:
                break;
            case ViewMode.CONVERSATION_LIST:
                showNavList();
                break;
            case ViewMode.SEARCH_RESULTS_CONVERSATION:
                mActionBar.setDisplayHomeAsUpEnabled(true);
                setEmptyMode();
                break;
            case ViewMode.CONVERSATION:
            case ViewMode.AD:
                closeSearchField();
                mActionBar.setDisplayHomeAsUpEnabled(true);
                setEmptyMode();
                break;
            case ViewMode.WAITING_FOR_ACCOUNT_INITIALIZATION:
                // We want the user to be able to switch accounts while waiting for an account
                // to sync.
                showNavList();
                break;
        }
    }

    /**
     * Close the search query entry field to avoid keyboard events, and to restore the actionbar
     * to non-search mode.
     */
    private void closeSearchField() {
        if (mSearch == null) {
            return;
        }
        mSearch.collapseActionView();
    }

    protected int getMode() {
        if (mViewModeController != null) {
            return mViewModeController.getMode();
        } else {
            return ViewMode.UNKNOWN;
        }
    }

    /**
     * Helper function to ensure that the menu items that are prone to variable changes and race
     * conditions are properly set to the correct visibility
     */
    public void validateVolatileMenuOptionVisibility() {
        if (mEmptyTrashItem != null) {
            mEmptyTrashItem.setVisible(mAccount != null && mFolder != null
                    && mAccount.supportsCapability(AccountCapabilities.EMPTY_TRASH)
                    && mFolder.isTrash() && mFolder.totalCount > 0
                    && (mController.getConversationListCursor() == null
                    || mController.getConversationListCursor().getCount() > 0));
        }
        if (mEmptySpamItem != null) {
            mEmptySpamItem.setVisible(mAccount != null && mFolder != null
                    && mAccount.supportsCapability(AccountCapabilities.EMPTY_SPAM)
                    && mFolder.isType(FolderType.SPAM) && mFolder.totalCount > 0
                    && (mController.getConversationListCursor() == null
                    || mController.getConversationListCursor().getCount() > 0));
        }
    }

    public boolean onPrepareOptionsMenu(Menu menu) {
        // We start out with every option enabled. Based on the current view, we disable actions
        // that are possible.
        LogUtils.d(LOG_TAG, "ActionBarView.onPrepareOptionsMenu().");

        if (mController.shouldHideMenuItems()) {
            // Shortcut: hide all menu items if the drawer is shown
            final int size = menu.size();

            for (int i = 0; i < size; i++) {
                final MenuItem item = menu.getItem(i);
                item.setVisible(false);
            }
            return false;
        }
        validateVolatileMenuOptionVisibility();

        switch (getMode()) {
            case ViewMode.CONVERSATION:
            case ViewMode.SEARCH_RESULTS_CONVERSATION:
                // We update the ActionBar options when we are entering conversation view because
                // waiting for the AbstractConversationViewFragment to do it causes duplicate icons
                // to show up during the time between the conversation is selected and the fragment
                // is added.
                setConversationModeOptions(menu);
                break;
            case ViewMode.CONVERSATION_LIST:
                // Show search if the account supports it
                Utils.setMenuItemVisibility(menu, R.id.search, mAccount.supportsSearch());
                break;
            case ViewMode.SEARCH_RESULTS_LIST:
                // Hide compose and search
                Utils.setMenuItemVisibility(menu, R.id.compose, false);
                Utils.setMenuItemVisibility(menu, R.id.search, false);
                break;
        }

        return false;
    }

    /**
     * Put the ActionBar in List navigation mode.
     */
    private void showNavList() {
        setTitleModeFlags(ActionBar.DISPLAY_SHOW_TITLE);
        setFolderAndAccount();
    }

    private void setTitle(String title) {
        if (!TextUtils.equals(title, mActionBar.getTitle())) {
            mActionBar.setTitle(title);
        }
    }

    /**
     * Set the actionbar mode to empty: no title, no subtitle, no custom view.
     */
    protected void setEmptyMode() {
        // Disable title/subtitle and the custom view by setting the bitmask to all off.
        setTitleModeFlags(0);
    }

    /**
     * Removes the back button from being shown
     */
    public void removeBackButton() {
        if (mActionBar == null) {
            return;
        }
        // Remove the back button but continue showing an icon.
        final int mask = ActionBar.DISPLAY_HOME_AS_UP | ActionBar.DISPLAY_SHOW_HOME;
        mActionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME, mask);
        mActionBar.setHomeButtonEnabled(false);
    }

    public void setBackButton() {
        if (mActionBar == null) {
            return;
        }
        // Show home as up, and show an icon.
        final int mask = ActionBar.DISPLAY_HOME_AS_UP | ActionBar.DISPLAY_SHOW_HOME;
        mActionBar.setDisplayOptions(mask, mask);
        mActionBar.setHomeButtonEnabled(true);
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        if (mSearch != null) {
            MenuItemCompat.collapseActionView(mSearch);
            mSearchWidget.setQuery("", false);
        }
        mController.executeSearch(query.trim());
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        return false;
    }

    // Next two methods are called when search suggestions are clicked.
    @Override
    public boolean onSuggestionSelect(int position) {
        return onSuggestionClick(position);
    }

    @Override
    public boolean onSuggestionClick(int position) {
        final Cursor c = mSearchWidget.getSuggestionsAdapter().getCursor();
        final boolean haveValidQuery = (c != null) && c.moveToPosition(position);
        if (!haveValidQuery) {
            LogUtils.d(LOG_TAG, "onSuggestionClick: Couldn't get a search query");
            // We haven't handled this query, but the default behavior will
            // leave EXTRA_ACCOUNT un-populated, leading to a crash. So claim
            // that we have handled the event.
            return true;
        }
        collapseSearch();
        // what is in the text field
        String queryText = mSearchWidget.getQuery().toString();
        // What the suggested query is
        String query = c.getString(c.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1));
        // If the text the user typed in is a prefix of what is in the search
        // widget suggestion query, just take the search widget suggestion
        // query. Otherwise, it is a suffix and we want to remove matching
        // prefix portions.
        if (!TextUtils.isEmpty(queryText) && query.indexOf(queryText) != 0) {
            final int queryTokenIndex = queryText
                    .lastIndexOf(SearchRecentSuggestionsProvider.QUERY_TOKEN_SEPARATOR);
            if (queryTokenIndex > -1) {
                queryText = queryText.substring(0, queryTokenIndex);
            }
            // Since we auto-complete on each token in a query, if the query the
            // user typed up until the last token is a substring of the
            // suggestion they click, make sure we don't double include the
            // query text. For example:
            // user types john, that matches john palo alto
            // User types john p, that matches john john palo alto
            // Remove the first john
            // Only do this if we have multiple query tokens.
            if (queryTokenIndex > -1 && !TextUtils.isEmpty(query) && query.contains(queryText)
                    && queryText.length() < query.length()) {
                int start = query.indexOf(queryText);
                query = query.substring(0, start) + query.substring(start + queryText.length());
            }
        }
        mController.executeSearch(query.trim());
        return true;
    }

    /**
     * Uses the current state to update the current folder {@link #mFolder} and the current
     * account {@link #mAccount} shown in the actionbar. Also updates the actionbar subtitle to
     * momentarily display the unread count if it has changed.
     */
    private void setFolderAndAccount() {
        // Very little can be done if the actionbar or activity is null.
        if (mActionBar == null || mActivity == null) {
            return;
        }
        if (ViewMode.isWaitingForSync(getMode())) {
            // Account is not synced: clear title and update the subtitle.
            setTitle("");
            return;
        }
        // Check if we should be changing the actionbar at all, and back off if not.
        final boolean isShowingFolder = mIsOnTablet || ViewMode.isListMode(getMode());
        if (!isShowingFolder) {
            // It isn't necessary to set the title in this case, as the title view will
            // be hidden
            return;
        }
        if (mFolder == null) {
            // Clear the action bar title.  We don't want the app name to be shown while
            // waiting for the folder query to finish
            setTitle("");
            return;
        }
        setTitle(mFolder.name);
    }


    /**
     * Notify that the folder has changed.
     */
    public void onFolderUpdated(Folder folder) {
        if (folder == null) {
            return;
        }
        /** True if we are changing folders. */
        final boolean changingFolders = (mFolder == null || !mFolder.equals(folder));
        mFolder = folder;
        setFolderAndAccount();
        final ConversationListContext listContext = mController == null ? null :
                mController.getCurrentListContext();
        if (changingFolders && !ConversationListContext.isSearchResult(listContext)) {
            closeSearchField();
        }
        // make sure that we re-validate the optional menu items
        validateVolatileMenuOptionVisibility();
    }

    @Override
    public boolean onMenuItemActionExpand(MenuItem item) {
        // Do nothing. Required as part of the interface, we ar only interested in
        // onMenuItemActionCollapse(MenuItem).
        // Have to return true here. Unlike other callbacks, the return value here is whether
        // we want to suppress the action (rather than consume the action). We don't want to
        // suppress the action.
        return true;
    }

    @Override
    public boolean onMenuItemActionCollapse(MenuItem item) {
        // Have to return true here. Unlike other callbacks, the return value
        // here is whether we want to suppress the action (rather than consume the action). We
        // don't want to suppress the action.
        return true;
    }

    /**
     * Sets the actionbar mode: Pass it an integer which contains each of these values, perhaps
     * OR'd together: {@link ActionBar#DISPLAY_SHOW_CUSTOM} and
     * {@link ActionBar#DISPLAY_SHOW_TITLE}. To disable all, pass a zero.
     * @param enabledFlags
     */
    private void setTitleModeFlags(int enabledFlags) {
        final int mask = ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_SHOW_CUSTOM;
        mActionBar.setDisplayOptions(enabledFlags, mask);
    }

    public void setCurrentConversation(Conversation conversation) {
        mCurrentConversation = conversation;
    }

    //We need to do this here instead of in the fragment
    public void setConversationModeOptions(Menu menu) {
        if (mCurrentConversation == null) {
            return;
        }
        final boolean showMarkImportant = !mCurrentConversation.isImportant();
        Utils.setMenuItemVisibility(menu, R.id.mark_important, showMarkImportant
                && mAccount.supportsCapability(UIProvider.AccountCapabilities.MARK_IMPORTANT));
        Utils.setMenuItemVisibility(menu, R.id.mark_not_important, !showMarkImportant
                && mAccount.supportsCapability(UIProvider.AccountCapabilities.MARK_IMPORTANT));
        final boolean isOutbox = mFolder.isType(FolderType.OUTBOX);
        final boolean showDiscardOutbox = mFolder != null && isOutbox &&
                mCurrentConversation.sendingState == UIProvider.ConversationSendingState.SEND_ERROR;
        Utils.setMenuItemVisibility(menu, R.id.discard_outbox, showDiscardOutbox);
        final boolean showDelete = !isOutbox && mFolder != null &&
                mFolder.supportsCapability(UIProvider.FolderCapabilities.DELETE);
        Utils.setMenuItemVisibility(menu, R.id.delete, showDelete);
        // We only want to show the discard drafts menu item if we are not showing the delete menu
        // item, and the current folder is a draft folder and the account supports discarding
        // drafts for a conversation
        final boolean showDiscardDrafts = !showDelete && mFolder != null && mFolder.isDraft() &&
                mAccount.supportsCapability(AccountCapabilities.DISCARD_CONVERSATION_DRAFTS);
        Utils.setMenuItemVisibility(menu, R.id.discard_drafts, showDiscardDrafts);
        final boolean archiveVisible = mAccount.supportsCapability(AccountCapabilities.ARCHIVE)
                && mFolder != null && mFolder.supportsCapability(FolderCapabilities.ARCHIVE)
                && !mFolder.isTrash();
        Utils.setMenuItemVisibility(menu, R.id.archive, archiveVisible);
        Utils.setMenuItemVisibility(menu, R.id.remove_folder, !archiveVisible && mFolder != null
                && mFolder.supportsCapability(FolderCapabilities.CAN_ACCEPT_MOVED_MESSAGES)
                && !mFolder.isProviderFolder()
                && mAccount.supportsCapability(AccountCapabilities.ARCHIVE));
        Utils.setMenuItemVisibility(menu, R.id.move_to, mFolder != null
                && mFolder.supportsCapability(FolderCapabilities.ALLOWS_REMOVE_CONVERSATION));
        Utils.setMenuItemVisibility(menu, R.id.move_to_inbox, mFolder != null
                && mFolder.supportsCapability(FolderCapabilities.ALLOWS_MOVE_TO_INBOX));
        Utils.setMenuItemVisibility(menu, R.id.change_folders, mAccount.supportsCapability(
                UIProvider.AccountCapabilities.MULTIPLE_FOLDERS_PER_CONV));

        final MenuItem removeFolder = menu.findItem(R.id.remove_folder);
        if (mFolder != null && removeFolder != null) {
            removeFolder.setTitle(mActivity.getApplicationContext().getString(
                    R.string.remove_folder, mFolder.name));
        }
        Utils.setMenuItemVisibility(menu, R.id.report_spam,
                mAccount.supportsCapability(AccountCapabilities.REPORT_SPAM) && mFolder != null
                        && mFolder.supportsCapability(FolderCapabilities.REPORT_SPAM)
                        && !mCurrentConversation.spam);
        Utils.setMenuItemVisibility(menu, R.id.mark_not_spam,
                mAccount.supportsCapability(AccountCapabilities.REPORT_SPAM) && mFolder != null
                        && mFolder.supportsCapability(FolderCapabilities.MARK_NOT_SPAM)
                        && mCurrentConversation.spam);
        Utils.setMenuItemVisibility(menu, R.id.report_phishing,
                mAccount.supportsCapability(AccountCapabilities.REPORT_PHISHING) && mFolder != null
                        && mFolder.supportsCapability(FolderCapabilities.REPORT_PHISHING)
                        && !mCurrentConversation.phishing);
        Utils.setMenuItemVisibility(menu, R.id.mute,
                        mAccount.supportsCapability(AccountCapabilities.MUTE) && mFolder != null
                        && mFolder.supportsCapability(FolderCapabilities.DESTRUCTIVE_MUTE)
                        && !mCurrentConversation.muted);
    }

    public void setViewModeController(ViewMode viewModeController) {
        mViewModeController = viewModeController;
        mViewModeController.addListener(this);
    }

    public Context getContext() {
        return mContext;
    }
}
