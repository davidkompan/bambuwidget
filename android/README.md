# Bambu Widget for Android

Plain Java app built without Gradle, using the Android tools packaged by Ubuntu / Debian.

```bash
sudo apt-get install aapt android-sdk-platform-23 dalvik-exchange apksigner zipalign openjdk-17-jdk
export BW_KS_PASS='your keystore password'     # creates release.keystore on the first build
./build.sh                                     # → build/BambuWidget.apk
```

Compiled against API 23 (minimum Android 6.0 / API 23), `targetSdkVersion` 34. Newer APIs (notification channels,
custom notification styles) are called through reflection.

`tools/gen_layouts.py` generates the themed widget / notification layouts (`res/layout*/{widget,row,col}_*.xml`)
and progress bar drawables; run it after editing the templates.

**Signing key.** Keep `release.keystore` and its password private and backed up. They are not part of the repository
(see `.gitignore`). Android installs an update over an existing app only if both are signed with the same key.
