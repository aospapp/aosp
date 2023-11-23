package com.android.car.media.widgets;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.media.MediaAppConfig;
import com.android.car.media.R;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.source.MediaSource;
import com.android.car.ui.toolbar.MenuItem;
import com.android.car.ui.toolbar.NavButtonMode;
import com.android.car.ui.toolbar.SearchCapabilities;
import com.android.car.ui.toolbar.SearchConfig;
import com.android.car.ui.toolbar.SearchMode;
import com.android.car.ui.toolbar.ToolbarController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Media template application bar. This class wraps a {@link ToolbarController} and
 * adds media-specific methods to it like {@link #setItems} and {@link #setSearchSupported}.
 */
public class AppBarController {

    private static final int MEDIA_UX_RESTRICTION_DEFAULT =
            CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP;
    private static final int MEDIA_UX_RESTRICTION_NONE = CarUxRestrictions.UX_RESTRICTIONS_BASELINE;

    private final int mMaxTabs;
    private final ArrayList<TabBinder<MediaItemMetadata.ArtworkRef>> mTabs = new ArrayList<>();
    private final ToolbarController mToolbarController;
    private final Context mApplicationContext;

    private final boolean mUseSourceLogoForAppSelector;

    private final MenuItem mSearch;
    private final MenuItem mSettings;
    private final MenuItem mEqualizer;
    private final MenuItem mAppSelector;

    @NonNull
    private AppBarListener mListener = new AppBarListener();
    private boolean mSearchSupported;
    private boolean mShowSearchIfSupported;
    private String mSearchQuery;
    private int mSelectedTab = -1;
    private Drawable mLogo;

    /**
     * Application bar listener
     */
    public static class AppBarListener {
        /**
         * Invoked when the user selects an item from the tabs
         */
        protected void onTabSelected(MediaItemMetadata item) {}

        /**
         * Invoked when the user clicks on the settings button.
         */
        protected void onSettingsSelection() {}

        /**
         * Invoked when the user clicks on the equalizer button.
         */
        protected void onEqualizerSelection() {}

        /**
         * Invoked when the user submits a search query.
         */
        protected void onSearch(String query) {}

        /**
         * Invoked when the user clicks on the search button
         */
        protected void onSearchSelection() {}
    }

    public AppBarController(Context context, ToolbarController controller) {
        mToolbarController = controller;
        mApplicationContext = context.getApplicationContext();
        mMaxTabs = context.getResources().getInteger(R.integer.max_tabs);

        mUseSourceLogoForAppSelector =
                context.getResources().getBoolean(R.bool.use_media_source_logo_for_app_selector);

        Intent appSelectorIntent = MediaSource.getSourceSelectorIntent(context, false);

        mToolbarController.registerSearchListener(query -> {
            mSearchQuery = query;
            mListener.onSearch(query);
        });
        mToolbarController.registerSearchCompletedListener(
                () -> mListener.onSearch(mSearchQuery));
        mSearch = MenuItem.builder(context)
                .setToSearch()
                .setOnClickListener(v -> mListener.onSearchSelection())
                .build();
        mSettings = MenuItem.builder(context)
                .setToSettings()
                .setUxRestrictions(MEDIA_UX_RESTRICTION_DEFAULT)
                .setOnClickListener(v -> mListener.onSettingsSelection())
                .build();
        mEqualizer = MenuItem.builder(context)
                .setTitle(R.string.menu_item_sound_settings_title)
                .setIcon(R.drawable.ic_equalizer)
                .setOnClickListener(v -> mListener.onEqualizerSelection())
                .build();
        mAppSelector = MenuItem.builder(context)
                .setTitle(R.string.menu_item_app_selector_title)
                .setTinted(!mUseSourceLogoForAppSelector)
                .setIcon(mUseSourceLogoForAppSelector
                        ? null : context.getDrawable(R.drawable.ic_app_switch))
                .setOnClickListener(m -> context.startActivity(appSelectorIntent))
                .build();
        mToolbarController.setMenuItems(
                Arrays.asList(mSearch, mEqualizer, mSettings, mAppSelector));

        mAppSelector.setVisible(appSelectorIntent != null);
    }

    /**
     * Sets a listener of this application bar events. In order to avoid memory leaks, consumers
     * must reset this reference by setting the listener to null.
     */
    public void setListener(@NonNull AppBarListener listener) {
        mListener = listener;
    }

    /**
     * Updates the list of items to show in the application bar tabs.
     *
     * @param items list of tabs to show, or null if no tabs should be shown.
     */
    public void setItems(@Nullable List<MediaItemMetadata> items) {
        if (items == null) {
            items = Collections.emptyList();
        }

        for (TabBinder<MediaItemMetadata.ArtworkRef> tabBinder : mTabs) {
            tabBinder.setUpdateListener(null);
            tabBinder.setImage(mApplicationContext, null);
        }

        mTabs.clear();

        Size maxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(mApplicationContext);
        for (MediaItemMetadata item : items.subList(0, Math.min(items.size(), mMaxTabs))) {
            TabBinder<MediaItemMetadata.ArtworkRef> newTab = new TabBinder<>(
                    mApplicationContext,
                    maxArtSize,
                    item,
                    item2 -> {
                        mSelectedTab = mTabs.indexOf(item2);
                        mListener.onTabSelected(item2.getMediaItemMetadata());
                    });
            newTab.setImage(mApplicationContext, item.getArtworkKey());
            mTabs.add(newTab);
        }
        mSelectedTab = mTabs.isEmpty() ? -1 : 0;
        for (TabBinder<MediaItemMetadata.ArtworkRef> tabBinder : mTabs) {
            tabBinder.setUpdateListener(x -> updateTabs());
        }
        updateTabs();
    }

    private void updateTabs() {
        if (mToolbarController.getNavButtonMode() != NavButtonMode.DISABLED) {
            mToolbarController.setTabs(Collections.emptyList());
        } else {
            mToolbarController.setTabs(mTabs.stream()
                            .map(TabBinder::getToolbarTab)
                            .collect(Collectors.toList()),
                    mSelectedTab);
        }
    }

    /** Sets whether the source has settings (not all screens show it). */
    public void setHasSettings(boolean hasSettings) {
        mSettings.setVisible(hasSettings);
    }

    /** Sets whether the source's settings is distraction optimized. */
    public void setSettingsDistractionOptimized(boolean isDistractionOptimized) {
        mSettings.setUxRestrictions(isDistractionOptimized
                ? MEDIA_UX_RESTRICTION_NONE
                : MEDIA_UX_RESTRICTION_DEFAULT);
    }

    /** Sets whether the source has equalizer support. */
    public void setHasEqualizer(boolean hasEqualizer) {
        mEqualizer.setVisible(hasEqualizer);
    }

    /**
     * Sets whether search is supported
     */
    public void setSearchSupported(boolean supported) {
        mSearchSupported = supported;
        updateSearchVisibility();
    }

    /** Sets whether to show the search MenuItem if supported */
    public void showSearchIfSupported(boolean show) {
        mShowSearchIfSupported = show;
        updateSearchVisibility();
    }

    private void updateSearchVisibility() {
        mSearch.setVisible(mShowSearchIfSupported && mSearchSupported);
    }

    /**
     * Updates the currently active item
     */
    public void setActiveItem(MediaItemMetadata item) {
        for (int i = 0; i < mTabs.size(); i++) {
            MediaItemMetadata mediaItemMetadata = mTabs.get(i).getMediaItemMetadata();
            boolean match = item != null && Objects.equals(
                    item.getId(),
                    mediaItemMetadata.getId());
            if (match) {
                mToolbarController.selectTab(i);
                return;
            }
        }
    }

    public void setSearchQuery(String query) {
        mToolbarController.setSearchQuery(query);
    }

    public void setLogo(Drawable drawable) {
        mLogo = drawable;
        updateLogo();
    }

    private void updateLogo() {
        if (mToolbarController.getSearchMode() == SearchMode.DISABLED) {
            if (mUseSourceLogoForAppSelector) {
                mAppSelector.setIcon(mLogo);
            } else {
                mToolbarController.setLogo(mLogo);
            }
        } else {
            mToolbarController.setLogo(null);
        }
    }

    public void setSearchIcon(Drawable drawable) {
        mToolbarController.setSearchIcon(drawable);
    }

    public void setTitle(CharSequence title) {
        mToolbarController.setTitle(title);
    }

    public void setTitle(int title) {
        mToolbarController.setTitle(title);
    }

    public void setMenuItems(List<MenuItem> items) {
        mToolbarController.setMenuItems(items);
    }

    public void setBackgroundShown(boolean shown) {
        mToolbarController.setBackgroundShown(shown);
    }

    /** Proxies to {@link ToolbarController#setSearchMode(SearchMode)} */
    public void setSearchMode(SearchMode mode) {
        if (mToolbarController.getSearchMode() != mode) {
            mToolbarController.setSearchMode(mode);
            updateTabs();
            updateLogo();
        }
    }

    /** Proxies to {@link ToolbarController#setNavButtonMode(NavButtonMode)} */
    public void setNavButtonMode(NavButtonMode mode) {
        if (mode != mToolbarController.getNavButtonMode()) {
            mToolbarController.setNavButtonMode(mode);
            updateTabs();
        }
    }

    /** Proxies to {@link ToolbarController#getSearchCapabilities()} */
    public SearchCapabilities getSearchCapabilities() {
        return mToolbarController.getSearchCapabilities();
    }

    /** Proxies to {@link ToolbarController#setSearchConfig(SearchConfig)} */
    public void setSearchConfig(SearchConfig searchConfig) {
        mToolbarController.setSearchConfig(searchConfig);
    }
}
