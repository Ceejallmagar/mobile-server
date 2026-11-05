# Third party runtime notices

Pocket Tunnel bundles the Android ARM64 MariaDB server runtime from the Termux package repository.

- MariaDB server 13.0.2, GPL-2.0: https://github.com/MariaDB/server
- Termux package recipes and package metadata: https://github.com/termux/termux-packages/tree/master/packages/mariadb
- Bundled runtime package dependencies include Android support, BZip2, LLVM libc++, libcrypt, libedit/ncurses, LZ4, libxml2/libiconv, xz, OpenSSL, PCRE2, zlib, and zstd. Their respective upstream licenses are retained in the Termux package repository.

The redistributable ARM64 runtime archive is `app/src/main/assets/runtime/mariadb-arm64.zip`. The package recipe and sources are available from the links above. MariaDB is provided as a MySQL-protocol-compatible server; it is not Oracle's MySQL distribution.

Pocket Tunnel also bundles the Cloudflare Tunnel client, built for Android ARM64 from Cloudflare's cloudflared 2026.10.0 source release. Cloudflared is licensed under Apache-2.0: https://github.com/cloudflare/cloudflared/blob/2026.10.0/LICENSE
