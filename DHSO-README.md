# Distant Horizons Server Optimized (DHSO)

A server-focused fork of Distant Horizons 3.3.3 for Minecraft 26.2 / NeoForge.
Unmodified DH 3.3.3 clients connect normally; the network protocol is untouched.

## Install
1. Stop the server and back up the world.
2. Remove the original Distant Horizons jar from `mods/` (never run both).
3. Put the DHSO jar in `mods/` and start the server.
4. Edit `config/DistantHorizonsServerOptimized.toml` (created on first start), then restart.

Keep the server folder out of OneDrive/Dropbox/Google Drive sync folders:
sync clients lock and upload SQLite and region files while the server writes them.

## How the storage budget works
The LOD database is a rebuildable cache. When a dimension's database passes
`cleanupStartPercent` of its budget, DHSO deletes full- and half-detail LOD
sections far from players until it is back at `cleanupTargetPercent`. Coarser
LODs are kept, so distant terrain stays visible at lower detail, and evicted
sections are regenerated when a player gets close again.

Deleting frees space *inside* the database file, which DH reuses for new LODs,
so the file stops growing. It does not make an existing large file smaller;
that needs an offline compaction (planned, not part of this build).

## Commands (operators only)
| Command | What it does |
| --- | --- |
| `/dhso status` | Generation state and why, budgets, last cleanup, last error |
| `/dhso storage` | Used size, file size and WAL size per dimension, free disk |
| `/dhso pause` / `/dhso resume` | Pause/resume DH world generation (cached LODs are still sent) |
| `/dhso cleanup dryrun` | Estimate what a cleanup would evict; deletes nothing |
| `/dhso cleanup run` | Evict down to the cleanup target now |

## Shutdown
DH waits at most 30 s for its databases to close during server stop, then lets
Minecraft's world save continue and logs a `DH-` thread dump if anything was stuck.
Change with the JVM argument `-Ddhso.shutdownTimeoutSeconds=60`.

## Known limitations
- Budgets are logical sizes; existing oversized files don't shrink yet.
- Eviction distance uses players seen since the last restart (plus the world origin).
- MSPT throttling needs MC 1.20.3+ tick timing (fine for 26.2).
- Not yet validated against C2ME, HBM, AE2 or BuildCraft on a live server.
