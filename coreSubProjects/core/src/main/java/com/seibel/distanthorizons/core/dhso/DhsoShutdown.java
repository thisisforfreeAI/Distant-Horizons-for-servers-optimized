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
import com.seibel.distanthorizons.coreapi.ModInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DHSO (Distant Horizons Server Optimized) shutdown helpers. <br><br>
 *
 * Upstream DH closes its levels on the server thread during
 * the server stopping event and waits for them without a time limit.
 * DHSO bounds that wait, and when it runs out, logs which DH threads
 * are still alive and what they're doing so a hang can be attributed
 * to a specific DH subsystem instead of guessed at. <br><br>
 *
 * A timeout never deletes data or reports a false success:
 * the stuck work keeps running on its own (daemon) thread,
 * and Minecraft's own world save is allowed to continue.
 */
public class DhsoShutdown
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	/**
	 * How long the server thread will wait for DH levels to close. <br>
	 * Can be changed with the JVM argument {@code -Ddhso.shutdownTimeoutSeconds=60}.
	 */
	public static final int SHUTDOWN_TIMEOUT_SECONDS = Math.max(1, Integer.getInteger("dhso.shutdownTimeoutSeconds", 30));

	/** set once the server starts stopping, so background work can stop accepting new jobs */
	private static final AtomicBoolean SHUTTING_DOWN_REF = new AtomicBoolean(false);



	public static boolean isShuttingDown() { return SHUTTING_DOWN_REF.get(); }
	public static void markShuttingDown() { SHUTTING_DOWN_REF.set(true); }
	public static void clearShuttingDown() { SHUTTING_DOWN_REF.set(false); }



	/**
	 * Waits for every future to complete, but no later than the given deadline.
	 * Futures that fail are treated as finished (their error should already be logged).
	 * 
	 * @param names the name for each future, in the same order
	 * @param deadlineMs a {@link System#currentTimeMillis()} time
	 * @return the names of the futures that didn't finish before the deadline
	 */
	public static ArrayList<String> awaitAll(List<CompletableFuture<Void>> futures, List<String> names, long deadlineMs)
	{
		ArrayList<String> unfinishedNames = new ArrayList<>();
		for (int i = 0; i < futures.size(); i++)
		{
			long remainingMs = Math.max(1L, deadlineMs - System.currentTimeMillis());
			try
			{
				futures.get(i).get(remainingMs, TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException e)
			{
				unfinishedNames.add(names.get(i));
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				// stop waiting, everything not done yet counts as unfinished
				for (int j = i; j < futures.size(); j++)
				{
					if (!futures.get(j).isDone())
					{
						unfinishedNames.add(names.get(j));
					}
				}
				break;
			}
			catch (ExecutionException e)
			{
				// finished, the error is logged by whoever completed the future
			}
		}
		return unfinishedNames;
	}
	
	/** Logs the stack trace of every live DH thread. */
	public static void logDhThreadDump(String reason)
	{
		StringBuilder builder = new StringBuilder();
		builder.append("[DHSO] DH thread dump (").append(reason).append("):\n");

		int threadCount = 0;
		for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet())
		{
			Thread thread = entry.getKey();
			String name = thread.getName();
			if (!isDhThread(name))
			{
				continue;
			}

			threadCount++;
			builder.append("  \"").append(name).append("\"")
					.append(" state=").append(thread.getState())
					.append(" daemon=").append(thread.isDaemon())
					.append("\n");

			for (StackTraceElement element : entry.getValue())
			{
				builder.append("      at ").append(element).append("\n");
			}
		}

		builder.append("[DHSO] [").append(threadCount).append("] DH thread(s) alive.");
		LOGGER.warn(builder.toString());
	}

	/** @return the number of live, non-daemon DH threads, these will prevent the JVM from exiting. */
	public static int countNonDaemonDhThreads()
	{
		int count = 0;
		for (Thread thread : Thread.getAllStackTraces().keySet())
		{
			if (thread.isAlive() && !thread.isDaemon() && isDhThread(thread.getName()))
			{
				count++;
			}
		}
		return count;
	}

	private static boolean isDhThread(String name) { return name.startsWith(ModInfo.THREAD_NAME_PREFIX); }
	
	/** prefixed so it shows up in {@link #logDhThreadDump} */
	public static final String LEVEL_SHUTDOWN_THREAD_NAME = ModInfo.THREAD_NAME_PREFIX + "Level Shutdown";

}
