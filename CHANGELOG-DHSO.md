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
