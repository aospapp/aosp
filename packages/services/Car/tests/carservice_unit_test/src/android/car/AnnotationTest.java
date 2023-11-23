/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.car;

import static com.google.common.truth.Truth.assertWithMessage;

import android.car.annotation.AddedIn;
import android.car.annotation.AddedInOrBefore;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public final class AnnotationTest {
    private static final String TAG = AnnotationTest.class.getSimpleName();

    /*
     *  TODO(b/192692829): Add logic to populate this list automatically.
     *  Currently this list is generate as follow:
     *    $ m -j  GenericCarApiBuilder
     *    $ GenericCarApiBuilder --classes-only
     */
    //
    private static final String[] CAR_API_CLASSES = new String[] {
            "android.car.VehicleAreaDoor",
            "android.car.CarTransactionException",
            "android.car.occupantawareness.DriverMonitoringDetection",
            "android.car.occupantawareness.OccupantAwarenessDetection",
            "android.car.occupantawareness.Point3D",
            "android.car.occupantawareness.SystemStatusEvent",
            "android.car.occupantawareness.GazeDetection",
            "android.car.occupantawareness.OccupantAwarenessManager",
            "android.car.occupantawareness.OccupantAwarenessManager$ChangeCallback",
            "android.car.VehicleOilLevel",
            "android.car.VehiclePropertyType",
            "android.car.projection.ProjectionStatus",
            "android.car.projection.ProjectionStatus$Builder",
            "android.car.projection.ProjectionStatus$MobileDevice",
            "android.car.projection.ProjectionStatus$MobileDevice$Builder",
            "android.car.projection.ProjectionOptions",
            "android.car.projection.ProjectionOptions$Builder",
            "android.car.evs.CarEvsManager",
            "android.car.evs.CarEvsManager$CarEvsStatusListener",
            "android.car.evs.CarEvsManager$CarEvsStreamCallback",
            "android.car.evs.CarEvsStatus",
            "android.car.evs.CarEvsBufferDescriptor",
            "android.car.media.CarMediaManager",
            "android.car.media.CarMediaManager$MediaSourceChangedListener",
            "android.car.media.CarAudioPatchHandle",
            "android.car.media.CarAudioManager",
            "android.car.media.CarAudioManager$CarVolumeCallback",
            "android.car.media.CarMediaIntents",
            "android.car.content.pm.CarAppBlockingPolicy",
            "android.car.content.pm.CarPackageManager",
            "android.car.content.pm.AppBlockingPackageInfo",
            "android.car.content.pm.CarAppBlockingPolicyService",
            "android.car.VehicleAreaSeat",
            "android.car.CarOccupantZoneManager",
            "android.car.CarOccupantZoneManager$OccupantZoneInfo",
            "android.car.CarOccupantZoneManager$OccupantZoneConfigChangeListener",
            "android.car.VehicleSeatOccupancyState",
            "android.car.VehicleGear",
            "android.car.EvConnectorType",
            "android.car.user.CarUserManager",
            "android.car.user.CarUserManager$UserLifecycleEvent",
            "android.car.user.CarUserManager$UserLifecycleListener",
            "android.car.user.CarUserManager$UserSwitchUiCallback",
            "android.car.user.ExperimentalCarUserManager",
            "android.car.user.UserStopResult",
            "android.car.user.UserCreationResult",
            "android.car.user.UserRemovalResult",
            "android.car.user.CommonResults",
            "android.car.user.OperationResult",
            "android.car.user.UserLifecycleEventFilter",
            "android.car.user.UserLifecycleEventFilter$Builder",
            "android.car.user.UserStartResult",
            "android.car.user.UserIdentificationAssociationResponse",
            "android.car.user.UserSwitchResult",
            "android.car.VehicleLightState",
            "android.car.CarLibLog",
            "android.car.VehicleHvacFanDirection",
            "android.car.test.CarLocationTestHelper",
            "android.car.test.CarTestManager",
            "android.car.VehicleAreaType",
            "android.car.CarBugreportManager",
            "android.car.CarBugreportManager$CarBugreportManagerCallback",
            "android.car.CarInfoManager",
            "android.car.settings.CarSettings",
            "android.car.settings.CarSettings$Global",
            "android.car.settings.CarSettings$Secure",
            "android.car.AoapService",
            "android.car.VehiclePropertyAccess",
            "android.car.VehicleIgnitionState",
            "android.car.CarProjectionManager",
            "android.car.CarProjectionManager$CarProjectionListener",
            "android.car.CarProjectionManager$ProjectionKeyEventHandler",
            "android.car.CarProjectionManager$ProjectionStatusListener",
            "android.car.CarProjectionManager$ProjectionAccessPointCallback",
            "android.car.admin.StartUserInBackgroundResult",
            "android.car.admin.CarDevicePolicyManager",
            "android.car.admin.RemoveUserResult",
            "android.car.admin.StopUserResult",
            "android.car.admin.CreateUserResult",
            "android.car.CarNotConnectedException",
            "android.car.storagemonitoring.IoStatsEntry",
            "android.car.storagemonitoring.IoStatsEntry$Metrics",
            "android.car.storagemonitoring.IoStats",
            "android.car.storagemonitoring.LifetimeWriteInfo",
            "android.car.storagemonitoring.CarStorageMonitoringManager",
            "android.car.storagemonitoring.CarStorageMonitoringManager$IoStatsListener",
            "android.car.storagemonitoring.UidIoRecord",
            "android.car.storagemonitoring.WearEstimateChange",
            "android.car.storagemonitoring.WearEstimate",
            "android.car.CarManagerBase",
            "android.car.VehicleAreaWindow",
            "android.car.navigation.CarNavigationInstrumentCluster",
            "android.car.navigation.CarNavigationStatusManager",
            "android.car.VehicleAreaMirror",
            "android.car.input.CustomInputEvent",
            "android.car.input.CarInputHandlingService",
            "android.car.input.CarInputHandlingService$InputFilter",
            "android.car.input.RotaryEvent",
            "android.car.input.CarInputManager",
            "android.car.input.CarInputManager$CarInputCaptureCallback",
            "android.car.VehicleLightSwitch",
            "android.car.drivingstate.CarUxRestrictionsManager",
            "android.car.drivingstate.CarUxRestrictionsManager$OnUxRestrictionsChangedListener",
            "android.car.drivingstate.CarDrivingStateManager",
            "android.car.drivingstate.CarDrivingStateManager$CarDrivingStateEventListener",
            "android.car.drivingstate.CarUxRestrictions",
            "android.car.drivingstate.CarUxRestrictions$Builder",
            "android.car.drivingstate.CarDrivingStateEvent",
            "android.car.drivingstate.CarUxRestrictionsConfiguration",
            "android.car.drivingstate.CarUxRestrictionsConfiguration$Builder",
            "android.car.drivingstate.CarUxRestrictionsConfiguration$Builder$SpeedRange",
            "android.car.drivingstate.CarUxRestrictionsConfiguration$DrivingStateRestrictions",
            "android.car.app.CarActivityManager",
            "android.car.hardware.CarVendorExtensionManager",
            "android.car.hardware.CarVendorExtensionManager$CarVendorExtensionCallback",
            "android.car.hardware.CarPropertyConfig",
            "android.car.hardware.CarPropertyConfig$AreaConfig",
            "android.car.hardware.CarPropertyConfig$Builder",
            "android.car.hardware.CarHvacFanDirection",
            "android.car.hardware.CarPropertyValue",
            "android.car.hardware.CarSensorConfig",
            "android.car.hardware.CarSensorEvent",
            "android.car.hardware.CarSensorEvent$EnvironmentData",
            "android.car.hardware.CarSensorEvent$IgnitionStateData",
            "android.car.hardware.CarSensorEvent$NightData",
            "android.car.hardware.CarSensorEvent$GearData",
            "android.car.hardware.CarSensorEvent$ParkingBrakeData",
            "android.car.hardware.CarSensorEvent$FuelLevelData",
            "android.car.hardware.CarSensorEvent$OdometerData",
            "android.car.hardware.CarSensorEvent$RpmData",
            "android.car.hardware.CarSensorEvent$CarSpeedData",
            "android.car.hardware.CarSensorEvent$CarWheelTickDistanceData",
            "android.car.hardware.CarSensorEvent$CarAbsActiveData",
            "android.car.hardware.CarSensorEvent$CarTractionControlActiveData",
            "android.car.hardware.CarSensorEvent$CarFuelDoorOpenData",
            "android.car.hardware.CarSensorEvent$CarEvBatteryLevelData",
            "android.car.hardware.CarSensorEvent$CarEvChargePortOpenData",
            "android.car.hardware.CarSensorEvent$CarEvChargePortConnectedData",
            "android.car.hardware.CarSensorEvent$CarEvBatteryChargeRateData",
            "android.car.hardware.CarSensorEvent$CarEngineOilLevelData",
            "android.car.hardware.CarSensorManager",
            "android.car.hardware.CarSensorManager$OnSensorChangedListener",
            "android.car.hardware.hvac.CarHvacManager",
            "android.car.hardware.hvac.CarHvacManager$CarHvacEventCallback",
            "android.car.hardware.cabin.CarCabinManager",
            "android.car.hardware.cabin.CarCabinManager$CarCabinEventCallback",
            "android.car.hardware.property.PropertyAccessDeniedSecurityException",
            "android.car.hardware.property.EvChargingConnectorType",
            "android.car.hardware.property.PropertyNotAvailableException",
            "android.car.hardware.property.VehicleVendorPermission",
            "android.car.hardware.property.VehicleElectronicTollCollectionCardStatus",
            "android.car.hardware.property.VehicleHalStatusCode",
            "android.car.hardware.property.PropertyNotAvailableAndRetryException",
            "android.car.hardware.property.VehicleElectronicTollCollectionCardType",
            "android.car.hardware.property.CarInternalErrorException",
            "android.car.hardware.property.CarPropertyEvent",
            "android.car.hardware.property.CarPropertyManager",
            "android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback",
            "android.car.hardware.power.CarPowerManager",
            "android.car.hardware.power.CarPowerManager$CompletablePowerStateChangeFuture",
            "android.car.hardware.power.CarPowerManager$CarPowerStateListener",
            "android.car.hardware.power.CarPowerManager$CarPowerStateListenerWithCompletion",
            "android.car.hardware.power.CarPowerManager$CarPowerPolicyListener",
            "android.car.hardware.power.PowerComponentUtil",
            "android.car.hardware.power.CarPowerPolicy",
            "android.car.hardware.power.CarPowerPolicyFilter",
            "android.car.hardware.power.CarPowerPolicyFilter$Builder",
            "android.car.CarFeatures",
            "android.car.Car",
            "android.car.Car$CarServiceLifecycleListener",
            "android.car.telemetry.CarTelemetryManager",
            "android.car.telemetry.CarTelemetryManager$AddMetricsConfigCallback",
            "android.car.telemetry.CarTelemetryManager$MetricsReportCallback",
            "android.car.telemetry.CarTelemetryManager$ReportReadyListener",
            "android.car.cluster.ClusterActivityState",
            "android.car.cluster.renderer.NavigationRenderer",
            "android.car.cluster.renderer.InstrumentClusterRenderer",
            "android.car.cluster.renderer.InstrumentClusterRenderingService",
            "android.car.cluster.CarInstrumentClusterManager",
            "android.car.cluster.CarInstrumentClusterManager$Callback",
            "android.car.cluster.ClusterHomeManager",
            "android.car.cluster.ClusterHomeManager$ClusterStateListener",
            "android.car.cluster.ClusterHomeManager$ClusterNavigationStateListener",
            "android.car.PortLocationType",
            "android.car.FuelType",
            "android.car.vms.VmsLayer",
            "android.car.vms.VmsOperationRecorder",
            "android.car.vms.VmsOperationRecorder$Writer",
            "android.car.vms.VmsAvailableLayers",
            "android.car.vms.VmsSubscriptionHelper",
            "android.car.vms.VmsClient",
            "android.car.vms.VmsLayerDependency",
            "android.car.vms.VmsRegistrationInfo",
            "android.car.vms.VmsSubscriptionState",
            "android.car.vms.VmsLayersOffering",
            "android.car.vms.VmsAssociatedLayer",
            "android.car.vms.VmsPublisherClientService",
            "android.car.vms.VmsSubscriberManager",
            "android.car.vms.VmsSubscriberManager$VmsSubscriberClientCallback",
            "android.car.vms.VmsProviderInfo",
            "android.car.vms.VmsClientManager",
            "android.car.vms.VmsClientManager$VmsClientCallback",
            "android.car.VehicleAreaWheel",
            "android.car.util.concurrent.AndroidFuture",
            "android.car.util.concurrent.AndroidAsyncFuture",
            "android.car.util.concurrent.AsyncFuture",
            "android.car.diagnostic.CarDiagnosticManager",
            "android.car.diagnostic.CarDiagnosticManager$OnDiagnosticEventListener",
            "android.car.diagnostic.FloatSensorIndex",
            "android.car.diagnostic.CarDiagnosticEvent",
            "android.car.diagnostic.CarDiagnosticEvent$Builder",
            "android.car.diagnostic.CarDiagnosticEvent$FuelSystemStatus",
            "android.car.diagnostic.CarDiagnosticEvent$SecondaryAirStatus",
            "android.car.diagnostic.CarDiagnosticEvent$FuelType",
            "android.car.diagnostic.CarDiagnosticEvent$IgnitionMonitor",
            "android.car.diagnostic.CarDiagnosticEvent$IgnitionMonitor$Decoder",
            "android.car.diagnostic.CarDiagnosticEvent$CommonIgnitionMonitors",
            "android.car.diagnostic.CarDiagnosticEvent$SparkIgnitionMonitors",
            "android.car.diagnostic.CarDiagnosticEvent$CompressionIgnitionMonitors",
            "android.car.diagnostic.IntegerSensorIndex",
            "android.car.VehiclePropertyIds",
            "android.car.VehicleUnit",
            "android.car.os.CarPerformanceManager",
            "android.car.os.CarPerformanceManager$CpuAvailabilityChangeListener",
            "android.car.os.CpuAvailabilityMonitoringConfig",
            "android.car.os.CpuAvailabilityMonitoringConfig$Builder",
            "android.car.os.CpuAvailabilityInfo",
            "android.car.os.CpuAvailabilityInfo$Builder",
            "android.car.watchdog.ResourceOveruseStats",
            "android.car.watchdog.ResourceOveruseStats$Builder",
            "android.car.watchdog.IoOveruseAlertThreshold",
            "android.car.watchdog.IoOveruseStats",
            "android.car.watchdog.IoOveruseStats$Builder",
            "android.car.watchdog.CarWatchdogManager",
            "android.car.watchdog.CarWatchdogManager$CarWatchdogClientCallback",
            "android.car.watchdog.CarWatchdogManager$ResourceOveruseListener",
            "android.car.watchdog.IoOveruseConfiguration",
            "android.car.watchdog.IoOveruseConfiguration$Builder",
            "android.car.watchdog.ResourceOveruseConfiguration",
            "android.car.watchdog.ResourceOveruseConfiguration$Builder",
            "android.car.watchdog.PerStateBytes",
            "android.car.watchdog.PackageKillableState",
            "android.car.CarAppFocusManager",
            "android.car.CarAppFocusManager$OnAppFocusChangedListener",
            "android.car.CarAppFocusManager$OnAppFocusOwnershipCallback"
            };

    @Test
    public void testClassAddedInAnnotation() throws Exception {
        List<String> errorsNoAnnotation = new ArrayList<>();
        List<String> errorsExtraAnnotation = new ArrayList<>();

        for (int i = 0; i < CAR_API_CLASSES.length; i++) {
            String className = CAR_API_CLASSES[i];
            Field[] fields = Class.forName(className).getDeclaredFields();
            for (int j = 0; j < fields.length; j++) {
                Field field = fields[j];
                boolean isAnnotated = containsAddedInAnnotation(field);
                boolean isPrivate = Modifier.isPrivate(field.getModifiers());

                if (isPrivate && isAnnotated) {
                    errorsExtraAnnotation.add(className + " FIELD: " + field.getName());
                }

                if (!isPrivate && !isAnnotated) {
                    errorsNoAnnotation.add(className + " FIELD: " + field.getName());
                }
            }

            Method[] methods = Class.forName(className).getDeclaredMethods();
            for (int j = 0; j < methods.length; j++) {
                Method method = methods[j];

                // These are some internal methods
                if (method.getName().contains("$")) continue;

                boolean isAnnotated = containsAddedInAnnotation(method);
                boolean isPrivate = Modifier.isPrivate(method.getModifiers());

                if (isPrivate && isAnnotated) {
                    errorsExtraAnnotation.add(className + " METHOD: " + method.getName());
                }

                if (!isPrivate && !isAnnotated) {
                    errorsNoAnnotation.add(className + " METHOD: " + method.getName());
                }
            }
        }

        StringBuilder errorFlatten = new StringBuilder();
        if (!errorsNoAnnotation.isEmpty()) {
            errorFlatten.append("Errors:\nNo AddedIn annotation found for-\n");
            for (int i = 0; i < errorsNoAnnotation.size(); i++) {
                errorFlatten.append(errorsNoAnnotation.get(i) + "\n");
            }
        }

        if (!errorsExtraAnnotation.isEmpty()) {
            errorFlatten.append("\nErrors:\nExtra AddedIn annotation found for-\n");
            for (int i = 0; i < errorsExtraAnnotation.size(); i++) {
                errorFlatten.append(errorsExtraAnnotation.get(i) + "\n");
            }
        }

        assertWithMessage(errorFlatten.toString())
                .that(errorsExtraAnnotation.size() + errorsNoAnnotation.size()).isEqualTo(0);
    }

    private boolean containsAddedInAnnotation(Field field) {
        return field.getAnnotation(AddedInOrBefore.class) != null
                || field.getAnnotation(AddedIn.class) != null;
    }

    private boolean containsAddedInAnnotation(Method method) {
        return method.getAnnotation(AddedInOrBefore.class) != null
                || method.getAnnotation(AddedIn.class) != null;
    }
}
