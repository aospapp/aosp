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

package com.android.adservices.service.measurement.registration;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** Wrapper for a list of redirect Uris and a redirect type */
public class AsyncRedirect {
    private final List<Uri> mLocationRedirects;
    private final List<Uri> mListRedirects;

    public AsyncRedirect() {
        mLocationRedirects = new ArrayList<>();
        mListRedirects = new ArrayList<>();
    }

    public AsyncRedirect(List<Uri> locationRedirects, List<Uri> listRedirects) {
        mLocationRedirects = locationRedirects;
        mListRedirects = listRedirects;
    }

    /** The list the redirect Uris */
    public List<Uri> getRedirects() {
        List<Uri> allRedirects = new ArrayList<>(mListRedirects);
        allRedirects.addAll(mLocationRedirects);
        return allRedirects;
    }

    /** Get list by redirect type */
    public List<Uri> getRedirectsByType(AsyncRegistration.RedirectType redirectType) {
        if (redirectType == AsyncRegistration.RedirectType.LOCATION) {
            return new ArrayList<>(mLocationRedirects);
        } else {
            return new ArrayList<>(mListRedirects);
        }
    }

    /** Add to the list the redirect Uris based on type */
    public void addToRedirects(AsyncRegistration.RedirectType redirectType, List<Uri> uris) {
        if (redirectType == AsyncRegistration.RedirectType.LOCATION) {
            mLocationRedirects.addAll(uris);
        } else {
            mListRedirects.addAll(uris);
        }
    }
}
