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

package com.android.car.settings.qc;

import static com.android.car.qc.QCItem.QC_ACTION_TOGGLE_STATE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.qc.QCItem;
import com.android.car.qc.QCList;
import com.android.car.qc.QCRow;
import com.android.settingslib.net.DataUsageController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class MobileDataRowTest {
    private static final String TEST_NETWORK_NAME = "TEST_NETWORK";

    private Context mContext = spy(ApplicationProvider.getApplicationContext());
    private MobileDataRow mMobileDataRow;

    @Mock
    private DataUsageController mDataUsageController;
    @Mock
    private TelephonyManager mTelephonyManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        mMobileDataRow = new TestMobileDataRow(mContext);
    }

    @Test
    public void getQCItem_mobileDataUnsupported_returnsNull() {
        when(mDataUsageController.isMobileDataSupported()).thenReturn(false);
        QCItem item = mMobileDataRow.getQCItem();
        assertThat(item).isNull();
    }

    @Test
    public void getQCItem_mobileDataDisabled_switchUnchecked() {
        when(mDataUsageController.isMobileDataSupported()).thenReturn(true);
        when(mTelephonyManager.getNetworkOperatorName()).thenReturn(TEST_NETWORK_NAME);
        when(mDataUsageController.isMobileDataEnabled()).thenReturn(false);
        QCRow row = getRow();
        assertThat(row.getEndItems().get(0).isChecked()).isFalse();
    }

    @Test
    public void getQCItem_mobileDataEnabled_switchChecked() {
        when(mDataUsageController.isMobileDataSupported()).thenReturn(true);
        when(mTelephonyManager.getNetworkOperatorName()).thenReturn(TEST_NETWORK_NAME);
        when(mDataUsageController.isMobileDataEnabled()).thenReturn(true);
        QCRow row = getRow();
        assertThat(row.getEndItems().get(0).isChecked()).isTrue();
    }

    @Test
    public void getQCItem_noNetworkName_nullSubtitle() {
        when(mDataUsageController.isMobileDataSupported()).thenReturn(true);
        when(mTelephonyManager.getNetworkOperatorName()).thenReturn("");
        when(mDataUsageController.isMobileDataEnabled()).thenReturn(true);
        QCRow row = getRow();
        assertThat(row.getSubtitle()).isNull();
    }

    @Test
    public void getQCItem_hasNetworkName_setsSubtitle() {
        when(mDataUsageController.isMobileDataSupported()).thenReturn(true);
        when(mTelephonyManager.getNetworkOperatorName()).thenReturn(TEST_NETWORK_NAME);
        when(mDataUsageController.isMobileDataEnabled()).thenReturn(true);
        QCRow row = getRow();
        assertThat(row.getSubtitle()).isEqualTo(TEST_NETWORK_NAME);
    }

    @Test
    public void onNotifyChange_togglesMobileData() {
        when(mDataUsageController.isMobileDataSupported()).thenReturn(true);
        when(mTelephonyManager.getNetworkOperatorName()).thenReturn(TEST_NETWORK_NAME);
        when(mDataUsageController.isMobileDataEnabled()).thenReturn(false);
        Intent intent = new Intent();
        intent.putExtra(QC_ACTION_TOGGLE_STATE, true);
        mMobileDataRow.onNotifyChange(intent);
        verify(mDataUsageController).setMobileDataEnabled(true);
    }

    private QCRow getRow() {
        QCItem item = mMobileDataRow.getQCItem();
        assertThat(item).isNotNull();
        assertThat(item instanceof QCList).isTrue();
        QCList list = (QCList) item;
        assertThat(list.getRows().size()).isEqualTo(1);
        return list.getRows().get(0);
    }

    private class TestMobileDataRow extends MobileDataRow {

        TestMobileDataRow(Context context) {
            super(context);
        }

        @Override
        DataUsageController getDataUsageController(Context context) {
            return mDataUsageController;
        }
    }
}
