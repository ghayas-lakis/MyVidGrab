# MyVidGrab for Android

A starter native Android app with an embedded WebView browser, basic detection of standard HTML5 video elements and direct MP4/WebM/OGV/MOV links, Android Download Manager integration, local private-session controls, and a GitHub Actions debug-APK workflow.

## Build on GitHub
1. Open the **Actions** tab.
2. Run **Build MyVidGrab APK**, or push a commit to `main`.
3. Open the completed workflow run and download the `MyVidGrab-debug-apk` artifact.
4. The artifact contains `app-debug.apk`.

## Build locally
Requires JDK 17 and Gradle 8.9:
```bash
gradle assembleDebug
```
APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Limitations
This is an initial starter, not a production-grade universal downloader. Detection is limited to standard HTML5 video/source elements and direct video links exposed by a page. Some sites block WebView, use cross-origin streams, segmented HLS/DASH, DRM, login-gated media, or other protections. The app does not bypass DRM, paywalls, authentication, or site restrictions.

Downloads use Android Download Manager; in-app progress and pause/resume controls are not implemented. Private mode clears local WebView history/cache/cookies when enabled, but it is not anonymity: websites and network providers may still observe connections. Downloads remain on the device.

VPN is not implemented. A real VPN requires a legitimate provider/server and Android VpnService integration.

Only download content you own or are authorized to save, and follow website terms and applicable law.
