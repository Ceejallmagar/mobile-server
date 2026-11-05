# Pocket Tunnel

Pocket Tunnel is a native Android app that can run a Node.js backend or host a built static frontend on the phone, then expose it through a Cloudflare Tunnel. Its Cloudflare setup is performed by the app through Cloudflare APIs. It does not use Workers, Termux, or a terminal app.

## In-app setup

1. Create a backend tunnel (select a ZIP, Node entry file, and port) or a frontend site (select a ZIP containing built web files and set its folder and port).
2. Paste a scoped Cloudflare API token and account ID. The token is encrypted with Android Keystore.
3. Create a remotely managed Cloudflare Tunnel from the app.
4. Enter a full hostname (for example, `api.example.com`). The app finds the matching active zone and creates or updates its proxied CNAME to the tunnel.
5. Apply the hostname and local port to the tunnel's Cloudflare configuration. The app also writes a `config.yml` snapshot in its private storage.
6. Extract the ZIP and start the backend or static frontend server and `cloudflared` from the app. An Android foreground service keeps them running and provides a Stop action in its notification.

For a frontend, build the app on your computer first (for example, `npm run build` in a Vite project). ZIP the generated output containing `index.html` (commonly `dist/` or `build/`), then choose **New frontend site**. Pocket Tunnel serves static assets and falls back to `index.html` for client-side routes. It does not run frontend development servers or server-rendered frameworks such as Next.js.

## Managing multiple tunnels

Tunnels are managed from the home list, so creating a second tunnel never resets the first one.

The home **Database** section starts and stops an on-device MariaDB server (MySQL protocol compatible), lists multiple named databases, creates local users, and connects an existing database to a selected project's `.env`. Database user credentials can be remembered encrypted by Android Keystore. Data lives in the app's private storage and the server binds only to `127.0.0.1:3306`; project Node code can connect locally using `mysql2` from its `node_modules`. From a tunnel's detail page, **Project tools** also opens a file browser and **Database manager**. The file browser previews and edits common text files. The database manager can configure MySQL/MariaDB, PostgreSQL, MongoDB, or local SQLite connections; run multi-statement MySQL schema scripts; and browse tables and up to 100 rows. Network databases require the matching `mysql2`, `pg`, or `mongodb` driver in the ZIP.

- Every tunnel profile (name, project type, ZIP, port, entry file or site folder, hostname, tunnel ID, zone, flags) is stored as JSON in `tunnels` shared preferences and survives app restarts.
- Each tunnel gets its own encrypted connector token (`tunnel-token:<id>` in the Keystore-backed SecretStore).
- From the list, tapping a finished tunnel opens its detail page with all saved info, Start/Stop actions, Edit, and Delete. Delete works locally, or locally plus remotely through `DELETE /accounts/<id>/cfd_tunnel`.
- Setup can be paused at any step; the draft persists and shows as "Setup incomplete" in the list.
- The **Profile** page shows the saved Cloudflare account ID (copy on tap), the API token (hidden, tap to reveal), token verification, counts, and a delete-all-data action.
- Up to four tunnels can run at the same time: each one holds a runtime slot that starts a dedicated foreground service in its own Android process (`:runtime0` … `:runtime3`), containing its Node backend and `cloudflared` connector. The list shows a live Running/Stopped badge via runtime status broadcasts.
- A 502 from the public URL means the tunnel connector is healthy but nothing answered on the local port. The detail page has a **Test local server** button (direct `http://127.0.0.1:<port>` probe) and a **View backend output** button: backend stdout/stderr and crash output are captured to a per-tunnel log file through `dup2` in the embedded Node runtime. ZIP extraction also hints when the chosen entry file actually lives in a subfolder.
- Existing single-tunnel installs migrate automatically: the old setup preferences are converted into the first tunnel profile and the old encrypted token is remapped, so nothing needs to be set up again after updating.

The laptop's `cloudflared tunnel login` state is not needed or copied. Cloudflare still requires explicit authorization: create a narrowly scoped API token in your account with Cloudflare Tunnel Write, Zone Read, and DNS Write, then paste it into the app. The account and token are not sent to any service other than Cloudflare. Cloudflare's API creates the tunnel and token, manages its ingress configuration, and creates the DNS route.

## Runtime and project constraints

- The app embeds Node.js, an Android `cloudflared` connector, and a MariaDB server runtime in its APK. It does not ask the phone owner to install Termux, Node, MariaDB, or cloudflared separately.
- The included MariaDB build is arm64-v8a only, uses the MySQL wire protocol, and is stored in Pocket Tunnel's private app data. Its default listener is `127.0.0.1:3306`, so it is available to backends running inside this app; other devices cannot connect directly. A project `.env` uses `127.0.0.1` because the backend shares the phone's network namespace.
- The MariaDB runtime is about 32 MB compressed and expands to roughly 200 MB on first start. Keep additional free storage for database files. Android may stop the server when battery or background restrictions apply.
- The current embedded Node runtime is Node 18.20.4, arm64 only. Backend ZIPs must contain the selected entry file and dependencies under `node_modules`; the app does not run `npm install` or compile native npm modules. Static frontend ZIPs only need the built site files and `index.html`.
- The phone must stay powered, online, and allowed to run the foreground service. Android battery controls may still stop services.
- Concurrent runtimes share one embedded Node process per runtime slot; four simultaneous tunnels are supported. Starting more than four shows a UI hint to stop a tunnel first.
- The APK currently targets Android API 28 to permit launching its build bundled connector binary. This is a sideload/development build configuration and is not ready for Google Play distribution.

## Build an APK

The Android project uses Android Studio/Gradle, NDK, and CMake. CMake downloads the pinned Node.js mobile library during native build. The workflow in `.github/workflows/android-apk.yml` builds an Android `cloudflared` connector and assembles a debug APK artifact. Trigger it with GitHub Actions after placing this project in a repository.

Build with Android Studio or run `./gradlew assembleDebug` with JDK 17, Android SDK, NDK, and CMake installed. The MariaDB runtime asset is built from pinned Termux ARM64 packages with `tools/build_mariadb_runtime.py`. The debug APK is a sideload build; installing it over a copy signed with a different key requires uninstalling that older copy first, which removes its private app data.
