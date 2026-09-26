package stellarium.display;

import stellarapi.api.lib.math.Matrix3;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.view.IAtmosphereEffect;
import stellarapi.api.view.ICCoordinates;
import stellarium.world.StellarCoordinates;

public class DisplayCacheInfo {

	public final Matrix3 projectionToGround;
	public final Matrix3 backgroundProjectionToGround;
	private final IAtmosphereEffect sky;

	public DisplayCacheInfo(ICCoordinates coordinate, IAtmosphereEffect sky) {
		this.projectionToGround = coordinate.getProjectionToGround();
		this.backgroundProjectionToGround = new Matrix3(StellarCoordinates.backgroundProjection(coordinate));
		this.sky = sky;
	}
	
	public void applyAtmRefraction(SpCoord appCoord) {
		sky.applyAtmRefraction(appCoord);
	}

}
