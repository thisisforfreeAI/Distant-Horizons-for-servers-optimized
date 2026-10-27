package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.core.dhso.DhsoShutdown;
import com.seibel.distanthorizons.core.file.structure.LocalSaveStructure;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerState;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerStateManager;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractDhServerWorld<TDhServerLevel extends AbstractDhServerLevel> extends AbstractDhWorld implements IDhServerWorld
{
	/** 
	 * Concurrent since levels can be added/remove while other processing is happening.
	 * (Otherwise we may need to just put the logic in a lock.
	 */
	protected final ConcurrentHashMap<ILevelWrapper, TDhServerLevel> dhLevelByLevelWrapper = new ConcurrentHashMap<>();
	public final LocalSaveStructure saveStructure = new LocalSaveStructure();
	
	private final ServerPlayerStateManager serverPlayerStateManager;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public AbstractDhServerWorld(EWorldEnvironment worldEnvironment)
	{
		super(worldEnvironment);
		this.serverPlayerStateManager = new ServerPlayerStateManager();
	}
	
	
	//=================//
	// player handling //
	//=================//
	
	@Override
	public ServerPlayerStateManager getServerPlayerStateManager()
	{
		return this.serverPlayerStateManager;
	}
	
	@Override
	public void addPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState playerState = this.serverPlayerStateManager.registerJoinedPlayer(serverPlayer);
		AbstractDhServerLevel serverLevel = (AbstractDhServerLevel) this.getOrLoadServerLevel(serverPlayer.getLevel());
		if (serverLevel == null)
		{
			return;
		}
		
		serverLevel.addPlayer(serverPlayer);
		
		Iterator<TDhServerLevel> it = this.dhLevelByLevelWrapper.values().stream().distinct().iterator();
		while (it.hasNext())
		{
			TDhServerLevel level = it.next();
			level.registerNetworkHandlers(playerState);
		}
		
		this.serverPlayerStateManager.handlePluginMessagesFromQueue(playerState);
	}
	
	@Override
	public void removePlayer(IServerPlayerWrapper serverPlayer)
	{
		IServerLevelWrapper playerLevel = serverPlayer.getLevel();
		if (playerLevel == null)
		{
			// can happen during server shutdown
			return;
		}
		
		TDhServerLevel serverLevel = this.getLevel(playerLevel);
		if (serverLevel == null)
		{
			// can happen during server shutdown
			return;
		}
		
		serverLevel.removePlayer(serverPlayer);
		this.serverPlayerStateManager.unregisterLeftPlayer(serverPlayer);
		
		// If player's left, session is already closed
	}
	
	@Override
	public void changePlayerLevel(IServerPlayerWrapper player, IServerLevelWrapper originLevel, IServerLevelWrapper destinationLevel)
	{
		this.getLevel(destinationLevel).addPlayer(player);
		this.getLevel(originLevel).removePlayer(player);
	}
	
	
	
	//================//
	// level handling //
	//================//
	
	@Override
	public TDhServerLevel getLevel(@NotNull ILevelWrapper wrapper) { return this.dhLevelByLevelWrapper.get(wrapper); }
	@Override
	public Iterable<? extends IDhLevel> getAllLoadedLevels() 
	{
		// hash set wrapper is used to filter out duplicate levels,
		// which can happen when on a singleplayer world and both a server/client level wrapper
		// are active for the same dimension
		return new HashSet<>(this.dhLevelByLevelWrapper.values()); 
	}
	@Override
	public int getLoadedLevelCount() { return this.dhLevelByLevelWrapper.size(); }
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public void close()
	{
		// needed to close player event handlers
		this.serverPlayerStateManager.close();
		
		
		// async level shutdown since level databases can take a moment
		ArrayList<CompletableFuture<Void>> closeFutures = new ArrayList<>();
		ArrayList<String> closeLevelNames = new ArrayList<>();
		long closeStartMs = System.currentTimeMillis();
		for (TDhServerLevel level : this.dhLevelByLevelWrapper.values())
		{
			// level wrapper shouldn't be null, but just in case
			IServerLevelWrapper serverLevelWrapper = level.getServerLevelWrapper();
			if (serverLevelWrapper != null)
			{
				serverLevelWrapper.onUnload();
				ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(serverLevelWrapper));
			}
			
			
			// close levels asynchronously to speed up
			// shutdown on servers with a lot of levels
			CompletableFuture<Void> closeFuture = new CompletableFuture<>();
			String levelName = String.valueOf(serverLevelWrapper != null ? serverLevelWrapper.getDhIdentifier() : level);
			Thread closeThread = new Thread(() ->
			{
				try
				{
					level.close();
					closeFuture.complete(null);
				}
				catch (Throwable e)
				{
					LOGGER.error("[DHSO] Error closing DH level [" + levelName + "]: [" + e.getMessage() + "].", e);
					closeFuture.completeExceptionally(e);
				}
			}, DhsoShutdown.LEVEL_SHUTDOWN_THREAD_NAME + " [" + levelName + "]");
			// DHSO: daemon so a stuck close can never keep the JVM alive after the server stops
			closeThread.setDaemon(true);
			closeThread.start();
			closeFutures.add(closeFuture);
			closeLevelNames.add(levelName);
		}
		
		// DHSO: wait for the levels to finish closing, but not forever.
		// This runs on the server thread before Minecraft's final world save,
		// so an unbounded wait here would block that save.
		ArrayList<String> unfinishedLevelNames = DhsoShutdown.awaitAll(closeFutures, closeLevelNames, closeStartMs + (DhsoShutdown.SHUTDOWN_TIMEOUT_SECONDS * 1_000L));
		
		long closeDurationMs = System.currentTimeMillis() - closeStartMs;
		if (unfinishedLevelNames.isEmpty())
		{
			LOGGER.info("[DHSO] Closed [" + closeFutures.size() + "] DH level(s) in [" + closeDurationMs + "]ms.");
		}
		else
		{
			LOGGER.error("[DHSO] [" + unfinishedLevelNames.size() + "] DH level(s) didn't finish closing within [" + DhsoShutdown.SHUTDOWN_TIMEOUT_SECONDS + "] seconds: " + unfinishedLevelNames + ". "
					+ "Continuing the server shutdown so the world save isn't blocked. Their database writes may not have finished; "
					+ "the LOD cache can be regenerated, but please report this with the thread dump below.");
			DhsoShutdown.logDhThreadDump("level close timeout");
		}
		
		this.dhLevelByLevelWrapper.clear();
		LOGGER.info("Closed DhWorld of type [" + this.environment + "].");
	}
	
}
