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

package android.car.telemetry;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A parcelable that wraps around the Manifest name and version.
 *
 * @hide
 */
public final class MetricsConfigKey implements Parcelable {

    @NonNull
    private String mName;
    private int mVersion;

    @NonNull
    public String getName() {
        return mName;
    }

    public int getVersion() {
        return mVersion;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeString(mName);
        out.writeInt(mVersion);
    }

    private MetricsConfigKey(Parcel in) {
        mName = in.readString();
        mVersion = in.readInt();
    }

    public MetricsConfigKey(@NonNull String name, int version) {
        mName = name;
        mVersion = version;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MetricsConfigKey)) {
            return false;
        }
        MetricsConfigKey other = (MetricsConfigKey) o;
        return mName.equals(other.getName()) && mVersion == other.getVersion();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mVersion);
    }

    public static final @NonNull Parcelable.Creator<MetricsConfigKey> CREATOR =
            new Parcelable.Creator<MetricsConfigKey>() {
                @Override
                public MetricsConfigKey createFromParcel(Parcel in) {
                    return new MetricsConfigKey(in);
                }

                @Override
                public MetricsConfigKey[] newArray(int size) {
                    return new MetricsConfigKey[size];
                }
            };
}
