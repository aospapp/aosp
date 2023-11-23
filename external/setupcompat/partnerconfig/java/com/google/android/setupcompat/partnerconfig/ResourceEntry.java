/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.setupcompat.partnerconfig;

import android.os.Bundle;
import androidx.annotation.VisibleForTesting;

/**
 * A potentially cross-package resource entry, which can then be retrieved using {@link
 * PackageManager#getApplicationForResources}. This class can also be sent across to other packages
 * on IPC via the Bundle representation.
 */
public final class ResourceEntry {
  @VisibleForTesting static final String KEY_PACKAGE_NAME = "packageName";
  @VisibleForTesting static final String KEY_RESOURCE_NAME = "resourceName";
  @VisibleForTesting static final String KEY_RESOURCE_ID = "resourceId";

  private final String packageName;
  private final String resourceName;
  private final int resourceId;

  /**
   * Creates a {@code ResourceEntry} object from a provided bundle.
   *
   * @param bundle the source bundle needs to have all the information for a {@code ResourceEntry}
   */
  public static ResourceEntry fromBundle(Bundle bundle) {
    String packageName;
    String resourceName;
    int resourceId;
    if (!bundle.containsKey(KEY_PACKAGE_NAME)
        || !bundle.containsKey(KEY_RESOURCE_NAME)
        || !bundle.containsKey(KEY_RESOURCE_ID)) {
      return null;
    }
    packageName = bundle.getString(KEY_PACKAGE_NAME);
    resourceName = bundle.getString(KEY_RESOURCE_NAME);
    resourceId = bundle.getInt(KEY_RESOURCE_ID);
    return new ResourceEntry(packageName, resourceName, resourceId);
  }

  public ResourceEntry(String packageName, String resourceName, int resourceId) {
    this.packageName = packageName;
    this.resourceName = resourceName;
    this.resourceId = resourceId;
  }

  public String getPackageName() {
    return this.packageName;
  }

  public String getResourceName() {
    return this.resourceName;
  }

  public int getResourceId() {
    return this.resourceId;
  }

  /**
   * Returns a bundle representation of this resource entry, which can then be sent over IPC.
   *
   * @see #fromBundle(Bundle)
   */
  public Bundle toBundle() {
    Bundle result = new Bundle();
    result.putString(KEY_PACKAGE_NAME, packageName);
    result.putString(KEY_RESOURCE_NAME, resourceName);
    result.putInt(KEY_RESOURCE_ID, resourceId);
    return result;
  }
}
