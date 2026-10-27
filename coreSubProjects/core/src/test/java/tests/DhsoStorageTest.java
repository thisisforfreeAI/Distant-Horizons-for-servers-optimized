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

import com.seibel.distanthorizons.core.dhso.DhsoConfig;
import com.seibel.distanthorizons.core.dhso.DhsoGenerationController;
import com.seibel.distanthorizons.core.dhso.DhsoGenerationController.EState;
import com.seibel.distanthorizons.core.dhso.DhsoLodEvictor;
import com.seibel.distanthorizons.core.dhso.DhsoLodEvictor.ProtectedPoint;
import com.seibel.distanthorizons.core.dhso.DhsoLodEvictor.SectionKey;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** DHSO: config validation, the generation throttle, and LOD eviction against a real DH database. */
public class DhsoStorageTest
{
	//========//
	// config //
	//========//

	@Test
	public void configFileIsCreatedWithDefaultsAndValidated() throws Exception
	{
		File folder = Files.createTempDirectory("dhso-config").toFile();

		DhsoConfig defaults = DhsoConfig.loadOrCreate(folder);
		File file = new File(folder, DhsoConfig.FILE_NAME);
		Assert.assertTrue(file.exists());
		Assert.assertEquals(20.0, defaults.globalBudgetGb, 0.0001);
		Assert.assertTrue(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains("globalBudgetGB"));

		// contradicting and out of range values are corrected
		Files.write(file.toPath(), ("[storage]\ncleanupStartPercent = 50\ncleanupTargetPercent = 80\nglobalBudgetGB = -5\n"
				+ "[eviction]\nmaxEvictableDetailLevel = 99\n"
				+ "[generation]\nmsptThrottleThreshold = 40\nmsptPauseThreshold = 30\n").getBytes(StandardCharsets.UTF_8));
		DhsoConfig fixed = DhsoConfig.loadOrCreate(folder);
		Assert.assertTrue(fixed.cleanupTargetPercent < fixed.cleanupStartPercent);
		Assert.assertEquals(0.1, fixed.globalBudgetGb, 0.0001);
		Assert.assertEquals(4, fixed.maxEvictableDetailLevel);
		Assert.assertTrue(fixed.msptPauseThreshold >= fixed.msptThrottleThreshold);
	}



	//====================//
	// generation control //
	//====================//

	@Test
	public void throttleUsesHysteresis()
	{
		DhsoConfig config = new DhsoConfig(); // recover 35, throttle 40, pause 48
		DhsoGenerationController controller = new DhsoGenerationController();

		Assert.assertEquals(EState.NORMAL, controller.update(config, 20, false, false, false));
		Assert.assertEquals(EState.THROTTLED, controller.update(config, 42, false, false, false));
		Assert.assertEquals(EState.CRITICAL, controller.update(config, 55, false, false, false));
		// eased off but still above the recover threshold: throttled, not normal
		Assert.assertEquals(EState.THROTTLED, controller.update(config, 38, false, false, false));
		Assert.assertEquals(EState.THROTTLED, controller.update(config, 36, false, false, false));
		Assert.assertEquals(EState.NORMAL, controller.update(config, 30, false, false, false));
		// from normal, 38 doesn't throttle
		Assert.assertEquals(EState.NORMAL, controller.update(config, 38, false, false, false));
	}

	@Test
	public void throttlePriorities()
	{
		DhsoConfig config = new DhsoConfig();
		DhsoGenerationController controller = new DhsoGenerationController();

		Assert.assertEquals(EState.NORMAL, controller.update(config, -1, false, false, false));
		Assert.assertEquals(EState.CRITICAL, controller.update(config, 10, true, false, false));
		Assert.assertEquals(EState.CRITICAL, controller.update(config, 10, false, true, false));
		Assert.assertEquals(EState.SHUTDOWN, controller.update(config, 10, false, false, true));

		controller.setManuallyPaused(true);
		Assert.assertEquals(EState.CRITICAL, controller.update(config, 10, false, false, false));
		Assert.assertFalse(controller.canStartGeneration(0, 4));
		controller.setManuallyPaused(false);

		controller.update(config, 42, false, false, false);
		Assert.assertTrue(controller.canStartGeneration(1, 4));
		Assert.assertFalse(controller.canStartGeneration(2, 4));
	}



	//==========//
	// eviction //
	//==========//

	@Test
	public void evictsFarthestFineDetailOnlyAndKeepsBusyAndProtectedSections() throws Exception
	{
		File dbFile = File.createTempFile("dhso-evict", ".sqlite");
		Assert.assertTrue(dbFile.delete());

		// the real repo runs DH's real migration scripts, so the schema matches a live server
		try (FullDataSourceV2Repo repo = new FullDataSourceV2Repo("jdbc:sqlite", dbFile))
		{
			Connection connection = repo.getConnection();

			// level 0 sections along +X, a player at block 0,0
			for (int x = 0; x < 50; x++)
			{
				insertSection(connection, 0, x, 0, 0);
			}
			// coarse ancestor far away, must never be evicted with maxEvictableDetailLevel = 1
			insertSection(connection, 2, 40, 0, 0);
			// level 1 section far away
			insertSection(connection, 1, 30, 0, 0);
			// far level 0 section still waiting to be applied to its parent
			insertSection(connection, 0, 60, 0, 1);
			insertChunkHash(connection, 49 * 4, 0);
			insertChunkHash(connection, 49 * 4 + 3, 3);
			insertChunkHash(connection, 0, 0);

			List<ProtectedPoint> player = Collections.singletonList(new ProtectedPoint(0, 0));
			List<SectionKey> candidates = DhsoLodEvictor.selectCandidates(connection, player, 1, 1000, 5);

			Assert.assertEquals(5, candidates.size());
			// level 1 at x=30 is 30*128 = 3840+ blocks out, the farthest level 0 at x=49 is ~3168
			Assert.assertEquals(1, candidates.get(0).detailLevel);
			Assert.assertEquals(49, candidates.get(1).posX);
			Assert.assertEquals(48, candidates.get(2).posX);
			for (SectionKey candidate : candidates)
			{
				Assert.assertTrue(candidate.detailLevel <= 1);
				Assert.assertNotEquals("busy section selected", 60, candidate.posX);
				Assert.assertTrue("protected section selected", candidate.distance > 1000);
			}

			int deleted = DhsoLodEvictor.deleteSections(connection, candidates);
			Assert.assertEquals(5, deleted);
			Assert.assertEquals(48L, count(connection, "SELECT COUNT(*) FROM FullData;"));
			Assert.assertEquals(1L, count(connection, "SELECT COUNT(*) FROM FullData WHERE DetailLevel = 2;"));
			Assert.assertEquals(1L, count(connection, "SELECT COUNT(*) FROM FullData WHERE PosX = 60;"));
			// chunk hashes for the evicted section x=49 (chunks 196-199) are gone, the one at 0,0 stays
			Assert.assertEquals(1L, count(connection, "SELECT COUNT(*) FROM ChunkHash;"));

			// everything left within 1000 blocks or busy: nothing more to evict at level 0 beyond the radius
			List<SectionKey> all = DhsoLodEvictor.selectCandidates(connection, player, 0, 1000, 1000);
			for (SectionKey candidate : all)
			{
				Assert.assertTrue(candidate.distance > 1000);
			}

			Assert.assertTrue(DhsoLodEvictor.getUsedBytes(connection) > 0);
		}
		finally
		{
			dbFile.delete();
			new File(dbFile.getPath() + "-wal").delete();
			new File(dbFile.getPath() + "-shm").delete();
		}
	}

	@Test
	public void noPlayersMeansEverythingFineIsEvictable()
	{
		double distance = DhsoLodEvictor.distanceToNearest(0, 5, 5, Collections.<ProtectedPoint>emptyList());
		Assert.assertEquals(Double.MAX_VALUE, distance, 0);

		// section 1,0 at level 0 spans blocks 64-127, center 96,32
		double near = DhsoLodEvictor.distanceToNearest(0, 1, 0, Arrays.asList(new ProtectedPoint(96, 32), new ProtectedPoint(10_000, 0)));
		Assert.assertEquals(0, near, 0.0001);
	}



	//=========//
	// helpers //
	//=========//

	private static void insertSection(Connection connection, int detailLevel, int posX, int posZ, int applyToParent) throws Exception
	{
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO FullData (DetailLevel, PosX, PosZ, MinY, DataChecksum, Data, ApplyToParent, LastModifiedUnixDateTime, CreatedUnixDateTime) " +
				"VALUES (?, ?, ?, 0, 0, ?, ?, 0, 0);"))
		{
			statement.setInt(1, detailLevel);
			statement.setInt(2, posX);
			statement.setInt(3, posZ);
			statement.setBytes(4, new byte[2048]);
			statement.setInt(5, applyToParent);
			statement.executeUpdate();
		}
	}

	private static void insertChunkHash(Connection connection, int chunkX, int chunkZ) throws Exception
	{
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO ChunkHash (ChunkPosX, ChunkPosZ, ChunkHash, LastModifiedUnixDateTime, CreatedUnixDateTime) VALUES (?, ?, 1, 0, 0);"))
		{
			statement.setInt(1, chunkX);
			statement.setInt(2, chunkZ);
			statement.executeUpdate();
		}
	}

	private static long count(Connection connection, String sql) throws Exception
	{
		try (Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery(sql))
		{
			resultSet.next();
			return resultSet.getLong(1);
		}
	}

}
