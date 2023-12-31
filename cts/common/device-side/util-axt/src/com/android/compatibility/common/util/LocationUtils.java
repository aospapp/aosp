/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.compatibility.common.util;

import android.app.Instrumentation;
import android.location.Location;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Random;

public class LocationUtils {

    private static final double MIN_LATITUDE = -90D;
    private static final double MAX_LATITUDE = 90D;
    private static final double MIN_LONGITUDE = -180D;
    private static final double MAX_LONGITUDE = 180D;

    private static final float MIN_ACCURACY = 1;
    private static final float MAX_ACCURACY = 100;

    public static void registerMockLocationProvider(Instrumentation instrumentation,
            boolean enable) throws IOException {
        SystemUtil.runShellCommand(instrumentation,
                String.format("appops set --user %d %s android:mock_location %s",
                        instrumentation.getContext().getUserId(),
                        instrumentation.getContext().getPackageName(),
                        enable ? "allow" : "deny"));
    }

    public static Location createLocation(String provider, Random random) {
        return createLocation(provider,
                MIN_LATITUDE + random.nextDouble() * (MAX_LATITUDE - MIN_LATITUDE),
                MIN_LONGITUDE + random.nextDouble() * (MAX_LONGITUDE - MIN_LONGITUDE),
                MIN_ACCURACY + random.nextFloat() * (MAX_ACCURACY - MIN_ACCURACY));
    }

    public static Location createLocation(String provider, double latitude, double longitude, float accuracy) {
        return createLocation(provider, latitude, longitude, accuracy, SystemClock.elapsedRealtimeNanos());
    }

    public static Location createLocation(String provider, double latitude, double longitude, float accuracy, long elapsedRealTimeNanos) {
        Location location = new Location(provider);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAccuracy(accuracy);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(elapsedRealTimeNanos);
        return location;
    }
}
