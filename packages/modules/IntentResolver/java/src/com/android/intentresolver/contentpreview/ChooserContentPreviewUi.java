/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static com.android.intentresolver.contentpreview.ContentPreviewType.CONTENT_PREVIEW_TEXT;

import android.content.ClipData;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;

import com.android.intentresolver.widget.ActionRow;
import com.android.intentresolver.widget.ImagePreviewView.TransitionElementStatusCallback;

import java.util.List;
import java.util.function.Consumer;

/**
 * Collection of helpers for building the content preview UI displayed in
 * {@link com.android.intentresolver.ChooserActivity}.
 * A content preview façade.
 */
public final class ChooserContentPreviewUi {

    private final Lifecycle mLifecycle;

    /**
     * Delegate to build the default system action buttons to display in the preview layout, if/when
     * they're determined to be appropriate for the particular preview we display.
     * TODO: clarify why action buttons are part of preview logic.
     */
    public interface ActionFactory {
        /**
         * @return Runnable to be run when an edit button is clicked (if available).
         */
        @Nullable
        Runnable getEditButtonRunnable();

        /**
         * @return Runnable to be run when a copy button is clicked (if available).
         */
        @Nullable
        Runnable getCopyButtonRunnable();

        /** Create custom actions */
        List<ActionRow.Action> createCustomActions();

        /**
         * Provides a share modification action, if any.
         */
        @Nullable
        ActionRow.Action getModifyShareAction();

        /**
         * <p>
         * Creates an exclude-text action that can be called when the user changes shared text
         * status in the Media + Text preview.
         * </p>
         * <p>
         * <code>true</code> argument value indicates that the text should be excluded.
         * </p>
         */
        Consumer<Boolean> getExcludeSharedTextAction();
    }

    @VisibleForTesting
    final ContentPreviewUi mContentPreviewUi;

    public ChooserContentPreviewUi(
            Lifecycle lifecycle,
            PreviewDataProvider previewData,
            Intent targetIntent,
            ImageLoader imageLoader,
            ActionFactory actionFactory,
            TransitionElementStatusCallback transitionElementStatusCallback,
            HeadlineGenerator headlineGenerator) {
        mLifecycle = lifecycle;
        mContentPreviewUi = createContentPreview(
                previewData,
                targetIntent,
                DefaultMimeTypeClassifier.INSTANCE,
                imageLoader,
                actionFactory,
                transitionElementStatusCallback,
                headlineGenerator);
        if (mContentPreviewUi.getType() != CONTENT_PREVIEW_IMAGE) {
            transitionElementStatusCallback.onAllTransitionElementsReady();
        }
    }

    private ContentPreviewUi createContentPreview(
            PreviewDataProvider previewData,
            Intent targetIntent,
            MimeTypeClassifier typeClassifier,
            ImageLoader imageLoader,
            ActionFactory actionFactory,
            TransitionElementStatusCallback transitionElementStatusCallback,
            HeadlineGenerator headlineGenerator) {

        int previewType = previewData.getPreviewType();
        if (previewType == CONTENT_PREVIEW_TEXT) {
            return createTextPreview(
                    mLifecycle,
                    targetIntent,
                    actionFactory,
                    imageLoader,
                    headlineGenerator);
        }
        if (previewType == CONTENT_PREVIEW_FILE) {
            FileContentPreviewUi fileContentPreviewUi = new FileContentPreviewUi(
                    previewData.getUriCount(),
                    actionFactory,
                    headlineGenerator);
            if (previewData.getUriCount() > 0) {
                previewData.getFirstFileName(
                        mLifecycle, fileContentPreviewUi::setFirstFileName);
            }
            return fileContentPreviewUi;
        }
        boolean isSingleImageShare = previewData.getUriCount() == 1
                        && typeClassifier.isImageType(previewData.getFirstFileInfo().getMimeType());
        CharSequence text = targetIntent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (!TextUtils.isEmpty(text)) {
            FilesPlusTextContentPreviewUi previewUi =
                    new FilesPlusTextContentPreviewUi(
                            mLifecycle,
                            isSingleImageShare,
                            previewData.getUriCount(),
                            targetIntent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                            actionFactory,
                            imageLoader,
                            typeClassifier,
                            headlineGenerator);
            if (previewData.getUriCount() > 0) {
                previewData.getFileMetadataForImagePreview(
                        mLifecycle, previewUi::updatePreviewMetadata);
            }
            return previewUi;
        }

        UnifiedContentPreviewUi unifiedContentPreviewUi = new UnifiedContentPreviewUi(
                isSingleImageShare,
                actionFactory,
                imageLoader,
                typeClassifier,
                transitionElementStatusCallback,
                headlineGenerator);
        previewData.getFileMetadataForImagePreview(mLifecycle, unifiedContentPreviewUi::setFiles);
        return unifiedContentPreviewUi;
    }

    public int getPreferredContentPreview() {
        return mContentPreviewUi.getType();
    }

    /**
     * Display a content preview of the specified {@code previewType} to preview the content of the
     * specified {@code intent}.
     */
    public ViewGroup displayContentPreview(
            Resources resources, LayoutInflater layoutInflater, ViewGroup parent) {

        return mContentPreviewUi.display(resources, layoutInflater, parent);
    }

    private static TextContentPreviewUi createTextPreview(
            Lifecycle lifecycle,
            Intent targetIntent,
            ChooserContentPreviewUi.ActionFactory actionFactory,
            ImageLoader imageLoader,
            HeadlineGenerator headlineGenerator) {
        CharSequence sharingText = targetIntent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String previewTitle = targetIntent.getStringExtra(Intent.EXTRA_TITLE);
        ClipData previewData = targetIntent.getClipData();
        Uri previewThumbnail = null;
        if (previewData != null) {
            if (previewData.getItemCount() > 0) {
                ClipData.Item previewDataItem = previewData.getItemAt(0);
                previewThumbnail = previewDataItem.getUri();
            }
        }
        return new TextContentPreviewUi(
                lifecycle,
                sharingText,
                previewTitle,
                previewThumbnail,
                actionFactory,
                imageLoader,
                headlineGenerator);
    }
}
