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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shrinks a DH database file by rewriting it without its free pages. <br><br>
 *
 * Evicting LODs frees pages inside the file but never makes the file smaller,
 * so a database that grew past its budget before DHSO was installed stays that
 * size on disk. This rewrites it with {@code VACUUM INTO}. <br><br>
 *
 * Only runs before DH opens the database (on level load), and only when an
 * admin asked for it with {@code /dhso compact}, which leaves a request file next
 * to the database. <br><br>
 *
 * Safety: <br>
 * - The original file is only read until the copy has been written, passed
 *   {@code PRAGMA quick_check} and has the same number of LOD rows. <br>
 * - Needs free disk for the copy, otherwise it's skipped and nothing changes. <br>
 * - The swap is two renames. If the server dies between them, the next start
 *   puts the original back ({@link #recoverInterruptedSwap}). <br>
 * - Any failure leaves the original database in place and removes the request,
 *   so a failing compaction can't block startup twice.
 */
public class DhsoCompactor
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	public static final String REQUEST_SUFFIX = ".dhso-compact";
	public static final String TEMP_SUFFIX = ".dhso-compacting";
	public static final String OLD_SUFFIX = ".dhso-old";

	/** skip when less than this fraction of the file is free pages, there's nothing worth gaining */
	public static final double MIN_FREE_FRACTION = 0.10;
	/** extra free disk kept on top of the copy's size */
	public static final long DISK_MARGIN_BYTES = 512L * 1024 * 1024;



	public static File getRequestFile(File dbFile) { return new File(dbFile.getPath() + REQUEST_SUFFIX); }
	private static File getTempFile(File dbFile) { return new File(dbFile.getPath() + TEMP_SUFFIX); }
	private static File getOldFile(File dbFile) { return new File(dbFile.getPath() + OLD_SUFFIX); }

	/** marks the database for compaction the next time it's opened */
	public static void requestCompaction(File dbFile) throws IOException
	{
		File requestFile = getRequestFile(dbFile);
		if (!requestFile.exists() && !requestFile.createNewFile())
		{
			throw new IOException("Unable to create [" + requestFile + "].");
		}
	}

	public static boolean isCompactionRequested(File dbFile) { return getRequestFile(dbFile).exists(); }



	/**
	 * Called right before DH opens a database. Never throws: on any problem the
	 * original database is left as it was and DH opens it normally.
	 */
	public static void compactIfRequested(File dbFile)
	{
		try
		{
			recoverInterruptedSwap(dbFile);
		}
		catch (Throwable e)
		{
			LOGGER.error("[DHSO] Unable to clean up after an interrupted compaction of [" + dbFile + "]: [" + e.getMessage() + "]. Check that file and its [" + OLD_SUFFIX + "] / [" + TEMP_SUFFIX + "] copies manually.", e);
			return;
		}

		File requestFile = getRequestFile(dbFile);
		if (!requestFile.exists())
		{
			return;
		}

		try
		{
			LOGGER.info("[DHSO] Compaction of [" + dbFile + "] was requested, starting. Server startup waits for this; it can take several minutes for large databases.");
			String result = compact(dbFile);
			LOGGER.info("[DHSO] " + result);
		}
		catch (Throwable e)
		{
			LOGGER.error("[DHSO] Compaction of [" + dbFile + "] failed: [" + e.getMessage() + "]. The original database is unchanged and will be used.", e);
			deleteQuietly(getTempFile(dbFile));
		}
		finally
		{
			// one attempt per request
			deleteQuietly(requestFile);
		}
	}

	/** Finishes or rolls back a swap that was cut off by a crash. Safe to call when nothing happened. */
	public static void recoverInterruptedSwap(File dbFile) throws IOException
	{
		File oldFile = getOldFile(dbFile);
		File tempFile = getTempFile(dbFile);

		if (oldFile.exists())
		{
			if (!dbFile.exists())
			{
				// died between the two renames: put the original back
				LOGGER.warn("[DHSO] Found an interrupted compaction for [" + dbFile + "], restoring the original database.");
				Files.move(oldFile.toPath(), dbFile.toPath());
			}
			else
			{
				// died after the verified copy was swapped in, before the old file was deleted
				LOGGER.info("[DHSO] Removing the previous database left by a finished compaction: [" + oldFile + "].");
				Files.delete(oldFile.toPath());
			}
		}

		// an unfinished copy is never used
		deleteQuietly(tempFile);
		deleteQuietly(new File(tempFile.getPath() + "-journal"));
	}

	/**
	 * Rewrites the database without free pages. The database must not be open. <br>
	 * @return a one line summary
	 * @throws Exception if anything went wrong; the original file is then untouched
	 */
	public static String compact(File dbFile) throws Exception
	{
		if (!dbFile.exists())
		{
			return "Nothing to compact, [" + dbFile + "] doesn't exist.";
		}

		// needed by Forge to load the Java database connection
		Class.forName("org.sqlite.JDBC");

		long startMs = System.currentTimeMillis();
		File tempFile = getTempFile(dbFile);
		File oldFile = getOldFile(dbFile);
		deleteQuietly(tempFile);

		long sizeBeforeBytes;
		long rowCount;
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getPath()))
		{
			// bring everything from the WAL into the main file first
			execute(connection, "PRAGMA wal_checkpoint(TRUNCATE);");

			long pageSize = queryLong(connection, "PRAGMA page_size;");
			long pageCount = queryLong(connection, "PRAGMA page_count;");
			long freePages = queryLong(connection, "PRAGMA freelist_count;");
			sizeBeforeBytes = pageCount * pageSize;
			long usedBytes = (pageCount - freePages) * pageSize;

			if (pageCount == 0 || freePages / (double) pageCount < MIN_FREE_FRACTION)
			{
				return "Compaction of [" + dbFile + "] skipped, only " + DhsoStorageManager.formatGb(freePages * pageSize) + " of " + DhsoStorageManager.formatGb(sizeBeforeBytes) + " is free space. Run /dhso cleanup first to free space inside the file.";
			}

			long freeDisk = dbFile.getAbsoluteFile().getParentFile().getUsableSpace();
			long neededBytes = (long) (usedBytes * 1.1) + DISK_MARGIN_BYTES;
			if (freeDisk < neededBytes)
			{
				return "Compaction of [" + dbFile + "] skipped, it needs about " + DhsoStorageManager.formatGb(neededBytes) + " free disk for the copy but only " + DhsoStorageManager.formatGb(freeDisk) + " is free. Nothing was changed.";
			}

			rowCount = queryLong(connection, "SELECT COUNT(*) FROM FullData;");

			// the path is ours, but escape quotes anyway
			execute(connection, "VACUUM INTO '" + tempFile.getPath().replace("'", "''") + "';");
		}

		// verify the copy before touching the original
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempFile.getPath()))
		{
			String check = queryString(connection, "PRAGMA quick_check;");
			if (!"ok".equalsIgnoreCase(check))
			{
				throw new SQLException("The compacted copy failed its integrity check: [" + check + "].");
			}
			long copyRowCount = queryLong(connection, "SELECT COUNT(*) FROM FullData;");
			if (copyRowCount != rowCount)
			{
				throw new SQLException("The compacted copy has [" + copyRowCount + "] LOD rows, the original has [" + rowCount + "].");
			}
			// VACUUM INTO writes a rollback journal database, DH expects WAL
			execute(connection, "PRAGMA journal_mode = WAL;");
		}

		// swap, the original is only renamed until the new file is in place
		Files.move(dbFile.toPath(), oldFile.toPath());
		try
		{
			Files.move(tempFile.toPath(), dbFile.toPath());
		}
		catch (IOException e)
		{
			Files.move(oldFile.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			throw e;
		}
		deleteQuietly(new File(dbFile.getPath() + "-wal"));
		deleteQuietly(new File(dbFile.getPath() + "-shm"));
		Files.delete(oldFile.toPath());

		long sizeAfterBytes = dbFile.length();
		return "Compacted [" + dbFile + "] from " + DhsoStorageManager.formatGb(sizeBeforeBytes) + " to " + DhsoStorageManager.formatGb(sizeAfterBytes)
				+ " (" + rowCount + " LOD rows kept) in " + ((System.currentTimeMillis() - startMs) / 1000) + " s.";
	}



	//=========//
	// helpers //
	//=========//

	private static void execute(Connection connection, String sql) throws SQLException
	{
		try (Statement statement = connection.createStatement())
		{
			statement.execute(sql);
		}
	}

	private static long queryLong(Connection connection, String sql) throws SQLException
	{
		try (Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery(sql))
		{
			return resultSet.next() ? resultSet.getLong(1) : 0;
		}
	}

	private static String queryString(Connection connection, String sql) throws SQLException
	{
		try (Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery(sql))
		{
			return resultSet.next() ? resultSet.getString(1) : null;
		}
	}

	private static void deleteQuietly(File file)
	{
		try
		{
			Files.deleteIfExists(file.toPath());
		}
		catch (IOException e)
		{
			LOGGER.warn("[DHSO] Unable to delete [" + file + "]: [" + e.getMessage() + "].");
		}
	}

}
