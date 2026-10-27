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

package com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.coreapi.ModInfo;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractChunkyAccessor implements IChunkyAccessor
{
	protected static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final ThreadPoolExecutor TIMEOUT_THREAD = ThreadUtil.makeSingleDaemonThreadPool("Chunky Timeout Handler");
	/** How often we should check if chunky is running. */
	private static final long TIME_BETWEEN_RUNNING_CHECKS_MS = 1_000L;
	/** 
	 * How long Chunky should be inactive before
	 * we assume it's no longer running.
	 */
	private static final long CHUNKY_MIN_INACTIVE_TIME_MS = 2_000L;
	
	private final AtomicBoolean timeoutThreadRunningRef = new AtomicBoolean(false);
	private final AtomicLong lastChunkyRunMsRef = new AtomicLong(0L);
	private final AtomicBoolean chunkyRunningRef = new AtomicBoolean(false);
	
	
	
	//==================//
	// abstract methods //
	//==================//
	//region
	
	protected abstract void bindOnGenerationProgressEvent();
	
	//endregion
	
	
	
	//==================//
	// concrete methods //
	//==================//
	//region
	
	@Override
	public boolean isRunning() { return this.chunkyRunningRef.get(); }
	
	
	/**
	 * DHSO: how long to wait between attempts to bind to Chunky
	 * if Chunky hasn't finished starting yet.
	 */
	private static final long BIND_RETRY_INTERVAL_MS = 5_000L;

	/** set once the listener is bound, or once binding failed for a reason retrying won't fix */
	private final AtomicBoolean setupFinishedRef = new AtomicBoolean(false);
	private final AtomicBoolean bindInProgressRef = new AtomicBoolean(false);
	private final AtomicLong lastBindAttemptMsRef = new AtomicLong(0L);
	private int failedBindAttemptCount = 0;
	
	/** overridable so tests don't have to wait 5 seconds between attempts */
	protected long getBindRetryIntervalMs() { return BIND_RETRY_INTERVAL_MS; }
	/** @return true once DH is listening to Chunky's events, or has given up after an unexpected error */
	public boolean isSetupFinished() { return this.setupFinishedRef.get(); }

	/**
	 * DHSO: this is called on every chunk load, which can happen before Chunky
	 * has finished its own startup. In that case Chunky throws
	 * "IllegalStateException: Chunky is not loaded". Upstream marked the listener as bound
	 * before trying, so a single early failure meant DH never learned when Chunky was running.
	 * Now the bind is only marked done once it succeeds, retries are rate limited,
	 * and a failure never propagates into the chunk load that triggered it.
	 */
	@Override
	public void tryRunFirstTimeSetup()
	{
		if (this.setupFinishedRef.get())
		{
			return;
		}

		long nowMs = System.currentTimeMillis();
		if (nowMs - this.lastBindAttemptMsRef.get() < this.getBindRetryIntervalMs())
		{
			return;
		}

		// only one thread should attempt the bind at a time
		if (!this.bindInProgressRef.compareAndSet(false, true))
		{
			return;
		}

		try
		{
			this.lastBindAttemptMsRef.set(nowMs);
			this.bindOnGenerationProgressEvent();
			this.setupFinishedRef.set(true);
			LOGGER.info("Bound to Chunky generation events" + (this.failedBindAttemptCount != 0 ? " after [" + this.failedBindAttemptCount + "] attempt(s) while Chunky was still starting." : "."));
		}
		catch (IllegalStateException e)
		{
			// Chunky isn't loaded/started yet, try again later
			this.failedBindAttemptCount++;
			if (this.failedBindAttemptCount == 1)
			{
				LOGGER.info("Chunky isn't ready yet [" + e.getMessage() + "], DH will retry binding to Chunky every [" + this.getBindRetryIntervalMs() + "]ms.");
			}
		}
		catch (Throwable e)
		{
			// anything else is unexpected, report it once and stop trying so chunk loading isn't affected
			this.setupFinishedRef.set(true);
			LOGGER.error("Unable to bind to Chunky generation events, DH won't pause its world generator while Chunky is running. Error: [" + e.getMessage() + "].", e);
		}
		finally
		{
			this.bindInProgressRef.set(false);
		}
	}
	
	/** 
	 * We don't care what information is in Chunky's event object,
	 * we just care that generation events are being fired at all.<Br><Br>
	 * 
	 * This shouldn't be fired on the client if connected
	 * to a dedicated server. <br><br>
	 * 
	 * Expected to be run on MC's server thread 
	 */
	protected void onGenEvent()
	{
		this.lastChunkyRunMsRef.set(System.currentTimeMillis());
		
		
		// only update the API value when needed
		// to prevent firing the listeners a bunch
		if (Config.Common.WorldGenerator.generatorPlan.getApiValue() == null)
		{
			LOGGER.info("Chunky running. Disabling DH world gen...");
			Config.Common.WorldGenerator.generatorPlan.setApiValue(EDhApiGeneratorPlan.DISABLED, ModInfo.READABLE_NAME + " / Chunky");
		}
		
		
		// we only need one thread to check if chunky is running at a time
		if (!this.timeoutThreadRunningRef.getAndSet(true))
		{
			// run on a separate thread to prevent lagging the server thread
			TIMEOUT_THREAD.execute(() ->
			{
				try
				{
					// wait for chunky to finish running
					long lastRunMs;
					long timeSinceLastRunMs = 0L;
					while (timeSinceLastRunMs < CHUNKY_MIN_INACTIVE_TIME_MS)
					{
						try { Thread.sleep(TIME_BETWEEN_RUNNING_CHECKS_MS); } catch (InterruptedException ignore) { }
						
						lastRunMs = this.lastChunkyRunMsRef.get();
						timeSinceLastRunMs = System.currentTimeMillis() - lastRunMs;
					}
					
					
					LOGGER.info("Chunky no longer running. Re-enabling DH world gen...");
					Config.Common.WorldGenerator.generatorPlan.setApiValue(null, null);
				}
				finally
				{
					this.timeoutThreadRunningRef.set(false);
				}
			});
		}
	}
	
	//endregion
	
	
	
}
