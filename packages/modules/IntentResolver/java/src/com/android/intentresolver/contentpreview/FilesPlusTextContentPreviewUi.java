/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.intentresolver.contentpreview;

import static com.android.intentresolver.contentpreview.ContentPreviewType.CONTENT_PREVIEW_FILE;
import static com.android.intentresolver.contentpreview.ContentPreviewType.CONTENT_PREVIEW_IMAGE;

import android.content.res.Resources;
import android.net.Uri;
import android.text.util.Linkify;
import android.util.PluralsMessageFormatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;

import com.android.intentresolver.R;
import com.android.intentresolver.widget.ActionRow;
import com.android.intentresolver.widget.ScrollableImagePreviewView;

import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * FilesPlusTextContentPreviewUi is shown when the user is sending 1 or more files along with
 * non-empty EXTRA_TEXT. The text can be toggled with a checkbox. If a single image file is being
 * shared, it is shown in a preview (otherwise the headline summary is the sole indication of the
 * file content).
 */
class FilesPlusTextContentPreviewUi extends ContentPreviewUi {
    private final Lifecycle mLifecycle;
    private final CharSequence mText;
    private final ChooserContentPreviewUi.ActionFactory mActionFactory;
    private final ImageLoader mImageLoader;
    private final MimeTypeClassifier mTypeClassifier;
    private final HeadlineGenerator mHeadlineGenerator;
    private final boolean mIsSingleImage;
    private final int mFileCount;
    private ViewGroup mContentPreviewView;
    private boolean mIsMetadataUpdated = false;
    @Nullable
    private Uri mFirstFilePreviewUri;
    private boolean mAllImages;
    private boolean mAllVideos;
    // TODO(b/285309527): make this a flag
    private static final boolean SHOW_TOGGLE_CHECKMARK = false;

    FilesPlusTextContentPreviewUi(
            Lifecycle lifecycle,
            boolean isSingleImage,
            int fileCount,
            CharSequence text,
            ChooserContentPreviewUi.ActionFactory actionFactory,
            ImageLoader imageLoader,
            MimeTypeClassifier typeClassifier,
            HeadlineGenerator headlineGenerator) {
        mLifecycle = lifecycle;
        if (isSingleImage && fileCount != 1) {
            throw new IllegalArgumentException(
                    "fileCount = " + fileCount + " and isSingleImage = true");
        }
        mFileCount = fileCount;
        mIsSingleImage = isSingleImage;
        mText = text;
        mActionFactory = actionFactory;
        mImageLoader = imageLoader;
        mTypeClassifier = typeClassifier;
        mHeadlineGenerator = headlineGenerator;
    }

    @Override
    public int getType() {
        return mIsSingleImage ? CONTENT_PREVIEW_IMAGE : CONTENT_PREVIEW_FILE;
    }

    @Override
    public ViewGroup display(Resources resources, LayoutInflater layoutInflater, ViewGroup parent) {
        ViewGroup layout = displayInternal(layoutInflater, parent);
        displayModifyShareAction(layout, mActionFactory);
        return layout;
    }

    public void updatePreviewMetadata(List<FileInfo> files) {
        boolean allImages = true;
        boolean allVideos = true;
        for (FileInfo fileInfo : files) {
            ScrollableImagePreviewView.PreviewType previewType =
                    getPreviewType(mTypeClassifier, fileInfo.getMimeType());
            allImages = allImages && previewType == ScrollableImagePreviewView.PreviewType.Image;
            allVideos = allVideos && previewType == ScrollableImagePreviewView.PreviewType.Video;
        }
        mAllImages = allImages;
        mAllVideos = allVideos;
        mFirstFilePreviewUri = files.isEmpty() ? null : files.get(0).getPreviewUri();
        mIsMetadataUpdated = true;
        if (mContentPreviewView != null) {
            updateUiWithMetadata(mContentPreviewView);
        }
    }

    private ViewGroup displayInternal(LayoutInflater layoutInflater, ViewGroup parent) {
        mContentPreviewView = (ViewGroup) layoutInflater.inflate(
                R.layout.chooser_grid_preview_files_text, parent, false);

        final ActionRow actionRow =
                mContentPreviewView.findViewById(com.android.internal.R.id.chooser_action_row);
        List<ActionRow.Action> actions = mActionFactory.createCustomActions();
        actionRow.setActions(actions);

        if (mIsMetadataUpdated) {
            updateUiWithMetadata(mContentPreviewView);
        } else if (!mIsSingleImage) {
            mContentPreviewView.requireViewById(R.id.image_view).setVisibility(View.GONE);
        }

        return mContentPreviewView;
    }

    private void updateUiWithMetadata(ViewGroup contentPreviewView) {
        prepareTextPreview(contentPreviewView, mActionFactory);
        updateHeadline(contentPreviewView);

        ImageView imagePreview = mContentPreviewView.requireViewById(R.id.image_view);
        if (mIsSingleImage && mFirstFilePreviewUri != null) {
            mImageLoader.loadImage(
                    mLifecycle,
                    mFirstFilePreviewUri,
                    bitmap -> {
                        if (bitmap == null) {
                            imagePreview.setVisibility(View.GONE);
                        } else {
                            imagePreview.setImageBitmap(bitmap);
                        }
                    });
        } else {
            imagePreview.setVisibility(View.GONE);
        }
    }

    private void updateHeadline(ViewGroup contentPreview) {
        CheckBox includeText = contentPreview.requireViewById(R.id.include_text_action);
        String headline;
        if (includeText.getVisibility() == View.VISIBLE && includeText.isChecked()) {
            if (mAllImages) {
                headline = mHeadlineGenerator.getImagesWithTextHeadline(mText, mFileCount);
            } else if (mAllVideos) {
                headline = mHeadlineGenerator.getVideosWithTextHeadline(mText, mFileCount);
            } else {
                headline = mHeadlineGenerator.getFilesWithTextHeadline(mText, mFileCount);
            }
        } else {
            if (mAllImages) {
                headline = mHeadlineGenerator.getImagesHeadline(mFileCount);
            } else if (mAllVideos) {
                headline = mHeadlineGenerator.getVideosHeadline(mFileCount);
            } else {
                headline = mHeadlineGenerator.getFilesHeadline(mFileCount);
            }
        }

        displayHeadline(contentPreview, headline);
    }

    private void prepareTextPreview(
            ViewGroup contentPreview,
            ChooserContentPreviewUi.ActionFactory actionFactory) {
        final TextView textView = contentPreview.requireViewById(R.id.content_preview_text);
        CheckBox includeText = contentPreview.requireViewById(R.id.include_text_action);
        boolean isLink = HttpUriMatcher.isHttpUri(mText.toString());
        textView.setAutoLinkMask(isLink ? Linkify.WEB_URLS : 0);
        textView.setText(mText);

        final Consumer<Boolean> shareTextAction = actionFactory.getExcludeSharedTextAction();
        includeText.setChecked(true);
        includeText.setText(isLink ? R.string.include_link : R.string.include_text);
        shareTextAction.accept(false);
        includeText.setOnCheckedChangeListener((view, isChecked) -> {
            if (isChecked) {
                textView.setText(mText);
            } else {
                textView.setText(getNoTextString(contentPreview.getResources()));
            }
            shareTextAction.accept(!isChecked);
            updateHeadline(contentPreview);
        });
        if (SHOW_TOGGLE_CHECKMARK) {
            includeText.setVisibility(View.VISIBLE);
        }
    }

    private String getNoTextString(Resources resources) {
        int stringResource;

        if (mAllImages) {
            stringResource = R.string.sharing_images_only;
        } else if (mAllVideos) {
            stringResource = R.string.sharing_videos_only;
        } else {
            stringResource = R.string.sharing_files_only;
        }

        HashMap<String, Object> params = new HashMap<>();
        params.put("count", mFileCount);

        return PluralsMessageFormatter.format(
                resources,
                params,
                stringResource
        );
    }
}
