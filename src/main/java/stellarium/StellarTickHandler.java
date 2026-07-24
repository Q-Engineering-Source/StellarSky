package stellarium;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import stellarium.time.StellarSkyTime;
import stellarium.world.StellarScene;

public class StellarTickHandler {
	@SubscribeEvent
	public void tickStart(TickEvent.ClientTickEvent e) {
		if(e.phase == TickEvent.Phase.START){
			World world = StellarSky.PROXY.getDefWorld();
			
			if(world != null) {				
				StellarScene dimManager = StellarScene.getScene(world);
				if(dimManager != null) {
					dimManager.update(world, world.getWorldTime(), world.getTotalWorldTime());
					StellarSky.PROXY.updateTick();
				}
			}
		}
	}

	@SubscribeEvent
	public void tickStart(TickEvent.ServerTickEvent e) {
		if(e.phase == TickEvent.Phase.START) {
			MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
			// Celestial state is advanced by each world's WorldTick START
			// handler, so dimensions do not inherit the overworld date.
		}
	}

	@SubscribeEvent
	public void tickStart(TickEvent.WorldTickEvent e) {
		if(e.phase == TickEvent.Phase.START && e.side == Side.SERVER){
			MinecraftServer server = e.world.getMinecraftServer();
			World defWorld = server.getEntityWorld();

			// Vanilla skips WorldServer#setWorldTime entirely when this gamerule is
			// false. Time control must still own only the daylight clock in that
			// case, otherwise system-time correction can never run.
			if(!e.world.getGameRules().getBoolean("doDaylightCycle")
					&& (StellarSkyTime.isSystemTimeSyncEnabled(e.world)
							|| StellarSkyTime.getMultiplier(e.world) != 1.0)) {
				e.world.setWorldTime(StellarSkyTime.nextWorldTime(e.world, e.world.getWorldTime()));
			}
			
			StellarScene dimManager = StellarScene.getScene(e.world);
			if(dimManager != null)
				dimManager.update(e.world, e.world.getWorldTime(), defWorld.getTotalWorldTime());
		}
	}
}
