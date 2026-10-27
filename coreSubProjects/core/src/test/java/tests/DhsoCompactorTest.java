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

import com.seibel.distanthorizons.core.dhso.DhsoCompactor;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** DHSO: offline compaction of a real DH database and recovery from an interrupted swap. */
public class DhsoCompactorTest
{
	@Test
	public void compactsOnlyWhenRequestedAndKeepsAllRows() throws Exception
	{
		File folder = Files.createTempDirectory("dhso-compact").toFile();
		File dbFile = new File(folder, "DistantHorizons.sqlite");

		try (FullDataSourceV2Repo repo = new FullDataSourceV2Repo("jdbc:sqlite", dbFile))
		{
			Connection connection = repo.getConnection();
			try (PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO FullData (DetailLevel, PosX, PosZ, MinY, DataChecksum, Data, ApplyToParent, LastModifiedUnixDateTime, CreatedUnixDateTime) VALUES (0, ?, 0, 0, 0, ?, 0, 0, 0);"))
			{
				for (int x = 0; x < 400; x++)
				{
					statement.setInt(1, x);
					statement.setBytes(2, new byte[16 * 1024]);
					statement.executeUpdate();
				}
			}
			execute(connection, "DELETE FROM FullData WHERE PosX >= 40;");
		}

		long sizeBefore = dbFile.length() + new File(dbFile.getPath() + "-wal").length();

		// not requested: nothing happens
		DhsoCompactor.compactIfRequested(dbFile);
		Assert.assertTrue(dbFile.length() + new File(dbFile.getPath() + "-wal").length() >= sizeBefore - 4096);

		DhsoCompactor.requestCompaction(dbFile);
		Assert.assertTrue(DhsoCompactor.isCompactionRequested(dbFile));
		DhsoCompactor.compactIfRequested(dbFile);

		Assert.assertFalse("request is consumed", DhsoCompactor.isCompactionRequested(dbFile));
		Assert.assertTrue("file shrank from " + sizeBefore + " to " + dbFile.length(), dbFile.length() < sizeBefore / 4);
		Assert.assertFalse(new File(dbFile.getPath() + DhsoCompactor.OLD_SUFFIX).exists());
		Assert.assertFalse(new File(dbFile.getPath() + DhsoCompactor.TEMP_SUFFIX).exists());

		// DH can open the result normally and everything kept is there, in WAL mode
		try (FullDataSourceV2Repo repo = new FullDataSourceV2Repo("jdbc:sqlite", dbFile))
		{
			Connection connection = repo.getConnection();
			Assert.assertEquals(40L, queryLong(connection, "SELECT COUNT(*) FROM FullData;"));
			Assert.assertEquals("wal", queryString(connection, "PRAGMA journal_mode;").toLowerCase());
		}

		// nothing to gain the second time: skipped, unchanged
		long compactedSize = dbFile.length();
		DhsoCompactor.requestCompaction(dbFile);
		DhsoCompactor.compactIfRequested(dbFile);
		Assert.assertFalse(DhsoCompactor.isCompactionRequested(dbFile));
		Assert.assertTrue(Math.abs(dbFile.length() - compactedSize) <= 64 * 1024);
	}

	@Test
	public void interruptedSwapRestoresOriginal() throws Exception
	{
		File folder = Files.createTempDirectory("dhso-compact-crash").toFile();
		File dbFile = new File(folder, "DistantHorizons.sqlite");
		File oldFile = new File(dbFile.getPath() + DhsoCompactor.OLD_SUFFIX);
		File tempFile = new File(dbFile.getPath() + DhsoCompactor.TEMP_SUFFIX);

		// crash between the renames: only the renamed original and the copy exist
		Files.write(oldFile.toPath(), new byte[] { 1, 2, 3 });
		Files.write(tempFile.toPath(), new byte[] { 9 });
		DhsoCompactor.recoverInterruptedSwap(dbFile);
		Assert.assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(dbFile.toPath()));
		Assert.assertFalse(oldFile.exists());
		Assert.assertFalse(tempFile.exists());

		// crash after the swap: the new file stays, the old one is removed
		Files.write(oldFile.toPath(), new byte[] { 7 });
		DhsoCompactor.recoverInterruptedSwap(dbFile);
		Assert.assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(dbFile.toPath()));
		Assert.assertFalse(oldFile.exists());
	}



	private static void execute(Connection connection, String sql) throws Exception
	{
		try (Statement statement = connection.createStatement())
		{
			statement.execute(sql);
		}
	}

	private static long queryLong(Connection connection, String sql) throws Exception
	{
		try (Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery(sql))
		{
			resultSet.next();
			return resultSet.getLong(1);
		}
	}

	private static String queryString(Connection connection, String sql) throws Exception
	{
		try (Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery(sql))
		{
			resultSet.next();
			return resultSet.getString(1);
		}
	}
}
