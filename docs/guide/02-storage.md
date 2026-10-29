# Storage (`:store-sqlite` + `:app/data`)

Verified against HEAD `4ff37c0`. Last changes: `fed3b0e`, `14aa84c` (stability
wave), plus the `ProjectStore` collision fix in Run B.

Two implementations of the same `SessionStore` SPI (`:core/store`):

| | `:store-sqlite` | `:app/data/AndroidDatabase` |
|---|---|---|
| used by | `:cli`, `:server`, tests | the Android app |
| driver | JDBC (Xerial SQLite) | `android.database.sqlite` |
| files | 4 / 2,278 lines | part of `:app` |

They must stay behaviourally identical. If you change one, check the other.

## Schema and migrations

There is no `Schema.kt`. The DDL is inline: `SqliteSessionStore` (~line 993,
`MIGRATIONS`) and `SqliteSnapshotStore` (~line 319), and in the app,
`AndroidDatabase.createSchema` + `migrate`. Worth knowing before you go looking
for a schema file.

### The two migration mechanisms

**`:app` (`AndroidDatabase.migrate`, line 148)** — a `listOf(Migration(1) … Migration(7))`,
skipping any `version <= current`, each in its own transaction with
`PRAGMA user_version` bumped *inside* the transaction so the version commits
atomically with the DDL. Current tail: 5 `partsSessionIndex`, 6
`sessionsProjectColumn`, 7 `hotIndexes`.

**`:store-sqlite` (`SqliteSessionStore.migrate`, line 77)** — the same idea but the
durable version is read as `maxOf(MAX(version) FROM schema_version,
PRAGMA user_version)`. The `schema_version` table is kept for compatibility with
older databases; a host that recorded neither starts at 0 and replays the
idempotent migrations.

Rules earned the hard way:

- **Never `DROP` to migrate.** Add a column with a default, backfill, move on.
- Add an index in the same migration that adds the column, not later. Migration
  7 (`hotIndexes`) is exactly that: `idx_todos_session_ord`,
  `idx_parts_session`, `idx_sessions_project` and friends were missing.
  If you add a table with a `session_id` column, index it immediately — every
  read is session-scoped.
- A migration must tolerate an older-than-expected schema. Do not `throw` inside
  the migration loop: it aborts mid-list and leaves the database between
  versions. Filter with `mapNotNull` instead.

WAL mode: the two implementations differ here, and only the Android one is
hardened. `AndroidDatabase` reads back the `PRAGMA journal_mode` result and only
applies `synchronous=NORMAL` once WAL is confirmed — because the PRAGMA answers
with the mode *actually in effect*, which is the old mode when the switch was
refused (e.g. an open reader on another connection). Previously the result was
ignored, so a refused switch silently degraded to non-concurrent mode.

`:store-sqlite` (used by `:cli`/`:server`) still just executes
`PRAGMA journal_mode=WAL` without inspecting the answer (see
`SqliteSessionStore.kt:69`, `SqliteSnapshotStore.kt:33`). That is a known
asymmetry, not an intentional design difference: fixing it is a small, contained
improvement.

## Durability

- Writes are transactional. A partially written message must never be readable.
- `delete` runs **before** preferences are cleared, not after. The old order
  could leave a row pointing at deleted preferences — an orphan the UI then
  failed to render.
- `busy_timeout` is set; a concurrent read must retry rather than fail.

## Leases / single-writer

`:app/data/ProjectStore` guards against two screens writing the same project.
Run B fixed **name collisions**: two different projects could normalise to the
same key and clobber each other. Identity is the id, not the display name;
normalisation is only ever used for lookup, never as a primary key.

The `activeOrNull` accessor previously trusted a caller-supplied id without
validation and could return a record for a deleted session. It now validates.

## Snapshots and revert

`SnapshotStore` (SPI in `:core/agent`, impl here) records file state before an
edit so a run can be reverted. `AgentLoop` takes it as an optional constructor
arg; `null` disables the capability cleanly. Retention is bounded — see
`SessionRetentionTest`, which is the spec for "what gets deleted and when".

There is **no undo beyond a snapshot**. Do not delete snapshot rows on write
failure; a failed edit is exactly when the snapshot is needed.

## Reading with an offset

A read with an offset past the scan cap silently returned nothing (fixed). Any
new paginated read must distinguish "no rows" from "offset beyond the data".

## Testing

- JVM, authoritative: `./gradlew :store-sqlite:test`.
- Robolectric (CI only): `AndroidDatabaseTest`, `ProjectStoreTest`,
  `SessionRetentionTest` in `:app`.
- For a migration, write the test against a database created at the **old**
  version, then open it with the new code. A migration that only ever runs on a
  fresh schema is untested.
