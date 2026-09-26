package stellarium.client;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import stellarium.client.ring.RingworldBoardRenderer;
import stellarium.client.ring.RingworldSpatialAirCompositor;
import stellarium.client.ring.SSCloudRenderer;
import stellarium.world.ring.RingworldClockClientState;
import stellarium.client.ring.TerrainPreviewClient;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class StellarClientFMLHook {
    @SubscribeEvent public void onClientTick(TickEvent.ClientTickEvent event) {
        if(event.phase==TickEvent.Phase.END) TerrainPreviewClient.tick();
    }
	@SubscribeEvent
	public void onWorldUnload(WorldEvent.Unload event) {
		World unloading = event.getWorld();
		if (!unloading.isRemote) return;
		Minecraft minecraft = Minecraft.getMinecraft();
		minecraft.addScheduledTask(() -> {
			// A delayed unload from A must not tear down the current B world's program.
			if (minecraft.world == null || minecraft.world == unloading)
				RingworldBoardRenderer.dispose();
			if (minecraft.world == null || minecraft.world == unloading)
				RingworldSpatialAirCompositor.dispose();
			if (minecraft.world == null || minecraft.world == unloading)
				SSCloudRenderer.dispose();
		});
	}
	
	public StellarClientFMLHook() { }
	
	@SubscribeEvent
	public void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
		RingworldClockClientState.onClientConnect(event.getHandler(), event.getManager());
	}

	@SubscribeEvent
	public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
		RingworldClockClientState.onClientDisconnect(event.getHandler(), event.getManager());
	}

}
