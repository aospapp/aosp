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

package com.android.car.ui.plugin.oemapis.toolbar;

import android.graphics.drawable.Drawable;

import java.util.Objects;

/** The OEM interface of a MenuItem, which is a button in the toolbar */
public final class MenuItemOEMV1 {
    private final int mKey;
    private final String mTitle;
    private final Drawable mIcon;
    private final boolean mIsVisible;
    private final boolean mIsEnabled;
    private final boolean mIsPrimary;
    private final boolean mIsTinted;
    private final boolean mIsRestricted;
    private final boolean mShowIconAndTitle;
    private final int mDisplayBehavior;
    private final boolean mIsCheckable;
    private final boolean mIsChecked;
    private final boolean mIsActivatable;
    private final boolean mIsActivated;
    private final Runnable mOnClickListener;

    private MenuItemOEMV1(Builder builder) {
        mKey = builder.mKey;
        mTitle = builder.mTitle;
        mIcon = builder.mIcon;
        mIsVisible = builder.mIsVisible;
        mIsEnabled = builder.mIsEnabled;
        mIsPrimary = builder.mIsPrimary;
        mIsTinted = builder.mIsTinted;
        mIsRestricted = builder.mIsRestricted;
        mShowIconAndTitle = builder.mShowIconAndTitle;
        mDisplayBehavior = builder.mDisplayBehavior;
        mIsCheckable = builder.mIsCheckable;
        mIsChecked = builder.mIsChecked;
        mIsActivatable = builder.mIsActivatable;
        mIsActivated = builder.mIsActivated;
        mOnClickListener = builder.mOnClickListener;
    }

    /**
     * Gets the key, which can be used to determine if a changed MenuItem is the same as
     * a previous MenuItem or not.
     */
    public int getKey() {
        return mKey;
    }

    /** Gets the title of this MenuItem. */
    public String getTitle() {
        return mTitle;
    }

    /** Gets the current Icon */
    public Drawable getIcon() {
        return mIcon;
    }

    /**
     * Returns whether the MenuItem is visible. This is somewhat of a redundant property, as
     * the MenuItem could've been excluded from the list passed to
     * {@link ToolbarControllerOEMV1#setMenuItems}, but having it makes it easier to diff
     * the old and new list of MenuItems to provide efficient updates.
     */
    public boolean isVisible() {
        return mIsVisible;
    }

    /**
     * Returns whether the MenuItem is enabled. A disabled MenuItem should look visually
     * different and not send click events to its onClickListener.
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * Returns if this MenuItem is a primary one, which should be visually different.
     */
    public boolean isPrimary() {
        return mIsPrimary;
    }

    /** Whether or not to tint the Icon to match the theme of the toolbar. */
    public boolean isTinted() {
        return mIsTinted;
    }

    /**
     * Returns if this MenuItem is restricted due to the current driving restrictions and driving
     * state. It should be displayed visually distinctly to indicate that. OnClick events should
     * still be passed to the onClickListener.
     */
    public boolean isRestricted() {
        return mIsRestricted;
    }

    /**
     * Returns if both the icon and title should be shown. If not, and they're both provided,
     * only the icon will be shown and the title will be used as a content description.
     */
    public boolean isShowingIconAndTitle() {
        return mShowIconAndTitle;
    }

    /** Always show the MenuItem on the toolbar */
    public static final int DISPLAY_BEHAVIOR_ALWAYS = 0;
    /** Show the MenuItem in the toolbar if there's space, otherwise show it in the overflow menu */
    public static final int DISPLAY_BEHAVIOR_IF_ROOM = 1;
    /** Never show the MenuItem on the toolbar, always put it in an overflow menu */
    public static final int DISPLAY_BEHAVIOR_NEVER = 2;

    /**
     * Gets the current display behavior.
     *
     * See {@link #DISPLAY_BEHAVIOR_ALWAYS}, {@link #DISPLAY_BEHAVIOR_IF_ROOM}, and
     * {@link #DISPLAY_BEHAVIOR_NEVER}.
     */
    public int getDisplayBehavior() {
        return mDisplayBehavior;
    }

    /** Returns whether the MenuItem is checkable. If it is, it will be displayed as a switch. */
    public boolean isCheckable() {
        return mIsCheckable;
    }

    /**
     * Returns whether the MenuItem is currently checked. Only valid if {@link #isCheckable()}
     * is true.
     */
    public boolean isChecked() {
        return mIsChecked;
    }

    /**
     * Returns whether the MenuItem is activatable. If it is, it's every click will toggle
     * the MenuItem's View to appear activated or not.
     */
    public boolean isActivatable() {
        return mIsActivatable;
    }

    /**
     * Returns whether or not this view is activated. Toggles after every click if
     * {@link #isActivatable} is true.
     */
    public boolean isActivated() {
        return mIsActivated;
    }

    /**
     * Returns the onClickListener for this MenuItem. The plugin must not call this
     * when the MenuItem is disabled, but still should call it when it's restricted.
     */
    public Runnable getOnClickListener() {
        return mOnClickListener;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MenuItemOEMV1)) return false;
        MenuItemOEMV1 that = (MenuItemOEMV1) o;
        return mKey == that.mKey
                && mIsVisible == that.mIsVisible
                && mIsEnabled == that.mIsEnabled
                && mIsPrimary == that.mIsPrimary
                && mIsTinted == that.mIsTinted
                && mIsRestricted == that.mIsRestricted
                && mShowIconAndTitle == that.mShowIconAndTitle
                && mDisplayBehavior == that.mDisplayBehavior
                && mIsCheckable == that.mIsCheckable
                && mIsChecked == that.mIsChecked
                && mIsActivatable == that.mIsActivatable
                && mIsActivated == that.mIsActivated
                && Objects.equals(mTitle, that.mTitle)
                && Objects.equals(mIcon, that.mIcon)
                && mOnClickListener == that.mOnClickListener;
    }

    @Override
    public int hashCode() {
        // Exclude mOnClickListener from the hashcode because it must be exactly the
        // same object. We don't want a Runnable subclass to be able to override hashCode()
        // and then break the contract that two equal objects must produce the same hashcode.
        return Objects.hash(mKey, mTitle, mIcon, mIsVisible, mIsEnabled, mIsPrimary, mIsTinted,
                mIsRestricted, mShowIconAndTitle, mDisplayBehavior, mIsCheckable, mIsChecked,
                mIsActivatable, mIsActivated);
    }

    /** A builder class for {@link MenuItemOEMV1} */
    public static final class Builder {
        private int mKey = 0;
        private String mTitle;
        private Drawable mIcon;
        private boolean mIsVisible = true;
        private boolean mIsEnabled = true;
        private boolean mIsPrimary = false;
        private boolean mIsTinted = true;
        private boolean mIsRestricted = false;
        private boolean mShowIconAndTitle = false;
        private int mDisplayBehavior = MenuItemOEMV1.DISPLAY_BEHAVIOR_ALWAYS;
        private boolean mIsCheckable = false;
        private boolean mIsChecked = false;
        private boolean mIsActivatable = false;
        private boolean mIsActivated = false;
        private Runnable mOnClickListener;

        private Builder() {
        }

        private Builder(MenuItemOEMV1 toCopy) {
            mKey = toCopy.mKey;
            mTitle = toCopy.mTitle;
            mIcon = toCopy.mIcon;
            mIsVisible = toCopy.mIsVisible;
            mIsEnabled = toCopy.mIsEnabled;
            mIsPrimary = toCopy.mIsPrimary;
            mIsTinted = toCopy.mIsTinted;
            mIsRestricted = toCopy.mIsRestricted;
            mShowIconAndTitle = toCopy.mShowIconAndTitle;
            mDisplayBehavior = toCopy.mDisplayBehavior;
            mIsCheckable = toCopy.mIsCheckable;
            mIsChecked = toCopy.mIsChecked;
            mIsActivatable = toCopy.mIsActivatable;
            mIsActivated = toCopy.mIsActivated;
            mOnClickListener = toCopy.mOnClickListener;
        }

        /** {@link MenuItemOEMV1#getKey()} */
        public Builder setKey(int key) {
            mKey = key;
            return this;
        }

        /** {@link MenuItemOEMV1#getTitle()} */
        public Builder setTitle(String title) {
            mTitle = title;
            return this;
        }

        /** {@link MenuItemOEMV1#getIcon()} */
        public Builder setIcon(Drawable icon) {
            mIcon = icon;
            return this;
        }

        /** {@link MenuItemOEMV1#isVisible()} */
        public Builder setVisible(boolean visible) {
            mIsVisible = visible;
            return this;
        }

        /** {@link MenuItemOEMV1#isEnabled()} */
        public Builder setEnabled(boolean enabled) {
            mIsEnabled = enabled;
            return this;
        }

        /** {@link MenuItemOEMV1#isPrimary()} */
        public Builder setPrimary(boolean primary) {
            mIsPrimary = primary;
            return this;
        }

        /** {@link MenuItemOEMV1#isTinted()} */
        public Builder setTinted(boolean tinted) {
            mIsTinted = tinted;
            return this;
        }

        /** {@link MenuItemOEMV1#isRestricted()} */
        public Builder setRestricted(boolean restricted) {
            mIsRestricted = restricted;
            return this;
        }

        /** {@link MenuItemOEMV1#isShowingIconAndTitle()} */
        public Builder setShowIconAndTitle(boolean showIconAndTitle) {
            mShowIconAndTitle = showIconAndTitle;
            return this;
        }

        /** {@link MenuItemOEMV1#getDisplayBehavior()} */
        public Builder setDisplayBehavior(int displayBehavior) {
            if (displayBehavior != MenuItemOEMV1.DISPLAY_BEHAVIOR_ALWAYS
                    && displayBehavior != MenuItemOEMV1.DISPLAY_BEHAVIOR_IF_ROOM
                    && displayBehavior != MenuItemOEMV1.DISPLAY_BEHAVIOR_NEVER) {
                throw new IllegalArgumentException("Invalid display behavior!");
            }
            mDisplayBehavior = displayBehavior;
            return this;
        }

        /** {@link MenuItemOEMV1#isCheckable()} */
        public Builder setCheckable(boolean checkable) {
            mIsCheckable = checkable;
            if (!mIsCheckable) {
                mIsChecked = false;
            }
            return this;
        }

        /** {@link MenuItemOEMV1#isChecked()} */
        public Builder setChecked(boolean checked) {
            if (!mIsCheckable) {
                throw new IllegalStateException("MenuItem must be checkable to be checked");
            }
            mIsChecked = checked;
            return this;
        }

        /** {@link MenuItemOEMV1#isActivatable()} */
        public Builder setActivatable(boolean activatable) {
            mIsActivatable = activatable;
            if (!mIsActivatable) {
                mIsActivated = false;
            }
            return this;
        }

        /** {@link MenuItemOEMV1#isActivated()} */
        public Builder setActivated(boolean activated) {
            if (!mIsActivatable) {
                throw new IllegalStateException("MenuItem must be activatable to be activated");
            }
            mIsActivated = activated;
            return this;
        }

        /** {@link MenuItemOEMV1#getOnClickListener()} */
        public Builder setOnClickListener(Runnable onClickListener) {
            mOnClickListener = onClickListener;
            return this;
        }

        /** Builds the final {@link MenuItemOEMV1} */
        public MenuItemOEMV1 build() {
            return new MenuItemOEMV1(this);
        }
    }

    /**
     * Creates a new {@link Builder}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new {@link Builder} that is initialized with all the properties of the current
     * MenuItem. This can be used for creating an altered copy of the MenuItem easily.
     */
    public Builder copy() {
        return new Builder(this);
    }
}
