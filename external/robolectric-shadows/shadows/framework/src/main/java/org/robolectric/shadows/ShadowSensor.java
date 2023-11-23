package org.robolectric.shadows;

import android.hardware.Sensor;
import android.os.Build.VERSION_CODES;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

@Implements(Sensor.class)
public class ShadowSensor {

  @RealObject private Sensor realSensor;

  /** Constructs a {@link Sensor} with a given type. */
  public static Sensor newInstance(int type) {
    Sensor sensor = Shadow.newInstanceOf(Sensor.class);
    if (RuntimeEnvironment.getApiLevel() >= VERSION_CODES.M) {
      Shadow.directlyOn(sensor, Sensor.class, "setType", ReflectionHelpers.ClassParameter.from(int.class, type));
    } else {
      ReflectionHelpers.setField(Sensor.class, sensor, "mType", type);
    }
    return sensor;
  }

  /** Controls the return value of {@link Sensor#isWakeUpSensor()}. */
  public void setWakeUpFlag(boolean wakeup) {
    int wakeUpSensorFlag = getWakeUpSensorFlag();

    if(wakeup) {
      setMask(wakeUpSensorFlag);
    } else {
      clearMask(wakeUpSensorFlag);
    }
  }

  private void setMask(int mask) {
    int value = ReflectionHelpers.getField(realSensor, "mFlags");
    ReflectionHelpers.setField(realSensor, "mFlags", (value | mask));
  }

  private void clearMask(int mask) {
    int value = ReflectionHelpers.getField(realSensor, "mFlags");
    ReflectionHelpers.setField(realSensor, "mFlags", (value & ~mask));
  }

  private int getWakeUpSensorFlag() {
    return ReflectionHelpers.getStaticField(Sensor.class, "SENSOR_FLAG_WAKE_UP_SENSOR");
  }
}
