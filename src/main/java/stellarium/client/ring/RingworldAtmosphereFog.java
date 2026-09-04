package stellarium.client.ring;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldAirFog;
import stellarium.world.ring.RingworldDisplaySnapshot;

/** Registered only by ClientProxy. RenderFogEvent is after vanilla's normal-air branch. */
public final class RingworldAtmosphereFog {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderAirFog(EntityViewRenderEvent.RenderFogEvent event) {
        Entity entity = event.getEntity();
        if (entity == null || entity.world == null) return;
        RingworldDisplaySnapshot snapshot = RingworldRenderSnapshots.currentFor(entity.world,
                StellarScene.getScene(entity.world));
        if (snapshot == null || snapshot.atmosphereFade() == 1.0) return;

        // FogDensity cancellation, blindness, cloud fog, water and lava never
        // reach this normal-air event in the verified 0.6.8 setupFog path.
        int mode = GL11.glGetInteger(GL11.GL_FOG_MODE);
        if (mode == GL11.GL_LINEAR) {
            RingworldAirFog.LinearRange range = RingworldAirFog.linearRange(
                    GL11.glGetFloat(GL11.GL_FOG_START), GL11.glGetFloat(GL11.GL_FOG_END), snapshot.atmosphereFade());
            GlStateManager.setFogStart(range.start());
            GlStateManager.setFogEnd(range.end());
        } else if (mode == GL11.GL_EXP || mode == GL11.GL_EXP2) {
            // Preserve a preceding normal-air listener's mode and scale optical depth.
            GlStateManager.setFogDensity(RingworldAirFog.density(GL11.glGetFloat(GL11.GL_FOG_DENSITY),
                    snapshot.atmosphereFade(), mode == GL11.GL_EXP2));
        } else {
            throw new IllegalStateException("Unsupported normal-air fog mode: " + mode);
        }
        // These parameters belong to the following world draws. Immediate
        // restoration would undo the event; the next setupFog rebuilds its baseline.
    }
}
