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
package com.android.car.ui.pluginsupport;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.CarUiText;
import com.android.car.ui.FocusArea;
import com.android.car.ui.FocusAreaAdapterV1;
import com.android.car.ui.FocusParkingView;
import com.android.car.ui.FocusParkingViewAdapterV1;
import com.android.car.ui.R;
import com.android.car.ui.appstyledview.AppStyledViewController;
import com.android.car.ui.appstyledview.AppStyledViewControllerAdapterV1;
import com.android.car.ui.appstyledview.AppStyledViewControllerImpl;
import com.android.car.ui.baselayout.Insets;
import com.android.car.ui.baselayout.InsetsChangedListener;
import com.android.car.ui.plugin.oemapis.InsetsOEMV1;
import com.android.car.ui.plugin.oemapis.PluginFactoryOEMV1;
import com.android.car.ui.plugin.oemapis.PluginFactoryOEMV2;
import com.android.car.ui.plugin.oemapis.TextOEMV1;
import com.android.car.ui.plugin.oemapis.appstyledview.AppStyledViewControllerOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.AdapterOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.ContentListItemOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.HeaderListItemOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.LayoutStyleOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.ListItemOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewAttributesOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.ViewHolderOEMV1;
import com.android.car.ui.plugin.oemapis.toolbar.ToolbarControllerOEMV1;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiHeaderListItem;
import com.android.car.ui.recyclerview.CarUiLayoutStyle;
import com.android.car.ui.recyclerview.CarUiListItem;
import com.android.car.ui.recyclerview.CarUiListItemAdapterAdapterV1;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.recyclerview.CarUiRecyclerView.CarUiRecyclerViewLayout;
import com.android.car.ui.recyclerview.RecyclerViewAdapterV1;
import com.android.car.ui.toolbar.ToolbarController;
import com.android.car.ui.toolbar.ToolbarControllerAdapterV1;
import com.android.car.ui.utils.CarUiUtils;
import com.android.car.ui.widget.CarUiTextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * This class is an wrapper around {@link PluginFactoryOEMV1} that implements {@link PluginFactory},
 * to provide a version-agnostic way of interfacing with the OEM's PluginFactory.
 */
@SuppressWarnings("AndroidJdkLibsChecker")
@TargetApi(MIN_TARGET_API)
public final class PluginFactoryAdapterV2 implements PluginFactory {
    @NonNull
    private final PluginFactoryOEMV2 mOem;
    @NonNull
    private final PluginFactoryStub mFactoryStub = new PluginFactoryStub();

    public PluginFactoryAdapterV2(@NonNull PluginFactoryOEMV2 oem) {
        mOem = oem;

        mOem.setRotaryFactories(
                c -> new FocusParkingViewAdapterV1(new FocusParkingView(c)),
                c -> new FocusAreaAdapterV1(new FocusArea(c)));
    }

    @Override
    @Nullable
    public ToolbarController installBaseLayoutAround(
            View contentView,
            InsetsChangedListener insetsChangedListener,
            boolean toolbarEnabled,
            boolean fullscreen) {

        if (!mOem.customizesBaseLayout()) {
            return mFactoryStub.installBaseLayoutAround(contentView,
                    insetsChangedListener, toolbarEnabled, fullscreen);
        }

        ToolbarControllerOEMV1 toolbar = mOem.installBaseLayoutAround(
                contentView.getContext(),
                contentView,
                insets -> insetsChangedListener.onCarUiInsetsChanged(adaptInsets(insets)),
                toolbarEnabled, fullscreen);

        return toolbar != null
                ? new ToolbarControllerAdapterV1(contentView.getContext(), toolbar)
                : null;
    }

    @NonNull
    @Override
    public CarUiTextView createTextView(Context context, AttributeSet attrs) {
        return mFactoryStub.createTextView(context, attrs);
    }

    @Override
    public AppStyledViewController createAppStyledView(Context activityContext) {
        AppStyledViewControllerOEMV1 appStyledViewControllerOEMV1 = mOem.createAppStyledView(
                activityContext);
        return appStyledViewControllerOEMV1 == null ? new AppStyledViewControllerImpl(
                activityContext) : new AppStyledViewControllerAdapterV1(
                appStyledViewControllerOEMV1);
    }

    private Insets adaptInsets(InsetsOEMV1 insetsOEM) {
        return new Insets(insetsOEM.getLeft(), insetsOEM.getTop(),
                insetsOEM.getRight(), insetsOEM.getBottom());
    }

    @Override
    public CarUiRecyclerView createRecyclerView(@NonNull Context context,
            @Nullable AttributeSet attrs) {
        RecyclerViewAttributesOEMV1 oemAttrs = from(context, attrs);
        RecyclerViewOEMV1 oemRecyclerView = mOem.createRecyclerView(context, oemAttrs);
        if (oemRecyclerView != null) {
            RecyclerViewAdapterV1 rv = new RecyclerViewAdapterV1(context, attrs, 0);
            rv.setRecyclerViewOEMV1(oemRecyclerView, oemAttrs);
            return rv;
        } else {
            return mFactoryStub.createRecyclerView(context, attrs);
        }
    }

    @Override
    public RecyclerView.Adapter<? extends RecyclerView.ViewHolder> createListItemAdapter(
            List<? extends CarUiListItem> items) {
        List<ListItemOEMV1> oemItems = CarUiUtils.convertList(items,
                PluginFactoryAdapterV2::toOemListItem);

        AdapterOEMV1<? extends ViewHolderOEMV1> oemAdapter = mOem.createListItemAdapter(oemItems);

        if (oemAdapter == null) {
            return mFactoryStub.createListItemAdapter(items);
        }

        RecyclerView.Adapter<? extends RecyclerView.ViewHolder> adapter =
                new CarUiListItemAdapterAdapterV1(oemAdapter);
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                oemItems.clear();
                oemItems.addAll(
                        CarUiUtils.convertList(items, PluginFactoryAdapterV2::toOemListItem));
            }

            @Override
            public void onItemRangeChanged(int positionStart, int itemCount) {
                for (int i = positionStart; i <= positionStart + itemCount; i++) {
                    oemItems.set(i, toOemListItem(items.get(i)));
                }
            }

            @Override
            public void onItemRangeChanged(int positionStart, int itemCount,
                    @Nullable Object payload) {
                for (int i = positionStart; i <= positionStart + itemCount; i++) {
                    oemItems.set(i, toOemListItem(items.get(i)));
                }
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                for (int i = positionStart; i <= positionStart + itemCount; i++) {
                    oemItems.add(i, toOemListItem(items.get(i)));
                }
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                for (int i = positionStart; i <= positionStart + itemCount; i++) {
                    oemItems.remove(i);
                }

            }

            @Override
            public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                for (int i = fromPosition; i <= fromPosition + itemCount; i++) {
                    ListItemOEMV1 item = oemItems.remove(i);
                    oemItems.add(toPosition, item);
                    toPosition++;
                }
            }
        });

        return adapter;
    }

    private static RecyclerViewAttributesOEMV1 from(Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(
                attrs,
                R.styleable.CarUiRecyclerView,
                0,
                0);
        final int carUiRecyclerViewLayout = a.getInt(
                R.styleable.CarUiRecyclerView_layoutStyle,
                CarUiRecyclerViewLayout.LINEAR);
        final int spanCount = a.getInt(
                R.styleable.CarUiRecyclerView_numOfColumns, /* defValue= */ 1);
        final boolean rotaryScrollEnabled = a.getBoolean(
                R.styleable.CarUiRecyclerView_rotaryScrollEnabled,
                /* defValue=*/ false);
        final int orientation = a.getInt(
                R.styleable.CarUiRecyclerView_android_orientation,
                CarUiLayoutStyle.VERTICAL);
        final boolean reversed = a.getBoolean(
                R.styleable.CarUiRecyclerView_reverseLayout, false);
        final int size = a.getInt(R.styleable.CarUiRecyclerView_carUiSize,
                CarUiRecyclerView.SIZE_LARGE);

        a.recycle();

        int[] attrsArray = new int[]{
                android.R.attr.layout_width,
                android.R.attr.layout_height,
                android.R.attr.minWidth,
                android.R.attr.minHeight,
                android.R.attr.paddingStart,
                android.R.attr.paddingLeft,
                android.R.attr.paddingEnd,
                android.R.attr.paddingRight,
                android.R.attr.paddingTop,
                android.R.attr.paddingBottom,
                android.R.attr.layout_marginStart,
                android.R.attr.layout_marginLeft,
                android.R.attr.layout_marginEnd,
                android.R.attr.layout_marginRight,
                android.R.attr.layout_marginTop,
                android.R.attr.layout_marginBottom,
                android.R.attr.background,
        };

        Arrays.sort(attrsArray);
        TypedArray ta = context.obtainStyledAttributes(attrs, attrsArray, 0, 0);
        final int width = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.layout_width),
                ViewGroup.LayoutParams.MATCH_PARENT);
        final int height = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.layout_height),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        final int minWidth = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.minWidth), 0);
        final int minHeight = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.minHeight), 0);
        final int paddingLeft = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.paddingLeft), 0);
        final int paddingRight = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.paddingRight), 0);
        final int paddingStart = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.paddingStart), 0);
        final int paddingEnd = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.paddingEnd), 0);
        final int paddingTop = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.paddingTop), 0);
        final int paddingBottom = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.paddingBottom), 0);
        final int marginLeft = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.layout_marginLeft), 0);
        final int marginRight = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.layout_marginRight), 0);
        final int marginStart = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.layout_marginStart), 0);
        final int marginEnd = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.layout_marginEnd), 0);
        final int marginTop = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.layout_marginTop), 0);
        final int marginBottom = ta.getLayoutDimension(
                Arrays.binarySearch(attrsArray, android.R.attr.layout_marginBottom), 0);
        final Drawable background = ta.getDrawable(
                Arrays.binarySearch(attrsArray, android.R.attr.background));
        ta.recycle();

        final LayoutStyleOEMV1 layoutStyle = new LayoutStyleOEMV1() {
            @Override
            public int getSpanCount() {
                return spanCount;
            }

            @Override
            public int getLayoutType() {
                switch (carUiRecyclerViewLayout) {
                    case CarUiRecyclerViewLayout.GRID:
                        return LayoutStyleOEMV1.LAYOUT_TYPE_GRID;
                    case CarUiRecyclerViewLayout.LINEAR:
                    default:
                        return LayoutStyleOEMV1.LAYOUT_TYPE_LINEAR;
                }
            }

            @Override
            public int getOrientation() {
                switch (orientation) {
                    case CarUiLayoutStyle.HORIZONTAL:
                        return LayoutStyleOEMV1.ORIENTATION_HORIZONTAL;
                    case CarUiLayoutStyle.VERTICAL:
                    default:
                        return LayoutStyleOEMV1.ORIENTATION_VERTICAL;
                }
            }

            @Override
            public boolean getReverseLayout() {
                return reversed;
            }
        };

        boolean isLtr = context.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_LTR;

        return new RecyclerViewAttributesOEMV1() {
            @Override
            public boolean isRotaryScrollEnabled() {
                return rotaryScrollEnabled;
            }

            @Override
            public int getSize() {
                switch (size) {
                    case CarUiRecyclerView.SIZE_SMALL:
                        return RecyclerViewAttributesOEMV1.SIZE_SMALL;
                    case CarUiRecyclerView.SIZE_MEDIUM:
                        return RecyclerViewAttributesOEMV1.SIZE_MEDIUM;
                    case CarUiRecyclerView.SIZE_LARGE:
                    default:
                        return RecyclerViewAttributesOEMV1.SIZE_LARGE;
                }
            }

            @Override
            public LayoutStyleOEMV1 getLayoutStyle() {
                return layoutStyle;
            }

            @Override
            public int getLayoutWidth() {
                return width;
            }

            @Override
            public int getLayoutHeight() {
                return height;
            }

            @Override
            public int geMinWidth() {
                return minWidth;
            }

            @Override
            public int getMinHeight() {
                return minHeight;
            }

            @Override
            public int getPaddingLeft() {
                if (paddingLeft != 0) {
                    return paddingLeft;
                } else if (isLtr) {
                    return paddingStart;
                } else {
                    return paddingEnd;
                }
            }

            @Override
            public int getPaddingRight() {
                if (paddingRight != 0) {
                    return paddingRight;
                } else if (isLtr) {
                    return paddingEnd;
                } else {
                    return paddingStart;
                }
            }

            @Override
            public int getPaddingTop() {
                return paddingTop;
            }

            @Override
            public int getPaddingBottom() {
                return paddingBottom;
            }

            @Override
            public int getMarginLeft() {
                if (marginLeft != 0) {
                    return marginLeft;
                } else if (isLtr) {
                    return marginStart;
                } else {
                    return marginEnd;
                }
            }

            @Override
            public int getMarginRight() {
                if (marginRight != 0) {
                    return marginRight;
                } else if (isLtr) {
                    return marginEnd;
                } else {
                    return marginStart;
                }
            }

            @Override
            public int getMarginTop() {
                return marginTop;
            }

            @Override
            public int getMarginBottom() {
                return marginBottom;
            }

            @Override
            public Drawable getBackground() {
                return background;
            }
        };
    }

    private static ListItemOEMV1 toOemListItem(CarUiListItem item) {
        if (item instanceof CarUiHeaderListItem) {
            CarUiHeaderListItem header = (CarUiHeaderListItem) item;
            return new HeaderListItemOEMV1.Builder(new SpannableString(header.getTitle()))
                    .setBody(new SpannableString(header.getBody()))
                    .build();
        } else if (item instanceof CarUiContentListItem) {
            CarUiContentListItem contentItem = (CarUiContentListItem) item;

            ContentListItemOEMV1.Builder builder = new ContentListItemOEMV1.Builder(
                    toOemListItemAction(contentItem.getAction()));

            if (contentItem.getTitle() != null) {
                builder.setTitle(toOemText(contentItem.getTitle()));
            }

            if (contentItem.getBody() != null) {
                builder.setBody(toOemText(contentItem.getBody()));
            }

            builder.setIcon(contentItem.getIcon(),
                    toOemListItemIconType(contentItem.getPrimaryIconType()));

            if (contentItem.getAction() == CarUiContentListItem.Action.ICON) {
                Consumer<ContentListItemOEMV1> listener =
                        contentItem.getSupplementalIconOnClickListener() != null
                                ? oemItem ->
                                contentItem.getSupplementalIconOnClickListener().onClick(
                                        contentItem) : null;
                builder.setSupplementalIcon(contentItem.getSupplementalIcon(), listener);
            }

            if (contentItem.getOnClickListener() != null) {
                Consumer<ContentListItemOEMV1> listener =
                        contentItem.getOnClickListener() != null
                                ? oemItem ->
                                contentItem.getOnClickListener().onClick(contentItem) : null;
                builder.setOnItemClickedListener(listener);
            }

            builder.setOnCheckedChangeListener(oem -> contentItem.setChecked(oem.isChecked()))
                    .setActionDividerVisible(contentItem.isActionDividerVisible())
                    .setEnabled(contentItem.isEnabled())
                    .setChecked(contentItem.isChecked())
                    .setActivated(contentItem.isActivated())
                    .setSecure(contentItem.isSecure());
            return builder.build();
        } else {
            throw new IllegalStateException("Unknown view type.");
        }
    }

    private static TextOEMV1 toOemText(CarUiText text) {
        return new TextOEMV1.Builder(text.getTextVariants()).setMaxChars(
                text.getMaxChars()).setMaxLines(text.getMaxLines()).build();
    }

    private static List<TextOEMV1> toOemText(List<CarUiText> lines) {
        List<TextOEMV1> oemLines = new ArrayList<>();

        for (CarUiText line : lines) {
            oemLines.add(new TextOEMV1.Builder(line.getTextVariants()).setMaxChars(
                    line.getMaxChars()).setMaxLines(line.getMaxLines()).build());
        }
        return oemLines;
    }

    private static ContentListItemOEMV1.Action toOemListItemAction(
            CarUiContentListItem.Action action) {
        switch (action) {
            case NONE:
                return ContentListItemOEMV1.Action.NONE;
            case SWITCH:
                return ContentListItemOEMV1.Action.SWITCH;
            case CHECK_BOX:
                return ContentListItemOEMV1.Action.CHECK_BOX;
            case RADIO_BUTTON:
                return ContentListItemOEMV1.Action.RADIO_BUTTON;
            case ICON:
                return ContentListItemOEMV1.Action.ICON;
            case CHEVRON:
                return ContentListItemOEMV1.Action.CHEVRON;
            default:
                throw new IllegalStateException("Unexpected list item action type");
        }
    }

    private static ContentListItemOEMV1.IconType toOemListItemIconType(
            CarUiContentListItem.IconType iconType) {
        switch (iconType) {
            case CONTENT:
                return ContentListItemOEMV1.IconType.CONTENT;
            case STANDARD:
                return ContentListItemOEMV1.IconType.STANDARD;
            case AVATAR:
                return ContentListItemOEMV1.IconType.AVATAR;
            default:
                throw new IllegalStateException("Unexpected list item icon type");
        }
    }
}
