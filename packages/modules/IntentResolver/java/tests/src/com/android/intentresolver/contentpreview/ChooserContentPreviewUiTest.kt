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

package com.android.intentresolver.contentpreview

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.Lifecycle
import com.android.intentresolver.any
import com.android.intentresolver.contentpreview.ChooserContentPreviewUi.ActionFactory
import com.android.intentresolver.mock
import com.android.intentresolver.whenever
import com.android.intentresolver.widget.ActionRow
import com.android.intentresolver.widget.ImagePreviewView
import com.google.common.truth.Truth.assertThat
import java.util.function.Consumer
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class ChooserContentPreviewUiTest {
    private val lifecycle = mock<Lifecycle>()
    private val previewData = mock<PreviewDataProvider>()
    private val headlineGenerator = mock<HeadlineGenerator>()
    private val imageLoader =
        object : ImageLoader {
            override fun loadImage(
                callerLifecycle: Lifecycle,
                uri: Uri,
                callback: Consumer<Bitmap?>,
            ) {
                callback.accept(null)
            }
            override fun prePopulate(uris: List<Uri>) = Unit
            override suspend fun invoke(uri: Uri, caching: Boolean): Bitmap? = null
        }
    private val actionFactory =
        object : ActionFactory {
            override fun getCopyButtonRunnable(): Runnable? = null
            override fun getEditButtonRunnable(): Runnable? = null
            override fun createCustomActions(): List<ActionRow.Action> = emptyList()
            override fun getModifyShareAction(): ActionRow.Action? = null
            override fun getExcludeSharedTextAction(): Consumer<Boolean> = Consumer<Boolean> {}
        }
    private val transitionCallback = mock<ImagePreviewView.TransitionElementStatusCallback>()

    @Test
    fun test_textPreviewType_useTextPreviewUi() {
        whenever(previewData.previewType).thenReturn(ContentPreviewType.CONTENT_PREVIEW_TEXT)
        val testSubject =
            ChooserContentPreviewUi(
                lifecycle,
                previewData,
                Intent(Intent.ACTION_VIEW),
                imageLoader,
                actionFactory,
                transitionCallback,
                headlineGenerator,
            )
        assertThat(testSubject.preferredContentPreview)
            .isEqualTo(ContentPreviewType.CONTENT_PREVIEW_TEXT)
        assertThat(testSubject.mContentPreviewUi).isInstanceOf(TextContentPreviewUi::class.java)
        verify(transitionCallback, times(1)).onAllTransitionElementsReady()
    }

    @Test
    fun test_filePreviewType_useFilePreviewUi() {
        whenever(previewData.previewType).thenReturn(ContentPreviewType.CONTENT_PREVIEW_FILE)
        val testSubject =
            ChooserContentPreviewUi(
                lifecycle,
                previewData,
                Intent(Intent.ACTION_SEND),
                imageLoader,
                actionFactory,
                transitionCallback,
                headlineGenerator,
            )
        assertThat(testSubject.preferredContentPreview)
            .isEqualTo(ContentPreviewType.CONTENT_PREVIEW_FILE)
        assertThat(testSubject.mContentPreviewUi).isInstanceOf(FileContentPreviewUi::class.java)
        verify(transitionCallback, times(1)).onAllTransitionElementsReady()
    }

    @Test
    fun test_imagePreviewTypeWithText_useFilePlusTextPreviewUi() {
        val uri = Uri.parse("content://org.pkg.app/img.png")
        whenever(previewData.previewType).thenReturn(ContentPreviewType.CONTENT_PREVIEW_IMAGE)
        whenever(previewData.uriCount).thenReturn(2)
        whenever(previewData.firstFileInfo)
            .thenReturn(FileInfo.Builder(uri).withPreviewUri(uri).withMimeType("image/png").build())
        val testSubject =
            ChooserContentPreviewUi(
                lifecycle,
                previewData,
                Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, "Shared text") },
                imageLoader,
                actionFactory,
                transitionCallback,
                headlineGenerator,
            )
        assertThat(testSubject.mContentPreviewUi)
            .isInstanceOf(FilesPlusTextContentPreviewUi::class.java)
        verify(previewData, times(1)).getFileMetadataForImagePreview(any(), any())
        verify(transitionCallback, times(1)).onAllTransitionElementsReady()
    }

    @Test
    fun test_imagePreviewTypeWithoutText_useImagePreviewUi() {
        val uri = Uri.parse("content://org.pkg.app/img.png")
        whenever(previewData.previewType).thenReturn(ContentPreviewType.CONTENT_PREVIEW_IMAGE)
        whenever(previewData.uriCount).thenReturn(2)
        whenever(previewData.firstFileInfo)
            .thenReturn(FileInfo.Builder(uri).withPreviewUri(uri).withMimeType("image/png").build())
        val testSubject =
            ChooserContentPreviewUi(
                lifecycle,
                previewData,
                Intent(Intent.ACTION_SEND),
                imageLoader,
                actionFactory,
                transitionCallback,
                headlineGenerator,
            )
        assertThat(testSubject.preferredContentPreview)
            .isEqualTo(ContentPreviewType.CONTENT_PREVIEW_IMAGE)
        assertThat(testSubject.mContentPreviewUi).isInstanceOf(UnifiedContentPreviewUi::class.java)
        verify(previewData, times(1)).getFileMetadataForImagePreview(any(), any())
        verify(transitionCallback, never()).onAllTransitionElementsReady()
    }
}
