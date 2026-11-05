# Pocket Tunnel

Pocket Tunnel is a native Android app that is designed to run a Node.js backend on the phone and expose it through a Cloudflare Tunnel. Its Cloudflare setup is performed by the app through Cloudflare APIs. It does not use Workers, Termux, or a terminal app.

## In-app setup

1. Select the backend ZIP, choose a Node entry file, and set the local port.
2. Paste a scoped Cloudflare API token and account ID. The token is encrypted with Android Keystore.
3. Create a remotely managed Cloudflare Tunnel from the app.
4. Enter a full hostname (for example, `api.example.com`). The app finds the matching active zone and creates or updates its proxied CNAME to the tunnel.
5. Apply the hostname and local port to the tunnel's Cloudflare configuration. The app also writes a `config.yml` snapshot in its private storage.
6. Extract the ZIP and start the Node process and `cloudflared` from the app. An Android foreground service keeps them running and provides a Stop action in its notification.

## Managing multiple tunnels

Tunnels are managed from the home list, so creating a second tunnel never resets the first one.

From a tunnel's detail page, **Project tools** opens a browser for the extracted ZIP and a **Database manager**. The file browser navigates folders, previews common text files, and can edit them in place. The database manager can write SQLite, MySQL/MariaDB, PostgreSQL, or MongoDB connection settings into the project `.env`; it lists SQLite tables directly, or connects to a network database using the matching `mysql2`, `pg`, or `mongodb` driver in the ZIP's `node_modules`. Selecting a table or collection shows at most 100 rows. The app configures connections but does not install or run MySQL, PostgreSQL, or MongoDB server engines on Android. Database writes are not available in the app.

- Every tunnel profile (name, project, ZIP, port, entry file, hostname, tunnel ID, zone, flags) is stored as JSON in `tunnels` shared preferences and survives app restarts.
- Each tunnel gets its own encrypted connector token (`tunnel-token:<id>` in the Keystore-backed SecretStore).
- From the list, tapping a finished tunnel opens its detail page with all saved info, Start/Stop actions, Edit, and Delete. Delete works locally, or locally plus remotely through `DELETE /accounts/<id>/cfd_tunnel`.
- Setup can be paused at any step; the draft persists and shows as "Setup incomplete" in the list.
- The **Profile** page shows the saved Cloudflare account ID (copy on tap), the API token (hidden, tap to reveal), token verification, counts, and a delete-all-data action.
- Up to four tunnels can run at the same time: each one holds a runtime slot that starts a dedicated foreground service in its own Android process (`:runtime0` … `:runtime3`), containing its Node backend and `cloudflared` connector. The list shows a live Running/Stopped badge via runtime status broadcasts.
- A 502 from the public URL means the tunnel connector is healthy but nothing answered on the local port. The detail page has a **Test local server** button (direct `http://127.0.0.1:<port>` probe) and a **View backend output** button: backend stdout/stderr and crash output are captured to a per-tunnel log file through `dup2` in the embedded Node runtime. ZIP extraction also hints when the chosen entry file actually lives in a subfolder.
- Existing single-tunnel installs migrate automatically: the old setup preferences are converted into the first tunnel profile and the old encrypted token is remapped, so nothing needs to be set up again after updating.

The laptop's `cloudflared tunnel login` state is not needed or copied. Cloudflare still requires explicit authorization: create a narrowly scoped API token in your account with Cloudflare Tunnel Write, Zone Read, and DNS Write, then paste it into the app. The account and token are not sent to any service other than Cloudflare. Cloudflare's API creates the tunnel and token, manages its ingress configuration, and creates the DNS route.

## Runtime and project constraints

- The app embeds Node.js for Android and an Android `cloudflared` connector in its APK. It does not ask the phone owner to install Termux, Node, or cloudflared separately.
- The current embedded Node runtime is Node 18.20.4, arm64 only. The uploaded ZIP must contain the selected entry file and its dependencies under `node_modules`; this starter does not run `npm install` or compile native npm modules.
- The phone must stay powered, online, and allowed to run the foreground service. Android battery controls may still stop services.
- Concurrent runtimes share one embedded Node process per runtime slot; four simultaneous tunnels are supported. Starting more than four shows a UI hint to stop a tunnel first.
- The APK currently targets Android API 28 to permit launching its build bundled connector binary. This is a sideload/development build configuration and is not ready for Google Play distribution.

## Build an APK

The Android project uses Android Studio/Gradle, NDK, and CMake. CMake downloads the pinned Node.js mobile library during native build. The workflow in `.github/workflows/android-apk.yml` builds an Android `cloudflared` connector and assembles a debug APK artifact. Trigger it with GitHub Actions after placing this project in a repository.

This workspace does not have the Android SDK, JDK, NDK, or Gradle, so I cannot compile or run the APK on this host. The runtime integrations still need device testing before treating this as a release build.
