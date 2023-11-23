# Rotary Playground: Test app for rotary controller

## Building
```
make RotaryPlayground
```

## Installing
```
adb install out/target/product/[hardware]/system/app/RotaryPlayground/RotaryPlayground.apk
```

## Once installed, launch Rotary Playground in the Launcher, or with this adb command:
```
adb shell am start -n com.android.car.rotaryplayground/com.android.car.rotaryplayground.RotaryActivity
```