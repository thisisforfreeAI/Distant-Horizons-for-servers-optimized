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

package tests;

import com.seibel.distanthorizons.core.dhso.DhsoShutdown;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** DHSO: the level-close wait during server shutdown must be bounded. */
public class DhsoShutdownTest
{
	@Test
	public void finishedAndFailedClosesAreNotReported()
	{
		CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
		CompletableFuture<Void> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("close failed"));

		ArrayList<String> unfinished = DhsoShutdown.awaitAll(
				Arrays.asList(done, failed), Arrays.asList("overworld", "nether"),
				System.currentTimeMillis() + 1_000L);

		Assert.assertEquals(Collections.emptyList(), unfinished);
	}

	@Test
	public void stuckCloseTimesOutInsteadOfHanging()
	{
		CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
		CompletableFuture<Void> neverFinishes = new CompletableFuture<>();
		CompletableFuture<Void> alsoStuck = new CompletableFuture<>();

		long startMs = System.currentTimeMillis();
		List<String> unfinished = DhsoShutdown.awaitAll(
				Arrays.asList(done, neverFinishes, alsoStuck), Arrays.asList("overworld", "nether", "the_end"),
				startMs + 300L);
		long elapsedMs = System.currentTimeMillis() - startMs;

		Assert.assertEquals(Arrays.asList("nether", "the_end"), unfinished);
		// one shared deadline, not 300ms per stuck level
		Assert.assertTrue("waited [" + elapsedMs + "]ms", elapsedMs < 1_000L);
	}

	@Test
	public void shuttingDownFlag()
	{
		DhsoShutdown.markShuttingDown();
		Assert.assertTrue(DhsoShutdown.isShuttingDown());
		DhsoShutdown.clearShuttingDown();
		Assert.assertFalse(DhsoShutdown.isShuttingDown());
	}

}
