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

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;

/**
 * Decides how much DH world generation the server can afford right now. <br><br>
 *
 * States: <br>
 * NORMAL - generation runs within DH's own limits <br>
 * THROTTLED - generation runs at half concurrency <br>
 * CRITICAL - optional generation is paused <br>
 * SHUTDOWN - the server is stopping, no new generation <br><br>
 *
 * Lag based transitions use hysteresis: escalating happens as soon as a
 * threshold is crossed, but returning to NORMAL needs MSPT below the
 * (lower) recover threshold, so generation doesn't flip on and off every check.
 */
public class DhsoGenerationController
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	public enum EState { NORMAL, THROTTLED, CRITICAL, SHUTDOWN }

	private volatile EState state = EState.NORMAL;
	private volatile String reason = "starting";

	/** set by an admin via /dhso pause */
	private volatile boolean manuallyPaused = false;



	public EState getState() { return this.state; }
	public String getReason() { return this.reason; }

	public boolean isManuallyPaused() { return this.manuallyPaused; }
	public void setManuallyPaused(boolean paused)
	{
		this.manuallyPaused = paused;
		LOGGER.info("[DHSO] DH world generation " + (paused ? "paused" : "resumed") + " by an administrator.");
	}



	/**
	 * @param averageMspt the recent average milliseconds per tick, negative if unknown
	 * @param diskLow true if free disk space is below the configured minimum
	 * @param overStorageBudget true if a database is over budget and eviction can't bring it back down
	 */
	public EState update(DhsoConfig config, double averageMspt, boolean diskLow, boolean overStorageBudget, boolean shuttingDown)
	{
		EState oldState = this.state;
		EState newState;
		String newReason;

		if (shuttingDown)
		{
			newState = EState.SHUTDOWN;
			newReason = "server stopping";
		}
		else if (this.manuallyPaused)
		{
			newState = EState.CRITICAL;
			newReason = "paused with /dhso pause";
		}
		else if (diskLow && config.pauseWhenDiskLow)
		{
			newState = EState.CRITICAL;
			newReason = "free disk space below " + config.minimumFreeDiskGb + " GB";
		}
		else if (!config.adaptiveThrottling)
		{
			newState = EState.NORMAL;
			newReason = "adaptive throttling disabled";
		}
		else if (overStorageBudget)
		{
			newState = EState.CRITICAL;
			newReason = "LOD storage over budget";
		}
		else if (averageMspt < 0)
		{
			newState = EState.NORMAL;
			newReason = "tick time unavailable";
		}
		else if (averageMspt >= config.msptPauseThreshold)
		{
			newState = EState.CRITICAL;
			newReason = String.format("MSPT %.1f >= %.1f", averageMspt, config.msptPauseThreshold);
		}
		else if (averageMspt >= config.msptThrottleThreshold)
		{
			newState = EState.THROTTLED;
			newReason = String.format("MSPT %.1f >= %.1f", averageMspt, config.msptThrottleThreshold);
		}
		else if ((oldState == EState.THROTTLED || oldState == EState.CRITICAL)
				&& averageMspt >= config.msptRecoverThreshold)
		{
			// hysteresis: lag has eased but not enough to go back to normal, step down one level at most
			newState = EState.THROTTLED;
			newReason = String.format("MSPT %.1f recovering (needs < %.1f)", averageMspt, config.msptRecoverThreshold);
		}
		else
		{
			newState = EState.NORMAL;
			newReason = String.format("MSPT %.1f", averageMspt);
		}

		this.state = newState;
		this.reason = newReason;
		if (newState != oldState)
		{
			LOGGER.info("[DHSO] Generation state [" + oldState + "] -> [" + newState + "]: " + newReason);
		}
		return newState;
	}

	/** @return true if a world gen thread may start a task while {@code runningTaskCount} tasks are already running */
	public boolean canStartGeneration(int runningTaskCount, int poolSize)
	{
		switch (this.state)
		{
			case NORMAL:
				return true;
			case THROTTLED:
				return runningTaskCount < Math.max(1, poolSize / 2);
			default:
				return false;
		}
	}

}
