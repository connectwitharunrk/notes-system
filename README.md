# Notes System

Offline-first, multi-device note application.

- **Client** — Kotlin Multiplatform + Compose Multiplatform (Android phone/tablet, iPhone/iPad, Desktop)
- **Server** — Kotlin + Spring Boot, REST, JWT
- **Database** — PostgreSQL

Full design: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

---

## Layout

```
notes-system/
├── client/     Kotlin Multiplatform app
├── server/     Spring Boot backend
├── docs/       architecture reference
└── docker-compose.yml
```

## Running

### 1. Database

```bash
docker compose up -d postgres
# optional pgAdmin on http://localhost:5050
docker compose --profile tools up -d
```

### 2. Backend

```bash
cd server
./gradlew :app:bootRun
# http://localhost:8080/api/v1  ·  docs at /swagger-ui.html
```

### 3. Client

```bash
cd client
./gradlew :desktopApp:run                    # Desktop
./gradlew :androidApp:installDebug           # Android
# iOS: open iosApp/iosApp.xcodeproj in Xcode and run
```

## Requirements

- JDK 17+ (Gradle provisions a JDK 21 toolchain)
- Docker (Postgres, and Testcontainers for backend tests)
- Xcode 15+ on macOS for the iOS target
