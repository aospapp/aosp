/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.storagemanager.deletionhelper;

import androidx.preference.PreferenceViewHolder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.android.storagemanager.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.LooperMode;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;
import static org.robolectric.annotation.LooperMode.Mode.LEGACY;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LEGACY)
public class PhotosDeletionPreferenceTest {
    private PreferenceViewHolder mHolder;
    private PhotosDeletionPreference mPreference;
    @Mock private DeletionType mDeletionType;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mPreference = new PhotosDeletionPreference(RuntimeEnvironment.application, null);
        mPreference.registerDeletionService(mDeletionType);

        // Inflate the preference and the widget.
        LayoutInflater inflater = LayoutInflater.from(RuntimeEnvironment.application);
        final View view =
                inflater.inflate(
                        mPreference.getLayoutResource(),
                        new LinearLayout(RuntimeEnvironment.application),
                        false);
        inflater.inflate(mPreference.getWidgetLayoutResource(),
                (ViewGroup) view.findViewById(android.R.id.widget_frame));

        mHolder = PreferenceViewHolder.createInstanceForTests(view);
    }

    @Test
    public void testConstructor() {
        assertThat(mPreference.getFreeableBytes(DeletionHelperSettings.COUNT_CHECKED_ONLY))
                .isEqualTo(0);
    }

    @Test
    public void testItemVisibilityBeforeLoaded() {
        mPreference.onBindViewHolder(mHolder);
        assertThat(mHolder.findViewById(R.id.progress_bar).getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mHolder.findViewById(android.R.id.icon).getVisibility()).isEqualTo(View.GONE);
        assertThat(mHolder.findViewById(android.R.id.widget_frame).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void testItemVisibilityAfterLoaded() {
        mPreference.onFreeableChanged(0, 0);
        Robolectric.flushBackgroundThreadScheduler();
        Robolectric.flushForegroundThreadScheduler();
        mPreference.onBindViewHolder(mHolder);

        // After onFreeableChanged is called, we're no longer loading.
        assertThat(mHolder.findViewById(R.id.progress_bar).getVisibility()).isEqualTo(View.GONE);
        assertThat(mHolder.findViewById(android.R.id.icon).getVisibility()).isEqualTo(View.GONE);
        assertThat(mHolder.findViewById(android.R.id.checkbox).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void testTitleAndSummaryAfterLoaded() {
        mPreference.onFreeableChanged(10, 1000L);
        Robolectric.flushBackgroundThreadScheduler();
        Robolectric.flushForegroundThreadScheduler();
        mPreference.onBindViewHolder(mHolder);

        assertThat(mPreference.getTitle()).isEqualTo("Backed up photos & videos");
        assertThat(mPreference.getSummary().toString()).isEqualTo("1.00 kB");
    }

    @Test
    public void testDisabledIfNothingToClear() {
        when(mDeletionType.isEmpty()).thenReturn(true);
        mPreference.onFreeableChanged(0, 0);
        Robolectric.flushBackgroundThreadScheduler();
        Robolectric.flushForegroundThreadScheduler();
        mPreference.onBindViewHolder(mHolder);

        assertThat(mPreference.isEnabled()).isFalse();
    }

    @Test
    public void testGetFreeableBytes() {
        mPreference.onFreeableChanged(100, 1024L);
        Robolectric.flushBackgroundThreadScheduler();
        Robolectric.flushForegroundThreadScheduler();

        assertThat(mPreference.getFreeableBytes(DeletionHelperSettings.COUNT_CHECKED_ONLY))
                .isEqualTo(0);
        assertThat(mPreference.getFreeableBytes(DeletionHelperSettings.COUNT_UNCHECKED))
                .isEqualTo(1024L);
    }
}
