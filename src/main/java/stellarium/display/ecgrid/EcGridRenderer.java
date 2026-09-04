package stellarium.display.ecgrid;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import stellarium.display.DisplayRenderInfo;
import stellarium.display.IDisplayRenderer;
import stellarium.render.stellars.layer.LayerRHelper;

@SideOnly(Side.CLIENT)
public class EcGridRenderer implements IDisplayRenderer<EcGridCache> {

	@Override
	public void render(DisplayRenderInfo info, EcGridCache cache) {
		if(!cache.enabled || info.isPostCelesitals)
			return;
		
		GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
		GlStateManager.disableTexture2D();
		GlStateManager.pushMatrix();
		try {
			GlStateManager.scale(LayerRHelper.DEEP_DEPTH, LayerRHelper.DEEP_DEPTH, LayerRHelper.DEEP_DEPTH);

			if(cache.gridEnabled) {
				GlStateManager.glLineWidth(2.0f);
				GlStateManager.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);

				info.builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

				for(int longc=0; longc<cache.longn; longc++){
					for(int latc=0; latc<cache.latn; latc++){
						int longcd=(longc+1)%cache.longn;

						addGridVertex(info, cache, longc, latc);
						addGridVertex(info, cache, longcd, latc);
						addGridVertex(info, cache, longcd, latc + 1);
						addGridVertex(info, cache, longc, latc + 1);
					}
				}

				info.tessellator.draw();
			}

			if(cache.eclipticEnabled) {
				GlStateManager.shadeModel(GL11.GL_SMOOTH);
				GlStateManager.glLineWidth(5.0f);
				GlStateManager.color(1.0f, 1.0f, 0, 2.0f * cache.brightness);
				info.builder.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);

				for(int longc=0; longc<cache.longn; longc++){
					int longcd=(longc+1)%cache.longn;
					info.posWithAtmosphereRefraction(cache.rawEcliptic[longc], cache.ecliptic[longc]).endVertex();
					info.posWithAtmosphereRefraction(cache.rawEcliptic[longcd], cache.ecliptic[longcd]).endVertex();
				}

				info.tessellator.draw();
			}
		} finally {
			GlStateManager.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
			GlStateManager.glLineWidth(1.0f);
			GlStateManager.shadeModel(GL11.GL_FLAT);
			GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
			GlStateManager.enableTexture2D();
			GlStateManager.popMatrix();
		}
	}

	private static void addGridVertex(DisplayRenderInfo info, EcGridCache cache, int longitude, int latitude) {
		info.posWithAtmosphereRefraction(cache.rawDisplayvec[longitude][latitude], cache.displayvec[longitude][latitude]);
		info.builder.color((float)cache.colorvec[longitude][latitude].getX(),
				(float)cache.colorvec[longitude][latitude].getY(),
				(float)cache.colorvec[longitude][latitude].getZ(), cache.brightness);
		info.builder.endVertex();
	}

}
