/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.leaudio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Objects;


public class BroadcastScanActivity extends AppCompatActivity {
    // Integer key used for sending/receiving receiver ID.
    public static final String EXTRA_BASS_RECEIVER_ID = "receiver_id";

    private static final int BIS_ALL = 0xFFFFFFFF;

    private BluetoothDevice device;
    private BroadcastScanViewModel mViewModel;
    private BroadcastItemsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.broadcast_scan_activity);

        RecyclerView recyclerView = findViewById(R.id.broadcast_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);

        adapter = new BroadcastItemsAdapter();
        adapter.setOnItemClickListener(broadcastId -> {
            mViewModel.scanForBroadcasts(device, false);

            BluetoothLeBroadcastMetadata broadcast = null;
            for (BluetoothLeBroadcastMetadata b : mViewModel.getAllBroadcasts().getValue()) {
                if (Objects.equals(b.getBroadcastId(), broadcastId)) {
                    broadcast = b;
                    break;
                }
            }

            if (broadcast == null) {
                Toast.makeText(recyclerView.getContext(), "Matching broadcast not found."
                                + " broadcastId=" + broadcastId, Toast.LENGTH_SHORT).show();
                return;
            }

            // Set broadcast source on peer only if scan delegator device context is available
            if (device != null) {
                Toast.makeText(recyclerView.getContext(), "Adding broadcast source"
                                + " broadcastId=" + broadcastId, Toast.LENGTH_SHORT).show();
                mViewModel.addBroadcastSource(device, broadcast);
            }
        });
        recyclerView.setAdapter(adapter);

        mViewModel = ViewModelProviders.of(this).get(BroadcastScanViewModel.class);
        mViewModel.getAllBroadcasts().observe(this, audioBroadcasts -> {
            // Update Broadcast list in the adapter
            adapter.setBroadcasts(audioBroadcasts);
        });

        Intent intent = getIntent();
        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    @Override
    protected void onPause() {
        super.onPause();

        mViewModel.scanForBroadcasts(device, false);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mViewModel.getAllBroadcasts().getValue() != null)
            adapter.setBroadcasts(mViewModel.getAllBroadcasts().getValue());

        mViewModel.scanForBroadcasts(device, true);
        mViewModel.refreshBroadcasts();
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
        finish();
    }
}
