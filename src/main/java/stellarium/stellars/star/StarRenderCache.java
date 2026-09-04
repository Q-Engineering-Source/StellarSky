package stellarium.stellars.star;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import stellarapi.api.lib.config.IConfigHandler;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.lib.math.Vector3;
import stellarapi.api.optics.Wavelength;
import stellarium.client.ClientSettings;
import stellarium.render.stellars.layer.IObjRenderCache;
import stellarium.render.stellars.layer.LayerRHelper;
import stellarium.stellars.OpticsHelper;
import stellarium.stellars.render.ICelestialObjectRenderer;
import stellarium.stellars.util.StarColor;
import stellarium.view.ViewerInfo;

public class StarRenderCache implements IObjRenderCache<BgStar, IConfigHandler> {
	protected SpCoord appPos = new SpCoord();
	protected Vector3 pos = new Vector3();
	protected float red, green, blue;
	protected float intrinsicRed, intrinsicGreen, intrinsicBlue;
	protected Vector3 ref = new Vector3();

	@Override
	public void updateSettings(ClientSettings settings, IConfigHandler config, BgStar star) { }

	@Override
	public void updateCache(BgStar object, ViewerInfo info) {
		ref.set(object.pos);
		info.coordinate.getProjectionToGround().transform(this.ref);
		pos.set(this.ref);
		pos.scale(LayerRHelper.DEEP_DEPTH);

		// TODO AA Mark object size to some buffer
		StarColor starColor = StarColor.getColor(object.B_V);

		double length = this.ref.size();
		double sinAltitude = length > 0.0 ? this.ref.getZ() / length : 1.0;
		float twinkle = info.sky.getSeeing(Wavelength.V) > 0.0
				? OpticsHelper.twinkleBrightness(sinAltitude) : 1.0f;
		double intrinsicAlpha = OpticsHelper.getBrightnessFromMag(object.mag);
		this.intrinsicRed = (float) (intrinsicAlpha * starColor.r / 255.0);
		this.intrinsicGreen = (float) (intrinsicAlpha * starColor.g / 255.0);
		this.intrinsicBlue = (float) (intrinsicAlpha * starColor.b / 255.0);
		this.red = (float) (intrinsicAlpha * twinkle * starColor.r / 255.0);
		this.green = (float) (intrinsicAlpha * twinkle * starColor.g / 255.0);
		this.blue = (float) (intrinsicAlpha * twinkle * starColor.b / 255.0);
	}

	@SideOnly(Side.CLIENT)
	@Override
	public ICelestialObjectRenderer getRenderer() {
		return StarRenderer.INSTANCE;
	}

}
