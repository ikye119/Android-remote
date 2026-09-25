# My Android Remote — first version

An Android app and a Node relay for viewing and controlling your own Android phone from a browser on another network. The phone owner connects the app and approves **each screen-sharing session**. A foreground notification shows the current session and has a Stop sharing action. Disconnecting the browser ends the screen share.

## What works

- Live screen at roughly 4 frames per second (JPEG), optimized for modest bandwidth.
- Tap, drag to swipe, Back, Home. On-screen keyboard can be tapped.
- Phone on mobile data and laptop elsewhere, provided both can reach the same TLS-protected relay.
- Separate long random credentials for the phone and dashboard. The relay stores no screenshots.

This is a first version: no audio, file transfer, unattended screen capture, reconnect automation, multi-device management, recording, or rotation during an active session. Rotate before starting a session, or stop and start it again. Protected apps may intentionally appear black. A phone lock may require local unlock before control resumes. The Android app needs Android 10 or later.

## Relay on the laptop (local test)

Use Node 20+; there are no npm dependencies in the relay.

```bash
cd relay
export PHONE_TOKEN="$(openssl rand -hex 32)"
export VIEWER_TOKEN="$(openssl rand -hex 32)"
printf 'Phone token: %s\nViewer token: %s\n' "$PHONE_TOKEN" "$VIEWER_TOKEN"
RELAY_HOST=0.0.0.0 npm start
```

For a LAN test, browse to `http://LAPTOP_LAN_IP:8080` and enter `VIEWER_TOKEN`. On the phone, use `ws://LAPTOP_LAN_IP:8080/ws` and `PHONE_TOKEN`. Use only on a network you trust: local `ws://` traffic is not encrypted. When finished, stop the server with Ctrl+C.

## Access from anywhere

Run the relay on an internet-reachable machine with HTTPS/WSS provided by a reverse proxy. Use a domain you control, with a valid TLS certificate. Example Caddy configuration:

```text
remote.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Start Node with the two tokens above, leaving `RELAY_HOST` at its default `127.0.0.1` on the server. Point your domain to that server; open `https://remote.example.com` on the laptop and put `wss://remote.example.com/ws` in the phone app. Do not expose the relay's HTTP port or Android ADB port to the public internet. Keep your tokens private and rotate them if exposed. Hosting, domain, and TLS are not provided in this source package.

## Install the phone app

1. Open `android/` in Android Studio on your Linux machine. Install the suggested Android SDK Platform 35 and Gradle tooling if prompted, then **Build → Build APK(s)**.
2. Install `android/app/build/outputs/apk/debug/app-debug.apk` onto your phone, using Android Studio's Run action or `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` while USB debugging is authorized.
3. Enter the relay WebSocket address (`wss://.../ws`, or private LAN IP `ws://.../ws` for testing) and `PHONE_TOKEN`; tap **Connect to relay**.
4. Tap **Enable remote control** and enable **My Android Remote** in Accessibility settings. This grants taps and swipes only while the screen is actively shared.
5. Connect the dashboard in the browser with `VIEWER_TOKEN` first, then tap **Start screen sharing** on the phone and approve Android's capture dialog.
6. Stop sharing from the app or its persistent notification. For the next session, approve capture again.

The screen-sharing prompt is an Android requirement, even if the app was installed and granted permissions before. A normal app cannot promise always-on remote viewing after a reboot or from an unapproved new session. The phone needs internet and sufficient battery. Some Android versions limit how long a connection-only foreground service can stay up; reopen and reconnect as needed.

## Run relay tests

```bash
cd relay
npm test
```

This project was designed for an owner-controlled phone. Keep the application and its active-session notification visible to the device user.
