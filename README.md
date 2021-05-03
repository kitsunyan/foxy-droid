# REV Robotics Software Manager for Android

Allows on-device updating of the Driver Hub Operating System and its built-in apps.

Based on the [Foxy Droid](https://github.com/kitsunyan/foxy-droid) F-Droid client. Installing apps
from the [F-Droid app repository](https://www.f-droid.org/en/packages/) can optionally be enabled.
These apps are provided by the F-Droid community and are not supported by REV Robotics.

## Building and Installing

You cannot replace the Software Manager app on a Driver Hub, so you will need to change the
`applicationId` in `build.gradle` from `com.revrobotics.softwaremanager` to something else.
Then you will be able to install your build of the Software Manager alongside the official one.
No functionality is exclusive to the official build; user-built versions can do everything the
official version can.

Specify your Android SDK path either using the `ANDROID_HOME` environment variable, or by filling out the `sdk.dir`
property in `local.properties`.

Signing can be done automatically using `keystore.properties` as follows:

```properties
store.file=/path/to/keystore
store.password=key-store-password
key.alias=key-alias
key.password=key-password
```

Run `./gradlew assembleRelease` to build the package, which can be installed using the Android package manager.

## License

This software is available under the terms of the GNU General Public License v3 or later.
Copyright © 2020-2021 kitsunyan, REV Robotics.
