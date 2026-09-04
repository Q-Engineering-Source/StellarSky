package stellarium.client;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent.KeyInputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import stellarium.client.ring.RingworldBoardRenderer;
import stellarium.world.ring.RingworldClockClientState;

public class StellarClientFMLHook {
	@SubscribeEvent
	public void onWorldUnload(WorldEvent.Unload event) {
		World unloading = event.getWorld();
		if (!unloading.isRemote) return;
		Minecraft minecraft = Minecraft.getMinecraft();
		minecraft.addScheduledTask(() -> {
			// A delayed unload from A must not tear down the current B world's program.
			if (minecraft.world == null || minecraft.world == unloading)
				RingworldBoardRenderer.dispose();
		});
	}
	
	public StellarClientFMLHook() { }
	
	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		
	}
	
	@SubscribeEvent
	public void onKeyInput(KeyInputEvent event) {
		
	}

	@SubscribeEvent
	public void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
		RingworldClockClientState.onClientConnect(event.getHandler(), event.getManager());
	}

	@SubscribeEvent
	public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
		RingworldClockClientState.onClientDisconnect(event.getHandler(), event.getManager());
	}

}
