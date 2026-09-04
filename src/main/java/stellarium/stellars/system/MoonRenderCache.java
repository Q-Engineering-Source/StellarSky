package stellarium.stellars.system;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.lib.math.Vector3;
import stellarapi.api.optics.Wavelength;
import stellarium.client.ClientSettings;
import stellarium.render.stellars.layer.IObjRenderCache;
import stellarium.stellars.OpticsHelper;
import stellarium.stellars.render.ICelestialObjectRenderer;
import stellarium.util.math.Allocator;
import stellarium.view.ViewerInfo;

public class MoonRenderCache implements IObjRenderCache<Moon, SolarSystemClientSettings> {
	
	protected boolean shouldRenderDominate, shouldRender;
	private boolean renderMoon = true;
	
	protected SpCoord appCoord;
	protected Vector3 appPos;
	protected int latn, longn;

	protected Vector3 pos[][];
	protected Vector3 normal[][];
	protected float surfBr[][];

	protected float domination;
	protected float spriteRed, spriteGreen, spriteBlue;
	protected float vacuumSpriteRed, vacuumSpriteGreen, vacuumSpriteBlue;
	protected int phaseIndex;

	protected Vector3 buf = new Vector3();
	protected float size;

	@Override
	public void updateSettings(ClientSettings settings, SolarSystemClientSettings specificSettings, Moon object) {
		this.renderMoon = settings == null || settings.renderMoon;
		if(!this.renderMoon) {
			this.shouldRender = false;
			this.shouldRenderDominate = false;
			return;
		}

		this.appCoord = new SpCoord();
		this.appPos = new Vector3();

		this.latn = specificSettings.imgFrac;
		this.longn = 2*specificSettings.imgFrac;

		this.pos = Allocator.createAndInitialize(longn, latn+1);
		this.surfBr = new float[longn][latn+1];
		this.normal = Allocator.createAndInitialize(longn, latn+1);
	}

	@Override
	public void updateCache(Moon object, ViewerInfo info) {
		if(!this.renderMoon)
			return;

		appPos.set(object.earthPos);
		info.coordinate.getProjectionToGround().transform(this.appPos);
		appPos.normalize();
		appCoord.setWithVec(appPos);

		this.domination = OpticsHelper.getDominationFromMag(object.currentMag);
		double phaseCos = Math.max(-1.0, Math.min(1.0,
				object.sunPos.dot(object.earthPos)
						/ (object.sunPos.size() * object.earthPos.size())));
		double phase = Math.acos(phaseCos) / (2.0 * Math.PI);
		Vector3 phaseCross = new Vector3();
		phaseCross.setCross(object.earthPos, object.sunPos);
		if(phaseCross.dot(object.Pole) < 0.0)
			phase = 1.0 - phase;
		this.phaseIndex = (int) Math.floor(phase * 8.0 + 0.5) % 8;
		double illumination = (1.0 + phaseCos) * 0.5;
		double atlasIllumination =
				(1.0 + Math.cos(this.phaseIndex * Math.PI / 4.0)) * 0.5;
		double phaseCorrection = atlasIllumination > 1.0e-6
				? Math.min(2.0, illumination / atlasIllumination) : 0.0;
		double surfaceBrightness = object.brightness * phaseCorrection;
		this.vacuumSpriteRed = (float) surfaceBrightness;
		this.vacuumSpriteGreen = (float) surfaceBrightness;
		this.vacuumSpriteBlue = (float) surfaceBrightness;
		this.spriteRed = this.vacuumSpriteRed
				* CelestialBrightness.atmosphericTransmission(
						info, appCoord, Wavelength.red);
		this.spriteGreen = this.vacuumSpriteGreen
				* CelestialBrightness.atmosphericTransmission(
							info, appCoord, Wavelength.V);
		this.spriteBlue = this.vacuumSpriteBlue
				* CelestialBrightness.atmosphericTransmission(
							info, appCoord, Wavelength.B);

		this.size = (float) (object.radius / object.earthPos.size());
		this.shouldRenderDominate = true; // TODO Proper render domination check

		this.shouldRender = true;

		if(!this.shouldRender)
			return;

		int latc, longc;
		for(longc=0; longc<longn; longc++){
			for(latc=0; latc<=latn; latc++){
				buf.set(object.posLocalM((double)longc/(double)longn*360.0, (double)latc/(double)latn*180.0-90.0));
				
				surfBr[longc][latc] = (float) Math.max(object.illumination(buf), 0.0);
				normal[longc][latc].set(buf);
				normal[longc][latc].normalize();
				
				buf.set(object.posLocalG(buf));
				info.coordinate.getProjectionToGround().transform(buf);

				buf.normalize();
				pos[longc][latc].set(buf);
			}
		}
	}

	@SideOnly(Side.CLIENT)
	@Override
	public ICelestialObjectRenderer getRenderer() {
		return MoonRenderer.INSTANCE;
	}

}
