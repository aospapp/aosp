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

package com.android.bedstead.nene.resources;


public class ResourcesWrapper {

    private final android.content.res.Resources mResources;

    ResourcesWrapper(android.content.res.Resources resources) {
        mResources = resources;
    }

    /**
     * Get resource identifier
     */
    public int getIdentifier(String configName, String defType, String defPackage) {
        return mResources.getIdentifier(configName, defType, defPackage);
    }

    /**
     * Get string resource through identifier
     */
    public String getString(int id) {
        return mResources.getString(id);
    }

    /**
     * Get string resource.
     */
    public String getString(String configName, String defType, String defPackage) {
        return getString(getIdentifier(configName, defType, defPackage));
    }

}
