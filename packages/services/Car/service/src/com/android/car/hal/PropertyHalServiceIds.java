/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.hal;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;

import static java.lang.Integer.toHexString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.VehicleHvacFanDirection;
import android.car.builtin.util.Slogf;
import android.car.hardware.property.VehicleVendorPermission;
import android.hardware.automotive.vehicle.AutomaticEmergencyBrakingState;
import android.hardware.automotive.vehicle.BlindSpotWarningState;
import android.hardware.automotive.vehicle.CruiseControlCommand;
import android.hardware.automotive.vehicle.CruiseControlState;
import android.hardware.automotive.vehicle.CruiseControlType;
import android.hardware.automotive.vehicle.ElectronicTollCollectionCardStatus;
import android.hardware.automotive.vehicle.ElectronicTollCollectionCardType;
import android.hardware.automotive.vehicle.EmergencyLaneKeepAssistState;
import android.hardware.automotive.vehicle.ErrorState;
import android.hardware.automotive.vehicle.EvChargeState;
import android.hardware.automotive.vehicle.EvConnectorType;
import android.hardware.automotive.vehicle.EvRegenerativeBrakingState;
import android.hardware.automotive.vehicle.EvStoppingMode;
import android.hardware.automotive.vehicle.ForwardCollisionWarningState;
import android.hardware.automotive.vehicle.FuelType;
import android.hardware.automotive.vehicle.GsrComplianceRequirementType;
import android.hardware.automotive.vehicle.HandsOnDetectionDriverState;
import android.hardware.automotive.vehicle.HandsOnDetectionWarning;
import android.hardware.automotive.vehicle.LaneCenteringAssistCommand;
import android.hardware.automotive.vehicle.LaneCenteringAssistState;
import android.hardware.automotive.vehicle.LaneDepartureWarningState;
import android.hardware.automotive.vehicle.LaneKeepAssistState;
import android.hardware.automotive.vehicle.LocationCharacterization;
import android.hardware.automotive.vehicle.PortLocationType;
import android.hardware.automotive.vehicle.TrailerState;
import android.hardware.automotive.vehicle.VehicleAreaSeat;
import android.hardware.automotive.vehicle.VehicleGear;
import android.hardware.automotive.vehicle.VehicleIgnitionState;
import android.hardware.automotive.vehicle.VehicleLightState;
import android.hardware.automotive.vehicle.VehicleLightSwitch;
import android.hardware.automotive.vehicle.VehicleOilLevel;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyGroup;
import android.hardware.automotive.vehicle.VehiclePropertyType;
import android.hardware.automotive.vehicle.VehicleSeatOccupancyState;
import android.hardware.automotive.vehicle.VehicleTurnSignal;
import android.hardware.automotive.vehicle.VehicleUnit;
import android.hardware.automotive.vehicle.WindshieldWipersState;
import android.hardware.automotive.vehicle.WindshieldWipersSwitch;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.car.CarLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helper class to define which AIDL HAL property IDs are used by PropertyHalService.  This class
 * binds the read and write permissions to the property ID.
 */
public class PropertyHalServiceIds {

    /**
     * Index key is an AIDL HAL property ID, and the value is readPermission, writePermission.
     * If the property can not be written (or read), set value as NULL.
     * Throw an IllegalArgumentException when try to write READ_ONLY properties or read WRITE_ONLY
     * properties.
     */
    private final SparseArray<Pair<String, String>> mHalPropIdToPermissions = new SparseArray<>();
    private final HashSet<Integer> mHalPropIdsForUnits = new HashSet<>();
    private final SparseIntArray mHalPropIdToValidBitFlag = new SparseIntArray();
    private static final String TAG = CarLog.tagFor(PropertyHalServiceIds.class);
    // Enums are used as return value in Vehicle HAL.
    private static final Set<Integer> FUEL_TYPE =
            new HashSet<>(getIntegersFromDataEnums(FuelType.class));
    private static final Set<Integer> EV_CONNECTOR_TYPE =
            new HashSet<>(getIntegersFromDataEnums(EvConnectorType.class));
    private static final Set<Integer> PORT_LOCATION =
            new HashSet<>(getIntegersFromDataEnums(PortLocationType.class));
    private static final Set<Integer> VEHICLE_SEAT =
            new HashSet<>(getIntegersFromDataEnums(VehicleAreaSeat.class));
    private static final Set<Integer> OIL_LEVEL =
            new HashSet<>(getIntegersFromDataEnums(VehicleOilLevel.class));
    private static final Set<Integer> VEHICLE_GEAR =
            new HashSet<>(getIntegersFromDataEnums(VehicleGear.class));
    private static final Set<Integer> TURN_SIGNAL =
            new HashSet<>(getIntegersFromDataEnums(VehicleTurnSignal.class));
    private static final Set<Integer> IGNITION_STATE =
            new HashSet<>(getIntegersFromDataEnums(VehicleIgnitionState.class));
    private static final Set<Integer> VEHICLE_UNITS =
            new HashSet<>(getIntegersFromDataEnums(VehicleUnit.class));
    private static final Set<Integer> SEAT_OCCUPANCY_STATE =
            new HashSet<>(getIntegersFromDataEnums(VehicleSeatOccupancyState.class));
    private static final Set<Integer> VEHICLE_LIGHT_STATE =
            new HashSet<>(getIntegersFromDataEnums(VehicleLightState.class));
    private static final Set<Integer> VEHICLE_LIGHT_SWITCH =
            new HashSet<>(getIntegersFromDataEnums(VehicleLightSwitch.class));
    private static final int HVAC_FAN_DIRECTION_COMBINATIONS =
            generateAllCombination(VehicleHvacFanDirection.class);
    private static final Set<Integer> ETC_CARD_TYPE =
            new HashSet<>(getIntegersFromDataEnums(ElectronicTollCollectionCardType.class));
    private static final Set<Integer> ETC_CARD_STATUS =
            new HashSet<>(getIntegersFromDataEnums(ElectronicTollCollectionCardStatus.class));
    private static final Set<Integer> EV_CHARGE_STATE =
            new HashSet<>(getIntegersFromDataEnums(EvChargeState.class));
    private static final Set<Integer> EV_REGENERATIVE_BREAKING_STATE =
            new HashSet<>(getIntegersFromDataEnums(EvRegenerativeBrakingState.class));
    private static final Set<Integer> EV_STOPPING_MODE =
            new HashSet<>(getIntegersFromDataEnums(EvStoppingMode.class));
    private static final Set<Integer> TRAILER_PRESENT =
            new HashSet<>(getIntegersFromDataEnums(TrailerState.class));
    private static final Set<Integer> GSR_COMP_TYPE =
            new HashSet<>(getIntegersFromDataEnums(GsrComplianceRequirementType.class));
    private static final int LOCATION_CHARACTERIZATION =
            generateAllCombination(LocationCharacterization.class);
    private static final Set<Integer> WINDSHIELD_WIPERS_STATE =
            new HashSet<>(getIntegersFromDataEnums(WindshieldWipersState.class));
    private static final Set<Integer> WINDSHIELD_WIPERS_SWITCH =
            new HashSet<>(getIntegersFromDataEnums(WindshieldWipersSwitch.class));
    private static final Set<Integer> EMERGENCY_LANE_KEEP_ASSIST_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                    EmergencyLaneKeepAssistState.class, ErrorState.class));
    private static final Set<Integer> CRUISE_CONTROL_TYPE =
            new HashSet<>(getIntegersFromDataEnums(
                    CruiseControlType.class, ErrorState.class));
    private static final Set<Integer> CRUISE_CONTROL_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                    CruiseControlState.class, ErrorState.class));
    private static final Set<Integer> CRUISE_CONTROL_COMMAND =
            new HashSet<>(getIntegersFromDataEnums(CruiseControlCommand.class));
    private static final Set<Integer> HANDS_ON_DETECTION_DRIVER_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                    HandsOnDetectionDriverState.class, ErrorState.class));
    private static final Set<Integer> HANDS_ON_DETECTION_WARNING =
            new HashSet<>(getIntegersFromDataEnums(
                    HandsOnDetectionWarning.class, ErrorState.class));
    private static final Set<Integer> AUTOMATIC_EMERGENCY_BRAKING_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                AutomaticEmergencyBrakingState.class, ErrorState.class));
    private static final Set<Integer> FORWARD_COLLISION_WARNING_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                ForwardCollisionWarningState.class, ErrorState.class));
    private static final Set<Integer> BLIND_SPOT_WARNING_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                BlindSpotWarningState.class, ErrorState.class));
    private static final Set<Integer> LANE_DEPARTURE_WARNING_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                LaneDepartureWarningState.class, ErrorState.class));
    private static final Set<Integer> LANE_KEEP_ASSIST_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                LaneKeepAssistState.class, ErrorState.class));
    private static final Set<Integer> LANE_CENTERING_ASSIST_COMMAND =
            new HashSet<>(getIntegersFromDataEnums(LaneCenteringAssistCommand.class));
    private static final Set<Integer> LANE_CENTERING_ASSIST_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                LaneCenteringAssistState.class, ErrorState.class));

    // TODO(b/264946993): Autogenerate HAL_PROP_ID_TO_ENUM_SET directly from AIDL definition.
    private static final SparseArray<Set<Integer>> HAL_PROP_ID_TO_ENUM_SET = new SparseArray<>();
    static {
        // HAL_PROP_ID_TO_ENUM_SET should contain all properties which have @data_enum in
        // VehicleProperty.aidl
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.INFO_FUEL_TYPE, FUEL_TYPE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.INFO_EV_CONNECTOR_TYPE, EV_CONNECTOR_TYPE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.INFO_FUEL_DOOR_LOCATION, PORT_LOCATION);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.INFO_DRIVER_SEAT, VEHICLE_SEAT);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.INFO_MULTI_EV_PORT_LOCATIONS, PORT_LOCATION);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.ENGINE_OIL_LEVEL, OIL_LEVEL);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.GEAR_SELECTION, VEHICLE_GEAR);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.CURRENT_GEAR, VEHICLE_GEAR);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.TURN_SIGNAL_STATE, TURN_SIGNAL);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.IGNITION_STATE, IGNITION_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.HVAC_TEMPERATURE_DISPLAY_UNITS, VEHICLE_UNITS);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.DISTANCE_DISPLAY_UNITS, VEHICLE_UNITS);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.FUEL_VOLUME_DISPLAY_UNITS, VEHICLE_UNITS);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.TIRE_PRESSURE_DISPLAY_UNITS, VEHICLE_UNITS);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.EV_BATTERY_DISPLAY_UNITS, VEHICLE_UNITS);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS, VEHICLE_UNITS);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.SEAT_OCCUPANCY, SEAT_OCCUPANCY_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.HIGH_BEAM_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.HEADLIGHTS_STATE, VEHICLE_LIGHT_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.FOG_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.FRONT_FOG_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.REAR_FOG_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.HAZARD_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.CABIN_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.READING_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.EV_CHARGE_STATE, EV_CHARGE_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.EV_REGENERATIVE_BRAKING_STATE,
                EV_REGENERATIVE_BREAKING_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.HEADLIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.HIGH_BEAM_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.FOG_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.FRONT_FOG_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.REAR_FOG_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.HAZARD_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.CABIN_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.READING_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.ELECTRONIC_TOLL_COLLECTION_CARD_TYPE,
                ETC_CARD_TYPE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.ELECTRONIC_TOLL_COLLECTION_CARD_STATUS,
                ETC_CARD_STATUS);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.TRAILER_PRESENT,
                TRAILER_PRESENT);
        HAL_PROP_ID_TO_ENUM_SET.put(
                VehicleProperty.GENERAL_SAFETY_REGULATION_COMPLIANCE_REQUIREMENT,
                GSR_COMP_TYPE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.EV_STOPPING_MODE,
                EV_STOPPING_MODE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.WINDSHIELD_WIPERS_STATE,
                WINDSHIELD_WIPERS_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.WINDSHIELD_WIPERS_SWITCH,
                WINDSHIELD_WIPERS_SWITCH);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.STEERING_WHEEL_LIGHTS_SWITCH,
                VEHICLE_LIGHT_SWITCH);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.STEERING_WHEEL_LIGHTS_STATE,
                VEHICLE_LIGHT_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.EMERGENCY_LANE_KEEP_ASSIST_STATE,
                EMERGENCY_LANE_KEEP_ASSIST_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.CRUISE_CONTROL_TYPE,
                CRUISE_CONTROL_TYPE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.CRUISE_CONTROL_STATE,
                CRUISE_CONTROL_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.CRUISE_CONTROL_COMMAND,
                CRUISE_CONTROL_COMMAND);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.HANDS_ON_DETECTION_DRIVER_STATE,
                HANDS_ON_DETECTION_DRIVER_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.HANDS_ON_DETECTION_WARNING,
                HANDS_ON_DETECTION_WARNING);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.AUTOMATIC_EMERGENCY_BRAKING_STATE,
                AUTOMATIC_EMERGENCY_BRAKING_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.FORWARD_COLLISION_WARNING_STATE,
                FORWARD_COLLISION_WARNING_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.BLIND_SPOT_WARNING_STATE,
                BLIND_SPOT_WARNING_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.LANE_DEPARTURE_WARNING_STATE,
                LANE_DEPARTURE_WARNING_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.LANE_KEEP_ASSIST_STATE,
                LANE_KEEP_ASSIST_STATE);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.LANE_CENTERING_ASSIST_COMMAND,
                LANE_CENTERING_ASSIST_COMMAND);
        HAL_PROP_ID_TO_ENUM_SET.put(VehicleProperty.LANE_CENTERING_ASSIST_STATE,
                LANE_CENTERING_ASSIST_STATE);
    }

    // default vendor permission
    private static final int PERMISSION_CAR_VENDOR_DEFAULT = 0x00000000;

    // permissions for the property related with window
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_WINDOW = 0X00000001;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_WINDOW = 0x00000002;
    // permissions for the property related with door
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_DOOR = 0x00000003;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_DOOR = 0x00000004;
    // permissions for the property related with seat
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_SEAT = 0x00000005;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_SEAT = 0x00000006;
    // permissions for the property related with mirror
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_MIRROR = 0x00000007;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_MIRROR = 0x00000008;

    // permissions for the property related with car's information
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_INFO = 0x00000009;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_INFO = 0x0000000A;
    // permissions for the property related with car's engine
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_ENGINE = 0x0000000B;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_ENGINE = 0x0000000C;
    // permissions for the property related with car's HVAC
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_HVAC = 0x0000000D;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_HVAC = 0x0000000E;
    // permissions for the property related with car's light
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_LIGHT = 0x0000000F;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_LIGHT = 0x00000010;

    // permissions reserved for other vendor permission
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_1 = 0x00010000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_1 = 0x00011000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_2 = 0x00020000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_2 = 0x00021000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_3 = 0x00030000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_3 = 0x00031000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_4 = 0x00040000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_4 = 0x00041000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_5 = 0x00050000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_5 = 0x00051000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_6 = 0x00060000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_6 = 0x00061000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_7 = 0x00070000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_7 = 0x00071000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_8 = 0x00080000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_8 = 0x00081000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_9 = 0x00090000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_9 = 0x00091000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_10 = 0x000A0000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_10 = 0x000A1000;
    // Not available for android
    private static final int PERMISSION_CAR_VENDOR_NOT_ACCESSIBLE = 0xF0000000;

    public PropertyHalServiceIds() {
        // Add propertyId and read/write permissions
        // Cabin Properties
        mHalPropIdToPermissions.put(VehicleProperty.DOOR_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_DOORS,
                Car.PERMISSION_CONTROL_CAR_DOORS));
        mHalPropIdToPermissions.put(VehicleProperty.DOOR_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_DOORS,
                Car.PERMISSION_CONTROL_CAR_DOORS));
        mHalPropIdToPermissions.put(VehicleProperty.DOOR_LOCK, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_DOORS,
                Car.PERMISSION_CONTROL_CAR_DOORS));
        mHalPropIdToPermissions.put(VehicleProperty.DOOR_CHILD_LOCK_ENABLED, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_DOORS,
                Car.PERMISSION_CONTROL_CAR_DOORS));
        mHalPropIdToPermissions.put(VehicleProperty.MIRROR_Z_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_MIRRORS,
                Car.PERMISSION_CONTROL_CAR_MIRRORS));
        mHalPropIdToPermissions.put(VehicleProperty.MIRROR_Z_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_MIRRORS,
                Car.PERMISSION_CONTROL_CAR_MIRRORS));
        mHalPropIdToPermissions.put(VehicleProperty.MIRROR_Y_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_MIRRORS,
                Car.PERMISSION_CONTROL_CAR_MIRRORS));
        mHalPropIdToPermissions.put(VehicleProperty.MIRROR_Y_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_MIRRORS,
                Car.PERMISSION_CONTROL_CAR_MIRRORS));
        mHalPropIdToPermissions.put(VehicleProperty.MIRROR_LOCK, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_MIRRORS,
                Car.PERMISSION_CONTROL_CAR_MIRRORS));
        mHalPropIdToPermissions.put(VehicleProperty.MIRROR_FOLD, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_MIRRORS,
                Car.PERMISSION_CONTROL_CAR_MIRRORS));
        mHalPropIdToPermissions.put(VehicleProperty.MIRROR_AUTO_FOLD_ENABLED, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_MIRRORS,
                Car.PERMISSION_CONTROL_CAR_MIRRORS));
        mHalPropIdToPermissions.put(VehicleProperty.MIRROR_AUTO_TILT_ENABLED, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_MIRRORS,
                Car.PERMISSION_CONTROL_CAR_MIRRORS));
        mHalPropIdToPermissions.put(VehicleProperty.GLOVE_BOX_DOOR_POS, new Pair<>(
                Car.PERMISSION_CONTROL_GLOVE_BOX,
                Car.PERMISSION_CONTROL_GLOVE_BOX));
        mHalPropIdToPermissions.put(VehicleProperty.GLOVE_BOX_LOCKED, new Pair<>(
                Car.PERMISSION_CONTROL_GLOVE_BOX,
                Car.PERMISSION_CONTROL_GLOVE_BOX));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_MEMORY_SELECT, new Pair<>(
                null,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_MEMORY_SET, new Pair<>(
                null,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_BELT_BUCKLED, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_BELT_HEIGHT_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_BELT_HEIGHT_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_FORE_AFT_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_FORE_AFT_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_BACKREST_ANGLE_1_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_BACKREST_ANGLE_1_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_BACKREST_ANGLE_2_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_BACKREST_ANGLE_2_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEIGHT_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEIGHT_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_DEPTH_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_DEPTH_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_TILT_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_TILT_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_FORE_AFT_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_FORE_AFT_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_SIDE_SUPPORT_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_SIDE_SUPPORT_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_HEIGHT_POS_V2, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_HEIGHT_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_ANGLE_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_ANGLE_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_FORE_AFT_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_FORE_AFT_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_FOOTWELL_LIGHTS_STATE, new Pair<>(
                Car.PERMISSION_READ_INTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_FOOTWELL_LIGHTS_SWITCH, new Pair<>(
                Car.PERMISSION_CONTROL_INTERIOR_LIGHTS,
                Car.PERMISSION_CONTROL_INTERIOR_LIGHTS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_EASY_ACCESS_ENABLED, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_AIRBAG_ENABLED, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_AIRBAGS,
                Car.PERMISSION_CONTROL_CAR_AIRBAGS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_CUSHION_SIDE_SUPPORT_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_CUSHION_SIDE_SUPPORT_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_VERTICAL_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_VERTICAL_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_WALK_IN_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                Car.PERMISSION_CONTROL_CAR_SEATS));
        mHalPropIdToPermissions.put(VehicleProperty.SEAT_OCCUPANCY, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_SEATS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.WINDOW_POS, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_WINDOWS,
                Car.PERMISSION_CONTROL_CAR_WINDOWS));
        mHalPropIdToPermissions.put(VehicleProperty.WINDOW_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_WINDOWS,
                Car.PERMISSION_CONTROL_CAR_WINDOWS));
        mHalPropIdToPermissions.put(VehicleProperty.WINDOW_LOCK, new Pair<>(
                Car.PERMISSION_CONTROL_CAR_WINDOWS,
                Car.PERMISSION_CONTROL_CAR_WINDOWS));
        mHalPropIdToPermissions.put(VehicleProperty.WINDSHIELD_WIPERS_PERIOD, new Pair<>(
                Car.PERMISSION_READ_WINDSHIELD_WIPERS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.WINDSHIELD_WIPERS_STATE, new Pair<>(
                Car.PERMISSION_READ_WINDSHIELD_WIPERS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.WINDSHIELD_WIPERS_SWITCH, new Pair<>(
                Car.PERMISSION_READ_WINDSHIELD_WIPERS,
                Car.PERMISSION_CONTROL_WINDSHIELD_WIPERS));
        mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_DEPTH_POS, new Pair<>(
                Car.PERMISSION_CONTROL_STEERING_WHEEL,
                Car.PERMISSION_CONTROL_STEERING_WHEEL));
        mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_DEPTH_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_STEERING_WHEEL,
                Car.PERMISSION_CONTROL_STEERING_WHEEL));
        mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_HEIGHT_POS, new Pair<>(
                Car.PERMISSION_CONTROL_STEERING_WHEEL,
                Car.PERMISSION_CONTROL_STEERING_WHEEL));
        mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_HEIGHT_MOVE, new Pair<>(
                Car.PERMISSION_CONTROL_STEERING_WHEEL,
                Car.PERMISSION_CONTROL_STEERING_WHEEL));
        mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_THEFT_LOCK_ENABLED, new Pair<>(
                Car.PERMISSION_CONTROL_STEERING_WHEEL,
                Car.PERMISSION_CONTROL_STEERING_WHEEL));
        mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_LOCKED, new Pair<>(
                Car.PERMISSION_CONTROL_STEERING_WHEEL,
                Car.PERMISSION_CONTROL_STEERING_WHEEL));
        mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_EASY_ACCESS_ENABLED, new Pair<>(
                Car.PERMISSION_CONTROL_STEERING_WHEEL,
                Car.PERMISSION_CONTROL_STEERING_WHEEL));

        // HVAC properties
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_FAN_SPEED, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_FAN_DIRECTION, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_TEMPERATURE_CURRENT, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_TEMPERATURE_SET, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_TEMPERATURE_VALUE_SUGGESTION, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_DEFROSTER, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_ELECTRIC_DEFROSTER_ON, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_AC_ON, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_MAX_AC_ON, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_MAX_DEFROST_ON, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_RECIRC_ON, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_DUAL_ON, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_AUTO_ON, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_SEAT_TEMPERATURE, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_SIDE_MIRROR_HEAT, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_STEERING_WHEEL_HEAT, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_TEMPERATURE_DISPLAY_UNITS, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_ACTUAL_FAN_SPEED_RPM, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_POWER_ON, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_FAN_DIRECTION_AVAILABLE, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_AUTO_RECIRC_ON, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));
        mHalPropIdToPermissions.put(VehicleProperty.HVAC_SEAT_VENTILATION, new Pair<>(
                    Car.PERMISSION_CONTROL_CAR_CLIMATE,
                    Car.PERMISSION_CONTROL_CAR_CLIMATE));

        // Info properties
        mHalPropIdToPermissions.put(VehicleProperty.INFO_VIN, new Pair<>(
                    Car.PERMISSION_IDENTIFICATION,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_MAKE, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_MODEL, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_MODEL_YEAR, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_FUEL_CAPACITY, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_FUEL_TYPE, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_EV_BATTERY_CAPACITY, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_EV_CONNECTOR_TYPE, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_FUEL_DOOR_LOCATION, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_MULTI_EV_PORT_LOCATIONS, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_EV_PORT_LOCATION, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_DRIVER_SEAT, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));
        mHalPropIdToPermissions.put(VehicleProperty.INFO_EXTERIOR_DIMENSIONS, new Pair<>(
                    Car.PERMISSION_CAR_INFO,
                    null));

        // Sensor properties
        mHalPropIdToPermissions.put(VehicleProperty.EMERGENCY_LANE_KEEP_ASSIST_ENABLED, new Pair<>(
                Car.PERMISSION_READ_ADAS_SETTINGS,
                Car.PERMISSION_CONTROL_ADAS_SETTINGS));
        mHalPropIdToPermissions.put(VehicleProperty.EMERGENCY_LANE_KEEP_ASSIST_STATE, new Pair<>(
                Car.PERMISSION_READ_ADAS_STATES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.CRUISE_CONTROL_ENABLED, new Pair<>(
                Car.PERMISSION_READ_ADAS_SETTINGS,
                Car.PERMISSION_CONTROL_ADAS_SETTINGS));
        mHalPropIdToPermissions.put(VehicleProperty.CRUISE_CONTROL_TYPE, new Pair<>(
                Car.PERMISSION_READ_ADAS_STATES,
                Car.PERMISSION_CONTROL_ADAS_STATES));
        mHalPropIdToPermissions.put(VehicleProperty.CRUISE_CONTROL_STATE, new Pair<>(
                Car.PERMISSION_READ_ADAS_STATES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.CRUISE_CONTROL_COMMAND, new Pair<>(
                null,
                Car.PERMISSION_CONTROL_ADAS_STATES));
        mHalPropIdToPermissions.put(VehicleProperty.CRUISE_CONTROL_TARGET_SPEED, new Pair<>(
                Car.PERMISSION_READ_ADAS_STATES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.ADAPTIVE_CRUISE_CONTROL_TARGET_TIME_GAP,
                new Pair<>(
                        Car.PERMISSION_READ_ADAS_STATES,
                        Car.PERMISSION_CONTROL_ADAS_STATES));
        mHalPropIdToPermissions.put(
                VehicleProperty.ADAPTIVE_CRUISE_CONTROL_LEAD_VEHICLE_MEASURED_DISTANCE, new Pair<>(
                        Car.PERMISSION_READ_ADAS_STATES,
                        null));
        mHalPropIdToPermissions.put(VehicleProperty.HANDS_ON_DETECTION_ENABLED, new Pair<>(
                Car.PERMISSION_READ_DRIVER_MONITORING_SETTINGS,
                Car.PERMISSION_CONTROL_DRIVER_MONITORING_SETTINGS));
        mHalPropIdToPermissions.put(VehicleProperty.HANDS_ON_DETECTION_DRIVER_STATE, new Pair<>(
                Car.PERMISSION_READ_DRIVER_MONITORING_STATES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.HANDS_ON_DETECTION_WARNING, new Pair<>(
                Car.PERMISSION_READ_DRIVER_MONITORING_STATES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.PERF_ODOMETER, new Pair<>(
                Car.PERMISSION_MILEAGE,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.PERF_VEHICLE_SPEED, new Pair<>(
                Car.PERMISSION_SPEED,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.PERF_VEHICLE_SPEED_DISPLAY, new Pair<>(
                Car.PERMISSION_SPEED,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.ENGINE_COOLANT_TEMP, new Pair<>(
                Car.PERMISSION_CAR_ENGINE_DETAILED,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.ENGINE_OIL_LEVEL, new Pair<>(
                Car.PERMISSION_CAR_ENGINE_DETAILED,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.ENGINE_OIL_TEMP, new Pair<>(
                Car.PERMISSION_CAR_ENGINE_DETAILED,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.ENGINE_RPM, new Pair<>(
                Car.PERMISSION_CAR_ENGINE_DETAILED,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.ENGINE_IDLE_AUTO_STOP_ENABLED, new Pair<>(
                Car.PERMISSION_CAR_ENGINE_DETAILED,
                Car.PERMISSION_CAR_ENGINE_DETAILED));
        mHalPropIdToPermissions.put(VehicleProperty.WHEEL_TICK, new Pair<>(
                Car.PERMISSION_SPEED,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.FUEL_LEVEL, new Pair<>(
                Car.PERMISSION_ENERGY,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.FUEL_DOOR_OPEN, new Pair<>(
                Car.PERMISSION_ENERGY_PORTS,
                Car.PERMISSION_CONTROL_ENERGY_PORTS));
        mHalPropIdToPermissions.put(VehicleProperty.EV_BATTERY_LEVEL, new Pair<>(
                Car.PERMISSION_ENERGY,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.EV_CURRENT_BATTERY_CAPACITY, new Pair<>(
                Car.PERMISSION_ENERGY,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_CURRENT_DRAW_LIMIT, new Pair<>(
                Car.PERMISSION_ENERGY, Car.PERMISSION_CONTROL_CAR_ENERGY));
        mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_PERCENT_LIMIT, new Pair<>(
                Car.PERMISSION_ENERGY, Car.PERMISSION_CONTROL_CAR_ENERGY));
        mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_STATE, new Pair<>(
                Car.PERMISSION_ENERGY, null));
        mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_SWITCH, new Pair<>(
                Car.PERMISSION_ENERGY, Car.PERMISSION_CONTROL_CAR_ENERGY));
        mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_TIME_REMAINING, new Pair<>(
                Car.PERMISSION_ENERGY, null));
        mHalPropIdToPermissions.put(VehicleProperty.EV_REGENERATIVE_BRAKING_STATE, new Pair<>(
                Car.PERMISSION_ENERGY, null));
        mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_PORT_OPEN, new Pair<>(
                Car.PERMISSION_ENERGY_PORTS,
                Car.PERMISSION_CONTROL_ENERGY_PORTS));
        mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_PORT_CONNECTED, new Pair<>(
                Car.PERMISSION_ENERGY_PORTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE,
                new Pair<>(Car.PERMISSION_ENERGY, null));
        mHalPropIdToPermissions.put(VehicleProperty.RANGE_REMAINING, new Pair<>(
                Car.PERMISSION_ENERGY,
                Car.PERMISSION_ADJUST_RANGE_REMAINING));
        mHalPropIdToPermissions.put(VehicleProperty.TIRE_PRESSURE, new Pair<>(
                Car.PERMISSION_TIRES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.CRITICALLY_LOW_TIRE_PRESSURE, new Pair<>(
                Car.PERMISSION_TIRES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.PERF_STEERING_ANGLE, new Pair<>(
                Car.PERMISSION_READ_STEERING_STATE,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.PERF_REAR_STEERING_ANGLE, new Pair<>(
                Car.PERMISSION_READ_STEERING_STATE,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.GEAR_SELECTION, new Pair<>(
                Car.PERMISSION_POWERTRAIN,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.CURRENT_GEAR, new Pair<>(
                Car.PERMISSION_POWERTRAIN,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.PARKING_BRAKE_ON, new Pair<>(
                Car.PERMISSION_POWERTRAIN,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.PARKING_BRAKE_AUTO_APPLY, new Pair<>(
                Car.PERMISSION_POWERTRAIN,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.EV_BRAKE_REGENERATION_LEVEL, new Pair<>(
                Car.PERMISSION_POWERTRAIN,
                Car.PERMISSION_CONTROL_POWERTRAIN));
        mHalPropIdToPermissions.put(VehicleProperty.EV_STOPPING_MODE, new Pair<>(
                Car.PERMISSION_POWERTRAIN,
                Car.PERMISSION_CONTROL_POWERTRAIN));
        mHalPropIdToPermissions.put(VehicleProperty.FUEL_LEVEL_LOW, new Pair<>(
                Car.PERMISSION_ENERGY,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.NIGHT_MODE, new Pair<>(
                Car.PERMISSION_EXTERIOR_ENVIRONMENT,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.TURN_SIGNAL_STATE, new Pair<>(
                Car.PERMISSION_EXTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.IGNITION_STATE, new Pair<>(
                Car.PERMISSION_POWERTRAIN,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.ABS_ACTIVE, new Pair<>(
                Car.PERMISSION_CAR_DYNAMICS_STATE,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.TRACTION_CONTROL_ACTIVE, new Pair<>(
                Car.PERMISSION_CAR_DYNAMICS_STATE,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.ENV_OUTSIDE_TEMPERATURE, new Pair<>(
                Car.PERMISSION_EXTERIOR_ENVIRONMENT,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.HEADLIGHTS_STATE, new Pair<>(
                Car.PERMISSION_EXTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.HIGH_BEAM_LIGHTS_STATE, new Pair<>(
                Car.PERMISSION_EXTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.FOG_LIGHTS_STATE, new Pair<>(
                Car.PERMISSION_EXTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.FRONT_FOG_LIGHTS_STATE, new Pair<>(
                Car.PERMISSION_EXTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.REAR_FOG_LIGHTS_STATE, new Pair<>(
                Car.PERMISSION_EXTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.HAZARD_LIGHTS_STATE, new Pair<>(
                Car.PERMISSION_EXTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.HEADLIGHTS_SWITCH, new Pair<>(
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS,
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS));
        mHalPropIdToPermissions.put(VehicleProperty.HIGH_BEAM_LIGHTS_SWITCH, new Pair<>(
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS,
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS));
        mHalPropIdToPermissions.put(VehicleProperty.FOG_LIGHTS_SWITCH, new Pair<>(
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS,
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS));
        mHalPropIdToPermissions.put(VehicleProperty.FRONT_FOG_LIGHTS_SWITCH, new Pair<>(
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS,
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS));
        mHalPropIdToPermissions.put(VehicleProperty.REAR_FOG_LIGHTS_SWITCH, new Pair<>(
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS,
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS));
        mHalPropIdToPermissions.put(VehicleProperty.HAZARD_LIGHTS_SWITCH, new Pair<>(
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS,
                Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS));
        mHalPropIdToPermissions.put(VehicleProperty.READING_LIGHTS_STATE, new Pair<>(
                Car.PERMISSION_READ_INTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.CABIN_LIGHTS_STATE, new Pair<>(
                Car.PERMISSION_READ_INTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_LIGHTS_STATE, new Pair<>(
                Car.PERMISSION_READ_INTERIOR_LIGHTS,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.READING_LIGHTS_SWITCH, new Pair<>(
                Car.PERMISSION_CONTROL_INTERIOR_LIGHTS,
                Car.PERMISSION_CONTROL_INTERIOR_LIGHTS));
        mHalPropIdToPermissions.put(VehicleProperty.CABIN_LIGHTS_SWITCH, new Pair<>(
                Car.PERMISSION_CONTROL_INTERIOR_LIGHTS,
                Car.PERMISSION_CONTROL_INTERIOR_LIGHTS));
        mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_LIGHTS_SWITCH, new Pair<>(
                Car.PERMISSION_CONTROL_INTERIOR_LIGHTS,
                Car.PERMISSION_CONTROL_INTERIOR_LIGHTS));
        mHalPropIdToPermissions.put(VehicleProperty.ANDROID_EPOCH_TIME, new Pair<>(
                null,
                Car.PERMISSION_CAR_EPOCH_TIME));
        mHalPropIdToPermissions.put(VehicleProperty.AUTOMATIC_EMERGENCY_BRAKING_ENABLED, new Pair<>(
                Car.PERMISSION_READ_ADAS_SETTINGS,
                Car.PERMISSION_CONTROL_ADAS_SETTINGS));
        mHalPropIdToPermissions.put(VehicleProperty.AUTOMATIC_EMERGENCY_BRAKING_STATE, new Pair<>(
                Car.PERMISSION_READ_ADAS_STATES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.FORWARD_COLLISION_WARNING_ENABLED, new Pair<>(
                Car.PERMISSION_READ_ADAS_SETTINGS,
                Car.PERMISSION_CONTROL_ADAS_SETTINGS));
        mHalPropIdToPermissions.put(VehicleProperty.FORWARD_COLLISION_WARNING_STATE, new Pair<>(
                Car.PERMISSION_READ_ADAS_STATES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.BLIND_SPOT_WARNING_ENABLED, new Pair<>(
                Car.PERMISSION_READ_ADAS_SETTINGS,
                Car.PERMISSION_CONTROL_ADAS_SETTINGS));
        mHalPropIdToPermissions.put(VehicleProperty.BLIND_SPOT_WARNING_STATE, new Pair<>(
                Car.PERMISSION_READ_ADAS_STATES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.LANE_DEPARTURE_WARNING_ENABLED, new Pair<>(
                Car.PERMISSION_READ_ADAS_SETTINGS,
                Car.PERMISSION_CONTROL_ADAS_SETTINGS));
        mHalPropIdToPermissions.put(VehicleProperty.LANE_DEPARTURE_WARNING_STATE, new Pair<>(
                Car.PERMISSION_READ_ADAS_STATES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.LANE_KEEP_ASSIST_ENABLED, new Pair<>(
                Car.PERMISSION_READ_ADAS_SETTINGS,
                Car.PERMISSION_CONTROL_ADAS_SETTINGS));
        mHalPropIdToPermissions.put(VehicleProperty.LANE_KEEP_ASSIST_STATE, new Pair<>(
                Car.PERMISSION_READ_ADAS_STATES,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.LANE_CENTERING_ASSIST_ENABLED, new Pair<>(
                Car.PERMISSION_READ_ADAS_SETTINGS,
                Car.PERMISSION_CONTROL_ADAS_SETTINGS));
        mHalPropIdToPermissions.put(VehicleProperty.LANE_CENTERING_ASSIST_COMMAND, new Pair<>(
                null,
                Car.PERMISSION_CONTROL_ADAS_STATES));
        mHalPropIdToPermissions.put(VehicleProperty.LANE_CENTERING_ASSIST_STATE, new Pair<>(
                Car.PERMISSION_READ_ADAS_STATES,
                null));

        // Display_Units
        mHalPropIdToPermissions.put(VehicleProperty.DISTANCE_DISPLAY_UNITS, new Pair<>(
                Car.PERMISSION_READ_DISPLAY_UNITS,
                Car.PERMISSION_CONTROL_DISPLAY_UNITS));
        mHalPropIdsForUnits.add(VehicleProperty.DISTANCE_DISPLAY_UNITS);
        mHalPropIdToPermissions.put(VehicleProperty.FUEL_VOLUME_DISPLAY_UNITS, new Pair<>(
                Car.PERMISSION_READ_DISPLAY_UNITS,
                Car.PERMISSION_CONTROL_DISPLAY_UNITS));
        mHalPropIdsForUnits.add(VehicleProperty.FUEL_VOLUME_DISPLAY_UNITS);
        mHalPropIdToPermissions.put(VehicleProperty.TIRE_PRESSURE_DISPLAY_UNITS, new Pair<>(
                Car.PERMISSION_READ_DISPLAY_UNITS,
                Car.PERMISSION_CONTROL_DISPLAY_UNITS));
        mHalPropIdsForUnits.add(VehicleProperty.TIRE_PRESSURE_DISPLAY_UNITS);
        mHalPropIdToPermissions.put(VehicleProperty.EV_BATTERY_DISPLAY_UNITS, new Pair<>(
                Car.PERMISSION_READ_DISPLAY_UNITS,
                Car.PERMISSION_CONTROL_DISPLAY_UNITS));
        mHalPropIdsForUnits.add(VehicleProperty.EV_BATTERY_DISPLAY_UNITS);
        mHalPropIdToPermissions.put(VehicleProperty.FUEL_CONSUMPTION_UNITS_DISTANCE_OVER_VOLUME,
                new Pair<>(Car.PERMISSION_READ_DISPLAY_UNITS,
                        Car.PERMISSION_CONTROL_DISPLAY_UNITS));
        mHalPropIdsForUnits.add(VehicleProperty.FUEL_CONSUMPTION_UNITS_DISTANCE_OVER_VOLUME);
        mHalPropIdToPermissions.put(VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS, new Pair<>(
                Car.PERMISSION_READ_DISPLAY_UNITS,
                Car.PERMISSION_CONTROL_DISPLAY_UNITS));
        mHalPropIdsForUnits.add(VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS);

        mHalPropIdToPermissions.put(VehicleProperty.SUPPORT_CUSTOMIZE_VENDOR_PERMISSION, new Pair<>(
                Car.PERMISSION_READ_CAR_VENDOR_PERMISSION_INFO,
                null));
        mHalPropIdToPermissions.put(VehicleProperty.ELECTRONIC_TOLL_COLLECTION_CARD_TYPE,
                new Pair<>(Car.PERMISSION_CAR_INFO, null));
        mHalPropIdToPermissions.put(VehicleProperty.ELECTRONIC_TOLL_COLLECTION_CARD_STATUS,
                new Pair<>(Car.PERMISSION_CAR_INFO, null));
        mHalPropIdToPermissions.put(VehicleProperty.VEHICLE_CURB_WEIGHT, new Pair<>(
                Car.PERMISSION_PRIVILEGED_CAR_INFO, null));
        mHalPropIdToPermissions.put(VehicleProperty.TRAILER_PRESENT, new Pair<>(
                Car.PERMISSION_PRIVILEGED_CAR_INFO, null));
        mHalPropIdToPermissions.put(
                VehicleProperty.GENERAL_SAFETY_REGULATION_COMPLIANCE_REQUIREMENT,
                new Pair<>(Car.PERMISSION_CAR_INFO, null));
        mHalPropIdToPermissions.put(VehicleProperty.LOCATION_CHARACTERIZATION, new Pair<>(
                ACCESS_FINE_LOCATION,
                null));

        // mPropToValidBitFlag contains all properties which return values are combinations of bits
        mHalPropIdToValidBitFlag.put(VehicleProperty.HVAC_FAN_DIRECTION_AVAILABLE,
                HVAC_FAN_DIRECTION_COMBINATIONS);
        mHalPropIdToValidBitFlag.put(VehicleProperty.HVAC_FAN_DIRECTION,
                HVAC_FAN_DIRECTION_COMBINATIONS);
        mHalPropIdToValidBitFlag.put(VehicleProperty.LOCATION_CHARACTERIZATION,
                LOCATION_CHARACTERIZATION);
    }

    /**
     * @param propId Property ID
     * @return Read permission string for given property ID. NULL if property ID does not exist or
     * the property is not available for reading.
     */
    @Nullable
    public String getReadPermission(int propId) {
        Pair<String, String> p = mHalPropIdToPermissions.get(propId);
        if (p != null) {
            // Property ID exists.  Return read permission.
            if (p.first == null) {
                Slogf.e(TAG, "propId is not available for reading : 0x" + toHexString(propId));
            }
            return p.first;
        } else if (isVendorProperty(propId)) {
            // if property is vendor property and do not have specific permission.
            return Car.PERMISSION_VENDOR_EXTENSION;
        } else {
            return null;
        }
    }

    /**
     * @param propId Property ID
     * @return Write permission string for given property ID. NULL if property ID does not exist or
     * the property is not writable.
     */
    @Nullable
    public String getWritePermission(int propId) {
        Pair<String, String> p = mHalPropIdToPermissions.get(propId);
        if (p != null) {
            // Property ID exists.  Return write permission.
            if (p.second == null) {
                Slogf.e(TAG, "propId is not writable : 0x" + toHexString(propId));
            }
            return p.second;
        } else if (isVendorProperty(propId)) {
            // if property is vendor property and do not have specific permission.
            return Car.PERMISSION_VENDOR_EXTENSION;
        } else {
            return null;
        }
    }

    private static boolean isVendorProperty(int propId) {
        return (propId & VehiclePropertyGroup.MASK) == VehiclePropertyGroup.VENDOR;
    }
    /**
     * Check if property ID is in the list of known IDs that PropertyHalService is interested it.
     */
    public boolean isSupportedProperty(int propId) {
        // Property is in the list of supported properties
        return mHalPropIdToPermissions.get(propId) != null || isVendorProperty(propId);
    }

    /**
     * Returns all possible supported enum values for the {@code halPropId}. If property does not
     * support an enum, then it returns {@code null}.
     */
    @Nullable
    public static Set<Integer> getAllPossibleSupportedEnumValues(int halPropId) {
        return HAL_PROP_ID_TO_ENUM_SET.contains(halPropId)
                ? Collections.unmodifiableSet(HAL_PROP_ID_TO_ENUM_SET.get(halPropId)) : null;
    }

    /**
     * Check if the property is one of display units properties.
     */
    public boolean isPropertyToChangeUnits(int propertyId) {
        return mHalPropIdsForUnits.contains(propertyId);
    }

    /**
     * Overrides the permission map for vendor properties
     *
     * @param configArray the configArray for
     * {@link VehicleProperty#SUPPORT_CUSTOMIZE_VENDOR_PERMISSION}
     */
    public void customizeVendorPermission(@NonNull int[] configArray) {
        if (configArray == null || configArray.length % 3 != 0) {
            throw new IllegalArgumentException(
                    "ConfigArray for SUPPORT_CUSTOMIZE_VENDOR_PERMISSION is wrong");
        }
        int index = 0;
        while (index < configArray.length) {
            int propId = configArray[index++];
            if (!isVendorProperty(propId)) {
                throw new IllegalArgumentException("Property Id: " + propId
                        + " is not in vendor range");
            }
            int readPermission = configArray[index++];
            int writePermission = configArray[index++];
            mHalPropIdToPermissions.put(propId, new Pair<>(
                    toPermissionString(readPermission, propId),
                    toPermissionString(writePermission, propId)));
        }

    }

    /**
     * Map VehicleVendorPermission enums in VHAL to android permissions.
     *
     * @return permission string, return null if vendor property is not available.
     */
    @Nullable
    private String toPermissionString(int permissionEnum, int propId) {
        switch (permissionEnum) {
            case PERMISSION_CAR_VENDOR_DEFAULT:
                return Car.PERMISSION_VENDOR_EXTENSION;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_WINDOW:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_WINDOW;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_WINDOW:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_WINDOW;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_DOOR:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_DOOR;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_DOOR:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_DOOR;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_SEAT:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_SEAT;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_SEAT:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_SEAT;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_MIRROR:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_MIRROR;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_MIRROR:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_MIRROR;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_INFO:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_INFO;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_INFO:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_INFO;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_ENGINE:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_ENGINE;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_ENGINE:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_ENGINE;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_HVAC:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_HVAC;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_HVAC:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_HVAC;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_LIGHT:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_LIGHT;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_LIGHT:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_LIGHT;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_1:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_1;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_1:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_1;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_2:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_2;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_2:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_2;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_3:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_3;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_3:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_3;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_4:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_4;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_4:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_4;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_5:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_5;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_5:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_5;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_6:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_6;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_6:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_6;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_7:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_7;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_7:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_7;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_8:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_8;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_8:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_8;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_9:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_9;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_9:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_9;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_10:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_10;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_10:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_10;
            case PERMISSION_CAR_VENDOR_NOT_ACCESSIBLE:
                return null;
            default:
                throw new IllegalArgumentException("permission Id: " + permissionEnum
                        + " for property:" + propId + " is invalid vendor permission Id");
        }
    }

    /**
     * Checks property value's format for all properties. Checks property value range if property
     * has @data_enum flag in types.hal.
     * @return true if property value's payload is valid.
     */
    public boolean checkPayload(HalPropValue propValue) {
        int propId = propValue.getPropId();
        // Mixed property uses config array to indicate the data format. Checked it when convert it
        // to CarPropertyValue.
        if ((propId & VehiclePropertyType.MASK) == VehiclePropertyType.MIXED) {
            return true;
        }
        if (!checkFormatForAllProperties(propValue)) {
            Slogf.e(TAG, "Property value" + propValue + "has an invalid data format");
            return false;
        }
        if (HAL_PROP_ID_TO_ENUM_SET.contains(propId)) {
            return checkDataEnum(propValue);
        }
        if (mHalPropIdToValidBitFlag.indexOfKey(propId) >= 0) {
            return checkValidBitFlag(propValue);
        }
        return true;
    }

    private boolean checkValidBitFlag(HalPropValue propValue) {
        int flagCombination = mHalPropIdToValidBitFlag.get(propValue.getPropId());
        for (int i = 0; i < propValue.getInt32ValuesSize(); i++) {
            int value = propValue.getInt32Value(i);
            if ((value & flagCombination) != value) {
                return false;
            }
        }
        return true;
    }

    private boolean checkFormatForAllProperties(HalPropValue propValue) {
        int propId = propValue.getPropId();
        //Records sum size of int32values, floatValue, int64Values, bytes, String
        int sizeOfAllValue = propValue.getInt32ValuesSize() + propValue.getFloatValuesSize()
                + propValue.getInt64ValuesSize() + propValue.getByteValuesSize()
                + propValue.getStringValue().length();
        if (sizeOfAllValue == 0) {
            Slogf.e(TAG, "Property value is empty: " + propValue);
            return false;
        }
        switch (propId & VehiclePropertyType.MASK) {
            case VehiclePropertyType.BOOLEAN:
            case VehiclePropertyType.INT32:
                return sizeOfAllValue == 1 && propValue.getInt32ValuesSize() == 1;
            case VehiclePropertyType.FLOAT:
                return sizeOfAllValue == 1 && propValue.getFloatValuesSize() == 1;
            case VehiclePropertyType.INT64:
                return sizeOfAllValue == 1 && propValue.getInt64ValuesSize() == 1;
            case VehiclePropertyType.FLOAT_VEC:
                return sizeOfAllValue == propValue.getFloatValuesSize();
            case VehiclePropertyType.INT64_VEC:
                return sizeOfAllValue == propValue.getInt64ValuesSize();
            case VehiclePropertyType.INT32_VEC:
                return sizeOfAllValue == propValue.getInt32ValuesSize();
            case VehiclePropertyType.BYTES:
                return sizeOfAllValue == propValue.getByteValuesSize();
            case VehiclePropertyType.STRING:
                return sizeOfAllValue == propValue.getStringValue().length();
            default:
                throw new IllegalArgumentException("Unexpected property type for propId: "
                        + Integer.toHexString(propId));
        }
    }
    private boolean checkDataEnum(HalPropValue propValue) {
        int propId = propValue.getPropId();
        Set<Integer> validValue = HAL_PROP_ID_TO_ENUM_SET.get(propId);
        for (int i = 0; i < propValue.getInt32ValuesSize(); i++) {
            if (!validValue.contains(propValue.getInt32Value(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> getIntegersFromDataEnums(Class... clazz) {
        List<Integer> integerList = new ArrayList<>(5);
        for (Class c: clazz) {
            Field[] fields = c.getDeclaredFields();
            for (Field f : fields) {
                if (f.getType() == int.class) {
                    try {
                        integerList.add(f.getInt(c));
                    } catch (IllegalAccessException | RuntimeException e) {
                        Slogf.w(TAG, "Failed to get value");
                    }
                }
            }
        }
        return integerList;
    }

    // Generate all combinations at once
    private static int generateAllCombination(Class clazz) {
        List<Integer> allBits = getIntegersFromDataEnums(clazz);
        int combination = allBits.get(0);
        for (int i = 1; i < allBits.size(); i++) {
            combination |= allBits.get(i);
        }
        return combination;
    }
}
