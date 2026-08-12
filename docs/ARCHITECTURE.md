# Notes System — Architecture Reference

Offline-first, multi-device note application.
Kotlin Multiplatform client (Android / iOS / Desktop) + Kotlin Spring Boot backend + PostgreSQL.

> This is the living contract for the implementation. When code and this document
> disagree, one of them is a bug — decide which and fix it.

---

## 1. Governing principles

1. **The local database is the single source of truth for the UI.**
   Every mutation writes locally and returns immediately. The UI observes a `Flow`
   from SQLite. Network latency is never on the interaction path.
2. **Never silently discard a user's writing.**
   Losing a pin state is a shrug; losing a paragraph is a bug report.
   Divergent edits become conflict copies, not overwrites.
3. **Client wall-clocks are untrusted.**
   Device clocks are wrong, skewed and user-settable. The server owns ordering
   authority via a monotonic sequence. Client timestamps are display-only.
4. **Domain layers have zero framework dependencies.**
   `:domain` (client) and `server/domain` must not compile against Ktor,
   SQLDelight, Compose or Spring. That is the enforcement mechanism for
   Clean Architecture, not a naming convention.

---

## 2. System topology

```
Compose Multiplatform UI  (Android phone/tablet · iPhone/iPad · Desktop)
        │  presentation (MVI) → domain (use cases) → data
        ▼
  Local SQLite (SQLDelight)  ←── SOURCE OF TRUTH ───┐
        ▲                                            │
        │  Sync Engine (push / pull / conflict)       │
        ▼                                            │
   Ktor Client ────── HTTPS/JSON ────────────────────┘
        │
   Spring Boot (Kotlin) — REST /api/v1
   api → domain (use cases, ports) → infrastructure
   Spring Security + JWT + rate limiting
        │
   PostgreSQL (Flyway-migrated)
```

---

## 3. Technology decisions

| Decision | Choice | Rationale |
|---|---|---|
| Local DB | **SQLDelight 2.x** | No KSP dependency ⇒ immune to Kotlin-version lock on a bleeding-edge toolchain (Kotlin 2.4.10). Full SQL control for sync queries. |
| Server persistence | **Spring Data JPA + Flyway** | Native `@Version` optimistic locking, fastest path. |
| Note ID | **UUID v7, client-generated** | Offline creation without a server round trip; time-ordered so B-tree indexes don't fragment. |
| Access token | JWT HS256, 15 min, stateless | No DB hit per request. |
| Refresh token | Opaque 256-bit random, SHA-256 hashed at rest, rotating, 60 d | Revocable and reuse-detectable. A JWT refresh token cannot be revoked — unacceptable for "logout". |
| Sync cursor | Per-user monotonic `change_seq` | Immune to clock skew and timestamp ties. |
| DI | Koin (runtime, `commonMain`-friendly) | |
| Navigation | AndroidX Navigation Compose (KMP), type-safe `@Serializable` routes | |
| Note content | Plain text in v1; `content_type` column reserved | Markdown/rich text becomes additive. |

---

## 4. Client module map

```
client/
├── shared/                 aggregator ONLY — App(), Koin startup, NavHost, iOS framework
├── core/
│   ├── common/             Result/AppError, DispatcherProvider, Logger, UuidV7,
│   │                       TimeProvider, ConnectivityObserver (expect/actual), Route defs
│   ├── designsystem/       theme, tokens, WindowSizeClass helpers, shared components
│   ├── database/           SQLDelight schema (.sq), DriverFactory (expect/actual), DAOs
│   ├── network/            Ktor client factory, auth plugin + refresh, DTOs, error mapping
│   └── datastore/          AppPreferences + SecureStorage (expect/actual)
├── domain/                 PURE KOTLIN — models, repository interfaces, use cases
├── data/                   repository implementations, mappers
├── sync/                   SyncEngine, scheduler, push/pull pipelines, ConflictResolver
├── feature/
│   ├── auth/               login · register · forgot-password
│   ├── notes/              list · editor · archived
│   └── settings/           settings · profile · change-password
├── androidApp/  desktopApp/  iosApp/
```

**Dependency rule (compile-time enforced):**

```
feature:*  →  domain, core:designsystem, core:common
data       →  domain, core:{database,network,datastore,common}
sync       →  domain, core:{database,network,common}
domain     →  kotlin stdlib + coroutines + datetime  ONLY
shared     →  everything (wiring only)
```

---

## 5. Backend module map

```
server/
├── app/              @SpringBootApplication, config, profiles, bootJar
├── common/           errors, IdGenerator, Clock, validation
├── domain/           PURE KOTLIN — models, ports (interfaces), use cases.
│                     Deliberately has NO spring-boot-starter dependency.
├── infrastructure/
│   ├── persistence/  JPA entities, Spring Data repos, port adapters, Flyway migrations
│   ├── security/     JwtTokenIssuer, BCrypt hasher, JwtAuthenticationFilter
│   └── mail/         LoggingEmailSender (dev) · SmtpEmailSender (prod)
└── api/              controllers, DTOs, mappers, GlobalExceptionHandler
```

Dependency direction: `api → domain ← infrastructure`, `app → all`.

---

## 6. PostgreSQL schema

See `server/infrastructure/persistence/src/main/resources/db/migration/` for the
authoritative DDL. Summary:

| Table | Purpose |
|---|---|
| `users` | account + `change_counter` (per-user sync sequence) + `tombstone_floor` |
| `devices` | stable per-install identity; conflict attribution, per-device sessions |
| `refresh_tokens` | hashed opaque tokens, rotation `family_id`, reuse detection |
| `password_reset_tokens` | hashed, single-use, 30 min TTL |
| `notes` | client-generated UUID PK, soft delete, `version`, `change_seq`, `content_hash` |

Key columns on `notes`:

- `version BIGINT` — optimistic concurrency; the push precondition.
- `change_seq BIGINT` — per-user monotonic cursor position. `UNIQUE (user_id, change_seq)`.
- `content_hash TEXT` — `SHA-256(title || 0x00 || content)`; drives the conflict fast-paths.
- `client_created_at` / `client_updated_at` — **display only**, never ordering authority.
- `conflict_of UUID` — back-link from a conflict copy to the original.

Future tables (additive, no migration of existing ones):
`labels`, `note_labels`, `folders`, `attachments`, `reminders`, `note_revisions`.

### Client-local extra columns

The local `note` table mirrors the server plus local-only bookkeeping:

| Column | Purpose |
|---|---|
| `sync_status` | `SYNCED / PENDING / SYNCING / FAILED / CONFLICT` |
| `local_revision` | bumped on every local edit — powers the compare-and-set guard |
| `base_version` | server `version` this copy descends from; sent as push precondition |
| `base_content_hash` | content hash at last successful sync; enables fast-forward merges |
| `sync_error`, `sync_attempts` | failure surface + backoff |

Plus a `sync_meta` singleton: `last_pull_cursor`, `last_successful_sync_at`, `device_id`.

---

## 7. The change-sequence guarantee

Naive cursors lose data. A transaction assigned seq 105 can commit *after* a reader
has already consumed seq 106 — that row is then never delivered to that device.

Every mutating write therefore runs as:

```sql
BEGIN;
  SELECT change_counter FROM users WHERE id = :userId FOR UPDATE;  -- serialises this user
  UPDATE users SET change_counter = change_counter + 1 WHERE id = :userId
    RETURNING change_counter INTO :seq;
  -- upsert the note with change_seq = :seq
COMMIT;
```

Because the counter is bumped under the `users` row lock, **sequence order equals
commit order for a given user**. A puller at cursor N can never skip a row.
Contention is scoped to one user's own concurrent writes — negligible in practice,
and correctness here is worth far more than throughput.

---

## 8. REST API contract

Base `/api/v1`. Authed endpoints require `Authorization: Bearer <access>` and `X-Device-Id`.

| Method | Path | Auth | OK | Notes |
|---|---|---|---|---|
| POST | `/auth/register` | — | 201 | |
| POST | `/auth/login` | — | 200 | |
| POST | `/auth/refresh` | — | 200 | rotates; reuse ⇒ 401 + family revoked |
| POST | `/auth/logout` | ✓ | 204 | this device |
| POST | `/auth/logout-all` | ✓ | 204 | |
| POST | `/auth/forgot-password` | — | 202 | **always** 202 — no user enumeration |
| POST | `/auth/reset-password` | — | 204 | revokes all sessions |
| POST | `/auth/change-password` | ✓ | 204 | revokes *other* sessions |
| GET/PATCH | `/users/me` | ✓ | 200 | |
| GET | `/notes` | ✓ | 200 | paginated |
| POST | `/notes` | ✓ | 201 | client supplies `id`; idempotent |
| GET | `/notes/{id}` | ✓ | 200 | |
| PUT | `/notes/{id}` | ✓ | 200 | `If-Match: <version>` ⇒ 409 on mismatch |
| DELETE | `/notes/{id}` | ✓ | 204 | soft delete |
| POST | `/notes/{id}/{restore,archive,unarchive,pin,unpin}` | ✓ | 200 | |
| GET | `/notes/search?q=` | ✓ | 200 | Postgres FTS |
| POST | `/sync/push` | ✓ | 200 | batch upload + conflict report |
| GET | `/sync/pull?cursor=&limit=` | ✓ | 200 | incremental download |
| GET | `/sync/status` | ✓ | 200 | serverCursor, serverTime, tombstoneFloor |

> The plain `/notes/*` endpoints exist for REST completeness and thin clients.
> **The KMP clients mutate exclusively through `/sync/push`** — one code path,
> one conflict story.

### Error envelope

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [{ "field": "email", "message": "must be a well-formed email address" }],
    "traceId": "b7c1...",
    "timestamp": "2026-08-12T10:05:02Z"
  }
}
```

Codes: `VALIDATION_ERROR` 400 · `UNAUTHENTICATED` 401 · `INVALID_CREDENTIALS` 401 ·
`TOKEN_EXPIRED` 401 · `TOKEN_REUSE_DETECTED` 401 · `FORBIDDEN` 403 · `NOT_FOUND` 404 ·
`EMAIL_ALREADY_EXISTS` 409 · `VERSION_CONFLICT` 409 · `PAYLOAD_TOO_LARGE` 413 ·
`RATE_LIMITED` 429 · `INTERNAL_ERROR` 500.

### Sync payloads

`POST /sync/push`

```jsonc
{
  "deviceId": "018f...",
  "changes": [{
    "id": "018f2c...",
    "baseVersion": 3,              // 0 ⇒ create
    "title": "Groceries",
    "content": "milk, eggs",
    "contentType": "PLAIN",
    "isPinned": false,
    "isArchived": false,
    "isDeleted": false,
    "clientCreatedAt": "2026-08-12T10:00:00Z",
    "clientUpdatedAt": "2026-08-12T10:05:00Z",
    // Content hash at the client's last successful sync. Strongly recommended:
    // without it the server cannot tell "I only toggled a flag, keep your text"
    // from "we both rewrote this", and must assume the latter — producing a
    // conflict copy where a clean merge was possible. Omitting it is safe but
    // noisy. See §11 tiers 3 and 4.
    "baseContentHash": "a3f1…"
  }]
}
```

```jsonc
// 200
{
  "serverTime": "2026-08-12T10:05:02Z",
  "serverCursor": 1043,
  "results": [
    { "id": "018f2c...", "status": "APPLIED", "version": 4, "changeSeq": 1043 },
    { "id": "018f7a...", "status": "CONFLICT",
      "resolution": "SERVER_WINS_COPY_CREATED",
      "server": { /* authoritative note */ },
      "conflictCopy": { /* new note the client must insert locally */ } },
    { "id": "018f9b...", "status": "REJECTED", "error": { "code": "NOTE_TOO_LARGE" } }
  ]
}
```

`GET /sync/pull?cursor=1002&limit=200`

```jsonc
{
  "serverTime": "2026-08-12T10:05:02Z",
  "nextCursor": 1043,
  "hasMore": false,
  "resyncRequired": false,   // true ⇒ cursor < tombstoneFloor: wipe + full pull
  "notes": [ /* full representations, tombstones included */ ]
}
```

---

## 9. Authentication flow

```
Register → validate → email unique → BCrypt(cost 12) → INSERT → token pair → 201
Login    → rate-limit (5/min per email+IP) → verify BCrypt (constant-time,
           dummy hash on unknown email to equalise timing) → upsert device → pair
Refresh  → look up SHA-256(token)
           ├─ missing / expired            → 401
           ├─ ALREADY REVOKED ⇒ token reuse → revoke entire family_id, 401
           └─ else → revoke, mint successor in same family, link replaced_by
```

**Transaction boundary on reuse detection.** The family revocation must commit in
its *own* transaction, separately from the exception that reports the breach.
Both in one transaction and the rollback silently undoes the revocation — the API
announces a compromise while leaving every token in the leaked family usable.
This was a real bug, caught only by the HTTP verification run: the unit test
passed because the in-memory `Transactor` fake did not roll back. The fake now
does (`RollingBackTransactor`).

**Every write path registers the calling device.** `refresh_tokens.device_id`
and `notes.last_modified_by` are both foreign keys into `devices`, so an
unrecognised device id (reinstall, restored backup, a second device that has only
ever synced and never logged in) would fail the insert with a constraint
violation surfaced as a 500. `DeviceResolver` upserts the device before any such
write, which also keeps `last_seen_at` current. Both instances of this bug were
found only by running the API, never by unit tests.

Client session restore on app start:

```
SecureStorage.readSession()
  ├─ none           → Auth graph
  ├─ access valid   → Main graph + kick off sync
  └─ access expired → silent refresh
                        ├─ ok   → Main graph
                        └─ fail → clear storage → Login
```

Ktor `Auth` (Bearer) plugin performs refresh, wrapped in a **single-flight `Mutex`**
so N concurrent 401s produce exactly one refresh call.

**The Auth plugin caches tokens in memory and never re-reads storage.** `loadTokens`
runs once per client. Every transition that changes who is signed in must call
`AuthSessionInvalidator.invalidate()`, or requests after sign-in go out with the
tokens cached at startup, and — worse — requests after sign-out keep the previous
user's access token and authenticate the *next* account as them. Found by
integration test, not by inspection.

**A device id identifies an installation, not a person.** One phone or laptop is
routinely used by several accounts in turn, so `DeviceRepository.upsert`
reassigns the row to whoever is currently signed in. An earlier version rejected
a change of owner with 403; that guarded nothing real (device ids are never
exposed to other users and are never an authorization input) while breaking the
ordinary case of a second account on the same device.

**Logout is offline-safe**: local session and data are cleared immediately; the
server revocation call is queued best-effort.

### Secure storage per platform

| Platform | Mechanism | Assessment |
|---|---|---|
| Android | `EncryptedSharedPreferences`, AES-256-GCM, key in AndroidKeystore | Strong |
| iOS | Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` | Strong |
| Desktop | AES-GCM file `~/.notes-system/session.enc`, 0600, machine-derived key | **Obfuscation only.** No OS keychain exists in pure JVM. A local attacker with the user's account can read it. Mitigated by a shorter refresh TTL (7 d) and server-side revocation. |

---

## 10. Sync engine

### Write path

```
User action → UseCase → NoteRepository.update()
   → local DB: apply change, sync_status = PENDING, local_revision += 1
   → return immediately (UI already recomposed from the DB Flow)
   → SyncScheduler.requestSync(debounce 3s)
```

### Cycle

```
SyncEngine.sync(trigger)          [Mutex — one cycle at a time]
 0. require authenticated && online
 1. global state = SYNCING
 2. PUSH  collect WHERE sync_status IN (PENDING, FAILED), chunks of 100
          POST /sync/push
            APPLIED  → compare-and-set clear (see below)
            CONFLICT → ConflictResolver, insert conflict copy if any
            REJECTED → sync_status = FAILED + sync_error
 3. PULL  loop GET /sync/pull?cursor=<last_pull_cursor>
            resyncRequired → push first, then wipe non-PENDING rows, cursor = 0, restart
            upsert remote note ONLY IF local sync_status == SYNCED
              (never clobber an unsynced local edit — it goes through conflict resolution)
            persist nextCursor after each page (crash-safe, resumable)
 4. write last_successful_sync_at; state = IDLE / ERROR
 5. on network failure → exponential backoff 2s → 4s → … → 5m, jittered
```

### The in-flight-edit guard

The single most important statement in the sync engine:

```sql
UPDATE note
   SET sync_status = 'SYNCED', base_version = :newVersion, base_content_hash = :hash
 WHERE id = :id AND local_revision = :snapshotTakenBeforeTheRequest;
```

If the user edited the note while its upload was in flight, `local_revision` has
moved and **zero rows update** — the note correctly stays `PENDING` and is pushed
again next cycle. Without this guard that edit is silently and permanently lost.

### Triggers

| Trigger | Behaviour |
|---|---|
| App start / foreground | immediate |
| Connectivity regained | immediate; also resets the backoff |
| Local mutation | debounced 3 s |
| Periodic | every 15 min while foregrounded |
| Manual (Settings) | immediate |
| Sign-in | immediate |

**The local-change trigger is derived, not announced.** A local edit marks its
note `PENDING`, so a rise in the pending count *is* the signal that something
needs pushing. `DefaultSyncManager` watches that count rather than having every
use case remember to call `requestSync` — which keeps the domain free of any
dependency on the sync engine and makes it impossible to add a write path that
forgets to trigger a sync.

**Sign-out pushes before it wipes.** `SignOutUseCase` attempts a final sync,
then stops the engine, then clears local notes, then ends the session. Wiping
first would destroy anything written since the last sync — and signing out is
exactly when the user stops being able to get it back. The push is best effort:
refusing to sign someone out because the network is down is worse.

### Status surface

| Status | Meaning | Shown |
|---|---|---|
| `SYNCED` | local == server | subtle check on the card |
| `SYNCING` | in flight | animated indicator |
| `PENDING` | queued local change | dot badge + "N pending" in Settings |
| `FAILED` | rejected / errored | amber badge, tap for reason + retry |
| `CONFLICT` | divergence resolved into a copy | banner + "Review" |

A global banner appears on the Notes screen only when offline or `FAILED > 0`.

---

## 11. Conflict resolution ladder

Detection: push carries `baseVersion`; if it differs from the stored `version`, conflict.
First matching tier wins.

As implemented in `ConflictResolver`, in evaluation order. Tier 4 of the original
plan (metadata-only) collapsed into tier 1: identical content *is* the
metadata-only case, so it is handled by one flag merge rather than two rungs.

| Rung | Condition | Outcome | `resolution` |
|---|---|---|---|
| T0 | both sides deleted | converge, nothing written | `BOTH_DELETED` |
| T1 | `clientHash == serverHash` | merge flags only; if the merge equals what the server already holds, nothing is written | `METADATA_MERGED` / `IDENTICAL` |
| T2 | one side deleted, the other edited | **edit wins, note is undeleted** | `EDIT_WINS_OVER_DELETE` |
| T2b | the deleting side is also the one that edited | delete stands | `DELETE_APPLIED` |
| T3 | `clientHash == baseHash` | server text wins, client's flags merge in | `SERVER_CONTENT_WINS` |
| T4 | `serverHash == baseHash` | client text wins cleanly | `CLIENT_WINS` |
| T5 | both changed content from a common base | **conflict copy**: server stays canonical, client's version becomes a new note titled `"<Title> (conflict copy)"` with `conflict_of` set. Both survive. | `CONFLICT_COPY_CREATED` |

Flag merge: `pinned` is a union (cheap, reversible, and losing a pin is worse
than gaining one); `archived` and `deleted` fall to whichever device reported the
later edit. That last rule leans on untrusted client clocks and is acknowledged
as weak — tolerable only because no text is at stake.

A missing `baseContentHash` is treated as "the client edited", which biases
towards a conflict copy (recoverable) rather than an overwrite (not).

Pure last-writer-wins on `updated_at` is the standard shortcut and is quietly
destructive: a device with a 4-minute-fast clock wins every race and the loser's
text is gone with no trace. Conflict copies are mildly annoying and never lossy.

**Tombstones** are retained 90 days, then purged by a scheduled job that raises
`users.tombstone_floor`. A device syncing with `cursor < tombstone_floor` receives
`resyncRequired: true` and re-downloads everything — its unsynced local edits are
pushed *first* so nothing is lost in the wipe.

---

## 12. Navigation

```
RootNavHost
├── Splash                            (session restore, no back)
├── AuthGraph
│   ├── Login ─▶ Register
│   │        ─▶ ForgotPassword ─▶ ResetSent
│   └── success ─▶ MainGraph (clear back stack)
└── MainGraph  (Scaffold: bottom bar / rail / drawer by size class)
    ├── NotesTab     NotesList ─▶ NoteEditor(id?)   // null ⇒ create
    ├── ArchivedTab  Archived  ─▶ NoteEditor(id)
    └── SettingsTab  Settings  ─▶ Profile · ChangePassword
                     logout ─▶ AuthGraph (clear back stack)
```

### Adaptive layout — one UI, three shapes

| Width class | Devices | Layout |
|---|---|---|
| Compact `<600dp` | phones | bottom bar · single pane · full-screen editor · 1 column |
| Medium `600–839` | small tablets, iPad portrait | navigation **rail** · single pane · 2 columns |
| Expanded `≥840` | tablets landscape, iPad Pro, desktop | rail (drawer ≥1240) · **list-detail two-pane**, editor beside the list · 3–4 columns |

Window size is measured once in `App()` with `BoxWithConstraints` and published
through `LocalWindowSize`, so every screen adapts from one measurement rather
than each querying the platform. The grid column count re-measures from the
*pane's* own width, not the window's, so the list inside a two-pane layout sizes
itself correctly instead of inheriting the window's verdict.

`NotesDestination` picks the shape: two panes when `supportsTwoPane`, otherwise
the editor is a separate full-screen destination. In two-pane mode the editor
stays mounted while the selection changes, so it is given an explicit
`viewModelKey` — without one, Koin returns the previous note's ViewModel and the
pane shows the wrong note's text.

---

## 13. MVI contract

```kotlin
interface UiState ; interface UiIntent ; interface UiEffect

abstract class MviViewModel<I : UiIntent, S : UiState, E : UiEffect>(initial: S) : ViewModel() {
    val state: StateFlow<S>
    val effect: Flow<E>          // Channel-backed: one-shot, must NOT replay on rotation
    fun onIntent(intent: I)
    protected abstract fun handleIntent(intent: I)
    protected fun setState(reducer: S.() -> S)
    protected fun sendEffect(effect: E)
}
```

State is a single immutable `data class` — **one** `StateFlow`, never a bag of
separate flows. Effects are one-shot (navigate, snackbar); anything durable
belongs in State.

Nine contracts: `Login`, `Register`, `ForgotPassword`, `NotesList`, `NoteEditor`,
`Archived`, `Settings`, `Profile`, `ChangePassword`.

---

## 14. Known limitations (deliberate)

| # | Limitation | Why |
|---|---|---|
| L1 | Desktop token storage is obfuscation, not encryption-at-rest with a protected key | No OS keychain in pure JVM. See §9. |
| L2 | No OS-level background sync in v1 | WorkManager / BGTaskScheduler are platform-specific; foreground + connectivity triggers cover the real use cases. Phase 8. |
| L3 | Plain-text notes only | `content_type` reserved; rich text is additive. |
| L4 | Search is client-side over the local DB | Required for offline. Server FTS endpoint exists for thin clients. |
| L5 | No email verification | `users.email_verified` reserved. |
| L6 | Password reset emails log to console in dev | Pluggable `EmailSender` port; SMTP adapter in Phase 8. |
| L7 | No `iosX64` target (Intel Mac simulators unsupported) | Not our choice: Compose Multiplatform 1.11.1 publishes only `ios_arm64` and `ios_simulator_arm64`. Adding `iosX64` fails dependency resolution. Apple-Silicon Macs only. |
| L8 | No `material-icons-extended` | The JetBrains artifact stopped at 1.7.3 and is not published for Compose MP 1.11.x. We use the core `Icons.*` set and hand-roll the few missing vectors (Archive, PushPin) in `:core:designsystem`. |
| L9 | Datasource URL pins `127.0.0.1`, not `localhost` | On Windows `localhost` resolves to `::1` first, and the local PostgreSQL install rejects SCRAM auth over IPv6 while accepting it over IPv4. The symptom is a "password authentication failed" that has nothing to do with the password. |
| L10 | Rate limiting is per-instance (in-memory) | Adequate for a single deployment. Horizontal scaling needs a shared backend (`bucket4j-redis`); the `RateLimiter` interface would not change. |
| L11 | **All `iosMain` code is unverified** | iOS targets cannot be compiled on a Windows host, so every `actual` in `iosMain` — Keychain storage, NWPathMonitor connectivity, the native SQLite driver, the Darwin HTTP engine — is written to the documented API but has never been built or run. Expect to fix compilation details on the first build from a Mac. Android and Desktop are verified by running them. |
| L12 | Desktop has no OS connectivity signal | A plain JVM process cannot observe network state without polling a hard-coded host, which is both a privacy smell and wrong behind a proxy. `NetworkMonitor` instead derives offline status from real request outcomes, so desktop notices it is offline one failed request later than mobile. |
| L13 | `Icons.Filled.*` is unavailable | Compose Multiplatform's material3 does not bundle `androidx.compose.material.icons` at all, and `material-icons-extended` stopped at 1.7.3. Every icon is hand-built from Material Symbols path data in `NoteIcons` — no dependency, no version skew. |
| L14 | One DataStore per file per process | DataStore registers its file for the process lifetime and refuses a second instance. Production has a single Koin graph so this never bites, but tests must start Koin once per class and fork a JVM per class (`forkEvery(1)`). |
