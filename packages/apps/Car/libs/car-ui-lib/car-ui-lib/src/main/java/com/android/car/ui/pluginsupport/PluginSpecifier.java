/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.content.pm.PackageInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * This is a data class that represents a particular plugin. It can be used
 * via {@link PluginConfigProvider} to deny loading certain plugins.
 *
 * Create a new instance of this class using {@link #builder()}.
 */
@TargetApi(MIN_TARGET_API)
public final class PluginSpecifier {

    @Nullable
    private final String mPackageName;
    @Nullable
    private final Long mMaxVersion;

    private PluginSpecifier(Builder builder) {
        mPackageName = builder.mPackageName;
        mMaxVersion = builder.mMaxVersion;
    }

    /** Creates a {@link Builder} */
    public static Builder builder() {
        return new Builder();
    }

    /* package */ boolean matches(@NonNull PackageInfo packageInfo) {
        boolean nameMatches = mPackageName == null
                || mPackageName.equals(packageInfo.packageName);
        boolean versionMatches = mMaxVersion == null
                || mMaxVersion >= packageInfo.getLongVersionCode();
        return nameMatches && versionMatches;
    }

    /** A builder class for {@link PluginSpecifier} */
    public static class Builder {
        @Nullable
        private String mPackageName;
        @Nullable
        private Long mMaxVersion;

        private Builder() {}

        /**
         * Sets the package name to match. If it is unset, any package name will be matched.
         *
         * @return This builder, for chaining calls.
         */
        public Builder setPackageName(String packageName) {
            mPackageName = packageName;
            return this;
        }

        /**
         * Sets the maximum version to match. This is the {@code android:versionCode} integer
         * in the plugin's {@code <manifest>} tag. If it is unset, any version will
         * be matched.
         *
         * @return This builder, for chaining calls.
         */
        public Builder setMaxVersion(long maxVersion) {
            mMaxVersion = maxVersion;
            return this;
        }

        /** Builds the {@link PluginSpecifier} */
        public PluginSpecifier build() {
            return new PluginSpecifier(this);
        }
    };
}
