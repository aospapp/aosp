package com.android.server.deviceconfig;

import java.io.FileDescriptor;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Binder;
import android.provider.UpdatableDeviceConfigServiceReadiness;

import com.android.server.SystemService;

/** @hide */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public class DeviceConfigInit {

    private DeviceConfigInit() {
        // do not instantiate
    }

    /** @hide */
    @SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    public static class Lifecycle extends SystemService {
        private DeviceConfigShellService mShellService;

        /** @hide */
        @SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
        public Lifecycle(@NonNull Context context) {
            super(context);
            // this service is always instantiated but should only launch subsequent services
            // if the module is ready
            if (UpdatableDeviceConfigServiceReadiness.shouldStartUpdatableService()) {
                mShellService = new DeviceConfigShellService();
            }
        }

        /** @hide */
        @Override
        public void onStart() {
            if (UpdatableDeviceConfigServiceReadiness.shouldStartUpdatableService()) {
                publishBinderService("device_config_updatable", mShellService);
            }
        }
    }
}
