/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.car.media;

import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_BROWSE;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.apps.common.BackgroundImageView;
import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.apps.common.imaging.ImageBinder.PlaceholderType;
import com.android.car.apps.common.imaging.ImageViewBinder;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MetadataController;
import com.android.car.media.common.PlaybackControlsActionBar;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaSourceViewModel;
import com.android.car.ui.toolbar.MenuItem;
import com.android.car.ui.toolbar.Toolbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


/**
 * A {@link Fragment} that implements both the playback and the content forward browsing experience.
 * It observes a {@link PlaybackViewModel} and updates its information depending on the currently
 * playing media source through the {@link android.media.session.MediaSession} API.
 */
public class PlaybackFragment extends Fragment {
    private static final String TAG = "PlaybackFragment";

    private ImageBinder<MediaItemMetadata.ArtworkRef> mAlbumArtBinder;
    private BackgroundImageView mAlbumBackground;
    private View mBackgroundScrim;
    private View mControlBarScrim;
    private PlaybackControlsActionBar mPlaybackControls;
    private QueueItemsAdapter mQueueAdapter;
    private RecyclerView mQueue;
    private ViewGroup mSeekBarContainer;
    private SeekBar mSeekBar;
    private Toolbar mToolbar;
    private List<View> mViewsToHideForCustomActions;
    private List<View> mViewsToHideWhenQueueIsVisible;
    private List<View> mViewsToShowWhenQueueIsVisible;
    private List<View> mViewsToHideImmediatelyWhenQueueIsVisible;
    private List<View> mViewsToShowImmediatelyWhenQueueIsVisible;

    private DefaultItemAnimator mItemAnimator;

    private MetadataController mMetadataController;

    private PlaybackFragmentListener mListener;

    private PlaybackViewModel.PlaybackController mController;
    private Long mActiveQueueItemId;

    private boolean mHasQueue;
    private boolean mQueueIsVisible;
    private boolean mShowTimeForActiveQueueItem;
    private boolean mShowIconForActiveQueueItem;
    private boolean mShowThumbnailForQueueItem;
    private boolean mShowSubtitleForQueueItem;

    private boolean mShowLinearProgressBar;

    private int mFadeDuration;

    private MediaActivity.ViewModel mViewModel;

    /**
     * PlaybackFragment listener
     */
    public interface PlaybackFragmentListener {
        /**
         * Invoked when the user clicks on the collapse button
         */
        void onCollapse();
    }

    public class QueueViewHolder extends RecyclerView.ViewHolder {

        private final View mView;
        private final ViewGroup mThumbnailContainer;
        private final ImageView mThumbnail;
        private final View mSpacer;
        private final TextView mTitle;
        private final TextView mSubtitle;
        private final TextView mCurrentTime;
        private final TextView mMaxTime;
        private final TextView mTimeSeparator;
        private final ImageView mActiveIcon;

        private final ImageViewBinder<MediaItemMetadata.ArtworkRef> mThumbnailBinder;

        QueueViewHolder(View itemView) {
            super(itemView);
            mView = itemView;
            mThumbnailContainer = itemView.findViewById(R.id.thumbnail_container);
            mThumbnail = itemView.findViewById(R.id.thumbnail);
            mSpacer = itemView.findViewById(R.id.spacer);
            mTitle = itemView.findViewById(R.id.queue_list_item_title);
            mSubtitle = itemView.findViewById(R.id.queue_list_item_subtitle);
            mCurrentTime = itemView.findViewById(R.id.current_time);
            mMaxTime = itemView.findViewById(R.id.max_time);
            mTimeSeparator = itemView.findViewById(R.id.separator);
            mActiveIcon = itemView.findViewById(R.id.now_playing_icon);

            Size maxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(itemView.getContext());
            mThumbnailBinder = new ImageViewBinder<>(maxArtSize, mThumbnail);
        }

        void bind(MediaItemMetadata item) {
            mView.setOnClickListener(v -> onQueueItemClicked(item));

            ViewUtils.setVisible(mThumbnailContainer, mShowThumbnailForQueueItem);
            if (mShowThumbnailForQueueItem) {
                Context context = mView.getContext();
                mThumbnailBinder.setImage(context, item != null ? item.getArtworkKey() : null);
            }

            ViewUtils.setVisible(mSpacer, !mShowThumbnailForQueueItem);

            mTitle.setText(item.getTitle());

            boolean active = mActiveQueueItemId != null && Objects.equals(mActiveQueueItemId,
                    item.getQueueId());
            if (active) {
                mCurrentTime.setText(mQueueAdapter.getCurrentTime());
                mMaxTime.setText(mQueueAdapter.getMaxTime());
            }
            boolean shouldShowTime =
                    mShowTimeForActiveQueueItem && active && mQueueAdapter.getTimeVisible();
            ViewUtils.setVisible(mCurrentTime, shouldShowTime);
            ViewUtils.setVisible(mMaxTime, shouldShowTime);
            ViewUtils.setVisible(mTimeSeparator, shouldShowTime);

            mView.setSelected(active);

            boolean shouldShowIcon = mShowIconForActiveQueueItem && active;
            ViewUtils.setVisible(mActiveIcon, shouldShowIcon);

            if (mShowSubtitleForQueueItem) {
                mSubtitle.setText(item.getSubtitle());
            }
        }

        void onViewAttachedToWindow() {
            if (mShowThumbnailForQueueItem) {
                Context context = mView.getContext();
                mThumbnailBinder.maybeRestartLoading(context);
            }
        }

        void onViewDetachedFromWindow() {
            if (mShowThumbnailForQueueItem) {
                Context context = mView.getContext();
                mThumbnailBinder.maybeCancelLoading(context);
            }
        }
    }


    private class QueueItemsAdapter extends RecyclerView.Adapter<QueueViewHolder> {

        private List<MediaItemMetadata> mQueueItems = Collections.emptyList();
        private String mCurrentTimeText = "";
        private String mMaxTimeText = "";
        private Integer mActiveItemPos;
        private boolean mTimeVisible;

        void setItems(@Nullable List<MediaItemMetadata> items) {
            List<MediaItemMetadata> newQueueItems =
                new ArrayList<>(items != null ? items : Collections.emptyList());
            if (newQueueItems.equals(mQueueItems)) {
                return;
            }
            mQueueItems = newQueueItems;
            updateActiveItem();
            notifyDataSetChanged();
        }

        // Updates mActiveItemPos, then scrolls the queue to mActiveItemPos.
        // It should be called when the active item (mActiveQueueItemId) changed or
        // the queue items (mQueueItems) changed.
        void updateActiveItem() {
            if (mQueueItems == null || mActiveQueueItemId == null) {
                mActiveItemPos = null;
                return;
            }
            Integer activeItemPos = null;
            for (int i = 0; i < mQueueItems.size(); i++) {
                if (mQueueItems.get(i).getQueueId() == mActiveQueueItemId) {
                    activeItemPos = i;
                    break;
                }
            }

            if (mActiveItemPos != activeItemPos) {
                if (mActiveItemPos != null) {
                    notifyItemChanged(mActiveItemPos.intValue());
                }
                mActiveItemPos = activeItemPos;
                if (mActiveItemPos != null) {
                    mQueue.scrollToPosition(mActiveItemPos.intValue());
                    notifyItemChanged(mActiveItemPos.intValue());
                }
            }
        }

        void setCurrentTime(String currentTime) {
            if (!mCurrentTimeText.equals(currentTime)) {
                mCurrentTimeText = currentTime;
                if (mActiveItemPos != null) {
                    notifyItemChanged(mActiveItemPos.intValue());
                }
            }
        }

        void setMaxTime(String maxTime) {
            if (!mMaxTimeText.equals(maxTime)) {
                mMaxTimeText = maxTime;
                if (mActiveItemPos != null) {
                    notifyItemChanged(mActiveItemPos.intValue());
                }
            }
        }

        void setTimeVisible(boolean visible) {
            if (mTimeVisible != visible) {
                mTimeVisible = visible;
                if (mActiveItemPos != null) {
                    notifyItemChanged(mActiveItemPos.intValue());
                }
            }
        }

        String getCurrentTime() {
            return mCurrentTimeText;
        }

        String getMaxTime() {
            return mMaxTimeText;
        }

        boolean getTimeVisible() {
            return mTimeVisible;
        }

        @Override
        public QueueViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            return new QueueViewHolder(inflater.inflate(R.layout.queue_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(QueueViewHolder holder, int position) {
            int size = mQueueItems.size();
            if (0 <= position && position < size) {
                holder.bind(mQueueItems.get(position));
            } else {
                Log.e(TAG, "onBindViewHolder invalid position " + position + " of " + size);
            }
        }

        @Override
        public void onViewAttachedToWindow(@NonNull QueueViewHolder holder) {
            super.onViewAttachedToWindow(holder);
            holder.onViewAttachedToWindow();
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull QueueViewHolder holder) {
            super.onViewDetachedFromWindow(holder);
            holder.onViewDetachedFromWindow();
        }

        @Override
        public int getItemCount() {
            return mQueueItems.size();
        }

        void refresh() {
            // TODO: Perform a diff between current and new content and trigger the proper
            // RecyclerView updates.
            this.notifyDataSetChanged();
        }

        @Override
        public long getItemId(int position) {
            return mQueueItems.get(position).getQueueId();
        }
    }

    private class QueueTopItemDecoration extends RecyclerView.ItemDecoration {
        int mHeight;
        int mDecorationPosition;

        QueueTopItemDecoration(int height, int decorationPosition) {
            mHeight = height;
            mDecorationPosition = decorationPosition;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                RecyclerView.State state) {
            super.getItemOffsets(outRect, view, parent, state);
            if (parent.getChildAdapterPosition(view) == mDecorationPosition) {
                outRect.top = mHeight;
            }
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, final ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_playback, container, false);
        mAlbumBackground = view.findViewById(R.id.playback_background);
        mQueue = view.findViewById(R.id.queue_list);
        mSeekBarContainer = view.findViewById(R.id.seek_bar_container);
        mSeekBar = view.findViewById(R.id.seek_bar);
        mToolbar = view.findViewById(R.id.toolbar);
        if (mToolbar != null) {
            mToolbar.setBackgroundShown(false);
            mToolbar.setNavButtonMode(Toolbar.NavButtonMode.DOWN);

            // Notify listeners when toolbar's down button is pressed.
            mToolbar.registerOnBackListener(() -> {
                if (mListener != null) {
                    mListener.onCollapse();
                }
                return true;
            });

            // Update toolbar's logo
            MediaSourceViewModel mediaSourceViewModel = getMediaSourceViewModel();
            mediaSourceViewModel.getPrimaryMediaSource().observe(this, mediaSource ->
                mToolbar.setLogo(mediaSource != null
                        ? new BitmapDrawable(getResources(), mediaSource.getCroppedPackageIcon())
                        : null));
        }
        mBackgroundScrim = view.findViewById(R.id.background_scrim);
        ViewUtils.setVisible(mBackgroundScrim, false);
        mControlBarScrim = view.findViewById(R.id.control_bar_scrim);
        if (mControlBarScrim != null) {
            ViewUtils.setVisible(mControlBarScrim, false);
            mControlBarScrim.setOnClickListener(scrim -> mPlaybackControls.close());
            mControlBarScrim.setClickable(false);
        }

        Resources res = getResources();
        mShowTimeForActiveQueueItem = res.getBoolean(
                R.bool.show_time_for_now_playing_queue_list_item);
        mShowIconForActiveQueueItem = res.getBoolean(
                R.bool.show_icon_for_now_playing_queue_list_item);
        mShowThumbnailForQueueItem = getContext().getResources().getBoolean(
                R.bool.show_thumbnail_for_queue_list_item);
        mShowLinearProgressBar = getContext().getResources().getBoolean(
                R.bool.show_linear_progress_bar);
        mShowSubtitleForQueueItem = getContext().getResources().getBoolean(
            R.bool.show_subtitle_for_queue_list_item);

        if (mSeekBar != null) {
            if (mShowLinearProgressBar) {
                boolean useMediaSourceColor = res.getBoolean(
                        R.bool.use_media_source_color_for_progress_bar);
                int defaultColor = res.getColor(R.color.progress_bar_highlight, null);
                if (useMediaSourceColor) {
                    getPlaybackViewModel().getMediaSourceColors().observe(getViewLifecycleOwner(),
                            sourceColors -> {
                                int color = sourceColors != null ? sourceColors.getAccentColor(
                                        defaultColor)
                                        : defaultColor;
                                mSeekBar.setThumbTintList(ColorStateList.valueOf(color));
                                mSeekBar.setProgressTintList(ColorStateList.valueOf(color));
                            });
                } else {
                    mSeekBar.setThumbTintList(ColorStateList.valueOf(defaultColor));
                    mSeekBar.setProgressTintList(ColorStateList.valueOf(defaultColor));
                }
            } else {
                mSeekBar.setVisibility(View.GONE);
            }
        }

        mViewModel = ViewModelProviders.of(requireActivity()).get(MediaActivity.ViewModel.class);

        getPlaybackViewModel().getPlaybackController().observe(getViewLifecycleOwner(),
                controller -> mController = controller);
        initPlaybackControls(view.findViewById(R.id.playback_controls));
        initMetadataController(view);
        initQueue();

        // Don't update the visibility of seekBar if show_linear_progress_bar is false.
        ViewUtils.Filter ignoreSeekBarFilter =
            (viewToFilter) -> mShowLinearProgressBar || viewToFilter != mSeekBarContainer;

        mViewsToHideForCustomActions = ViewUtils.getViewsById(view, res,
            R.array.playback_views_to_hide_when_showing_custom_actions, ignoreSeekBarFilter);
        mViewsToHideWhenQueueIsVisible = ViewUtils.getViewsById(view, res,
            R.array.playback_views_to_hide_when_queue_is_visible, ignoreSeekBarFilter);
        mViewsToShowWhenQueueIsVisible = ViewUtils.getViewsById(view, res,
            R.array.playback_views_to_show_when_queue_is_visible, null);
        mViewsToHideImmediatelyWhenQueueIsVisible = ViewUtils.getViewsById(view, res,
            R.array.playback_views_to_hide_immediately_when_queue_is_visible, ignoreSeekBarFilter);
        mViewsToShowImmediatelyWhenQueueIsVisible = ViewUtils.getViewsById(view, res,
            R.array.playback_views_to_show_immediately_when_queue_is_visible, null);

        mAlbumArtBinder = new ImageBinder<>(
                PlaceholderType.BACKGROUND,
                MediaAppConfig.getMediaItemsBitmapMaxSize(getContext()),
                drawable -> mAlbumBackground.setBackgroundDrawable(drawable));

        getPlaybackViewModel().getMetadata().observe(getViewLifecycleOwner(),
                item -> mAlbumArtBinder.setImage(PlaybackFragment.this.getContext(),
                        item != null ? item.getArtworkKey() : null));

        return view;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
    }

    @Override
    public void onDetach() {
        super.onDetach();
    }

    private void initPlaybackControls(PlaybackControlsActionBar playbackControls) {
        mPlaybackControls = playbackControls;
        mPlaybackControls.setModel(getPlaybackViewModel(), getViewLifecycleOwner());
        mPlaybackControls.registerExpandCollapseCallback((expanding) -> {
            Resources res = getContext().getResources();
            int millis = expanding ? res.getInteger(R.integer.control_bar_expand_anim_duration) :
                res.getInteger(R.integer.control_bar_collapse_anim_duration);

            if (mControlBarScrim != null) {
                mControlBarScrim.setClickable(expanding);
            }

            if (expanding) {
                if (mControlBarScrim != null) {
                    ViewUtils.showViewAnimated(mControlBarScrim, millis);
                }
            } else {
                if (mControlBarScrim != null) {
                    ViewUtils.hideViewAnimated(mControlBarScrim, millis);
                }
            }

            if (!mQueueIsVisible) {
                for (View view : mViewsToHideForCustomActions) {
                    if (expanding) {
                        ViewUtils.hideViewAnimated(view, millis);
                    } else {
                        ViewUtils.showViewAnimated(view, millis);
                    }
                }
            }
        });
    }

    private void initQueue() {
        mFadeDuration = getResources().getInteger(
                R.integer.fragment_playback_queue_fade_duration_ms);

        int decorationHeight = getResources().getDimensionPixelSize(
                R.dimen.playback_queue_list_padding_top);
        // Put the decoration above the first item.
        int decorationPosition = 0;
        mQueue.addItemDecoration(new QueueTopItemDecoration(decorationHeight, decorationPosition));

        mQueue.setVerticalFadingEdgeEnabled(
                getResources().getBoolean(R.bool.queue_fading_edge_length_enabled));
        mQueueAdapter = new QueueItemsAdapter();

        getPlaybackViewModel().getPlaybackStateWrapper().observe(getViewLifecycleOwner(),
                state -> {
                    Long itemId = (state != null) ? state.getActiveQueueItemId() : null;
                    if (!Objects.equals(mActiveQueueItemId, itemId)) {
                        mActiveQueueItemId = itemId;
                        mQueueAdapter.updateActiveItem();
                    }
                });
        mQueue.setAdapter(mQueueAdapter);

        // Disable item changed animation.
        mItemAnimator = new DefaultItemAnimator();
        mItemAnimator.setSupportsChangeAnimations(false);
        mQueue.setItemAnimator(mItemAnimator);

        getPlaybackViewModel().getQueue().observe(this, this::setQueue);

        getPlaybackViewModel().hasQueue().observe(getViewLifecycleOwner(), hasQueue -> {
            boolean enableQueue = (hasQueue != null) && hasQueue;
            mQueueIsVisible = mViewModel.getQueueVisible();
            setHasQueue(enableQueue);
        });
        getPlaybackViewModel().getProgress().observe(getViewLifecycleOwner(),
                playbackProgress ->
                {
                    mQueueAdapter.setCurrentTime(playbackProgress.getCurrentTimeText().toString());
                    mQueueAdapter.setMaxTime(playbackProgress.getMaxTimeText().toString());
                    mQueueAdapter.setTimeVisible(playbackProgress.hasTime());
                });
    }

    private void setQueue(List<MediaItemMetadata> queueItems) {
        mQueueAdapter.setItems(queueItems);
    }

    private void initMetadataController(View view) {
        ImageView albumArt = view.findViewById(R.id.album_art);
        TextView title = view.findViewById(R.id.title);
        TextView artist = view.findViewById(R.id.artist);
        TextView albumTitle = view.findViewById(R.id.album_title);
        TextView outerSeparator = view.findViewById(R.id.outer_separator);
        TextView curTime = view.findViewById(R.id.current_time);
        TextView innerSeparator = view.findViewById(R.id.inner_separator);
        TextView maxTime = view.findViewById(R.id.max_time);
        SeekBar seekbar = mShowLinearProgressBar ? mSeekBar : null;

        Size maxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(view.getContext());
        mMetadataController = new MetadataController(getViewLifecycleOwner(),
                getPlaybackViewModel(), title, artist, albumTitle, outerSeparator,
                curTime, innerSeparator, maxTime, seekbar, albumArt, maxArtSize);
    }

    /**
     * Hides or shows the playback queue when the user clicks the queue button.
     */
    private void toggleQueueVisibility() {
        boolean updatedQueueVisibility = !mQueueIsVisible;
        setQueueVisible(updatedQueueVisibility);

        // When the visibility of queue is changed by the user, save the visibility into ViewModel
        // so that we can restore PlaybackFragment properly when needed. If it's changed by media
        // source change (media source changes -> hasQueue becomes false -> queue is hidden), don't
        // save it.
        mViewModel.setQueueVisible(updatedQueueVisibility);
    }

    private void setQueueVisible(boolean visible) {
        mQueueIsVisible = visible;

        if (mToolbar != null) {
            if (mHasQueue) {
                MenuItem queueMenuItem = MenuItem.builder(getContext())
                        .setIcon(R.drawable.ic_queue_button)
                        .setActivated(mQueueIsVisible)
                        .setOnClickListener(button -> toggleQueueVisibility())
                        .build();
                mToolbar.setMenuItems(Collections.singletonList(queueMenuItem));
            } else {
                mToolbar.setMenuItems(Collections.emptyList());
            }
        }

        if (mQueueIsVisible) {
            ViewUtils.showViewsAnimated(mViewsToShowWhenQueueIsVisible, mFadeDuration);
            ViewUtils.hideViewsAnimated(mViewsToHideWhenQueueIsVisible, mFadeDuration);
            ViewUtils.setVisible(mViewsToShowImmediatelyWhenQueueIsVisible, true);
            ViewUtils.setVisible(mViewsToHideImmediatelyWhenQueueIsVisible, false);
        } else {
            ViewUtils.hideViewsAnimated(mViewsToShowWhenQueueIsVisible, mFadeDuration);
            ViewUtils.showViewsAnimated(mViewsToHideWhenQueueIsVisible, mFadeDuration);
            ViewUtils.setVisible(mViewsToShowImmediatelyWhenQueueIsVisible, false);
            ViewUtils.setVisible(mViewsToHideImmediatelyWhenQueueIsVisible, true);
        }
    }

    /** Sets whether the source has a queue. */
    private void setHasQueue(boolean hasQueue) {
        mHasQueue = hasQueue;
        setQueueVisible(hasQueue && mQueueIsVisible);
    }

    private void onQueueItemClicked(MediaItemMetadata item) {
        if (mController != null) {
            mController.skipToQueueItem(item.getQueueId());
        }
        boolean switchToPlayback = getResources().getBoolean(
                R.bool.switch_to_playback_view_when_playable_item_is_clicked);
        if (switchToPlayback) {
            toggleQueueVisibility();
        }
    }

    /**
     * Collapses the playback controls.
     */
    public void closeOverflowMenu() {
        mPlaybackControls.close();
    }

    // TODO(b/151174811): Use appropriate modes, instead of just MEDIA_SOURCE_MODE_BROWSE
    private PlaybackViewModel getPlaybackViewModel() {
        return PlaybackViewModel.get(getActivity().getApplication(), MEDIA_SOURCE_MODE_BROWSE);
    }

    private MediaSourceViewModel getMediaSourceViewModel() {
        return MediaSourceViewModel.get(getActivity().getApplication(), MEDIA_SOURCE_MODE_BROWSE);
    }

    /**
     * Sets a listener of this PlaybackFragment events. In order to avoid memory leaks, consumers
     * must reset this reference by setting the listener to null.
     */
    public void setListener(PlaybackFragmentListener listener) {
        mListener = listener;
    }
}
