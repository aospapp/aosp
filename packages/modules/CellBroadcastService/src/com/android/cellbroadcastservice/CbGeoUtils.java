/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.cellbroadcastservice;

import android.annotation.NonNull;
import android.telephony.CbGeoUtils.Circle;
import android.telephony.CbGeoUtils.Geometry;
import android.telephony.CbGeoUtils.LatLng;
import android.telephony.CbGeoUtils.Polygon;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This utils class is specifically used for geo-targeting of CellBroadcast messages.
 * The coordinates used by this utils class are latitude and longitude, but some algorithms in this
 * class only use them as coordinates on plane, so the calculation will be inaccurate. So don't use
 * this class for anything other then geo-targeting of cellbroadcast messages.
 */
public class CbGeoUtils {
    /**
     * Tolerance for determining if the value is 0. If the absolute value of a value is less than
     * this tolerance, it will be treated as 0.
     */
    public static final double EPS = 1e-7;

    private static final String TAG = "CbGeoUtils";

    /** The TLV tags of WAC, defined in ATIS-0700041 5.2.3 WAC tag coding. */
    public static final int GEO_FENCING_MAXIMUM_WAIT_TIME = 0x01;
    public static final int GEOMETRY_TYPE_POLYGON = 0x02;
    public static final int GEOMETRY_TYPE_CIRCLE = 0x03;

    /** The identifier of geometry in the encoded string. */
    private static final String CIRCLE_SYMBOL = "circle";
    private static final String POLYGON_SYMBOL = "polygon";

    /**
     * Parse the geometries from the encoded string {@code str}. The string must follow the
     * geometry encoding specified by {@link android.provider.Telephony.CellBroadcasts#GEOMETRIES}.
     */
    @NonNull
    public static List<Geometry> parseGeometriesFromString(@NonNull String str) {
        List<Geometry> geometries = new ArrayList<>();
        for (String geometryStr : str.split("\\s*;\\s*")) {
            String[] geoParameters = geometryStr.split("\\s*\\|\\s*");
            switch (geoParameters[0]) {
                case CIRCLE_SYMBOL:
                    geometries.add(new Circle(parseLatLngFromString(geoParameters[1]),
                            Double.parseDouble(geoParameters[2])));
                    break;
                case POLYGON_SYMBOL:
                    List<LatLng> vertices = new ArrayList<>(geoParameters.length - 1);
                    for (int i = 1; i < geoParameters.length; i++) {
                        vertices.add(parseLatLngFromString(geoParameters[i]));
                    }
                    geometries.add(new Polygon(vertices));
                    break;
                default:
                    final String errorMessage = "Invalid geometry format " + geometryStr;
                    Log.e(TAG, errorMessage);
                    CellBroadcastStatsLog.write(CellBroadcastStatsLog.CB_MESSAGE_ERROR,
                            CellBroadcastStatsLog.CELL_BROADCAST_MESSAGE_ERROR__TYPE__UNEXPECTED_GEOMETRY_FROM_FWK,
                            errorMessage);
            }
        }
        return geometries;
    }

    /**
     * Encode a list of geometry objects to string. The encoding format is specified by
     * {@link android.provider.Telephony.CellBroadcasts#GEOMETRIES}.
     *
     * @param geometries the list of geometry objects need to be encoded.
     * @return the encoded string.
     */
    @NonNull
    public static String encodeGeometriesToString(@NonNull List<Geometry> geometries) {
        return geometries.stream()
                .map(geometry -> encodeGeometryToString(geometry))
                .filter(encodedStr -> !TextUtils.isEmpty(encodedStr))
                .collect(Collectors.joining(";"));
    }


    /**
     * Encode the geometry object to string. The encoding format is specified by
     * {@link android.provider.Telephony.CellBroadcasts#GEOMETRIES}.
     * @param geometry the geometry object need to be encoded.
     * @return the encoded string.
     */
    @NonNull
    private static String encodeGeometryToString(@NonNull Geometry geometry) {
        StringBuilder sb = new StringBuilder();
        if (geometry instanceof Polygon) {
            sb.append(POLYGON_SYMBOL);
            for (LatLng latLng : ((Polygon) geometry).getVertices()) {
                sb.append("|");
                sb.append(latLng.lat);
                sb.append(",");
                sb.append(latLng.lng);
            }
        } else if (geometry instanceof Circle) {
            sb.append(CIRCLE_SYMBOL);
            Circle circle = (Circle) geometry;

            // Center
            sb.append("|");
            sb.append(circle.getCenter().lat);
            sb.append(",");
            sb.append(circle.getCenter().lng);

            // Radius
            sb.append("|");
            sb.append(circle.getRadius());
        } else {
            Log.e(TAG, "Unsupported geometry object " + geometry);
            return null;
        }
        return sb.toString();
    }

    /**
     * Parse {@link LatLng} from {@link String}. Latitude and longitude are separated by ",".
     * Example: "13.56,-55.447".
     *
     * @param str encoded lat/lng string.
     * @Return {@link LatLng} object.
     */
    @NonNull
    private static LatLng parseLatLngFromString(@NonNull String str) {
        String[] latLng = str.split("\\s*,\\s*");
        return new LatLng(Double.parseDouble(latLng[0]), Double.parseDouble(latLng[1]));
    }
}
