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
package com.android.wallpaper.picker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.service.wallpaper.IWallpaperConnection;
import android.service.wallpaper.WallpaperService;
import android.service.wallpaper.WallpaperSettingsActivity;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.slice.Slice;
import androidx.slice.widget.SliceLiveData;
import androidx.slice.widget.SliceView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.wallpaper.R;
import com.android.wallpaper.compat.BuildCompat;
import com.android.wallpaper.model.LiveWallpaperInfo;
import com.android.wallpaper.module.WallpaperPersister.SetWallpaperCallback;
import com.android.wallpaper.util.WallpaperConnection;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment which displays the UI for previewing an individual live wallpaper, its attribution
 * information and settings slices if available.
 */
public class LivePreviewFragment extends PreviewFragment implements
        WallpaperConnection.WallpaperConnectionListener {

    private static final String TAG = "LivePreviewFragment";

    private static final String KEY_ACTION_DELETE_LIVE_WALLPAPER = "action_delete_live_wallpaper";
    private static final String EXTRA_LIVE_WALLPAPER_INFO = "android.live_wallpaper.info";

    /**
     * Instance of {@link WallpaperConnection} used to bind to the live wallpaper service to show
     * it in this preview fragment.
     * @see IWallpaperConnection
     */
    protected WallpaperConnection mWallpaperConnection;

    private Intent mWallpaperIntent;
    private Intent mDeleteIntent;
    private Intent mSettingsIntent;

    private List<Pair<String, View>> mPages;
    private ViewPager mViewPager;
    private TabLayout mTabLayout;
    private SliceView mSettingsSliceView;
    private LiveData<Slice> mSettingsLiveData;
    private View mLoadingScrim;
    private InfoPageController mInfoPageController;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.app.WallpaperInfo info = mWallpaper.getWallpaperComponent();
        mWallpaperIntent = getWallpaperIntent(info);
        setUpExploreIntent(null);

        android.app.WallpaperInfo currentWallpaper =
                WallpaperManager.getInstance(requireContext()).getWallpaperInfo();
        String deleteAction = getDeleteAction(info, currentWallpaper);

        if (!TextUtils.isEmpty(deleteAction)) {
            mDeleteIntent = new Intent(deleteAction);
            mDeleteIntent.setPackage(info.getPackageName());
            mDeleteIntent.putExtra(EXTRA_LIVE_WALLPAPER_INFO, info);
        }

        String settingsActivity = getSettingsActivity(info);
        if (settingsActivity != null) {
            mSettingsIntent = new Intent();
            mSettingsIntent.setComponent(new ComponentName(info.getPackageName(),
                    settingsActivity));
            mSettingsIntent.putExtra(WallpaperSettingsActivity.EXTRA_PREVIEW_MODE, true);
            PackageManager pm = requireContext().getPackageManager();
            ActivityInfo activityInfo = mSettingsIntent.resolveActivityInfo(pm, 0);
            if (activityInfo == null) {
                Log.i(TAG, "Couldn't find wallpaper settings activity: " + settingsActivity);
                mSettingsIntent = null;
            }
        }
    }

    @Nullable
    protected String getSettingsActivity(WallpaperInfo info) {
        return info.getSettingsActivity();
    }

    protected Intent getWallpaperIntent(WallpaperInfo info) {
        return new Intent(WallpaperService.SERVICE_INTERFACE)
                .setClassName(info.getPackageName(), info.getServiceName());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        mPages = new ArrayList<>();
        View view = super.onCreateView(inflater, container, savedInstanceState);
        if (view == null) {
            return null;
        }

        Activity activity = requireActivity();

        mLoadingScrim = view.findViewById(R.id.loading);
        setUpLoadingIndicator();

        mWallpaperConnection = new WallpaperConnection(mWallpaperIntent, activity,
                this, null);
        container.post(() -> {
            if (!mWallpaperConnection.connect()) {
                mWallpaperConnection = null;
            }
        });

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mSettingsLiveData != null && mSettingsLiveData.hasObservers()) {
            mSettingsLiveData.removeObserver(mSettingsSliceView);
            mSettingsLiveData = null;
        }
        if (mWallpaperConnection != null) {
            mWallpaperConnection.disconnect();
        }
        mWallpaperConnection = null;
        super.onDestroy();
    }

    @Override
    protected void setUpBottomSheetView(ViewGroup bottomSheet) {

        initInfoPage();
        initSettingsPage();

        mViewPager = bottomSheet.findViewById(R.id.viewpager);
        mTabLayout = bottomSheet.findViewById(R.id.tablayout);

        // Create PagerAdapter
        final PagerAdapter pagerAdapter = new PagerAdapter() {
            @Override
            public Object instantiateItem(ViewGroup container, int position) {
                final View page = mPages.get(position).second;
                container.addView(page);
                return page;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup container, int position,
                    @NonNull Object object) {
                if (object instanceof View) {
                    container.removeView((View) object);
                }
            }

            @Override
            public int getCount() {
                return mPages.size();
            }

            @Override
            public CharSequence getPageTitle(int position) {
                try {
                    return mPages.get(position).first;
                } catch (IndexOutOfBoundsException e) {
                    return null;
                }
            }

            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return (view == object);
            }
        };

        // Add OnPageChangeListener to re-measure ViewPager's height
        mViewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mViewPager.requestLayout();
                logLiveWallpaperPageSelected(position);
            }
        });

        // Set PagerAdapter
        mViewPager.setAdapter(pagerAdapter);

        // Make TabLayout visible if there are more than one page
        if (mPages.size() > 1) {
            mTabLayout.setVisibility(View.VISIBLE);
            mTabLayout.setupWithViewPager(mViewPager);
        }
        mViewPager.setCurrentItem(0);
    }

    private void logLiveWallpaperPageSelected(int position) {
        switch (position) {
            case 0:
                mUserEventLogger.logLiveWallpaperInfoSelected(
                        mWallpaper.getCollectionId(getActivity()), mWallpaper.getWallpaperId());
                break;
            case 1:
                mUserEventLogger.logLiveWallpaperCustomizeSelected(
                        mWallpaper.getCollectionId(getActivity()), mWallpaper.getWallpaperId());
                break;
        }
    }

    @Override
    public void onEngineShown() {
        mLoadingScrim.post(() -> mLoadingScrim.animate()
                .alpha(0f)
                .setDuration(220)
                .setStartDelay(300)
                .setInterpolator(AnimationUtils.loadInterpolator(getActivity(),
                        android.R.interpolator.fast_out_linear_in))
                .withEndAction(() -> {
                    if (mLoadingProgressBar != null) {
                        mLoadingProgressBar.hide();
                    }
                    mLoadingScrim.setVisibility(View.INVISIBLE);
                    populateInfoPage(mInfoPageController);
                }));
    }

    @Override
    protected boolean isLoaded() {
        return mWallpaperConnection != null && mWallpaperConnection.isEngineReady();
    }

    private void initInfoPage() {
        View pageInfo = InfoPageController.createView(getLayoutInflater());
        mInfoPageController = new InfoPageController(pageInfo, mPreviewMode);
        mPages.add(Pair.create(getString(R.string.tab_info), pageInfo));
    }

    private void initSettingsPage() {
        final Uri uriSettingsSlice = getSettingsSliceUri(mWallpaper.getWallpaperComponent());
        if (uriSettingsSlice == null) {
            return;
        }

        final View pageSettings = getLayoutInflater().inflate(R.layout.preview_page_settings,
                null /* root */);

        mSettingsSliceView = pageSettings.findViewById(R.id.settings_slice);
        mSettingsSliceView.setMode(SliceView.MODE_LARGE);
        mSettingsSliceView.setScrollable(false);

        // Set LiveData for SliceView
        mSettingsLiveData = SliceLiveData.fromUri(requireContext() /* context */, uriSettingsSlice);
        mSettingsLiveData.observeForever(mSettingsSliceView);

        pageSettings.findViewById(R.id.preview_settings_pane_set_wallpaper_button)
                .setOnClickListener(this::onSetWallpaperClicked);

        mPages.add(Pair.create(getResources().getString(R.string.tab_customize), pageSettings));
    }

    @Override
    protected CharSequence getExploreButtonLabel(Context context) {
        CharSequence exploreLabel = ((LiveWallpaperInfo) mWallpaper).getActionDescription(context);
        if (TextUtils.isEmpty(exploreLabel)) {
            exploreLabel = context.getString(mWallpaper.getActionLabelRes(context));
        }
        return exploreLabel;
    }

    @SuppressLint("NewApi") //Already checking with isAtLeastQ
    protected Uri getSettingsSliceUri(android.app.WallpaperInfo info) {
        if (BuildCompat.isAtLeastQ()) {
            return info.getSettingsSliceUri();
        }
        return null;
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_live_preview;
    }

    @Override
    protected int getBottomSheetResId() {
        return R.id.bottom_sheet;
    }

    @Override
    protected int getLoadingIndicatorResId() {
        return R.id.loading_indicator;
    }

    @Override
    protected void setCurrentWallpaper(int destination) {
        mWallpaperSetter.setCurrentWallpaper(getActivity(), mWallpaper, null,
                destination, 0, null, new SetWallpaperCallback() {
                    @Override
                    public void onSuccess(com.android.wallpaper.model.WallpaperInfo wallpaperInfo) {
                        finishActivityWithResultOk();
                    }

                    @Override
                    public void onError(@Nullable Throwable throwable) {
                        showSetWallpaperErrorDialog(destination);
                    }
                });
    }

    @Override
    protected void setBottomSheetContentAlpha(float alpha) {
        mInfoPageController.setContentAlpha(alpha);
    }


    @Nullable
    protected String getDeleteAction(android.app.WallpaperInfo wallpaperInfo,
            @Nullable android.app.WallpaperInfo currentInfo) {
        ServiceInfo serviceInfo = wallpaperInfo.getServiceInfo();
        if (!isPackagePreInstalled(serviceInfo.applicationInfo)) {
            Log.d(TAG, "This wallpaper is not pre-installed: " + serviceInfo.name);
            return null;
        }

        ServiceInfo currentService = currentInfo == null ? null : currentInfo.getServiceInfo();
        // A currently set Live wallpaper should not be deleted.
        if (currentService != null && TextUtils.equals(serviceInfo.name, currentService.name)) {
            return null;
        }

        final Bundle metaData = serviceInfo.metaData;
        if (metaData != null) {
            return metaData.getString(KEY_ACTION_DELETE_LIVE_WALLPAPER);
        }
        return null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mWallpaperConnection != null) {
            mWallpaperConnection.setVisibility(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mWallpaperConnection != null) {
            mWallpaperConnection.setVisibility(false);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        menu.findItem(R.id.configure).setVisible(mSettingsIntent != null);
        menu.findItem(R.id.delete_wallpaper).setVisible(mDeleteIntent != null);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.configure) {
            if (getActivity() != null) {
                startActivity(mSettingsIntent);
                return true;
            }
        } else if (id == R.id.delete_wallpaper) {
            showDeleteConfirmDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showDeleteConfirmDialog() {
        final AlertDialog alertDialog = new AlertDialog.Builder(
                new ContextThemeWrapper(getContext(), getDeviceDefaultTheme()))
                .setMessage(R.string.delete_wallpaper_confirmation)
                .setPositiveButton(R.string.delete_live_wallpaper,
                        (dialog, which) -> deleteLiveWallpaper())
                .setNegativeButton(android.R.string.cancel, null /* listener */)
                .create();
        alertDialog.show();
    }

    private void deleteLiveWallpaper() {
        if (mDeleteIntent != null) {
            requireContext().startService(mDeleteIntent);
            finishActivityWithResultOk();
        }
    }

    private boolean isPackagePreInstalled(ApplicationInfo info) {
        if (info != null && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return true;
        }
        return false;
    }
}
