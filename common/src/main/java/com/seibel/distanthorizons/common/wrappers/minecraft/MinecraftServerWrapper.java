package com.seibel.distanthorizons.common.wrappers.minecraft;

import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import net.minecraft.server.dedicated.DedicatedServer;
import org.jetbrains.annotations.Nullable;

#if MC_VER > MC_1_12_2
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
#else
import net.minecraft.world.WorldServer;
#if MC_VER <= MC_1_7_10
import net.minecraftforge.common.DimensionManager;
#endif
#endif

#if  MC_VER <= MC_1_12_2
#elif  MC_VER <= MC_1_21_10
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

#if  MC_VER > MC_1_19_2
import net.minecraft.core.registries.Registries;
#elif MC_VER > MC_1_12_2
import net.minecraft.core.Registry;
#endif

import java.io.File;

public class MinecraftServerWrapper extends AbstractMinecraftSharedWrapper
{
	public static final MinecraftServerWrapper INSTANCE = new MinecraftServerWrapper();
	
	/** set during server startup */
	@Nullable
	public DedicatedServer dedicatedServer = null;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	private MinecraftServerWrapper() { }
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public boolean isDedicatedServer() { return true; }
	
	@Override
	public File getInstallationDirectory()
	{
		if (this.dedicatedServer == null)
		{
			throw new IllegalStateException("Trying to get Installation Direction before dedicated server completed initialization!");
		}
		
		#if MC_VER <= MC_1_7_10
		return new File("."); // equivalent to new MC's "server.getDataDirectory()"
		#elif MC_VER <= MC_1_12_2
		return this.dedicatedServer.getDataDirectory();
		#elif MC_VER < MC_1_21_1
		return this.dedicatedServer.getServerDirectory();
		#else
		return this.dedicatedServer.getServerDirectory().toFile();
		#endif
	}
	
	/** DHSO: used by the adaptive generation throttle */
	@Override
	public double getRecentAverageTickMs()
	{
		DedicatedServer server = this.dedicatedServer;
		if (server == null)
		{
			return -1;
		}
		
		#if MC_VER <= MC_1_20_2
		// not implemented for older MC versions
		return -1;
		#else
		long[] tickTimesNanos = server.getTickTimesNanos();
		if (tickTimesNanos == null || tickTimesNanos.length == 0)
		{
			return -1;
		}
		
		long totalNanos = 0;
		int sampleCount = 0;
		for (long tickTimeNanos : tickTimesNanos)
		{
			// unfilled slots are 0 right after startup
			if (tickTimeNanos > 0)
			{
				totalNanos += tickTimeNanos;
				sampleCount++;
			}
		}
		return (sampleCount == 0) ? -1 : (totalNanos / (double) sampleCount) / 1_000_000.0;
		#endif
	}
	
		@Override
	public int getPlayerCount() 
	{
		if (this.dedicatedServer == null)
		{
			throw new IllegalStateException("Trying to get player count before dedicated server completed initialization!");
		}
		
		#if MC_VER <= MC_1_12_2
		return this.dedicatedServer.getCurrentPlayerCount();
		#else
		return this.dedicatedServer.getPlayerCount();
		#endif
	}
	
	
	@Nullable
	@Override
	public IServerLevelWrapper getLevelWrapper(String dimensionResourceLocation)
	{
		if (this.dedicatedServer == null)
		{
			throw new IllegalStateException("Trying to get the server mcLevel before dedicated server completed initialization!");
		}
		
		#if MC_VER <= MC_1_12_2
		Integer dimensionKey = this.deserializeDimensionResourceKey(dimensionResourceLocation);
		if (dimensionKey == null)
		{
			return null;
		}
		
		WorldServer mcLevel;
		{
			#if MC_VER <= MC_1_7_10
			mcLevel = DimensionManager.getWorld(dimensionKey);
			#else
			mcLevel = dedicatedServer.getWorld(dimensionKey);
			#endif
		}
		
		#else
		ResourceKey<Level> dimensionKey = this.deserializeDimensionResourceKey(dimensionResourceLocation);
		ServerLevel mcLevel = dedicatedServer.getLevel(dimensionKey);
		#endif
		return ServerLevelWrapper.getWrapper(mcLevel);
	}
	
}
