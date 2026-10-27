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

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;

import java.io.File;

/**
 * DHSO server settings, stored in {@code config/DistantHorizonsServerOptimized.toml}. <br>
 * These are separate from DH's own config so they never conflict with upstream keys. <br><br>
 *
 * Every setting here is backed by real behavior; values out of range are
 * clamped and reported in the log instead of being silently accepted.
 */
public class DhsoConfig
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	public static final String FILE_NAME = "DistantHorizonsServerOptimized.toml";


	// storage //
	public volatile boolean storageEnabled = true;
	public volatile double globalBudgetGb = 20.0;
	public volatile double dimensionBudgetGb = 10.0;
	public volatile int cleanupStartPercent = 90;
	public volatile int cleanupTargetPercent = 75;
	public volatile double minimumFreeDiskGb = 10.0;
	public volatile int checkIntervalSeconds = 60;

	// eviction //
	public volatile boolean evictionEnabled = true;
	public volatile boolean evictionDryRun = false;
	/** LODs within this many blocks of an online player are never evicted */
	public volatile int protectedRadiusBlocks = 2048;
	/**
	 * The highest (coarsest) LOD detail level that may be evicted.
	 * 0 = only full detail, 1 = full detail and half detail.
	 * Coarser levels are kept so distant terrain stays visible.
	 */
	public volatile int maxEvictableDetailLevel = 1;
	public volatile int evictionBatchSize = 500;

	// generation //
	public volatile boolean adaptiveThrottling = true;
	public volatile double msptThrottleThreshold = 40.0;
	public volatile double msptPauseThreshold = 48.0;
	/** generation only returns to normal once MSPT drops below this, to prevent flip-flopping */
	public volatile double msptRecoverThreshold = 35.0;
	public volatile boolean pauseWhenDiskLow = true;

	// monitoring //
	public volatile int statisticsLogIntervalSeconds = 300;



	//=========//
	// loading //
	//=========//

	/** Loads the config from the given folder, creating or completing the file with defaults as needed. */
	public static DhsoConfig loadOrCreate(File configFolder)
	{
		DhsoConfig config = new DhsoConfig();
		File file = new File(configFolder, FILE_NAME);

		try
		{
			if (!configFolder.exists() && !configFolder.mkdirs())
			{
				LOGGER.warn("[DHSO] Unable to create config folder [" + configFolder + "], using default settings.");
				return config;
			}

			if (!file.exists() && !file.createNewFile())
			{
				LOGGER.warn("[DHSO] Unable to create [" + file + "], using default settings.");
				return config;
			}

			try (CommentedFileConfig fileConfig = CommentedFileConfig.builder(file).sync().build())
			{
				fileConfig.load();
				config.readAndFill(fileConfig);
				fileConfig.save();
			}
			LOGGER.info("[DHSO] Loaded settings from [" + file + "]: " + config);
		}
		catch (Exception e)
		{
			LOGGER.error("[DHSO] Unable to read [" + file + "], using default settings. Error: [" + e.getMessage() + "].", e);
			config = new DhsoConfig();
		}

		return config;
	}

	private void readAndFill(CommentedFileConfig file)
	{
		this.storageEnabled = this.readBool(file, "storage.enabled", this.storageEnabled,
				"Keep the LOD databases within the budgets below by evicting rebuildable LOD data.");
		this.globalBudgetGb = this.readDouble(file, "storage.globalBudgetGB", this.globalBudgetGb, 0.1, 100_000,
				"Target size of all DH databases combined, in GB (logical size, see README).");
		this.dimensionBudgetGb = this.readDouble(file, "storage.dimensionBudgetGB", this.dimensionBudgetGb, 0.1, 100_000,
				"Target size of one dimension's DH database, in GB.");
		this.cleanupStartPercent = this.readInt(file, "storage.cleanupStartPercent", this.cleanupStartPercent, 2, 100,
				"Start evicting when a database reaches this percent of its budget.");
		this.cleanupTargetPercent = this.readInt(file, "storage.cleanupTargetPercent", this.cleanupTargetPercent, 1, 99,
				"Stop evicting once the database is back down to this percent of its budget.");
		this.minimumFreeDiskGb = this.readDouble(file, "storage.minimumFreeDiskGB", this.minimumFreeDiskGb, 0, 100_000,
				"Pause DH world generation when the world drive has less free space than this, in GB.");
		this.checkIntervalSeconds = this.readInt(file, "storage.checkIntervalSeconds", this.checkIntervalSeconds, 10, 3600,
				"How often database sizes and free disk space are checked, in seconds.");

		this.evictionEnabled = this.readBool(file, "eviction.enabled", this.evictionEnabled,
				"Allow deleting rebuildable LOD data to stay within the storage budgets.");
		this.evictionDryRun = this.readBool(file, "eviction.dryRun", this.evictionDryRun,
				"Log what would be evicted without deleting anything.");
		this.protectedRadiusBlocks = this.readInt(file, "eviction.protectedRadiusBlocks", this.protectedRadiusBlocks, 0, 1_000_000,
				"LODs within this many blocks of an online player are never evicted.");
		this.maxEvictableDetailLevel = this.readInt(file, "eviction.maxEvictableDetailLevel", this.maxEvictableDetailLevel, 0, 4,
				"Coarsest LOD detail level that may be evicted. 0 = full detail only, 1 = full and half detail. Coarser levels are always kept so distant terrain stays visible.");
		this.evictionBatchSize = this.readInt(file, "eviction.batchSize", this.evictionBatchSize, 10, 10_000,
				"LOD sections deleted per database transaction.");

		this.adaptiveThrottling = this.readBool(file, "generation.adaptiveThrottling", this.adaptiveThrottling,
				"Slow down or pause DH world generation when the server is lagging, low on disk, or over its storage budget.");
		this.msptThrottleThreshold = this.readDouble(file, "generation.msptThrottleThreshold", this.msptThrottleThreshold, 1, 1000,
				"Average milliseconds per tick at which DH generation is halved.");
		this.msptPauseThreshold = this.readDouble(file, "generation.msptPauseThreshold", this.msptPauseThreshold, 1, 1000,
				"Average milliseconds per tick at which DH generation is paused.");
		this.msptRecoverThreshold = this.readDouble(file, "generation.msptRecoverThreshold", this.msptRecoverThreshold, 1, 1000,
				"Average milliseconds per tick generation must drop below to return to normal.");
		this.pauseWhenDiskLow = this.readBool(file, "generation.pauseWhenDiskLow", this.pauseWhenDiskLow,
				"Pause DH world generation when free disk space is below storage.minimumFreeDiskGB.");

		this.statisticsLogIntervalSeconds = this.readInt(file, "monitoring.statisticsLogIntervalSeconds", this.statisticsLogIntervalSeconds, 0, 86_400,
				"How often a one-line DHSO status is written to the log, in seconds. 0 disables it.");

		this.validate();
	}

	/** fixes values that are individually valid but contradict each other */
	public void validate()
	{
		if (this.cleanupTargetPercent >= this.cleanupStartPercent)
		{
			int newTarget = Math.max(1, this.cleanupStartPercent - 10);
			LOGGER.warn("[DHSO] storage.cleanupTargetPercent [" + this.cleanupTargetPercent + "] must be below cleanupStartPercent [" + this.cleanupStartPercent + "], using [" + newTarget + "].");
			this.cleanupTargetPercent = newTarget;
		}
		if (this.msptPauseThreshold < this.msptThrottleThreshold)
		{
			LOGGER.warn("[DHSO] generation.msptPauseThreshold [" + this.msptPauseThreshold + "] is below msptThrottleThreshold [" + this.msptThrottleThreshold + "], using [" + this.msptThrottleThreshold + "].");
			this.msptPauseThreshold = this.msptThrottleThreshold;
		}
		if (this.msptRecoverThreshold > this.msptThrottleThreshold)
		{
			LOGGER.warn("[DHSO] generation.msptRecoverThreshold [" + this.msptRecoverThreshold + "] is above msptThrottleThreshold [" + this.msptThrottleThreshold + "], using [" + this.msptThrottleThreshold + "].");
			this.msptRecoverThreshold = this.msptThrottleThreshold;
		}
	}



	//=================//
	// file read utils //
	//=================//

	private boolean readBool(CommentedFileConfig file, String path, boolean defaultValue, String comment)
	{
		Object value = this.readOrSetDefault(file, path, defaultValue, comment);
		if (value instanceof Boolean)
		{
			return (Boolean) value;
		}
		LOGGER.warn("[DHSO] [" + path + "] should be true or false, found [" + value + "], using [" + defaultValue + "].");
		file.set(path, defaultValue);
		return defaultValue;
	}

	private int readInt(CommentedFileConfig file, String path, int defaultValue, int min, int max, String comment)
	{
		Object value = this.readOrSetDefault(file, path, defaultValue, comment);
		if (!(value instanceof Number))
		{
			LOGGER.warn("[DHSO] [" + path + "] should be a whole number, found [" + value + "], using [" + defaultValue + "].");
			file.set(path, defaultValue);
			return defaultValue;
		}

		long longValue = ((Number) value).longValue();
		int clamped = (int) Math.max(min, Math.min(max, longValue));
		if (clamped != longValue)
		{
			LOGGER.warn("[DHSO] [" + path + "] value [" + value + "] is outside [" + min + "-" + max + "], using [" + clamped + "].");
		}
		return clamped;
	}

	private double readDouble(CommentedFileConfig file, String path, double defaultValue, double min, double max, String comment)
	{
		Object value = this.readOrSetDefault(file, path, defaultValue, comment);
		if (!(value instanceof Number))
		{
			LOGGER.warn("[DHSO] [" + path + "] should be a number, found [" + value + "], using [" + defaultValue + "].");
			file.set(path, defaultValue);
			return defaultValue;
		}

		double doubleValue = ((Number) value).doubleValue();
		double clamped = Math.max(min, Math.min(max, doubleValue));
		if (clamped != doubleValue)
		{
			LOGGER.warn("[DHSO] [" + path + "] value [" + value + "] is outside [" + min + "-" + max + "], using [" + clamped + "].");
		}
		return clamped;
	}

	private Object readOrSetDefault(CommentedFileConfig file, String path, Object defaultValue, String comment)
	{
		if (!file.contains(path))
		{
			file.set(path, defaultValue);
		}
		file.setComment(path, " " + comment);
		return file.get(path);
	}



	//==========//
	// overrides //
	//==========//

	@Override
	public String toString()
	{
		return "storage=" + this.storageEnabled
				+ " globalBudgetGB=" + this.globalBudgetGb
				+ " dimensionBudgetGB=" + this.dimensionBudgetGb
				+ " cleanup=" + this.cleanupStartPercent + "%->" + this.cleanupTargetPercent + "%"
				+ " minFreeDiskGB=" + this.minimumFreeDiskGb
				+ " eviction=" + this.evictionEnabled + (this.evictionDryRun ? " (dry run)" : "")
				+ " maxEvictableDetailLevel=" + this.maxEvictableDetailLevel
				+ " protectedRadius=" + this.protectedRadiusBlocks
				+ " throttling=" + this.adaptiveThrottling
				+ " mspt=" + this.msptRecoverThreshold + "/" + this.msptThrottleThreshold + "/" + this.msptPauseThreshold;
	}

}
