// BEGIN-INTERNAL
package org.robolectric.shadows;

import static com.google.common.truth.Truth.assertThat;

import android.icu.util.TimeZone;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.stream.Collectors;
import libcore.timezone.TimeZoneFinder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link ShadowTimeZoneFinderQ}. */
@RunWith(AndroidJUnit4.class)
public class ShadowTimeZoneFinderQTest {

  @Test
  @Config(minSdk = Build.VERSION_CODES.Q)
  public void lookupTimeZonesByCountry_shouldReturnExpectedTimeZones() {
    TimeZoneFinder timeZoneFinder = TimeZoneFinder.getInstance();
    assertThat(
            timeZoneFinder
                .lookupTimeZonesByCountry("us")
                .stream()
                .map(TimeZone::getID)
                .collect(Collectors.toList()))
        .containsAllOf("America/Los_Angeles", "America/New_York", "Pacific/Honolulu");
  }
}
// END-INTERNAL
