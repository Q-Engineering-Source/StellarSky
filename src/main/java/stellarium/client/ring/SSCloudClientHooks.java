package stellarium.client.ring;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Vanilla cloud suppression is global while installed; SS cloud visibility has its own option. */
public final class SSCloudClientHooks {
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        // Runtime policy only: never write options.txt every tick.
        if (minecraft.gameSettings != null) minecraft.gameSettings.clouds = 0;
        if (event.phase == TickEvent.Phase.END && minecraft.world != null && !minecraft.isGamePaused())
            SSCloudRenderer.onClientTick();
    }
}
