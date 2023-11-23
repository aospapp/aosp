/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.makeMeasureSpec;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.provider.Settings;
import android.service.wallpaper.WallpaperService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.systemui.shared.system.SurfaceViewRequestUtils;
import com.android.wallpaper.R;
import com.android.wallpaper.config.Flags;
import com.android.wallpaper.model.Category;
import com.android.wallpaper.model.LiveWallpaperInfo;
import com.android.wallpaper.model.WallpaperInfo;
import com.android.wallpaper.module.CurrentWallpaperInfoFactory;
import com.android.wallpaper.module.CurrentWallpaperInfoFactory.WallpaperInfoCallback;
import com.android.wallpaper.module.ExploreIntentChecker;
import com.android.wallpaper.module.InjectorProvider;
import com.android.wallpaper.module.LockWallpaperStatusChecker;
import com.android.wallpaper.module.UserEventLogger;
import com.android.wallpaper.module.WallpaperPreferences;
import com.android.wallpaper.module.WallpaperPreferences.PresentationMode;
import com.android.wallpaper.module.WallpaperRotationRefresher;
import com.android.wallpaper.module.WallpaperRotationRefresher.Listener;
import com.android.wallpaper.picker.CategorySelectorFragment.CategorySelectorFragmentHost;
import com.android.wallpaper.picker.MyPhotosStarter.MyPhotosStarterProvider;
import com.android.wallpaper.picker.MyPhotosStarter.PermissionChangedListener;
import com.android.wallpaper.picker.individual.IndividualPickerFragment.ThumbnailUpdater;
import com.android.wallpaper.util.DisplayMetricsRetriever;
import com.android.wallpaper.util.PreviewUtils;
import com.android.wallpaper.util.ScreenSizeCalculator;
import com.android.wallpaper.util.TileSizeCalculator;
import com.android.wallpaper.util.WallpaperConnection;
import com.android.wallpaper.util.WallpaperConnection.WallpaperConnectionListener;
import com.android.wallpaper.widget.LiveTileOverlay;
import com.android.wallpaper.widget.PreviewPager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.MemoryCategory;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Displays the Main UI for picking a category of wallpapers to choose from.
 */
public class CategoryFragment extends ToolbarFragment
        implements CategorySelectorFragmentHost, ThumbnailUpdater {

    /**
     * Interface to be implemented by an Activity hosting a {@link CategoryFragment}
     */
    public interface CategoryFragmentHost extends MyPhotosStarterProvider {

        void requestExternalStoragePermission(PermissionChangedListener listener);

        boolean isReadExternalStoragePermissionGranted();

        void showViewOnlyPreview(WallpaperInfo wallpaperInfo);
    }

    public static CategoryFragment newInstance(CharSequence title) {
        CategoryFragment fragment = new CategoryFragment();
        fragment.setArguments(ToolbarFragment.createArguments(title));
        return fragment;
    }

    private static final String TAG = "CategoryFragment";
    private static final int MAX_ALPHA = 255;

    // The number of ViewHolders that don't pertain to category tiles.
    // Currently 2: one for the metadata section and one for the "Select wallpaper" header.
    private static final int NUM_NON_CATEGORY_VIEW_HOLDERS = 0;

    private static final int SETTINGS_APP_INFO_REQUEST_CODE = 1;

    private static final String PERMISSION_READ_WALLPAPER_INTERNAL =
            "android.permission.READ_WALLPAPER_INTERNAL";

    private ProgressDialog mRefreshWallpaperProgressDialog;
    private boolean mTestingMode;
    private ImageView mHomePreview;
    private SurfaceView mWorkspaceSurface;
    private SurfaceView mWallpaperSurface;
    private ImageView mLockscreenPreview;
    private PreviewPager mPreviewPager;
    private List<View> mWallPaperPreviews;
    private WallpaperConnection mWallpaperConnection;
    private CategorySelectorFragment mCategorySelectorFragment;
    private boolean mShowSelectedWallpaper;
    private BottomSheetBehavior mBottomSheetBehavior;
    private PreviewUtils mPreviewUtils;

    // Home workspace surface is behind the app window, and so must the home image wallpaper like
    // the live wallpaper. This view is rendered on mWallpaperSurface for home image wallpaper.
    private ImageView mHomeImageWallpaper;

    public CategoryFragment() {
        mCategorySelectorFragment = new CategorySelectorFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(
                ADD_SCALABLE_HEADER
                ? R.layout.fragment_category_scalable_picker
                : R.layout.fragment_category_picker, container, /* attachToRoot= */ false);

        mWallPaperPreviews = new ArrayList<>();
        CardView homePreviewCard = (CardView) inflater.inflate(
                R.layout.wallpaper_preview_card, null);
        mHomePreview = homePreviewCard.findViewById(R.id.wallpaper_preview_image);
        mWorkspaceSurface = homePreviewCard.findViewById(R.id.workspace_surface);
        mWallpaperSurface = homePreviewCard.findViewById(R.id.wallpaper_surface);
        mWallPaperPreviews.add(homePreviewCard);

        if (LockWallpaperStatusChecker.isLockWallpaperSet(getContext())) {
            CardView lockscreenPreviewCard = (CardView) inflater.inflate(
                    R.layout.wallpaper_preview_card, null);
            mLockscreenPreview = lockscreenPreviewCard.findViewById(R.id.wallpaper_preview_image);
            lockscreenPreviewCard.findViewById(R.id.workspace_surface).setVisibility(View.GONE);
            lockscreenPreviewCard.findViewById(R.id.wallpaper_surface).setVisibility(View.GONE);
            mWallPaperPreviews.add(lockscreenPreviewCard);
        }

        mPreviewPager = view.findViewById(R.id.wallpaper_preview_pager);
        mPreviewPager.setAdapter(new PreviewPagerAdapter(mWallPaperPreviews));
        mPreviewPager.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            int[] mLocation = new int[2];
            Rect mHomePreviewRect = new Rect();
            @Override
            public void onPageScrolled(int position, float positionOffset,
                    int positionOffsetPixels) {
                if (mWallpaperConnection != null) {
                    mHomePreview.getLocationOnScreen(mLocation);
                    mHomePreviewRect.set(0, 0, mHomePreview.getMeasuredWidth(),
                            mHomePreview.getMeasuredHeight());
                    mHomePreviewRect.offset(mLocation[0], mLocation[1]);
                    mWallpaperConnection.updatePreviewPosition(mHomePreviewRect);
                }
            }

            @Override
            public void onPageSelected(int i) {
            }

            @Override
            public void onPageScrollStateChanged(int i) {
            }
        });
        setupCurrentWallpaperPreview(view);

        View fragmentContainer = view.findViewById(R.id.category_fragment_container);
        mBottomSheetBehavior = BottomSheetBehavior.from(fragmentContainer);
        fragmentContainer.addOnLayoutChangeListener((containerView, left, top, right, bottom,
                                                     oldLeft, oldTop, oldRight, oldBottom) -> {
            int minimumHeight = containerView.getHeight() - mPreviewPager.getMeasuredHeight();
            mBottomSheetBehavior.setPeekHeight(minimumHeight);
            containerView.setMinimumHeight(minimumHeight);
            ((CardView) mHomePreview.getParent())
                    .setRadius(TileSizeCalculator.getPreviewCornerRadius(
                            getActivity(), homePreviewCard.getMeasuredWidth()));
        });

        mPreviewUtils = new PreviewUtils(getContext(),
                getString(R.string.grid_control_metadata_name));

        setUpToolbar(view);

        getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.category_fragment_container, mCategorySelectorFragment)
                .commitNow();
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        updateWallpaperSurface();
        updateWorkspaceSurface();
    }

    @Override
    public CharSequence getDefaultTitle() {
        return getContext().getString(R.string.app_name);
    }

    @Override
    public void onResume() {
        super.onResume();

        WallpaperPreferences preferences = InjectorProvider.getInjector().getPreferences(getActivity());
        preferences.setLastAppActiveTimestamp(new Date().getTime());

        // Reset Glide memory settings to a "normal" level of usage since it may have been lowered in
        // PreviewFragment.
        Glide.get(getActivity()).setMemoryCategory(MemoryCategory.NORMAL);

        // The wallpaper may have been set while this fragment was paused, so force refresh the current
        // wallpapers and presentation mode.
        if (!mShowSelectedWallpaper) {
            refreshCurrentWallpapers(/* MetadataHolder= */ null, /* forceRefresh= */ true);
        }
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
    public void onDestroyView() {
        super.onDestroyView();
        if (mWallpaperConnection != null) {
            mWallpaperConnection.disconnect();
            mWallpaperConnection = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mWallpaperConnection != null) {
            mWallpaperConnection.disconnect();
            mWallpaperConnection = null;
        }
        if (mRefreshWallpaperProgressDialog != null) {
            mRefreshWallpaperProgressDialog.dismiss();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SETTINGS_APP_INFO_REQUEST_CODE) {
            mCategorySelectorFragment.notifyDataSetChanged();
        }
    }

    @Override
    public void requestCustomPhotoPicker(PermissionChangedListener listener) {
        getFragmentHost().getMyPhotosStarter().requestCustomPhotoPicker(listener);
    }

    @Override
    public void show(String collectionId) {
        getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.category_fragment_container,
                        InjectorProvider.getInjector().getIndividualPickerFragment(collectionId))
                .addToBackStack(null)
                .commit();
        getChildFragmentManager().executePendingTransactions();
    }

    @Override
    public void updateThumbnail(WallpaperInfo wallpaperInfo) {
        new android.os.Handler().post(() -> {
            // A config change may have destroyed the activity since the refresh started, so check
            // for that.
            if (getActivity() == null) {
                return;
            }

            updateThumbnail(wallpaperInfo, mHomePreview, true);
            updateThumbnail(wallpaperInfo, mLockscreenPreview, false);
            mShowSelectedWallpaper = true;
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        });
    }

    @Override
    public void restoreThumbnails() {
        refreshCurrentWallpapers(/* MetadataHolder= */ null, /* forceRefresh= */ true);
        mShowSelectedWallpaper = false;
    }

    /**
     * Pops the child fragment from the stack if {@link CategoryFragment} is visible to the users.
     *
     * @return {@code true} if the child fragment is popped, {@code false} otherwise.
     */
    public boolean popChildFragment() {
        return isVisible() && getChildFragmentManager().popBackStackImmediate();
    }

    /**
     * Inserts the given category into the categories list in priority order.
     */
    void addCategory(Category category, boolean loading) {
        mCategorySelectorFragment.addCategory(category, loading);
    }

    void removeCategory(Category category) {
        mCategorySelectorFragment.removeCategory(category);
    }

    void updateCategory(Category category) {
        mCategorySelectorFragment.updateCategory(category);
    }

    void clearCategories() {
        mCategorySelectorFragment.clearCategories();
    }

    /**
     * Notifies the CategoryFragment that no further categories are expected so it may hide
     * the loading indicator.
     */
    void doneFetchingCategories() {
        mCategorySelectorFragment.doneFetchingCategories();
    }

    /**
     * Enable a test mode of operation -- in which certain UI features are disabled to allow for
     * UI tests to run correctly. Works around issue in ProgressDialog currently where the dialog
     * constantly keeps the UI thread alive and blocks a test forever.
     */
    void setTestingMode(boolean testingMode) {
        mTestingMode = testingMode;
    }

    private boolean canShowCurrentWallpaper() {
        Activity activity = getActivity();
        CategoryFragmentHost host = getFragmentHost();
        PackageManager packageManager = activity.getPackageManager();
        String packageName = activity.getPackageName();

        boolean hasReadWallpaperInternal = packageManager.checkPermission(
                PERMISSION_READ_WALLPAPER_INTERNAL, packageName) == PackageManager.PERMISSION_GRANTED;
        return hasReadWallpaperInternal || host.isReadExternalStoragePermissionGranted();
    }

    private void showCurrentWallpaper(View rootView, boolean show) {
        rootView.findViewById(R.id.wallpaper_preview_pager)
                .setVisibility(show ? View.VISIBLE : View.GONE);
        rootView.findViewById(R.id.permission_needed)
                .setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void setupCurrentWallpaperPreview(View rootView) {
        if (canShowCurrentWallpaper()) {
            showCurrentWallpaper(rootView, true);
        } else {
            showCurrentWallpaper(rootView, false);

            Button mAllowAccessButton = rootView
                    .findViewById(R.id.permission_needed_allow_access_button);
            mAllowAccessButton.setOnClickListener(view ->
                    getFragmentHost().requestExternalStoragePermission(
                            new PermissionChangedListener() {

                                @Override
                                public void onPermissionsGranted() {
                                    showCurrentWallpaper(rootView, true);
                                    mCategorySelectorFragment.notifyDataSetChanged();
                                }

                                @Override
                                public void onPermissionsDenied(boolean dontAskAgain) {
                                    if (!dontAskAgain) {
                                        return;
                                    }
                                    showPermissionNeededDialog();
                                }
                            })
            );

            // Replace explanation text with text containing the Wallpapers app name which replaces
            // the placeholder.
            String appName = getString(R.string.app_name);
            String explanation = getString(R.string.permission_needed_explanation, appName);
            TextView explanationView = rootView.findViewById(R.id.permission_needed_explanation);
            explanationView.setText(explanation);
        }
    }

    private void showPermissionNeededDialog() {
        String permissionNeededMessage = getString(
                R.string.permission_needed_explanation_go_to_settings);
        AlertDialog dialog = new AlertDialog.Builder(getActivity(), R.style.LightDialogTheme)
                .setMessage(permissionNeededMessage)
                .setPositiveButton(android.R.string.ok, /* onClickListener= */ null)
                .setNegativeButton(
                        R.string.settings_button_label,
                        (dialogInterface, i) -> {
                            Intent appInfoIntent = new Intent();
                            appInfoIntent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package",
                                    getActivity().getPackageName(), /* fragment= */ null);
                            appInfoIntent.setData(uri);
                            startActivityForResult(appInfoIntent, SETTINGS_APP_INFO_REQUEST_CODE);
                        })
                .create();
        dialog.show();
    }

    private CategoryFragmentHost getFragmentHost() {
        return (CategoryFragmentHost) getActivity();
    }

    private Intent getWallpaperIntent(android.app.WallpaperInfo info) {
        return new Intent(WallpaperService.SERVICE_INTERFACE)
                .setClassName(info.getPackageName(), info.getServiceName());
    }
    /**
     * Obtains the {@link WallpaperInfo} object(s) representing the wallpaper(s) currently set to the
     * device from the {@link CurrentWallpaperInfoFactory} and binds them to the provided
     * {@link MetadataHolder}.
     */
    private void refreshCurrentWallpapers(@Nullable final MetadataHolder holder,
                                          boolean forceRefresh) {
        CurrentWallpaperInfoFactory factory = InjectorProvider.getInjector()
                .getCurrentWallpaperFactory(getActivity().getApplicationContext());

        factory.createCurrentWallpaperInfos(new WallpaperInfoCallback() {
            @Override
            public void onWallpaperInfoCreated(
                    final WallpaperInfo homeWallpaper,
                    @Nullable final WallpaperInfo lockWallpaper,
                    @PresentationMode final int presentationMode) {

                // Update the metadata displayed on screen. Do this in a Handler so it is scheduled at the
                // end of the message queue. This is necessary to ensure we do not remove or add data from
                // the adapter while the layout is being computed. RecyclerView documentation therefore
                // recommends performing such changes in a Handler.
                new android.os.Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        final Activity activity = getActivity();
                        // A config change may have destroyed the activity since the refresh
                        // started, so check for that.
                        if (activity == null) {
                            return;
                        }

                        updateThumbnail(homeWallpaper, mHomePreview, true);
                        updateThumbnail(lockWallpaper, mLockscreenPreview, false);

                        // The MetadataHolder may be null if the RecyclerView has not yet created the view
                        // holder.
                        if (holder != null) {
                            holder.bindWallpapers(homeWallpaper, lockWallpaper, presentationMode);
                        }
                    }
                });
            }
        }, forceRefresh);
    }

    private void setUpLiveWallpaperPreview(WallpaperInfo homeWallpaper, ImageView previewView,
            Drawable thumbnail) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        if (mWallpaperConnection != null) {
            mWallpaperConnection.disconnect();
        }
        if (thumbnail != null) {
            thumbnail.setBounds(previewView.getLeft(), previewView.getTop(), previewView.getRight(),
                    previewView.getBottom());
        }

        Rect previewLocalRect = new Rect();
        Rect previewGlobalRect = new Rect();
        previewView.getLocalVisibleRect(previewLocalRect);
        previewView.getGlobalVisibleRect(previewGlobalRect);
        mWallpaperConnection = new WallpaperConnection(
                getWallpaperIntent(homeWallpaper.getWallpaperComponent()), activity,
                new WallpaperConnectionListener() {
                    @Override
                    public void onEngineShown() {
                        final Drawable placeholder = previewView.getDrawable() == null
                                ? new ColorDrawable(getResources().getColor(R.color.secondary_color,
                                    activity.getTheme()))
                                : previewView.getDrawable();
                        LiveTileOverlay.INSTANCE.setForegroundDrawable(placeholder);
                        LiveTileOverlay.INSTANCE.attach(previewView.getOverlay());
                        previewView.animate()
                                .setStartDelay(400)
                                .setDuration(400)
                                .setInterpolator(AnimationUtils.loadInterpolator(getContext(),
                                        android.R.interpolator.fast_out_linear_in))
                                .setUpdateListener(value -> placeholder.setAlpha(
                                        (int) (MAX_ALPHA * (1 - value.getAnimatedFraction()))))
                                .withEndAction(() -> {
                                    LiveTileOverlay.INSTANCE.setForegroundDrawable(null);

                                }).start();

                    }
                }, previewGlobalRect);

        LiveTileOverlay.INSTANCE.update(new RectF(previewLocalRect),
                ((CardView) previewView.getParent()).getRadius());

        mWallpaperConnection.setVisibility(true);
        previewView.post(() -> {
            if (!mWallpaperConnection.connect()) {
                mWallpaperConnection = null;
                LiveTileOverlay.INSTANCE.detach(previewView.getOverlay());
            }
        });
    }

    /**
     * Returns the width to use for the home screen wallpaper in the "single metadata" configuration.
     */
    private int getSingleWallpaperImageWidth() {
        Point screenSize = ScreenSizeCalculator.getInstance()
                .getScreenSize(getActivity().getWindowManager().getDefaultDisplay());

        int height = getResources().getDimensionPixelSize(R.dimen.single_metadata_card_layout_height);
        return height * screenSize.x / screenSize.y;
    }

    /**
     * Refreshes the current wallpaper in a daily wallpaper rotation.
     */
    private void refreshDailyWallpaper() {
        // ProgressDialog endlessly updates the UI thread, keeping it from going idle which therefore
        // causes Espresso to hang once the dialog is shown.
        if (!mTestingMode) {
            int themeResId;
            if (VERSION.SDK_INT < VERSION_CODES.LOLLIPOP) {
                themeResId = R.style.ProgressDialogThemePreL;
            } else {
                themeResId = R.style.LightDialogTheme;
            }
            mRefreshWallpaperProgressDialog = new ProgressDialog(getActivity(), themeResId);
            mRefreshWallpaperProgressDialog.setTitle(null);
            mRefreshWallpaperProgressDialog.setMessage(
                    getResources().getString(R.string.refreshing_daily_wallpaper_dialog_message));
            mRefreshWallpaperProgressDialog.setIndeterminate(true);
            mRefreshWallpaperProgressDialog.setCancelable(false);
            mRefreshWallpaperProgressDialog.show();
        }

        WallpaperRotationRefresher wallpaperRotationRefresher =
                InjectorProvider.getInjector().getWallpaperRotationRefresher();
        wallpaperRotationRefresher.refreshWallpaper(getContext(), new Listener() {
            @Override
            public void onRefreshed() {
                // If the fragment is detached from the activity there's nothing to do here and the UI will
                // update when the fragment is resumed.
                if (getActivity() == null) {
                    return;
                }

                if (mRefreshWallpaperProgressDialog != null) {
                    mRefreshWallpaperProgressDialog.dismiss();
                }
            }

            @Override
            public void onError() {
                if (getActivity() == null) {
                    return;
                }

                if (mRefreshWallpaperProgressDialog != null) {
                    mRefreshWallpaperProgressDialog.dismiss();
                }

                AlertDialog errorDialog = new AlertDialog.Builder(getActivity(), R.style.LightDialogTheme)
                        .setMessage(R.string.refresh_daily_wallpaper_failed_message)
                        .setPositiveButton(android.R.string.ok, null /* onClickListener */)
                        .create();
                errorDialog.show();
            }
        });
    }

    /**
     * Returns the width to use for the home and lock screen wallpapers in the "both metadata"
     * configuration.
     */
    private int getBothWallpaperImageWidth() {
        DisplayMetrics metrics = DisplayMetricsRetriever.getInstance().getDisplayMetrics(getResources(),
                getActivity().getWindowManager().getDefaultDisplay());

        // In the "both metadata" configuration, wallpaper images minus the gutters account for the full
        // width of the device's screen.
        return metrics.widthPixels - (3 * getResources().getDimensionPixelSize(R.dimen.grid_padding));
    }

    private void updateThumbnail(WallpaperInfo wallpaperInfo, ImageView thumbnailView,
                                 boolean isHomeWallpaper) {
        if (wallpaperInfo == null) {
            return;
        }

        if (thumbnailView == null) {
            return;
        }

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        UserEventLogger eventLogger = InjectorProvider.getInjector().getUserEventLogger(activity);

        boolean renderInImageWallpaperSurface =
                !(wallpaperInfo instanceof LiveWallpaperInfo) && isHomeWallpaper;
        wallpaperInfo.getThumbAsset(activity.getApplicationContext())
                .loadDrawable(activity,
                        renderInImageWallpaperSurface ? mHomeImageWallpaper : thumbnailView,
                        getResources().getColor(R.color.secondary_color));
        if (isHomeWallpaper) {
            LiveTileOverlay.INSTANCE.detach(thumbnailView.getOverlay());
            if (wallpaperInfo instanceof LiveWallpaperInfo) {
                setUpLiveWallpaperPreview(wallpaperInfo, thumbnailView,
                        new ColorDrawable(getResources().getColor(
                                R.color.secondary_color, activity.getTheme())));
            } else {
                if (mWallpaperConnection != null) {
                    mWallpaperConnection.disconnect();
                    mWallpaperConnection = null;
                }
            }
        }
        thumbnailView.setOnClickListener(view -> {
            getFragmentHost().showViewOnlyPreview(wallpaperInfo);
            eventLogger.logCurrentWallpaperPreviewed();
        });
    }

    private void updateWallpaperSurface() {
        mWallpaperSurface.getHolder().addCallback(mWallpaperSurfaceCallback);
    }

    private void updateWorkspaceSurface() {
        mWorkspaceSurface.setZOrderMediaOverlay(true);
        mWorkspaceSurface.getHolder().addCallback(mWorkspaceSurfaceCallback);
    }

    private final SurfaceHolder.Callback mWallpaperSurfaceCallback = new SurfaceHolder.Callback() {

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
            if (mHomeImageWallpaper == null) {
                mHomeImageWallpaper = new ImageView(getContext());
                mHomeImageWallpaper.setBackgroundColor(
                        ContextCompat.getColor(getContext(), R.color.primary_color));
                mHomeImageWallpaper.measure(makeMeasureSpec(mHomePreview.getWidth(), EXACTLY),
                        makeMeasureSpec(mHomePreview.getHeight(), EXACTLY));
                mHomeImageWallpaper.layout(0, 0, mHomePreview.getWidth(), mHomePreview.getHeight());

                SurfaceControlViewHost host = new SurfaceControlViewHost(getContext(),
                        getContext().getDisplay(), mWallpaperSurface.getHostToken());
                host.setView(mHomeImageWallpaper, mHomeImageWallpaper.getWidth(),
                        mHomeImageWallpaper.getHeight());
                mWallpaperSurface.setChildSurfacePackage(host.getSurfacePackage());
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
        }
    };

    private final SurfaceHolder.Callback mWorkspaceSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Bundle bundle = SurfaceViewRequestUtils.createSurfaceBundle(mWorkspaceSurface);
            if (mPreviewUtils.supportsPreview()) {
                mPreviewUtils.renderPreview(bundle);
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) { }
    };

    private interface MetadataHolder {
        /**
         * Binds {@link WallpaperInfo} objects representing the currently-set wallpapers to the
         * ViewHolder layout.
         */
        void bindWallpapers(WallpaperInfo homeWallpaper, WallpaperInfo lockWallpaper,
                            @PresentationMode int presentationMode);
    }

    private static class SelectWallpaperHeaderHolder extends RecyclerView.ViewHolder {
        public SelectWallpaperHeaderHolder(View headerView) {
            super(headerView);
        }
    }

    /**
     * ViewHolder subclass for a metadata "card" at the beginning of the RecyclerView.
     */
    private class SingleWallpaperMetadataHolder extends RecyclerView.ViewHolder
            implements MetadataHolder {
        private WallpaperInfo mWallpaperInfo;
        private ImageView mWallpaperImage;
        private TextView mWallpaperPresentationModeSubtitle;
        private TextView mWallpaperTitle;
        private TextView mWallpaperSubtitle;
        private TextView mWallpaperSubtitle2;
        private ImageButton mWallpaperExploreButtonNoText;
        private ImageButton mSkipWallpaperButton;

        public SingleWallpaperMetadataHolder(View metadataView) {
            super(metadataView);

            mWallpaperImage = metadataView.findViewById(R.id.wallpaper_image);
            mWallpaperImage.getLayoutParams().width = getSingleWallpaperImageWidth();

            mWallpaperPresentationModeSubtitle =
                    metadataView.findViewById(R.id.wallpaper_presentation_mode_subtitle);
            mWallpaperTitle = metadataView.findViewById(R.id.wallpaper_title);
            mWallpaperSubtitle = metadataView.findViewById(R.id.wallpaper_subtitle);
            mWallpaperSubtitle2 = metadataView.findViewById(R.id.wallpaper_subtitle2);

            mWallpaperExploreButtonNoText =
                    metadataView.findViewById(R.id.wallpaper_explore_button_notext);

            mSkipWallpaperButton = metadataView.findViewById(R.id.skip_wallpaper_button);
        }

        /**
         * Binds home screen wallpaper to the ViewHolder layout.
         */
        @Override
        public void bindWallpapers(WallpaperInfo homeWallpaper, WallpaperInfo lockWallpaper,
                @PresentationMode int presentationMode) {
            mWallpaperInfo = homeWallpaper;

            bindWallpaperAsset();
            bindWallpaperText(presentationMode);
            bindWallpaperActionButtons(presentationMode);
        }

        private void bindWallpaperAsset() {
            final UserEventLogger eventLogger =
                    InjectorProvider.getInjector().getUserEventLogger(getActivity());

            mWallpaperInfo.getThumbAsset(getActivity().getApplicationContext()).loadDrawable(
                    getActivity(), mWallpaperImage, getResources().getColor(R.color.secondary_color));

            mWallpaperImage.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    getFragmentHost().showViewOnlyPreview(mWallpaperInfo);
                    eventLogger.logCurrentWallpaperPreviewed();
                }
            });
        }

        private void bindWallpaperText(@PresentationMode int presentationMode) {
            Context appContext = getActivity().getApplicationContext();

            mWallpaperPresentationModeSubtitle.setText(
                    AttributionFormatter.getHumanReadableWallpaperPresentationMode(
                            appContext, presentationMode));

            List<String> attributions = mWallpaperInfo.getAttributions(appContext);
            if (!attributions.isEmpty()) {
                mWallpaperTitle.setText(attributions.get(0));
            }
            if (attributions.size() > 1) {
                mWallpaperSubtitle.setText(attributions.get(1));
            } else {
                mWallpaperSubtitle.setVisibility(View.INVISIBLE);
            }
            if (attributions.size() > 2) {
                mWallpaperSubtitle2.setText(attributions.get(2));
            } else {
                mWallpaperSubtitle2.setVisibility(View.INVISIBLE);
            }
        }

        private void bindWallpaperActionButtons(@PresentationMode int presentationMode) {
            final Context appContext = getActivity().getApplicationContext();

            final String actionUrl = mWallpaperInfo.getActionUrl(appContext);
            if (actionUrl != null && !actionUrl.isEmpty()) {

                Uri exploreUri = Uri.parse(actionUrl);

                ExploreIntentChecker intentChecker =
                        InjectorProvider.getInjector().getExploreIntentChecker(appContext);
                intentChecker.fetchValidActionViewIntent(exploreUri, (@Nullable Intent exploreIntent) -> {
                    if (getActivity() == null) {
                        return;
                    }

                    updateExploreSectionVisibility(presentationMode, exploreIntent);
                });
            } else {
                updateExploreSectionVisibility(presentationMode, null /* exploreIntent */);
            }
        }

        /**
         * Shows or hides appropriate elements in the "Explore section" (containing the Explore button
         * and the Next Wallpaper button) depending on the current wallpaper.
         *
         * @param presentationMode The presentation mode of the current wallpaper.
         * @param exploreIntent    An optional explore intent for the current wallpaper.
         */
        private void updateExploreSectionVisibility(
                @PresentationMode int presentationMode, @Nullable Intent exploreIntent) {

            final Context appContext = getActivity().getApplicationContext();
            final UserEventLogger eventLogger =
                    InjectorProvider.getInjector().getUserEventLogger(appContext);

            boolean showSkipWallpaperButton = Flags.skipDailyWallpaperButtonEnabled
                    && presentationMode == WallpaperPreferences.PRESENTATION_MODE_ROTATING;

            if (exploreIntent != null) {
                mWallpaperExploreButtonNoText.setImageDrawable(getContext().getDrawable(
                                mWallpaperInfo.getActionIconRes(appContext)));
                mWallpaperExploreButtonNoText.setContentDescription(
                                getString(mWallpaperInfo.getActionLabelRes(appContext)));
                mWallpaperExploreButtonNoText.setColorFilter(
                                getResources().getColor(R.color.currently_set_explore_button_color,
                                        getContext().getTheme()),
                                Mode.SRC_IN);
                mWallpaperExploreButtonNoText.setVisibility(View.VISIBLE);
                mWallpaperExploreButtonNoText.setOnClickListener((View view) -> {
                    eventLogger.logActionClicked(mWallpaperInfo.getCollectionId(appContext),
                           mWallpaperInfo.getActionLabelRes(appContext));
                    startActivity(exploreIntent);
                });
            }

            if (showSkipWallpaperButton) {
                mSkipWallpaperButton.setVisibility(View.VISIBLE);
                mSkipWallpaperButton.setOnClickListener((View view) -> refreshDailyWallpaper());
            }
        }
    }

    /**
     * ViewHolder subclass for a metadata "card" at the beginning of the RecyclerView that shows
     * both home screen and lock screen wallpapers.
     */
    private class TwoWallpapersMetadataHolder extends RecyclerView.ViewHolder
            implements MetadataHolder {
        private WallpaperInfo mHomeWallpaperInfo;
        private ImageView mHomeWallpaperImage;
        private TextView mHomeWallpaperPresentationMode;
        private TextView mHomeWallpaperTitle;
        private TextView mHomeWallpaperSubtitle1;
        private TextView mHomeWallpaperSubtitle2;

        private ImageButton mHomeWallpaperExploreButton;
        private ImageButton mSkipWallpaperButton;
        private ViewGroup mHomeWallpaperPresentationSection;

        private WallpaperInfo mLockWallpaperInfo;
        private ImageView mLockWallpaperImage;
        private TextView mLockWallpaperTitle;
        private TextView mLockWallpaperSubtitle1;
        private TextView mLockWallpaperSubtitle2;

        private ImageButton mLockWallpaperExploreButton;

        public TwoWallpapersMetadataHolder(View metadataView) {
            super(metadataView);

            // Set the min width of the metadata panel to be the screen width minus space for the
            // 2 gutters on the sides. This ensures the RecyclerView's GridLayoutManager gives it
            // a wide-enough initial width to fill up the width of the grid prior to the view being
            // fully populated.
            final Display display = getActivity().getWindowManager().getDefaultDisplay();
            Point screenSize = ScreenSizeCalculator.getInstance().getScreenSize(display);
            metadataView.setMinimumWidth(
                    screenSize.x - 2 * getResources().getDimensionPixelSize(R.dimen.grid_padding));

            int bothWallpaperImageWidth = getBothWallpaperImageWidth();

            FrameLayout homeWallpaperSection = metadataView.findViewById(
                    R.id.home_wallpaper_section);
            homeWallpaperSection.setMinimumWidth(bothWallpaperImageWidth);
            mHomeWallpaperImage = metadataView.findViewById(R.id.home_wallpaper_image);

            mHomeWallpaperPresentationMode =
                    metadataView.findViewById(R.id.home_wallpaper_presentation_mode);
            mHomeWallpaperTitle = metadataView.findViewById(R.id.home_wallpaper_title);
            mHomeWallpaperSubtitle1 = metadataView.findViewById(R.id.home_wallpaper_subtitle1);
            mHomeWallpaperSubtitle2 = metadataView.findViewById(R.id.home_wallpaper_subtitle2);
            mHomeWallpaperPresentationSection = metadataView.findViewById(
                    R.id.home_wallpaper_presentation_section);
            mHomeWallpaperExploreButton =
                    metadataView.findViewById(R.id.home_wallpaper_explore_button);
            mSkipWallpaperButton = metadataView.findViewById(R.id.skip_home_wallpaper);

            FrameLayout lockWallpaperSection = metadataView.findViewById(
                    R.id.lock_wallpaper_section);
            lockWallpaperSection.setMinimumWidth(bothWallpaperImageWidth);
            mLockWallpaperImage = metadataView.findViewById(R.id.lock_wallpaper_image);

            mLockWallpaperTitle = metadataView.findViewById(R.id.lock_wallpaper_title);
            mLockWallpaperSubtitle1 = metadataView.findViewById(R.id.lock_wallpaper_subtitle1);
            mLockWallpaperSubtitle2 = metadataView.findViewById(R.id.lock_wallpaper_subtitle2);
            mLockWallpaperExploreButton =
                    metadataView.findViewById(R.id.lock_wallpaper_explore_button);
        }

        @Override
        public void bindWallpapers(WallpaperInfo homeWallpaper, WallpaperInfo lockWallpaper,
                @PresentationMode int presentationMode) {
            bindHomeWallpaper(homeWallpaper, presentationMode);
            bindLockWallpaper(lockWallpaper);
        }

        private void bindHomeWallpaper(WallpaperInfo homeWallpaper,
                                       @PresentationMode int presentationMode) {
            final Context appContext = getActivity().getApplicationContext();
            final UserEventLogger eventLogger =
                    InjectorProvider.getInjector().getUserEventLogger(appContext);

            mHomeWallpaperInfo = homeWallpaper;

            homeWallpaper.getThumbAsset(appContext).loadDrawable(
                    getActivity(), mHomeWallpaperImage,
                    getResources().getColor(R.color.secondary_color, getContext().getTheme()));

            mHomeWallpaperPresentationMode.setText(
                    AttributionFormatter.getHumanReadableWallpaperPresentationMode(
                            appContext, presentationMode));

            List<String> attributions = homeWallpaper.getAttributions(appContext);
            if (!attributions.isEmpty()) {
                mHomeWallpaperTitle.setText(attributions.get(0));
            }
            if (attributions.size() > 1) {
                mHomeWallpaperSubtitle1.setText(attributions.get(1));
            }
            if (attributions.size() > 2) {
                mHomeWallpaperSubtitle2.setText(attributions.get(2));
            }

            final String homeActionUrl = homeWallpaper.getActionUrl(appContext);

            if (homeActionUrl != null && !homeActionUrl.isEmpty()) {
                Uri homeExploreUri = Uri.parse(homeActionUrl);

                ExploreIntentChecker intentChecker =
                        InjectorProvider.getInjector().getExploreIntentChecker(appContext);

                intentChecker.fetchValidActionViewIntent(
                    homeExploreUri, (@Nullable Intent exploreIntent) -> {
                        if (exploreIntent == null || getActivity() == null) {
                            return;
                        }

                        mHomeWallpaperExploreButton.setVisibility(View.VISIBLE);
                        mHomeWallpaperExploreButton.setImageDrawable(getContext().getDrawable(
                                homeWallpaper.getActionIconRes(appContext)));
                        mHomeWallpaperExploreButton.setContentDescription(getString(homeWallpaper
                                .getActionLabelRes(appContext)));
                        mHomeWallpaperExploreButton.setColorFilter(
                                getResources().getColor(R.color.currently_set_explore_button_color,
                                        getContext().getTheme()),
                                Mode.SRC_IN);
                        mHomeWallpaperExploreButton.setOnClickListener(v -> {
                            eventLogger.logActionClicked(
                                    mHomeWallpaperInfo.getCollectionId(appContext),
                                    mHomeWallpaperInfo.getActionLabelRes(appContext));
                            startActivity(exploreIntent);
                        });
                    });
            } else {
                mHomeWallpaperExploreButton.setVisibility(View.GONE);
            }

            if (presentationMode == WallpaperPreferences.PRESENTATION_MODE_ROTATING) {
                mHomeWallpaperPresentationSection.setVisibility(View.VISIBLE);
                if (Flags.skipDailyWallpaperButtonEnabled) {
                    mSkipWallpaperButton.setVisibility(View.VISIBLE);
                    mSkipWallpaperButton.setColorFilter(
                            getResources().getColor(R.color.currently_set_explore_button_color,
                                    getContext().getTheme()), Mode.SRC_IN);
                    mSkipWallpaperButton.setOnClickListener(view -> refreshDailyWallpaper());
                } else {
                    mSkipWallpaperButton.setVisibility(View.GONE);
                }
            } else {
                mHomeWallpaperPresentationSection.setVisibility(View.GONE);
            }

            mHomeWallpaperImage.setOnClickListener(v -> {
                eventLogger.logCurrentWallpaperPreviewed();
                getFragmentHost().showViewOnlyPreview(mHomeWallpaperInfo);
            });
        }

        private void bindLockWallpaper(WallpaperInfo lockWallpaper) {
            if (lockWallpaper == null) {
                Log.e(TAG, "TwoWallpapersMetadataHolder bound without a lock screen wallpaper.");
                return;
            }

            final Context appContext = getActivity().getApplicationContext();
            final UserEventLogger eventLogger =
                    InjectorProvider.getInjector().getUserEventLogger(getActivity());

            mLockWallpaperInfo = lockWallpaper;

            lockWallpaper.getThumbAsset(appContext).loadDrawable(
                    getActivity(), mLockWallpaperImage, getResources().getColor(R.color.secondary_color));

            List<String> lockAttributions = lockWallpaper.getAttributions(appContext);
            if (!lockAttributions.isEmpty()) {
                mLockWallpaperTitle.setText(lockAttributions.get(0));
            }
            if (lockAttributions.size() > 1) {
                mLockWallpaperSubtitle1.setText(lockAttributions.get(1));
            }
            if (lockAttributions.size() > 2) {
                mLockWallpaperSubtitle2.setText(lockAttributions.get(2));
            }

            final String lockActionUrl = lockWallpaper.getActionUrl(appContext);

            if (lockActionUrl != null && !lockActionUrl.isEmpty()) {
                Uri lockExploreUri = Uri.parse(lockActionUrl);

                ExploreIntentChecker intentChecker =
                        InjectorProvider.getInjector().getExploreIntentChecker(appContext);
                intentChecker.fetchValidActionViewIntent(
                        lockExploreUri, (@Nullable Intent exploreIntent) -> {
                            if (exploreIntent == null || getActivity() == null) {
                                return;
                            }
                            mLockWallpaperExploreButton.setImageDrawable(getContext().getDrawable(
                                    lockWallpaper.getActionIconRes(appContext)));
                            mLockWallpaperExploreButton.setContentDescription(getString(
                                    lockWallpaper.getActionLabelRes(appContext)));
                            mLockWallpaperExploreButton.setVisibility(View.VISIBLE);
                            mLockWallpaperExploreButton.setColorFilter(
                                    getResources().getColor(
                                            R.color.currently_set_explore_button_color),
                                    Mode.SRC_IN);
                            mLockWallpaperExploreButton.setOnClickListener(new OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    eventLogger.logActionClicked(
                                            mLockWallpaperInfo.getCollectionId(appContext),
                                            mLockWallpaperInfo.getActionLabelRes(appContext));
                                    startActivity(exploreIntent);
                                }
                            });
                        });
            } else {
                mLockWallpaperExploreButton.setVisibility(View.GONE);
            }

            mLockWallpaperImage.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    eventLogger.logCurrentWallpaperPreviewed();
                    getFragmentHost().showViewOnlyPreview(mLockWallpaperInfo);
                }
            });
        }
    }

    private class PreviewPagerAdapter extends PagerAdapter {

        private List<View> mPages;

        PreviewPagerAdapter(List<View> pages) {
            mPages = pages;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position,
                                @NonNull Object object) {
            container.removeView((View) object);
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            View view = mPages.get(position);
            container.addView(view);
            return view;
        }

        @Override
        public int getCount() {
            return mPages.size();
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object o) {
            return view == o;
        }
    }
}
