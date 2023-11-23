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

import static com.android.intentresolver.contentpreview.ContentPreviewType.CONTENT_PREVIEW_IMAGE;

import android.content.res.Resources;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.android.intentresolver.R;
import com.android.intentresolver.widget.ActionRow;
import com.android.intentresolver.widget.ImagePreviewView.TransitionElementStatusCallback;
import com.android.intentresolver.widget.ScrollableImagePreviewView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class UnifiedContentPreviewUi extends ContentPreviewUi {
    private final boolean mShowEditAction;
    private final ChooserContentPreviewUi.ActionFactory mActionFactory;
    private final ImageLoader mImageLoader;
    private final MimeTypeClassifier mTypeClassifier;
    private final TransitionElementStatusCallback mTransitionElementStatusCallback;
    private final HeadlineGenerator mHeadlineGenerator;
    @Nullable
    private List<FileInfo> mFiles;
    @Nullable
    private ViewGroup mContentPreviewView;

    UnifiedContentPreviewUi(
            boolean isSingleImage,
            ChooserContentPreviewUi.ActionFactory actionFactory,
            ImageLoader imageLoader,
            MimeTypeClassifier typeClassifier,
            TransitionElementStatusCallback transitionElementStatusCallback,
            HeadlineGenerator headlineGenerator) {
        mShowEditAction = isSingleImage;
        mActionFactory = actionFactory;
        mImageLoader = imageLoader;
        mTypeClassifier = typeClassifier;
        mTransitionElementStatusCallback = transitionElementStatusCallback;
        mHeadlineGenerator = headlineGenerator;
    }

    @Override
    public int getType() {
        return CONTENT_PREVIEW_IMAGE;
    }

    @Override
    public ViewGroup display(Resources resources, LayoutInflater layoutInflater, ViewGroup parent) {
        ViewGroup layout = displayInternal(layoutInflater, parent);
        displayModifyShareAction(layout, mActionFactory);
        return layout;
    }

    public void setFiles(List<FileInfo> files) {
        mImageLoader.prePopulate(files.stream()
                .map(FileInfo::getPreviewUri)
                .filter(Objects::nonNull)
                .toList());
        mFiles = files;
        if (mContentPreviewView != null) {
            updatePreviewWithFiles(mContentPreviewView, files);
        }
    }

    private ViewGroup displayInternal(LayoutInflater layoutInflater, ViewGroup parent) {
        mContentPreviewView = (ViewGroup) layoutInflater.inflate(
                R.layout.chooser_grid_preview_image, parent, false);

        final ActionRow actionRow =
                mContentPreviewView.findViewById(com.android.internal.R.id.chooser_action_row);
        List<ActionRow.Action> actions = mActionFactory.createCustomActions();
        actionRow.setActions(actions);

        ScrollableImagePreviewView imagePreview =
                mContentPreviewView.requireViewById(R.id.scrollable_image_preview);
        imagePreview.setOnNoPreviewCallback(() -> imagePreview.setVisibility(View.GONE));
        imagePreview.setTransitionElementStatusCallback(mTransitionElementStatusCallback);

        if (mFiles != null) {
            updatePreviewWithFiles(mContentPreviewView, mFiles);
        }

        return mContentPreviewView;
    }

    private void updatePreviewWithFiles(ViewGroup contentPreviewView, List<FileInfo> files) {
        final int count = files.size();
        ScrollableImagePreviewView imagePreview =
                contentPreviewView.requireViewById(R.id.scrollable_image_preview);
        if (count == 0) {
            Log.i(
                    TAG,
                    "Attempted to display image preview area with zero"
                            + " available images detected in EXTRA_STREAM list");
            imagePreview.setVisibility(View.GONE);
            mTransitionElementStatusCallback.onAllTransitionElementsReady();
            return;
        }

        List<ScrollableImagePreviewView.Preview> previews = new ArrayList<>();
        boolean allImages = true;
        boolean allVideos = true;
        for (FileInfo fileInfo : files) {
            ScrollableImagePreviewView.PreviewType previewType =
                    getPreviewType(mTypeClassifier, fileInfo.getMimeType());
            allImages = allImages && previewType == ScrollableImagePreviewView.PreviewType.Image;
            allVideos = allVideos && previewType == ScrollableImagePreviewView.PreviewType.Video;

            if (fileInfo.getPreviewUri() != null) {
                Runnable editAction =
                        mShowEditAction ? mActionFactory.getEditButtonRunnable() : null;
                previews.add(
                        new ScrollableImagePreviewView.Preview(
                                previewType, fileInfo.getPreviewUri(), editAction));
            }
        }

        imagePreview.setPreviews(previews, count - previews.size(), mImageLoader);

        if (allImages) {
            displayHeadline(contentPreviewView, mHeadlineGenerator.getImagesHeadline(count));
        } else if (allVideos) {
            displayHeadline(contentPreviewView, mHeadlineGenerator.getVideosHeadline(count));
        } else {
            displayHeadline(contentPreviewView, mHeadlineGenerator.getFilesHeadline(count));
        }
    }
}
