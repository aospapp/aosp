package org.robolectric.shadows;

import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTOR_STATUS_RUNNING;

import android.annotation.SystemApi;
import android.app.time.Capabilities;
import android.app.time.Capabilities.CapabilityState;
import android.app.time.ExternalTimeSuggestion;
import android.app.time.LocationTimeZoneAlgorithmStatus;
import android.app.time.TelephonyTimeZoneAlgorithmStatus;
import android.app.time.TimeManager;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.time.TimeZoneDetectorStatus;
import android.os.Build.VERSION_CODES;
import android.os.UserHandle;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Shadow for internal Android {@code TimeManager} class introduced in S. */
@Implements(value = TimeManager.class, minSdk = VERSION_CODES.S, isInAndroidSdk = false)
public class ShadowTimeManager {

  public static final String CONFIGURE_GEO_DETECTION_CAPABILITY =
      "configure_geo_detection_capability";

  private TimeZoneCapabilities timeZoneCapabilities =
      new TimeZoneCapabilities.Builder(UserHandle.CURRENT)
          .setConfigureAutoDetectionEnabledCapability(Capabilities.CAPABILITY_POSSESSED)
          .setUseLocationEnabled(true)
          .setConfigureGeoDetectionEnabledCapability(Capabilities.CAPABILITY_POSSESSED)
          .setSetManualTimeZoneCapability(Capabilities.CAPABILITY_POSSESSED)
          .build();

  private TimeZoneDetectorStatus detectorStatus =
          new TimeZoneDetectorStatus(
                  DETECTOR_STATUS_RUNNING,
                  new TelephonyTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING),
                  new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                          LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_READY, null,
                          LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_READY, null));

  private TimeZoneConfiguration timeZoneConfiguration;

  /**
   * Capabilites are predefined and not controlled by user, so they can't be changed via TimeManager
   * API.
   */
  public void setCapabilityState(String capability, @CapabilityState int value) {
    TimeZoneCapabilities.Builder builder = new TimeZoneCapabilities.Builder(timeZoneCapabilities);

    switch (capability) {
      case CONFIGURE_GEO_DETECTION_CAPABILITY:
        builder.setConfigureGeoDetectionEnabledCapability(value);
        break;
      default:
        throw new IllegalArgumentException("Unrecognized capability=" + capability);
    }

    this.timeZoneCapabilities = builder.build();
  }

  @Implementation
  @SystemApi
  protected TimeZoneCapabilitiesAndConfig getTimeZoneCapabilitiesAndConfig() {
    Objects.requireNonNull(timeZoneConfiguration, "timeZoneConfiguration was not set");

    return new TimeZoneCapabilitiesAndConfig(
            detectorStatus, timeZoneCapabilities, timeZoneConfiguration);
  }

  @Implementation
  @SystemApi
  protected boolean updateTimeZoneConfiguration(TimeZoneConfiguration configuration) {
    this.timeZoneConfiguration = configuration;
    return true;
  }

  @Implementation
  protected void addTimeZoneDetectorListener(
      Executor executor, TimeManager.TimeZoneDetectorListener listener) {}

  @Implementation
  protected void removeTimeZoneDetectorListener(TimeManager.TimeZoneDetectorListener listener) {}

  @Implementation
  protected void suggestExternalTime(ExternalTimeSuggestion timeSuggestion) {}
}
