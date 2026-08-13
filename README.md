# Notes System

Offline-first, multi-device note application.

- **Client** — Kotlin Multiplatform + Compose Multiplatform (Android, iOS, Desktop)
- **Server** — Kotlin + Spring Boot, REST, JWT
- **Database** — PostgreSQL

Full design and the reasoning behind it: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

---

## The idea in one paragraph

The local database is the source of truth for the UI. Every edit is written to
SQLite and rendered from a Flow, so nothing waits for the network — the app is
fully usable with the server switched off. A background engine reconciles with
the server afterwards, and when two devices have changed the same note it keeps
**both** versions rather than picking a winner. Losing a pin state is a shrug;
losing a paragraph is a bug report.

---

## Layout

```
notes-system/
├── client/     Kotlin Multiplatform app (12 modules)
├── server/     Spring Boot backend (7 modules)
├── docs/       architecture reference
└── docker-compose.yml
```

---

## Running it

### 1. Database

With Docker:

```bash
docker compose up -d postgres
```

Or against a local PostgreSQL:

```bash
psql -U postgres -h 127.0.0.1 \
  -c "CREATE ROLE notes WITH LOGIN PASSWORD 'notes';" \
  -c "CREATE DATABASE notes OWNER notes ENCODING 'UTF8';"
```

> On Windows, use `127.0.0.1` rather than `localhost`. `localhost` resolves to
> `::1` first, and a default PostgreSQL install rejects SCRAM auth over IPv6 —
> which surfaces as a "password authentication failed" that has nothing to do
> with the password.

### 2. Backend

```bash
cd server
./gradlew :app:bootRun
```

Flyway creates the schema on first run. API docs at
<http://localhost:8080/swagger-ui.html>.

### 3. Client

```bash
cd client
./gradlew :desktopApp:run           # Desktop
./gradlew :androidApp:installDebug  # Android
# iOS: open iosApp/iosApp.xcodeproj in Xcode
```

The Android emulator reaches the host at `10.0.2.2`, which the client already
defaults to. A physical device needs the host's LAN address passed to
`initKoin(context, baseUrl = "http://192.168.x.x:8080")`.

---

## Verifying it

```bash
# Backend: 85 unit tests, no database required
cd server && ./gradlew :common:test :domain:test

# Backend over HTTP, needs the server running
./scripts/verify-phase1.ps1     # 41 assertions: auth, sessions, rate limiting
./scripts/verify-phase2.ps1     # 61 assertions: notes, search, sync, conflicts

# Client: 88 tests. Most need nothing; the integration tests need the server.
cd client && ./gradlew jvmTest
```

The two scripts are the quickest way to see the system actually work — the
conflict section of `verify-phase2.ps1` makes two devices disagree and shows
both versions surviving.

---

## Packaging

```bash
cd client
./gradlew :desktopApp:packageDistributionForCurrentOS
```

Produces an `.msi`, `.dmg` or `.deb` under
`desktopApp/build/compose/binaries/main/`.

---

## Requirements

- JDK 17+ (Gradle provisions a JDK 21 toolchain)
- PostgreSQL 15+, or Docker
- Xcode 15+ on macOS for the iOS target
- Windows PowerShell 5.1+ for the verification scripts

---

## Known limitations

These are deliberate and documented rather than overlooked. The full list with
reasoning is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §14.

| | |
|---|---|
| **Desktop session storage is obfuscation, not encryption** | A plain JVM process has no OS keychain, so the key sits beside the data. It keeps tokens out of backups and casual browsing; it does not stop a local attacker. Mitigated by a 7-day desktop refresh-token lifetime instead of 60. |
| **All iOS code is unverified** | iOS cannot be compiled on a Windows host, so Keychain storage, NWPathMonitor, the native SQLite driver and the Darwin HTTP engine are written to the documented APIs but have never been built or run. |
| **No OS-level background sync** | Sync runs on app start, on reconnect, after edits, every 15 minutes while foregrounded, and on demand. WorkManager / BGTaskScheduler are not wired up. |
| **Plain-text notes only** | `content_type` is reserved in the schema, so Markdown or rich text is additive. |
| **Search is client-side** | Required for offline. A Postgres full-text endpoint exists for thin clients. |
| **Password-reset emails go to the server log** | `EmailSender` is a port with an SMTP adapter ready; the dev implementation prints the token. |
| **Rate limiting is per-instance** | In-memory. Horizontal scaling needs a shared backend; the interface would not change. |
