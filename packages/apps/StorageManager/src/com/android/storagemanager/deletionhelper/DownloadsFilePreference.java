/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.storagemanager.deletionhelper;

import android.content.Context;
import android.support.v7.preference.Preference;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.webkit.MimeTypeMap;
import com.android.storagemanager.R;
import com.android.storagemanager.utils.IconUtils;

import java.io.File;

/**
 * DownloadsFilePreference is a preference representing a file in the Downloads folder
 * with a checkbox that represents if the file should be deleted.
 */
public class DownloadsFilePreference extends NestedCheckboxPreference {
    private File mFile;

    public DownloadsFilePreference(Context context, File file) {
        super(context);
        mFile = file;
        setKey(mFile.getPath());
        setTitle(file.getName());
        setSummary(context.getString(R.string.deletion_helper_downloads_file_summary,
                Formatter.formatFileSize(getContext(), file.length()),
                DateUtils.formatDateTime(context,
                        mFile.lastModified(),DateUtils.FORMAT_SHOW_DATE)));
        setIcon(context.getContentResolver().getTypeDrawable(getMimeType()));

        // We turn off persistence because we need the file preferences to reset their check when
        // you return to the view.
        setPersistent(false);
    }

    public File getFile() {
        return mFile;
    }

    @Override
    public int compareTo(Preference other) {
        if (other == null) {
            return 1;
        }

        if (other instanceof DownloadsFilePreference) {
            DownloadsFilePreference preference = (DownloadsFilePreference) other;
            return Long.compare(preference.getFile().length(), getFile().length());
        } else {
            // If a non-DownloadsFilePreference appears, consider ourselves to be greater.
            // This means if a non-DownloadsFilePreference sneaks into a DownloadsPreferenceGroup
            // then the DownloadsFilePreference will appear higher.
            return 1;
        }
    }

    private String getMimeType() {
        String name = getFile().getName();
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1);
            final String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    extension.toLowerCase());
            if (mimeType != null) {
                return mimeType;
            }
        }
        return "application/octet-stream";
    }
}
