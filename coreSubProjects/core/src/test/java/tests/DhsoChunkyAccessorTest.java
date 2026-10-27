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

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractChunkyAccessor;
import org.junit.Assert;
import org.junit.Test;

/** DHSO: verifies DH keeps retrying when Chunky isn't loaded yet instead of giving up after one failure. */
public class DhsoChunkyAccessorTest
{
	/** fails with Chunky's "not loaded" error for the first N binds */
	private static class FakeChunkyAccessor extends AbstractChunkyAccessor
	{
		int bindCallCount = 0;
		final int failuresBeforeSuccess;
		final long retryIntervalMs;
		RuntimeException errorToThrow = new IllegalStateException("Chunky is not loaded");

		FakeChunkyAccessor(int failuresBeforeSuccess, long retryIntervalMs)
		{
			this.failuresBeforeSuccess = failuresBeforeSuccess;
			this.retryIntervalMs = retryIntervalMs;
		}

		@Override
		protected long getBindRetryIntervalMs() { return this.retryIntervalMs; }

		@Override
		protected void bindOnGenerationProgressEvent()
		{
			this.bindCallCount++;
			if (this.bindCallCount <= this.failuresBeforeSuccess)
			{
				throw this.errorToThrow;
			}
		}

		@Override
		public String getModName() { return "chunky"; }
	}



	@Test
	public void bindSucceedsAfterChunkyFinishesLoading()
	{
		FakeChunkyAccessor accessor = new FakeChunkyAccessor(2, 0L);

		// none of these may throw, they run inside chunk load events
		accessor.tryRunFirstTimeSetup();
		Assert.assertFalse(accessor.isSetupFinished());
		accessor.tryRunFirstTimeSetup();
		Assert.assertFalse(accessor.isSetupFinished());
		accessor.tryRunFirstTimeSetup();
		Assert.assertTrue(accessor.isSetupFinished());

		// once bound, no further bind attempts
		accessor.tryRunFirstTimeSetup();
		Assert.assertEquals(3, accessor.bindCallCount);
	}

	@Test
	public void retriesAreRateLimited()
	{
		FakeChunkyAccessor accessor = new FakeChunkyAccessor(Integer.MAX_VALUE, 60_000L);

		for (int i = 0; i < 100; i++)
		{
			accessor.tryRunFirstTimeSetup();
		}

		// simulates 100 chunk loads within the retry interval, only the first should try binding
		Assert.assertEquals(1, accessor.bindCallCount);
		Assert.assertFalse(accessor.isSetupFinished());
	}

	@Test
	public void unexpectedErrorStopsRetryingWithoutThrowing()
	{
		FakeChunkyAccessor accessor = new FakeChunkyAccessor(Integer.MAX_VALUE, 0L);
		accessor.errorToThrow = new UnsupportedOperationException("unexpected");

		accessor.tryRunFirstTimeSetup();
		accessor.tryRunFirstTimeSetup();

		Assert.assertTrue(accessor.isSetupFinished());
		Assert.assertEquals(1, accessor.bindCallCount);
	}

}
