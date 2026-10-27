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

package com.seibel.distanthorizons.common.commands;

#if MC_VER > MC_1_12_2
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.seibel.distanthorizons.core.dhso.DhsoStorageManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import static net.minecraft.commands.Commands.literal;

/**
 * DHSO administrator commands, registered as {@code /dhso} with the same
 * owner-level permission check as {@code /dh}. <br>
 * Nothing here can be triggered by a client request; destructive work
 * (cleanup run) only deletes rebuildable LOD cache data.
 */
public class DhsoCommand extends AbstractDhCommand
{
	@Override
	public LiteralArgumentBuilder<CommandSourceStack> buildCommand()
	{
		return literal("dhso")
				.executes(c -> this.sendSuccessResponse(c, getHelpString(), false))
				.then(literal("help").executes(c -> this.sendSuccessResponse(c, getHelpString(), false)))
				.then(literal("status").executes(c -> this.withManager(c, manager -> this.sendSuccessResponse(c, manager.getStatusString(), false))))
				.then(literal("storage").executes(c -> this.withManager(c, manager -> this.sendSuccessResponse(c, manager.getStorageString(), false))))
				.then(literal("pause").executes(c -> this.withManager(c, manager ->
				{
					manager.controller.setManuallyPaused(true);
					return this.sendSuccessResponse(c, "DH world generation paused. Cached LODs are still sent to players. Use /dhso resume to continue.", true);
				})))
				.then(literal("resume").executes(c -> this.withManager(c, manager ->
				{
					manager.controller.setManuallyPaused(false);
					return this.sendSuccessResponse(c, "DH world generation resumed (subject to lag, disk and storage limits, see /dhso status).", true);
				})))
				.then(literal("compact").executes(c -> this.withManager(c, manager -> this.sendSuccessResponse(c, manager.requestCompaction(), true))))
				.then(literal("cleanup")
						.then(literal("dryrun").executes(c -> this.runCleanup(c, true)))
						.then(literal("run").executes(c -> this.runCleanup(c, false))));
	}

	private int runCleanup(CommandContext<CommandSourceStack> commandContext, boolean dryRun)
	{
		return this.withManager(commandContext, manager ->
		{
			this.sendSuccessResponse(commandContext, (dryRun ? "Estimating" : "Running") + " LOD cache cleanup in the background, results will be posted here and in the log...", !dryRun);

			MinecraftServer server = commandContext.getSource().getServer();
			manager.requestCleanup(dryRun).whenComplete((summary, throwable) ->
			{
				// reply on the server thread
				server.execute(() ->
				{
					if (throwable != null)
					{
						this.sendFailureResponse(commandContext, "Cleanup failed: " + throwable.getMessage() + " (no LOD data was changed by the failed batch, see log)");
					}
					else
					{
						this.sendSuccessResponse(commandContext, (dryRun ? "Dry run: " : "Cleanup done: ") + summary, !dryRun);
					}
				});
			});
			return 1;
		});
	}

	private interface IManagerAction { int run(DhsoStorageManager manager); }

	private int withManager(CommandContext<CommandSourceStack> commandContext, IManagerAction action)
	{
		DhsoStorageManager manager = DhsoStorageManager.getCurrent();
		if (manager == null)
		{
			return this.sendFailureResponse(commandContext, "DHSO storage management only runs on a dedicated server with a loaded world.");
		}
		return action.run(manager);
	}

	public static String getHelpString()
	{
		return "Distant Horizons Server Optimized commands:\n"
				+ "§e/dhso status§r - Generation throttle state, budgets, last cleanup and last error.\n"
				+ "§e/dhso storage§r - LOD database size per dimension, WAL size and free disk.\n"
				+ "§e/dhso pause§r / §e/dhso resume§r - Pause or resume DH world generation.\n"
				+ "§e/dhso cleanup dryrun§r - Estimate what a cleanup would evict, deletes nothing.\n"
				+ "§e/dhso cleanup run§r - Evict rebuildable LOD data down to the cleanup target now.\n"
				+ "§e/dhso compact§r - Shrink the database files on the next restart (run a cleanup first).\n"
				+ "Settings: config/DistantHorizonsServerOptimized.toml (restart to apply).";
	}

}
#endif
