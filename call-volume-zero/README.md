# Call Volume Zero

Android won't let the volume buttons take the call (voice) stream below 1.
This app uses [Shizuku](https://shizuku.rikka.app/) to run
`media volume --stream 0 --set 0`, which sets it to a true 0.

## Install
1. This folder lives in the `call-volume-zero` branch; the "Build Call Volume Zero APK" workflow builds it.
2. Open the Actions tab, wait for "Build APK" to finish.
3. Download `CallVolumeZero.apk` from the Releases page (tag `call-volume-zero-latest`) and install it.
4. Start Shizuku, open the app, grant permission, tap "Set call volume to 0".

Do this before or during the call. Pressing the volume buttons later may raise it again.

## No-app alternative
`./mute-call-volume.sh` does the same over ADB from a PC.
