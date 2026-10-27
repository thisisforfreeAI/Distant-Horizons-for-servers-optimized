/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.dhso;

import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerState;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerStateManager;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Keeps the DH databases within their storage budgets and
 * feeds the {@link DhsoGenerationController}. <br><br>
 *
 * All database work happens on one background daemon thread,
 * never on Minecraft's server thread.
 */
public class DhsoStorageManager implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
	/** how often lag is sampled for the generation controller */
	private static final int CONTROLLER_INTERVAL_SECONDS = 5;
	/** an automatic cleanup gives the database writer lock back after this long, and continues next check */
	private static final long MAX_CLEANUP_MS_PER_CHECK = 20_000L;

	@Nullable
	private static volatile DhsoStorageManager current = null;
	/** @return the manager for the running server, null if there isn't one */
	@Nullable
	public static DhsoStorageManager getCurrent() { return current; }


	public final DhsoConfig config;
	public final DhsoGenerationController controller = new DhsoGenerationController();

	private final Supplier<Collection<? extends AbstractDhServerLevel>> levelSupplier;
	private final ServerPlayerStateManager playerStateManager;
	private final ScheduledExecutorService executor;
	private final AtomicBoolean closedRef = new AtomicBoolean(false);

	private volatile boolean diskLow = false;
	private volatile boolean overBudget = false;
	private volatile Map<String, LevelStats> lastStats = Collections.emptyMap();
	private volatile String lastCleanupSummary = "none yet";
	private volatile String lastError = null;
	private volatile long lastStatsLogMs = System.currentTimeMillis();
	private volatile long totalEvictedSections = 0;


	public static class LevelStats
	{
		public final String levelName;
		public final long usedBytes;
		public final long fileBytes;
		public final long walBytes;
		public final long freeDiskBytes;

		public LevelStats(String levelName, long usedBytes, long fileBytes, long walBytes, long freeDiskBytes)
		{
			this.levelName = levelName;
			this.usedBytes = usedBytes;
			this.fileBytes = fileBytes;
			this.walBytes = walBytes;
			this.freeDiskBytes = freeDiskBytes;
		}
	}



	//=============//
	// constructor //
	//=============//

	public DhsoStorageManager(DhsoConfig config, Supplier<Collection<? extends AbstractDhServerLevel>> levelSupplier, ServerPlayerStateManager playerStateManager)
	{
		this.config = config;
		this.levelSupplier = levelSupplier;
		this.playerStateManager = playerStateManager;

		this.executor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, ModInfo.THREAD_NAME_PREFIX + "DHSO Storage");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});

		// give the levels a moment to load before the first check
		this.executor.scheduleWithFixedDelay(this::safeUpdateController, CONTROLLER_INTERVAL_SECONDS, CONTROLLER_INTERVAL_SECONDS, TimeUnit.SECONDS);
		this.executor.scheduleWithFixedDelay(this::safeStorageCheck, 30, config.checkIntervalSeconds, TimeUnit.SECONDS);

		current = this;
		LOGGER.info("[DHSO] Storage manager started: " + config);
	}



	//=================//
	// periodic checks //
	//=================//

	private void safeUpdateController()
	{
		try
		{
			this.recordPlayerPositions();
			
			double mspt = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class).getRecentAverageTickMs();
			this.controller.update(this.config, mspt, this.diskLow, this.overBudget, DhsoShutdown.isShuttingDown());
		}
		catch (Throwable e)
		{
			this.recordError("generation controller", e);
		}
	}

	private void safeStorageCheck()
	{
		try
		{
			this.runStorageCheck(false, false);
			this.maybeLogStatistics();
		}
		catch (Throwable e)
		{
			this.recordError("storage check", e);
		}
	}

	/**
	 * @param forceCleanup run eviction down to the target even if the start threshold wasn't reached
	 * @param dryRun only report what would be evicted
	 * @return a human-readable summary
	 */
	private synchronized String runStorageCheck(boolean forceCleanup, boolean dryRun) throws Exception
	{
		if (this.closedRef.get() || DhsoShutdown.isShuttingDown())
		{
			return "Server is stopping, nothing done.";
		}


		// measure //

		LinkedHashMap<String, LevelStats> statsByLevel = new LinkedHashMap<>();
		LinkedHashMap<String, AbstractDhServerLevel> levelsByName = new LinkedHashMap<>();
		boolean anyDiskLow = false;
		long totalUsedBytes = 0;
		for (AbstractDhServerLevel level : this.levelSupplier.get())
		{
			FullDataSourceV2Repo repo = level.getFullDataProvider().repo;
			Connection connection = repo.getConnection();
			if (connection == null)
			{
				// level closing
				continue;
			}

			String levelName = getLevelName(level);
			File dbFile = repo.databaseFile;
			File walFile = new File(dbFile.getPath() + "-wal");
			long freeDiskBytes = dbFile.getAbsoluteFile().getParentFile().getUsableSpace();
			LevelStats stats = new LevelStats(levelName, DhsoLodEvictor.getUsedBytes(connection), dbFile.length(), walFile.exists() ? walFile.length() : 0, freeDiskBytes);

			statsByLevel.put(levelName, stats);
			levelsByName.put(levelName, level);
			totalUsedBytes += stats.usedBytes;
			anyDiskLow |= (freeDiskBytes < this.config.minimumFreeDiskGb * BYTES_PER_GB);
		}
		this.lastStats = statsByLevel;

		if (anyDiskLow != this.diskLow)
		{
			if (anyDiskLow)
			{
				LOGGER.warn("[DHSO] Free disk space is below [" + this.config.minimumFreeDiskGb + "] GB. " + (this.config.pauseWhenDiskLow ? "DH world generation is paused until space is freed. " : "") + "Minecraft's own world saving is not affected by DHSO, but may fail if the disk fills up.");
			}
			else
			{
				LOGGER.info("[DHSO] Free disk space is back above [" + this.config.minimumFreeDiskGb + "] GB.");
			}
			this.diskLow = anyDiskLow;
		}

		if (!this.config.storageEnabled)
		{
			this.overBudget = false;
			return "Storage management is disabled in " + DhsoConfig.FILE_NAME + ".";
		}


		// decide how much each level needs to shed //

		long dimensionBudget = (long) (this.config.dimensionBudgetGb * BYTES_PER_GB);
		long globalBudget = (long) (this.config.globalBudgetGb * BYTES_PER_GB);
		long startPercent = forceCleanup ? 0 : this.config.cleanupStartPercent;

		LinkedHashMap<String, Long> bytesToFreeByLevel = new LinkedHashMap<>();
		for (LevelStats stats : statsByLevel.values())
		{
			if (stats.usedBytes > dimensionBudget * startPercent / 100)
			{
				long target = dimensionBudget * this.config.cleanupTargetPercent / 100;
				if (stats.usedBytes > target)
				{
					bytesToFreeByLevel.put(stats.levelName, stats.usedBytes - target);
				}
			}
		}

		if (totalUsedBytes > globalBudget * startPercent / 100)
		{
			long globalExcess = totalUsedBytes - (globalBudget * this.config.cleanupTargetPercent / 100);
			globalExcess -= sum(bytesToFreeByLevel.values());
			// take the rest from the largest databases first
			ArrayList<LevelStats> largestFirst = new ArrayList<>(statsByLevel.values());
			largestFirst.sort((a, b) -> Long.compare(b.usedBytes, a.usedBytes));
			for (LevelStats stats : largestFirst)
			{
				if (globalExcess <= 0)
				{
					break;
				}
				long alreadyFreeing = bytesToFreeByLevel.getOrDefault(stats.levelName, 0L);
				long canFree = Math.max(0, stats.usedBytes - alreadyFreeing);
				long extra = Math.min(canFree, globalExcess);
				bytesToFreeByLevel.put(stats.levelName, alreadyFreeing + extra);
				globalExcess -= extra;
			}
		}

		if (bytesToFreeByLevel.isEmpty())
		{
			this.overBudget = false;
			return "All databases are within budget.";
		}

		if (!this.config.evictionEnabled && !dryRun)
		{
			this.overBudget = true;
			return "Over budget, but eviction is disabled in " + DhsoConfig.FILE_NAME + ".";
		}


		// evict //

		boolean effectiveDryRun = dryRun || this.config.evictionDryRun;
		long deadlineMs = System.currentTimeMillis() + (forceCleanup ? Long.MAX_VALUE / 2 : MAX_CLEANUP_MS_PER_CHECK);
		StringBuilder summary = new StringBuilder();
		boolean stillOverBudget = false;
		for (Map.Entry<String, Long> entry : bytesToFreeByLevel.entrySet())
		{
			AbstractDhServerLevel level = levelsByName.get(entry.getKey());
			LevelStats before = statsByLevel.get(entry.getKey());
			EvictionResult result = this.evictFromLevel(level, entry.getValue(), effectiveDryRun, deadlineMs);

			summary.append(entry.getKey()).append(": ");
			if (effectiveDryRun)
			{
				summary.append("would evict ").append(result.sectionCount).append(" LOD sections (needs to free ~").append(formatGb(entry.getValue())).append(")");
			}
			else
			{
				summary.append("evicted ").append(result.sectionCount).append(" LOD sections, ")
						.append(formatGb(before.usedBytes)).append(" -> ").append(formatGb(result.usedBytesAfter)).append(" used");
				this.totalEvictedSections += result.sectionCount;
			}
			if (!result.reachedTarget)
			{
				summary.append(result.timedOut ? " (continuing next check)" : " (nothing more is safe to evict)");
				stillOverBudget |= !result.timedOut;
			}
			summary.append("; ");
		}

		this.overBudget = stillOverBudget && !effectiveDryRun;
		String summaryString = summary.toString();
		if (!effectiveDryRun)
		{
			this.lastCleanupSummary = new java.util.Date() + " " + summaryString;
		}
		LOGGER.info("[DHSO] " + (effectiveDryRun ? "Cleanup dry run: " : "Cleanup: ") + summaryString);
		return summaryString;
	}

	private static class EvictionResult
	{
		int sectionCount = 0;
		long usedBytesAfter;
		boolean reachedTarget = false;
		boolean timedOut = false;
	}

	private EvictionResult evictFromLevel(AbstractDhServerLevel level, long bytesToFree, boolean dryRun, long deadlineMs) throws Exception
	{
		EvictionResult result = new EvictionResult();
		Connection connection = level.getFullDataProvider().repo.getConnection();
		if (connection == null)
		{
			return result;
		}

		long usedBefore = DhsoLodEvictor.getUsedBytes(connection);
		long target = usedBefore - bytesToFree;
		result.usedBytesAfter = usedBefore;

		List<DhsoLodEvictor.ProtectedPoint> protectedPoints = this.getProtectedPoints(level);

		if (dryRun)
		{
			// estimate from the average section size, nothing is deleted
			long averageSectionBytes = Math.max(1, usedBefore / Math.max(1, countRows(connection)));
			long sectionsNeeded = (bytesToFree + averageSectionBytes - 1) / averageSectionBytes;
			List<DhsoLodEvictor.SectionKey> candidates = DhsoLodEvictor.selectCandidates(connection, protectedPoints,
					this.config.maxEvictableDetailLevel, this.config.protectedRadiusBlocks, (int) Math.min(Integer.MAX_VALUE - 1, sectionsNeeded));
			result.sectionCount = (int) Math.min(candidates.size(), sectionsNeeded);
			result.reachedTarget = candidates.size() >= sectionsNeeded;
			return result;
		}

		while (result.usedBytesAfter > target)
		{
			if (System.currentTimeMillis() > deadlineMs)
			{
				result.timedOut = true;
				return result;
			}
			if (this.closedRef.get() || DhsoShutdown.isShuttingDown())
			{
				result.timedOut = true;
				return result;
			}

			List<DhsoLodEvictor.SectionKey> candidates = DhsoLodEvictor.selectCandidates(connection, protectedPoints,
					this.config.maxEvictableDetailLevel, this.config.protectedRadiusBlocks, this.config.evictionBatchSize);
			if (candidates.isEmpty())
			{
				return result;
			}

			int deleted = DhsoLodEvictor.deleteSections(connection, candidates);
			result.sectionCount += deleted;
			result.usedBytesAfter = DhsoLodEvictor.getUsedBytes(connection);
			if (deleted == 0)
			{
				// everything selected became busy, try again next check
				result.timedOut = true;
				return result;
			}

			// give DH's own writers a turn between batches
			Thread.sleep(50);
		}

		DhsoLodEvictor.passiveCheckpoint(connection);
		result.reachedTarget = true;
		return result;
	}

	/** 
	 * Online players, plus where every player was last seen this session
	 * (so a player who logs off doesn't have the LODs around their base evicted right away).
	 * If nobody has been seen in this level, the world origin is used,
	 * so eviction starts at the edge of the world instead of in an arbitrary order.
	 */
	private List<DhsoLodEvictor.ProtectedPoint> getProtectedPoints(AbstractDhServerLevel level)
	{
		this.recordPlayerPositions();
		
		ArrayList<DhsoLodEvictor.ProtectedPoint> points = new ArrayList<>();
		String levelName = getLevelName(level);
		for (LastSeenPosition lastSeen : this.lastSeenPositionByPlayer.values())
		{
			if (lastSeen.levelName.equals(levelName))
			{
				points.add(new DhsoLodEvictor.ProtectedPoint(lastSeen.blockX, lastSeen.blockZ));
			}
		}
		
		if (points.isEmpty())
		{
			points.add(new DhsoLodEvictor.ProtectedPoint(0, 0));
		}
		return points;
	}
	
	private static class LastSeenPosition
	{
		final String levelName;
		final double blockX;
		final double blockZ;
		LastSeenPosition(String levelName, double blockX, double blockZ) { this.levelName = levelName; this.blockX = blockX; this.blockZ = blockZ; }
	}
	private final java.util.concurrent.ConcurrentHashMap<String, LastSeenPosition> lastSeenPositionByPlayer = new java.util.concurrent.ConcurrentHashMap<>();
	
	/** called every few seconds so players that log off are still remembered */
	private void recordPlayerPositions()
	{
		for (ServerPlayerState playerState : this.playerStateManager.getConnectedPlayers())
		{
			try
			{
				IServerPlayerWrapper player = playerState.getServerPlayer();
				if (player == null)
				{
					continue;
				}
				IServerLevelWrapper levelWrapper = player.getLevel();
				if (levelWrapper == null)
				{
					continue;
				}
				DhVec3d position = player.getPosition();
				this.lastSeenPositionByPlayer.put(player.getName(), new LastSeenPosition(levelWrapper.getDhIdentifier(), position.x, position.z));
			}
			catch (Exception e)
			{
				// a player leaving mid-check, skip them
			}
		}
	}

	private static long countRows(Connection connection) throws Exception
	{
		try (java.sql.Statement statement = connection.createStatement();
			java.sql.ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM FullData;"))
		{
			return resultSet.next() ? resultSet.getLong(1) : 0;
		}
	}



	//==========//
	// commands //
	//==========//

	/** runs a cleanup on the storage thread, regardless of the start threshold */
	public CompletableFuture<String> requestCleanup(boolean dryRun)
	{
		CompletableFuture<String> future = new CompletableFuture<>();
		this.executor.execute(() ->
		{
			try
			{
				future.complete(this.runStorageCheck(true, dryRun));
			}
			catch (Throwable e)
			{
				this.recordError("manual cleanup", e);
				future.completeExceptionally(e);
			}
		});
		return future;
	}

	/**
	 * Marks every loaded level's database for compaction on the next start. <br>
	 * Compaction can't run while DH has the database open.
	 */
	public String requestCompaction()
	{
		StringBuilder builder = new StringBuilder();
		long totalFreeInsideFiles = 0;
		for (AbstractDhServerLevel level : this.levelSupplier.get())
		{
			FullDataSourceV2Repo repo = level.getFullDataProvider().repo;
			String levelName = getLevelName(level);
			try
			{
				DhsoCompactor.requestCompaction(repo.databaseFile);
				LevelStats stats = this.lastStats.get(levelName);
				if (stats != null)
				{
					long reclaimable = Math.max(0, stats.fileBytes - stats.usedBytes);
					totalFreeInsideFiles += reclaimable;
					builder.append(levelName).append(": about ").append(formatGb(reclaimable)).append(" can be reclaimed, the copy needs about ")
							.append(formatGb(stats.usedBytes)).append(" free disk\n");
				}
				else
				{
					builder.append(levelName).append(": marked\n");
				}
			}
			catch (Exception e)
			{
				this.recordError("compaction request", e);
				builder.append(levelName).append(": failed to mark, ").append(e.getMessage()).append("\n");
			}
		}
		LOGGER.info("[DHSO] Compaction requested for the next start, about " + formatGb(totalFreeInsideFiles) + " reclaimable.");
		builder.append("Compaction runs on the next server start, before players can join, and can take several minutes. Databases with under 10% free space are skipped.");
		return builder.toString();
	}

	public String getStatusString()
	{
		StringBuilder builder = new StringBuilder();
		builder.append("DHSO generation: ").append(this.controller.getState()).append(" (").append(this.controller.getReason()).append(")\n");
		builder.append("Storage: ").append(this.config.storageEnabled ? "managed" : "disabled")
				.append(", budget ").append(this.config.dimensionBudgetGb).append(" GB per dimension / ").append(this.config.globalBudgetGb).append(" GB total")
				.append(this.overBudget ? ", OVER BUDGET" : "")
				.append(this.diskLow ? ", DISK LOW" : "").append("\n");
		builder.append("Evicted this session: ").append(this.totalEvictedSections).append(" LOD sections\n");
		builder.append("Last cleanup: ").append(this.lastCleanupSummary).append("\n");
		if (this.lastError != null)
		{
			builder.append("Last error: ").append(this.lastError).append("\n");
		}
		return builder.toString();
	}

	public String getStorageString()
	{
		Map<String, LevelStats> stats = this.lastStats;
		if (stats.isEmpty())
		{
			return "No measurements yet, the first check runs about 30 seconds after startup.";
		}

		StringBuilder builder = new StringBuilder();
		long totalUsed = 0;
		long totalFile = 0;
		for (LevelStats level : stats.values())
		{
			totalUsed += level.usedBytes;
			totalFile += level.fileBytes + level.walBytes;
			builder.append(level.levelName).append(": ").append(formatGb(level.usedBytes)).append(" used, file ")
					.append(formatGb(level.fileBytes)).append(", WAL ").append(formatGb(level.walBytes)).append("\n");
		}
		LevelStats any = stats.values().iterator().next();
		builder.append("Total: ").append(formatGb(totalUsed)).append(" used of ").append(this.config.globalBudgetGb).append(" GB budget, ")
				.append(formatGb(totalFile)).append(" on disk. Free disk: ").append(formatGb(any.freeDiskBytes)).append("\n");
		builder.append("Note: evicting frees space inside the files for new LODs; it doesn't shrink the files.");
		return builder.toString();
	}



	//=========//
	// helpers //
	//=========//

	private void maybeLogStatistics()
	{
		int intervalSeconds = this.config.statisticsLogIntervalSeconds;
		if (intervalSeconds <= 0 || System.currentTimeMillis() - this.lastStatsLogMs < intervalSeconds * 1000L)
		{
			return;
		}
		this.lastStatsLogMs = System.currentTimeMillis();

		long totalUsed = 0;
		for (LevelStats stats : this.lastStats.values())
		{
			totalUsed += stats.usedBytes;
		}
		LOGGER.info("[DHSO] status: generation=" + this.controller.getState() + " (" + this.controller.getReason() + ")"
				+ ", lodUsed=" + formatGb(totalUsed) + "/" + this.config.globalBudgetGb + "GB"
				+ ", overBudget=" + this.overBudget + ", diskLow=" + this.diskLow
				+ ", evictedThisSession=" + this.totalEvictedSections);
	}

	private void recordError(String task, Throwable e)
	{
		if (this.closedRef.get())
		{
			// expected when the server stops mid-check, the current transaction is rolled back by SQLite
			return;
		}

		this.lastError = new java.util.Date() + " " + task + ": " + e;
		LOGGER.error("[DHSO] Error during " + task + ": [" + e.getMessage() + "]. LOD data is unaffected, will retry next check.", e);
	}

	private static String getLevelName(AbstractDhServerLevel level)
	{
		IServerLevelWrapper wrapper = level.getServerLevelWrapper();
		return (wrapper != null) ? wrapper.getDhIdentifier() : String.valueOf(level);
	}

	private static long sum(Collection<Long> values)
	{
		long total = 0;
		for (Long value : values)
		{
			total += value;
		}
		return total;
	}

	public static String formatGb(long bytes) { return String.format("%.2f GB", bytes / (double) BYTES_PER_GB); }



	//==========//
	// shutdown //
	//==========//

	@Override
	public void close()
	{
		this.closedRef.set(true);
		if (current == this)
		{
			current = null;
		}
		// running checks stop between batches once closed
		this.executor.shutdownNow();
		try
		{
			if (!this.executor.awaitTermination(5, TimeUnit.SECONDS))
			{
				LOGGER.warn("[DHSO] Storage manager didn't stop within 5 seconds, continuing shutdown.");
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

}
