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

package com.android.intentresolver.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.AttributeSet
import android.util.PluralsMessageFormatter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.intentresolver.R
import com.android.intentresolver.util.throttle
import com.android.intentresolver.widget.ImagePreviewView.TransitionElementStatusCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

private const val TRANSITION_NAME = "screenshot_preview_image"
private const val PLURALS_COUNT = "count"
private const val ADAPTER_UPDATE_INTERVAL_MS = 150L
private const val MIN_ASPECT_RATIO = 0.4f
private const val MIN_ASPECT_RATIO_STRING = "2:5"
private const val MAX_ASPECT_RATIO = 2.5f
private const val MAX_ASPECT_RATIO_STRING = "5:2"

private typealias CachingImageLoader = suspend (Uri, Boolean) -> Bitmap?

class ScrollableImagePreviewView : RecyclerView, ImagePreviewView {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int
    ) : super(context, attrs, defStyleAttr) {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        adapter = Adapter(context)

        context
            .obtainStyledAttributes(attrs, R.styleable.ScrollableImagePreviewView, defStyleAttr, 0)
            .use { a ->
                var innerSpacing =
                    a.getDimensionPixelSize(
                        R.styleable.ScrollableImagePreviewView_itemInnerSpacing,
                        -1
                    )
                if (innerSpacing < 0) {
                    innerSpacing =
                        TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP,
                                3f,
                                context.resources.displayMetrics
                            )
                            .toInt()
                }
                outerSpacing =
                    a.getDimensionPixelSize(
                        R.styleable.ScrollableImagePreviewView_itemOuterSpacing,
                        -1
                    )
                if (outerSpacing < 0) {
                    outerSpacing =
                        TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP,
                                16f,
                                context.resources.displayMetrics
                            )
                            .toInt()
                }
                addItemDecoration(SpacingDecoration(innerSpacing, outerSpacing))

                maxWidthHint =
                    a.getDimensionPixelSize(R.styleable.ScrollableImagePreviewView_maxWidthHint, -1)
            }
    }

    private var batchLoader: BatchPreviewLoader? = null
    private val previewAdapter
        get() = adapter as Adapter

    /**
     * A hint about the maximum width this view can grow to, this helps to optimize preview loading.
     */
    var maxWidthHint: Int = -1
    private var requestedHeight: Int = 0
    private var isMeasured = false
    private var maxAspectRatio = MAX_ASPECT_RATIO
    private var maxAspectRatioString = MAX_ASPECT_RATIO_STRING
    private var outerSpacing: Int = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        if (!isMeasured) {
            isMeasured = true
            updateMaxWidthHint(widthSpec)
            updateMaxAspectRatio()
            batchLoader?.loadAspectRatios(getMaxWidth(), this::updatePreviewSize)
        }
    }

    private fun updateMaxWidthHint(widthSpec: Int) {
        if (maxWidthHint > 0) return
        if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED) {
            maxWidthHint = View.MeasureSpec.getSize(widthSpec)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        setOverScrollMode(
            if (areAllChildrenVisible) View.OVER_SCROLL_NEVER else View.OVER_SCROLL_ALWAYS
        )
    }

    override fun setTransitionElementStatusCallback(callback: TransitionElementStatusCallback?) {
        previewAdapter.transitionStatusElementCallback = callback
    }

    override fun getTransitionView(): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val vh = getChildViewHolder(child)
            if (vh is PreviewViewHolder && vh.image.transitionName != null) return child
        }
        return null
    }

    fun setPreviews(previews: List<Preview>, otherItemCount: Int, imageLoader: CachingImageLoader) {
        previewAdapter.reset(0, imageLoader)
        batchLoader?.cancel()
        batchLoader =
            BatchPreviewLoader(
                    imageLoader,
                    previews,
                    otherItemCount,
                    onReset = { totalItemCount ->
                        previewAdapter.reset(totalItemCount, imageLoader)
                    },
                    onUpdate = previewAdapter::addPreviews,
                    onCompletion = {
                        if (!previewAdapter.hasPreviews) {
                            onNoPreviewCallback?.run()
                        }
                    }
                )
                .apply {
                    if (isMeasured) {
                        loadAspectRatios(
                            getMaxWidth(),
                            this@ScrollableImagePreviewView::updatePreviewSize
                        )
                    }
                }
    }

    var onNoPreviewCallback: Runnable? = null

    private fun getMaxWidth(): Int =
        when {
            maxWidthHint > 0 -> maxWidthHint
            isLaidOut -> width
            else -> measuredWidth
        }

    private fun updateMaxAspectRatio() {
        val padding = outerSpacing * 2
        val w = maxOf(padding, getMaxWidth() - padding)
        val h = if (isLaidOut) height else measuredHeight
        if (w > 0 && h > 0) {
            maxAspectRatio =
                (w.toFloat() / h.toFloat()).coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
            maxAspectRatioString =
                when {
                    maxAspectRatio <= MIN_ASPECT_RATIO -> MIN_ASPECT_RATIO_STRING
                    maxAspectRatio >= MAX_ASPECT_RATIO -> MAX_ASPECT_RATIO_STRING
                    else -> "$w:$h"
                }
        }
    }

    /**
     * Sets [preview]'s aspect ratio based on the preview image size.
     *
     * @return adjusted preview width
     */
    private fun updatePreviewSize(preview: Preview, width: Int, height: Int): Int {
        val effectiveHeight = if (isLaidOut) height else measuredHeight
        return if (width <= 0 || height <= 0) {
            preview.aspectRatioString = "1:1"
            effectiveHeight
        } else {
            val aspectRatio =
                (width.toFloat() / height.toFloat()).coerceIn(MIN_ASPECT_RATIO, maxAspectRatio)
            preview.aspectRatioString =
                when {
                    aspectRatio <= MIN_ASPECT_RATIO -> MIN_ASPECT_RATIO_STRING
                    aspectRatio >= maxAspectRatio -> maxAspectRatioString
                    else -> "$width:$height"
                }
            (effectiveHeight * aspectRatio).toInt()
        }
    }

    class Preview
    internal constructor(
        val type: PreviewType,
        val uri: Uri,
        val editAction: Runnable?,
        internal var aspectRatioString: String
    ) {
        constructor(
            type: PreviewType,
            uri: Uri,
            editAction: Runnable?
        ) : this(type, uri, editAction, "1:1")
    }

    enum class PreviewType {
        Image,
        Video,
        File
    }

    private class Adapter(private val context: Context) : RecyclerView.Adapter<ViewHolder>() {
        private val previews = ArrayList<Preview>()
        private val imagePreviewDescription =
            context.resources.getString(R.string.image_preview_a11y_description)
        private val videoPreviewDescription =
            context.resources.getString(R.string.video_preview_a11y_description)
        private val filePreviewDescription =
            context.resources.getString(R.string.file_preview_a11y_description)
        private var imageLoader: CachingImageLoader? = null
        private var firstImagePos = -1
        private var totalItemCount: Int = 0

        private val hasOtherItem
            get() = previews.size < totalItemCount
        val hasPreviews: Boolean
            get() = previews.isNotEmpty()

        var transitionStatusElementCallback: TransitionElementStatusCallback? = null

        fun reset(totalItemCount: Int, imageLoader: CachingImageLoader) {
            this.imageLoader = imageLoader
            firstImagePos = -1
            previews.clear()
            this.totalItemCount = maxOf(0, totalItemCount)
            notifyDataSetChanged()
        }

        fun addPreviews(newPreviews: Collection<Preview>) {
            if (newPreviews.isEmpty()) return
            val insertPos = previews.size
            val hadOtherItem = hasOtherItem
            previews.addAll(newPreviews)
            if (firstImagePos < 0) {
                val pos = newPreviews.indexOfFirst { it.type == PreviewType.Image }
                if (pos >= 0) firstImagePos = insertPos + pos
            }
            notifyItemRangeInserted(insertPos, newPreviews.size)
            when {
                hadOtherItem && previews.size >= totalItemCount -> {
                    notifyItemRemoved(previews.size)
                }
                !hadOtherItem && previews.size < totalItemCount -> {
                    notifyItemInserted(previews.size)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, itemType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(itemType, parent, false)
            return if (itemType == R.layout.image_preview_other_item) {
                OtherItemViewHolder(view)
            } else {
                PreviewViewHolder(
                    view,
                    imagePreviewDescription,
                    videoPreviewDescription,
                    filePreviewDescription,
                )
            }
        }

        override fun getItemCount(): Int = previews.size + if (hasOtherItem) 1 else 0

        override fun getItemViewType(position: Int): Int {
            return if (position == previews.size) {
                R.layout.image_preview_other_item
            } else {
                R.layout.image_preview_image_item
            }
        }

        override fun onBindViewHolder(vh: ViewHolder, position: Int) {
            when (vh) {
                is OtherItemViewHolder -> vh.bind(totalItemCount - previews.size)
                is PreviewViewHolder ->
                    vh.bind(
                        previews[position],
                        imageLoader ?: error("ImageLoader is missing"),
                        isSharedTransitionElement = position == firstImagePos,
                        previewReadyCallback =
                            if (
                                position == firstImagePos && transitionStatusElementCallback != null
                            ) {
                                this::onTransitionElementReady
                            } else {
                                null
                            }
                    )
            }
        }

        override fun onViewRecycled(vh: ViewHolder) {
            vh.unbind()
        }

        override fun onFailedToRecycleView(vh: ViewHolder): Boolean {
            vh.unbind()
            return super.onFailedToRecycleView(vh)
        }

        private fun onTransitionElementReady(name: String) {
            transitionStatusElementCallback?.apply {
                onTransitionElementReady(name)
                onAllTransitionElementsReady()
            }
            transitionStatusElementCallback = null
        }
    }

    private sealed class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun unbind()
    }

    private class PreviewViewHolder(
        view: View,
        private val imagePreviewDescription: String,
        private val videoPreviewDescription: String,
        private val filePreviewDescription: String,
    ) : ViewHolder(view) {
        val image = view.requireViewById<ImageView>(R.id.image)
        private val badgeFrame = view.requireViewById<View>(R.id.badge_frame)
        private val badge = view.requireViewById<ImageView>(R.id.badge)
        private val editActionContainer = view.findViewById<View?>(R.id.edit)
        private var scope: CoroutineScope? = null

        fun bind(
            preview: Preview,
            imageLoader: CachingImageLoader,
            isSharedTransitionElement: Boolean,
            previewReadyCallback: ((String) -> Unit)?
        ) {
            image.setImageDrawable(null)
            (image.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
                params.dimensionRatio = preview.aspectRatioString
            }
            image.transitionName =
                if (isSharedTransitionElement) {
                    TRANSITION_NAME
                } else {
                    null
                }
            when (preview.type) {
                PreviewType.Image -> {
                    itemView.contentDescription = imagePreviewDescription
                    badgeFrame.visibility = View.GONE
                }
                PreviewType.Video -> {
                    itemView.contentDescription = videoPreviewDescription
                    badge.setImageResource(R.drawable.ic_file_video)
                    badgeFrame.visibility = View.VISIBLE
                }
                else -> {
                    itemView.contentDescription = filePreviewDescription
                    badge.setImageResource(R.drawable.chooser_file_generic)
                    badgeFrame.visibility = View.VISIBLE
                }
            }
            preview.editAction?.also { onClick ->
                editActionContainer?.apply {
                    setOnClickListener { onClick.run() }
                    visibility = View.VISIBLE
                }
            }
            resetScope().launch {
                loadImage(preview, imageLoader)
                if (preview.type == PreviewType.Image) {
                    previewReadyCallback?.let { callback ->
                        image.waitForPreDraw()
                        callback(TRANSITION_NAME)
                    }
                }
            }
        }

        private suspend fun loadImage(preview: Preview, imageLoader: CachingImageLoader) {
            val bitmap =
                runCatching {
                        // it's expected for all loading/caching optimizations to be implemented by
                        // the loader
                        imageLoader(preview.uri, true)
                    }
                    .getOrNull()
            image.setImageBitmap(bitmap)
        }

        private fun resetScope(): CoroutineScope =
            (MainScope() + Dispatchers.Main.immediate).also {
                scope?.cancel()
                scope = it
            }

        override fun unbind() {
            scope?.cancel()
            scope = null
        }
    }

    private class OtherItemViewHolder(view: View) : ViewHolder(view) {
        private val label = view.requireViewById<TextView>(R.id.label)

        fun bind(count: Int) {
            label.text =
                PluralsMessageFormatter.format(
                    itemView.context.resources,
                    mapOf(PLURALS_COUNT to count),
                    R.string.other_files
                )
        }

        override fun unbind() = Unit
    }

    private class SpacingDecoration(private val innerSpacing: Int, private val outerSpacing: Int) :
        ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: State) {
            val itemCount = parent.adapter?.itemCount ?: return
            val pos = parent.getChildAdapterPosition(view)
            var startMargin = if (pos == 0) outerSpacing else innerSpacing
            var endMargin = if (pos == itemCount - 1) outerSpacing else 0

            if (ViewCompat.getLayoutDirection(parent) == ViewCompat.LAYOUT_DIRECTION_RTL) {
                outRect.set(endMargin, 0, startMargin, 0)
            } else {
                outRect.set(startMargin, 0, endMargin, 0)
            }
        }
    }

    @VisibleForTesting
    class BatchPreviewLoader(
        private val imageLoader: CachingImageLoader,
        previews: List<Preview>,
        otherItemCount: Int,
        private val onReset: (Int) -> Unit,
        private val onUpdate: (List<Preview>) -> Unit,
        private val onCompletion: () -> Unit,
    ) {
        private val previews: List<Preview> =
            if (previews is RandomAccess) previews else ArrayList(previews)
        private val totalItemCount = previews.size + otherItemCount
        private var scope: CoroutineScope? = MainScope() + Dispatchers.Main.immediate

        fun cancel() {
            scope?.cancel()
            scope = null
        }

        fun loadAspectRatios(maxWidth: Int, previewSizeUpdater: (Preview, Int, Int) -> Int) {
            val scope = this.scope ?: return
            // -1 encodes that the preview has not been processed,
            // 0 means failed, > 0 is a preview width
            val previewWidths = IntArray(previews.size) { -1 }
            var blockStart = 0 // inclusive
            var blockEnd = 0 // exclusive

            // replay 2 items to guarantee that we'd get at least one update
            val reportFlow = MutableSharedFlow<Any>(replay = 2)
            val updateEvent = Any()
            val completedEvent = Any()

            // throttle adapter updates using flow; the flow first emits when enough preview
            // elements is loaded to fill the viewport and then each time a subsequent block of
            // previews is loaded
            scope.launch(Dispatchers.Main) {
                reportFlow
                    .takeWhile { it !== completedEvent }
                    .throttle(ADAPTER_UPDATE_INTERVAL_MS)
                    .onCompletion { cause ->
                        if (cause == null) {
                            onCompletion()
                        }
                    }
                    .collect {
                        if (blockStart == 0) {
                            onReset(totalItemCount)
                        }
                        val updates = ArrayList<Preview>(blockEnd - blockStart)
                        while (blockStart < blockEnd) {
                            if (previewWidths[blockStart] > 0) {
                                updates.add(previews[blockStart])
                            }
                            blockStart++
                        }
                        if (updates.isNotEmpty()) {
                            onUpdate(updates)
                        }
                    }
            }

            scope.launch {
                var blockWidth = 0
                var isFirstBlock = true
                var nextIdx = 0
                List<Job>(4) {
                        launch {
                            while (true) {
                                val i = nextIdx++
                                if (i >= previews.size) break
                                val preview = previews[i]

                                previewWidths[i] =
                                    runCatching {
                                            // TODO: decide on adding a timeout
                                            imageLoader(preview.uri, isFirstBlock)?.let { bitmap ->
                                                previewSizeUpdater(
                                                    preview,
                                                    bitmap.width,
                                                    bitmap.height
                                                )
                                            }
                                                ?: 0
                                        }
                                        .getOrDefault(0)

                                if (blockEnd != i) continue
                                while (
                                    blockEnd < previewWidths.size && previewWidths[blockEnd] >= 0
                                ) {
                                    blockWidth += previewWidths[blockEnd]
                                    blockEnd++
                                }
                                if (isFirstBlock) {
                                    if (blockWidth >= maxWidth) {
                                        isFirstBlock = false
                                        // notify that the preview now can be displayed
                                        reportFlow.emit(updateEvent)
                                    }
                                } else {
                                    reportFlow.emit(updateEvent)
                                }
                            }
                        }
                    }
                    .joinAll()
                // in case all previews have failed to load
                reportFlow.emit(updateEvent)
                reportFlow.emit(completedEvent)
            }
        }
    }
}
