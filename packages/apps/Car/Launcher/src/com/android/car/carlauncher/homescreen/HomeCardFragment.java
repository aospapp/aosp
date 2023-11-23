/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.carlauncher.homescreen;

import static com.android.car.carlauncher.homescreen.ui.CardContent.HomeCardContentType.DESCRIPTIVE_TEXT_WITH_CONTROLS;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.android.car.apps.common.CrossfadeImageView;
import com.android.car.carlauncher.R;
import com.android.car.carlauncher.homescreen.audio.MediaViewModel.PlaybackCallback;
import com.android.car.carlauncher.homescreen.ui.CardContent;
import com.android.car.carlauncher.homescreen.ui.CardHeader;
import com.android.car.carlauncher.homescreen.ui.DescriptiveTextView;
import com.android.car.carlauncher.homescreen.ui.DescriptiveTextWithControlsView;
import com.android.car.carlauncher.homescreen.ui.SeekBarViewModel;
import com.android.car.carlauncher.homescreen.ui.TextBlockView;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

/**
 * Abstract class for a {@link Fragment} that implements the Home App's View interface.
 *
 * {@link HomeCardInterface.View} classes that extend HomeCardFragment will override the update
 * methods of the types of CardContent that they support. Each CardContent class corresponds to a
 * layout shown on the card. The layout is a combination of xml files.
 * {@link DescriptiveTextWithControlsView}: card_content_descriptive_text, card_content_button_trio
 * {@link DescriptiveTextView}: card_content_descriptive_text, card_content_tap_for_more_text
 * {@link TextBlockView}: card_content_text_block, card_content_tap_for_more_text
 */
public class HomeCardFragment extends Fragment implements HomeCardInterface.View {

    private HomeCardInterface.Presenter mPresenter;
    private Size mSize;
    private View mCardBackground;
    private CrossfadeImageView mCardBackgroundImage;
    private View mRootView;
    private TextView mCardTitle;
    private ImageView mCardIcon;
    private ProgressBar mOptionalProgressBar;
    private SeekBar mOptionalSeekBar;
    private TextView mOptionalTimes;
    private ViewGroup mOptionalSeekBarWithTimesContainer;
    private int mOptionalSeekBarColor;

    // Views from card_content_text_block.xml
    private View mTextBlockLayoutView;
    private TextView mTextBlock;
    private TextView mTextBlockTapForMore;

    // Views from card_content_descriptive_text_only.xml
    private View mDescriptiveTextOnlyLayoutView;
    private ImageView mDescriptiveTextOnlyOptionalImage;
    private TextView mDescriptiveTextOnlyTitle;
    private TextView mDescriptiveTextOnlySubtitle;
    private TextView mDescriptiveTextOnlyTapForMore;

    // Views from card_content_descriptive_text_with_controls.xml
    private View mDescriptiveTextWithControlsLayoutView;
    private ImageView mDescriptiveTextWithControlsOptionalImage;
    private TextView mDescriptiveTextWithControlsTitle;
    private TextView mDescriptiveTextWithControlsSubtitle;
    private View mControlBarView;
    private ImageButton mControlBarLeftButton;
    private ImageButton mControlBarCenterButton;
    private ImageButton mControlBarRightButton;

    private boolean mTrackingTouch;
    private PlaybackCallback mPlaybackCallback;
    private SeekBar.OnSeekBarChangeListener mOnSeekBarChangeListener =
            new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mTrackingTouch = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (mTrackingTouch) {
                        mPlaybackCallback.seekTo(seekBar.getProgress());
                    }
                    mTrackingTouch = false;
                }
            };

    @Override
    public void setPresenter(HomeCardInterface.Presenter presenter) {
        mPresenter = presenter;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.card_fragment, container, false);
        mCardTitle = mRootView.findViewById(R.id.card_name);
        mCardIcon = mRootView.findViewById(R.id.card_icon);
        return mRootView;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mPresenter.onViewCreated();
        mRootView.setOnClickListener(v -> mPresenter.onViewClicked(v));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }
        mSize = null;
    }

    @Override
    public Fragment getFragment() {
        return this;
    }

    /**
     * Returns the size of the card or null if the view hasn't yet been laid out
     */
    protected Size getCardSize() {
        if (mSize == null && mRootView.isLaidOut()) {
            mSize = new Size(mRootView.getWidth(), mRootView.getHeight());
        }
        return mSize;
    }

    /**
     * Removes the audio card from view
     */
    @Override
    public void hideCard() {
        hideAllViews();
        mRootView.setVisibility(View.GONE);
    }

    /**
     * Updates the card's header: name and icon of source app
     */
    @Override
    public void updateHeaderView(CardHeader header) {
        requireActivity().runOnUiThread(() -> {
            mRootView.setVisibility(View.VISIBLE);
            mCardTitle.setText(header.getCardTitle());
            mCardIcon.setImageDrawable(header.getCardIcon());
        });
    }

    @Override
    public final void updateContentView(CardContent content) {
        requireActivity().runOnUiThread(() -> {
            hideAllViews();
            updateContentViewInternal(content);
        });
    }

    @Override
    public void updateContentView(CardContent content, boolean updateProgress) {
        requireActivity().runOnUiThread(() ->
                updateSeekBarAndTimes(content, updateProgress)
        );
    }


    /**
     * Child classes can override this method for updating their specific types of card content
     */
    protected void updateContentViewInternal(CardContent content) {
        switch (content.getType()) {
            case DESCRIPTIVE_TEXT:
                DescriptiveTextView descriptiveTextContent = (DescriptiveTextView) content;
                updateDescriptiveTextOnlyView(descriptiveTextContent.getTitle(),
                        descriptiveTextContent.getSubtitle(), descriptiveTextContent.getImage(),
                        descriptiveTextContent.getFooter());
                break;
            case DESCRIPTIVE_TEXT_WITH_CONTROLS:
                DescriptiveTextWithControlsView descriptiveTextWithControlsContent =
                        (DescriptiveTextWithControlsView) content;
                updateDescriptiveTextWithControlsView(descriptiveTextWithControlsContent.getTitle(),
                        descriptiveTextWithControlsContent.getSubtitle(),
                        descriptiveTextWithControlsContent.getImage(),
                        descriptiveTextWithControlsContent.getLeftControl(),
                        descriptiveTextWithControlsContent.getCenterControl(),
                        descriptiveTextWithControlsContent.getRightControl());
                break;
            case TEXT_BLOCK:
                TextBlockView textBlockContent = (TextBlockView) content;
                updateTextBlock(textBlockContent.getText(), textBlockContent.getFooter());
                break;
        }
    }

    protected void updateSeekBarAndTimes(CardContent content, boolean updateProgress) {
        DescriptiveTextWithControlsView descriptiveTextWithControlsContent =
                (DescriptiveTextWithControlsView) content;
        if (!isSeekbarWithTimesAvailable() || content.getType() != DESCRIPTIVE_TEXT_WITH_CONTROLS
                || descriptiveTextWithControlsContent.getSeekBarViewModel() == null) {
            return;
        }

        SeekBarViewModel seekBarViewModel =
                descriptiveTextWithControlsContent.getSeekBarViewModel();
        boolean shouldUseSeekBar = seekBarViewModel.isSeekEnabled();

        if (updateProgress) {
            ProgressBar progressBar = shouldUseSeekBar ? getOptionalSeekBar()
                    : getOptionalProgressBar();
            progressBar.setProgress(seekBarViewModel.getProgress(), /* animate = */ true);
        } else {
            SeekBar seekBar = getOptionalSeekBar();
            ProgressBar progressBar = getOptionalProgressBar();
            if (shouldUseSeekBar) {
                updateSeekBar(seekBar, seekBarViewModel);
            } else {
                updateProgressBar(progressBar, seekBarViewModel);
            }
            seekBar.setVisibility(shouldUseSeekBar ? View.VISIBLE : View.GONE);
            progressBar.setVisibility(shouldUseSeekBar ? View.GONE : View.VISIBLE);
        }
        getOptionalTimes().setText(seekBarViewModel.getTimes());

    }

    private void updateProgressBar(ProgressBar progressBar, SeekBarViewModel seekBarViewModel) {
        if (mOptionalSeekBarColor != seekBarViewModel.getSeekBarColor()) {
            mOptionalSeekBarColor = seekBarViewModel.getSeekBarColor();
            progressBar.setProgressTintList(ColorStateList.valueOf(mOptionalSeekBarColor));
        }
        progressBar.setProgress(seekBarViewModel.getProgress(), /* animate = */ true);
    }

    private void updateSeekBar(SeekBar seekBar, SeekBarViewModel seekBarViewModel) {
        mPlaybackCallback = seekBarViewModel.getPlaybackCallback();
        seekBar.setOnSeekBarChangeListener(mOnSeekBarChangeListener);
        if (mOptionalSeekBarColor != seekBarViewModel.getSeekBarColor()) {
            mOptionalSeekBarColor = seekBarViewModel.getSeekBarColor();
            seekBar.setThumbTintList(ColorStateList.valueOf(mOptionalSeekBarColor));
            seekBar.setProgressTintList(ColorStateList.valueOf(mOptionalSeekBarColor));
        }
        seekBar.setProgress(seekBarViewModel.getProgress(), /* animate = */ true);
    }

    private boolean isSeekbarWithTimesAvailable() {
        return (getOptionalSeekbarWithTimesContainer() != null
                && getOptionalSeekbarWithTimesContainer().getVisibility() == View.VISIBLE)
                && getOptionalSeekBar() != null
                && getOptionalTimes() != null;
    }

    protected final void updateDescriptiveTextOnlyView(CharSequence primaryText,
            CharSequence secondaryText, Drawable optionalImage, CharSequence tapForMoreText) {
        getDescriptiveTextOnlyLayoutView().setVisibility(View.VISIBLE);
        mDescriptiveTextOnlyTitle.setText(primaryText);
        mDescriptiveTextOnlySubtitle.setText(secondaryText);
        mDescriptiveTextOnlyOptionalImage.setImageDrawable(optionalImage);
        mDescriptiveTextOnlyOptionalImage.setVisibility(
                optionalImage == null ? View.GONE : View.VISIBLE);
        mDescriptiveTextOnlyTapForMore.setText(tapForMoreText);
        mDescriptiveTextOnlyTapForMore.setVisibility(
                tapForMoreText == null ? View.GONE : View.VISIBLE);
    }

    protected final void updateDescriptiveTextWithControlsView(CharSequence primaryText,
            CharSequence secondaryText, CardContent.CardBackgroundImage optionalImage,
            DescriptiveTextWithControlsView.Control leftButton,
            DescriptiveTextWithControlsView.Control centerButton,
            DescriptiveTextWithControlsView.Control rightButton) {
        getDescriptiveTextWithControlsLayoutView().setVisibility(View.VISIBLE);
        mDescriptiveTextWithControlsTitle.setText(primaryText);
        mDescriptiveTextWithControlsSubtitle.setText(secondaryText);
        if (optionalImage != null) {
            mDescriptiveTextWithControlsOptionalImage.setImageDrawable(
                    optionalImage.getForeground());
        }
        mDescriptiveTextWithControlsOptionalImage.setVisibility(
                (optionalImage == null || optionalImage.getForeground() == null) ? View.GONE
                        : View.VISIBLE);

        updateControlBarButton(leftButton, mControlBarLeftButton);
        updateControlBarButton(centerButton, mControlBarCenterButton);
        updateControlBarButton(rightButton, mControlBarRightButton);
    }

    private void updateControlBarButton(DescriptiveTextWithControlsView.Control buttonContent,
            ImageButton buttonView) {
        if (buttonContent != null) {
            buttonView.setImageDrawable(buttonContent.getIcon());
            if (buttonContent.getIcon() != null) {
                // update the button view according to icon's selected state
                buttonView.setSelected(ArrayUtils.contains(buttonContent.getIcon().getState(),
                        android.R.attr.state_selected));
            }
            buttonView.setOnClickListener(buttonContent.getOnClickListener());
            buttonView.setVisibility(View.VISIBLE);
        } else {
            buttonView.setVisibility(View.GONE);
        }
    }

    protected final void updateTextBlock(CharSequence mainText, CharSequence tapForMoreText) {
        getTextBlockLayoutView().setVisibility(View.VISIBLE);
        mTextBlock.setText(mainText);
        mTextBlockTapForMore.setText(tapForMoreText);
        mTextBlockTapForMore.setVisibility(tapForMoreText == null ? View.GONE : View.VISIBLE);
    }

    protected void hideAllViews() {
        getTextBlockLayoutView().setVisibility(View.GONE);
        getDescriptiveTextOnlyLayoutView().setVisibility(View.GONE);
        getDescriptiveTextWithControlsLayoutView().setVisibility(View.GONE);
        getOptionalSeekbarWithTimesContainer().setVisibility(View.GONE);
    }

    protected final View getRootView() {
        return mRootView;
    }

    protected final View getCardBackground() {
        if (mCardBackground == null) {
            mCardBackground = getRootView().findViewById(R.id.card_background);
        }
        return mCardBackground;
    }

    protected final CrossfadeImageView getCardBackgroundImage() {
        if (mCardBackgroundImage == null) {
            mCardBackgroundImage = getRootView().findViewById(R.id.card_background_image);
        }
        return mCardBackgroundImage;
    }

    private ProgressBar getOptionalProgressBar() {
        if (mOptionalProgressBar == null) {
            mOptionalProgressBar = getRootView().findViewById(R.id.optional_progress_bar);
        }
        return mOptionalProgressBar;
    }

    private SeekBar getOptionalSeekBar() {
        if (mOptionalSeekBar == null) {
            mOptionalSeekBar = getRootView().findViewById(R.id.optional_seek_bar);
        }
        return mOptionalSeekBar;
    }

    private TextView getOptionalTimes() {
        if (mOptionalTimes == null) {
            mOptionalTimes = getRootView().findViewById(R.id.optional_times);
        }
        return mOptionalTimes;
    }

    protected ViewGroup getOptionalSeekbarWithTimesContainer() {
        if (mOptionalSeekBarWithTimesContainer == null) {
            mOptionalSeekBarWithTimesContainer = getRootView().findViewById(
                    R.id.optional_seek_bar_with_times_container);
        }
        return mOptionalSeekBarWithTimesContainer;
    }

    protected final View getDescriptiveTextOnlyLayoutView() {
        if (mDescriptiveTextOnlyLayoutView == null) {
            ViewStub stub = mRootView.findViewById(R.id.descriptive_text_layout);
            mDescriptiveTextOnlyLayoutView = stub.inflate();
            mDescriptiveTextOnlyTitle = mDescriptiveTextOnlyLayoutView.findViewById(
                    R.id.primary_text);
            mDescriptiveTextOnlySubtitle = mDescriptiveTextOnlyLayoutView.findViewById(
                    R.id.secondary_text);
            mDescriptiveTextOnlyOptionalImage = mDescriptiveTextOnlyLayoutView.findViewById(
                    R.id.optional_image);
            mDescriptiveTextOnlyTapForMore = mDescriptiveTextOnlyLayoutView.findViewById(
                    R.id.tap_for_more_text);
        }
        return mDescriptiveTextOnlyLayoutView;
    }

    protected final View getDescriptiveTextWithControlsLayoutView() {
        if (mDescriptiveTextWithControlsLayoutView == null) {
            ViewStub stub = mRootView.findViewById(R.id.descriptive_text_with_controls_layout);
            mDescriptiveTextWithControlsLayoutView = stub.inflate();
            mDescriptiveTextWithControlsTitle = mDescriptiveTextWithControlsLayoutView.findViewById(
                    R.id.primary_text);
            mDescriptiveTextWithControlsSubtitle =
                    mDescriptiveTextWithControlsLayoutView.findViewById(R.id.secondary_text);
            mDescriptiveTextWithControlsOptionalImage =
                    mDescriptiveTextWithControlsLayoutView.findViewById(R.id.optional_image);
            mControlBarView = mDescriptiveTextWithControlsLayoutView.findViewById(R.id.button_trio);
            mControlBarLeftButton = mDescriptiveTextWithControlsLayoutView.findViewById(
                    R.id.button_left);
            mControlBarCenterButton = mDescriptiveTextWithControlsLayoutView.findViewById(
                    R.id.button_center);
            mControlBarRightButton = mDescriptiveTextWithControlsLayoutView.findViewById(
                    R.id.button_right);
        }
        return mDescriptiveTextWithControlsLayoutView;
    }

    private View getTextBlockLayoutView() {
        if (mTextBlockLayoutView == null) {
            ViewStub stub = mRootView.findViewById(R.id.text_block_layout);
            mTextBlockLayoutView = stub.inflate();
            mTextBlock = mTextBlockLayoutView.findViewById(R.id.text_block);
            mTextBlockTapForMore = mTextBlockLayoutView.findViewById(R.id.tap_for_more_text);
        }
        return mTextBlockLayoutView;
    }

    @VisibleForTesting
    void setControlBarLeftButton(ImageButton controlBarLeftButton) {
        mControlBarLeftButton = controlBarLeftButton;
    }
}
