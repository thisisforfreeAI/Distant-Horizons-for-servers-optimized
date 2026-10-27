# DHSO changelog

Changes on top of unmodified Distant Horizons 3.3.3 (see `UPSTREAM.md`).
All changed code is marked with a `DHSO:` comment.

## Step 2: stability fixes

### Chunky integration ("Chunky is not loaded")
- `AbstractChunkyAccessor`: DH tried to bind to Chunky on the first chunk load,
  which can happen before Chunky has started, and marked the bind as done
  *before* trying. One early `IllegalStateException` meant DH never learned when
  Chunky was running. The bind is now only marked done on success, retried at
  most every 5 seconds, and a failure never propagates into the chunk load that
  triggered it. Unexpected errors are logged once (with stack trace) and not retried.

### Shutdown
- `AbstractDhServerWorld.close()`: the server thread waited for every
  dimension's DH close with no time limit, during `ServerStoppingEvent`, which
  runs before Minecraft's final world save. The wait is now bounded
  (default 30 s, JVM arg `-Ddhso.shutdownTimeoutSeconds=N`). On timeout DHSO
  logs which dimensions are stuck plus a stack dump of all `DH-` threads, and
  lets the server continue to its world save. Close threads are daemon threads
  and are named `DH-Level Shutdown [<dimension>]`.
- `ServerApi.serverUnloadEvent()`: every level was closed twice
  (`dhWorld.close()` and again in `SharedApi.setDhWorld(null)`); now once.
  Logs DH shutdown duration and any non-daemon DH threads still alive.
- New shutdown flag (`DhsoShutdown`): set when the server starts stopping;
  world generation and update propagation stop picking up new work.
- `PriorityTaskPicker.shutdownNow()`: one shared 5 s deadline for all
  executors (was up to 5 s per stuck executor); logs which executor was
  interrupted and its queue size.
- `AbstractDhRepo.close()`: waits once (max 1 s) for in-flight queries per
  database, instead of up to 1 s per open connection (one per thread that used it).
- `DataMigratorV1`: `close()` never stopped a running V1 migration, and the
  migration ran on a non-daemon thread that could keep the JVM alive after the
  server stopped. `close()` now stops it between batches, and the thread is a daemon.

### Tests
- `DhsoChunkyAccessorTest`, `DhsoShutdownTest` (6 tests, JUnit 4).

## Step 3a: storage budgets, eviction, adaptive throttling, /dhso

### New: `config/DistantHorizonsServerOptimized.toml`
Created with commented defaults on first start (`DhsoConfig`). Values out of
range are clamped and contradictions (e.g. target above start) are fixed, with
a log warning for each. Separate from DH's own config so no keys conflict.

### Storage budgets and eviction (`DhsoStorageManager`, `DhsoLodEvictor`)
- Background daemon thread `DH-DHSO Storage`; never touches the server thread.
- Every `checkIntervalSeconds` measures each dimension's *used* bytes
  (`(page_count - freelist_count) * page_size`), file size, WAL size and free disk.
- Over `cleanupStartPercent` of the dimension or global budget, it evicts down
  to `cleanupTargetPercent`, largest database first for the global budget.
- Only detail levels `<= maxEvictableDetailLevel` (default 1: full and half
  detail) are evicted; coarser levels are kept so distant terrain stays visible.
  DH merges each child into its parent incrementally, so a missing child does
  not erase anything from its parent.
- Sections with pending `ApplyToParent` / `ApplyToChildren` / `Regenerate`
  work are never evicted (checked at selection and again in the DELETE).
- Order: farthest from every online player and every player's last position
  this session first; nothing within `protectedRadiusBlocks` is touched.
  With no players seen, distance is measured from the world origin.
- Matching `ChunkHash` rows are deleted in the same transaction so DH rebuilds
  those sections when their chunks load again.
- One transaction per batch (`batchSize`), 50 ms pause between batches,
  passive WAL checkpoint afterwards, at most 20 s per automatic check.
- Deleting frees pages inside the file for new LODs; it does not shrink the file.

### Adaptive generation throttle (`DhsoGenerationController`)
- States NORMAL / THROTTLED (half the world gen threads) / CRITICAL (paused) / SHUTDOWN.
- Inputs: average MSPT, free disk below `minimumFreeDiskGB`, still over budget
  after eviction, `/dhso pause`, server stopping.
- Hysteresis: goes back to NORMAL only below `msptRecoverThreshold`.
- Hooked into `ThreadPoolUtil.worldGenThreadsCanRun()`, which also gates DH's pregen.
- `MinecraftServerWrapper.getRecentAverageTickMs()`: upstream's server-health
  check returns "healthy" on dedicated servers because it only reads singleplayer
  tick times, so `limitIfServerUnhealthy` never did anything on a dedicated server.
  DHSO reads the dedicated server's tick times instead (upstream check unchanged).

### `/dhso` commands (owner permission, same check as `/dh`)
`status`, `storage`, `pause`, `resume`, `cleanup dryrun`, `cleanup run`.

### Tests
`DhsoStorageTest` (5 tests): config creation/validation, throttle hysteresis and
priorities, and eviction against a database built by DH's real migration scripts.

## Step 3b: compaction of oversized databases

- `/dhso compact` marks each loaded dimension's database (a
  `DistantHorizons.sqlite.dhso-compact` file next to it).
- On the next start, before DH opens a marked database, `DhsoCompactor` checkpoints
  the WAL, runs `VACUUM INTO` a copy, verifies the copy with `PRAGMA quick_check`
  and an equal `FullData` row count, sets WAL mode on it, then swaps it in by
  renaming. The original is only read until the verified copy is ready.
- Skipped (nothing changed) when under 10% of the file is free or free disk is
  below ~1.1x the kept data + 512 MB. One attempt per request.
- Crash recovery: a leftover `.dhso-old` with no database is renamed back; a
  leftover `.dhso-old` next to a database is the finished case and is deleted;
  `.dhso-compacting` copies are always deleted.
- Tests: `DhsoCompactorTest` (2 tests) on a real DH database: shrinks, keeps all
  rows, DH reopens it in WAL mode, second run is skipped; interrupted swap recovery.
