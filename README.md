# Pocket Tunnel

Pocket Tunnel is a native Android app that is designed to run a Node.js backend on the phone and expose it through a Cloudflare Tunnel. Its Cloudflare setup is performed by the app through Cloudflare APIs. It does not use Workers, Termux, or a terminal app.

## In-app setup

1. Select the backend ZIP, choose a Node entry file, and set the local port.
2. Paste a scoped Cloudflare API token and account ID. The token is encrypted with Android Keystore.
3. Create a remotely managed Cloudflare Tunnel from the app.
4. Enter a full hostname (for example, `api.example.com`). The app finds the matching active zone and creates or updates its proxied CNAME to the tunnel.
5. Apply the hostname and local port to the tunnel's Cloudflare configuration. The app also writes a `config.yml` snapshot in its private storage.
6. Extract the ZIP and start the Node process and `cloudflared` from the app. An Android foreground service keeps them running and provides a Stop action in its notification.

The laptop's `cloudflared tunnel login` state is not needed or copied. Cloudflare still requires explicit authorization: create a narrowly scoped API token in your account with Cloudflare Tunnel Write, Zone Read, and DNS Write, then paste it into the app. The account and token are not sent to any service other than Cloudflare. Cloudflare's API creates the tunnel and token, manages its ingress configuration, and creates the DNS route.

## Runtime and project constraints

- The app embeds Node.js for Android and an Android `cloudflared` connector in its APK. It does not ask the phone owner to install Termux, Node, or cloudflared separately.
- The current embedded Node runtime is Node 18.20.4, arm64 only. The uploaded ZIP must contain the selected entry file and its dependencies under `node_modules`; this starter does not run `npm install` or compile native npm modules.
- The phone must stay powered, online, and allowed to run the foreground service. Android battery controls may still stop services.
- The APK currently targets Android API 28 to permit launching its build bundled connector binary. This is a sideload/development build configuration and is not ready for Google Play distribution.

## Build an APK

The Android project uses Android Studio/Gradle, NDK, and CMake. CMake downloads the pinned Node.js mobile library during native build. The workflow in `.github/workflows/android-apk.yml` builds an Android `cloudflared` connector and assembles a debug APK artifact. Trigger it with GitHub Actions after placing this project in a repository.

This workspace does not have the Android SDK, JDK, NDK, or Gradle, so I cannot compile or run the APK on this host. The runtime integrations still need device testing before treating this as a release build.
