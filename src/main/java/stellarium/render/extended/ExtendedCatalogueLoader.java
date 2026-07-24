package stellarium.render.extended;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.lib.math.Vector3;
import stellarium.StellarSky;
import stellarium.render.util.BufferBuilderEx;
import stellarium.render.util.FloatVertexFormats;
import stellarium.render.util.VertexBufferEx;
import stellarium.render.util.VertexReferences;
import stellarium.stellars.util.StarColor;

final class ExtendedCatalogueLoader {
	private static final int CATALOG_MAGIC = 0x835f040a;
	private static final int CATALOG_MAGIC_NATIVE = 0x835f040b;
	private static final int HEADER_BYTES = 28;
	private static final int STAR_BYTES = 48;
	private static final double POSITION_SCALE = 2.0e9;
	private static final double RENDER_DEPTH = 100.0;

	private static final String[] STAR_CATALOGUES = {
			"/assets/stellarium/catalog/stars_0.cat",
			"/assets/stellarium/catalog/stars_1.cat",
			"/assets/stellarium/catalog/stars_2.cat",
			"/assets/stellarium/catalog/stars_3.cat"
	};

	private ExtendedCatalogueLoader() {
	}

	static CatalogueBuffer loadStars(float magnitudeLimit) throws IOException {
		BufferBuilderEx builder = VertexReferences.getBuilder();
		try {
			builder.begin(GL11.GL_POINTS, FloatVertexFormats.POSITION_COLOR_F);
			int count = 0;

			for(String path : STAR_CATALOGUES)
				count += appendStarCatalogue(builder, path, magnitudeLimit);

			builder.finishDrawing();
			VertexBufferEx buffer = new VertexBufferEx();
			buffer.upload(builder);
			StellarSky.INSTANCE.getLogger().info(
					"Loaded {} extended stars through magnitude {}", count, magnitudeLimit);
			return new CatalogueBuffer(buffer, count);
		} catch(IOException | RuntimeException exception) {
			abortBuild(builder);
			throw exception;
		}
	}

	private static int appendStarCatalogue(BufferBuilderEx builder, String path,
			float magnitudeLimit) throws IOException {
		InputStream input = ExtendedCatalogueLoader.class.getResourceAsStream(path);
		if(input == null)
			throw new IOException("Missing extended star catalogue " + path);

		byte[] bytes;
		try(InputStream closeable = input) {
			bytes = IOUtils.toByteArray(closeable);
		}

		ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		int magic = data.getInt(0);
		int type = data.getInt(4);
		int level = data.getInt(16);
		if((magic != CATALOG_MAGIC && magic != CATALOG_MAGIC_NATIVE) || type != 0
				|| level < 0 || level > 8)
			throw new IOException("Unsupported Stellarium star catalogue header in " + path);

		int zoneCount = 20 * (1 << (2 * level)) + 1;
		int dataOffset = HEADER_BYTES + zoneCount * Integer.BYTES;
		long starCount = 0;
		for(int zone = 0; zone < zoneCount; zone++)
			starCount += Integer.toUnsignedLong(data.getInt(HEADER_BYTES + zone * Integer.BYTES));
		if(dataOffset + starCount * STAR_BYTES > bytes.length)
			throw new IOException("Truncated Stellarium star catalogue " + path);

		int accepted = 0;
		for(int index = 0; index < starCount; index++) {
			int offset = dataOffset + index * STAR_BYTES;
			float magnitude = data.getShort(offset + 34) / 1000.0f;
			if(magnitude > magnitudeLimit)
				continue;

			double x = data.getInt(offset + 8) / POSITION_SCALE;
			double y = data.getInt(offset + 12) / POSITION_SCALE;
			double z = data.getInt(offset + 16) / POSITION_SCALE;
			double length = Math.sqrt(x * x + y * y + z * z);
			if(length < 0.5)
				continue;

			float bv = data.getShort(offset + 32) / 1000.0f;
			StarColor color = StarColor.getColor(bv);
			float brightness = starDisplayBrightness(magnitude);
			builder.pos(x / length * RENDER_DEPTH, y / length * RENDER_DEPTH,
					z / length * RENDER_DEPTH)
					.color(color.r / 255.0f, color.g / 255.0f, color.b / 255.0f, brightness)
					.endVertex();
			accepted++;
		}
		return accepted;
	}

	private static float starDisplayBrightness(float magnitude) {
		// Keep the actual logarithmic flux. A brightness floor makes every
		// faint catalogue star bloom like a bright star in the HDR pass.
		double flux = Math.pow(10.0, -0.4 * (magnitude + 0.5));
		return (float) Math.min(4.0, flux);
	}

	static CatalogueBuffer loadDeepSky(float magnitudeLimit) throws IOException {
		InputStream input = ExtendedCatalogueLoader.class.getResourceAsStream(
				"/assets/stellarium/catalog/dso_catalog.txt");
		if(input == null)
			throw new IOException("Missing extended deep-sky catalogue");

		BufferBuilderEx builder = VertexReferences.getBuilder();
		try {
			builder.begin(GL11.GL_POINTS, FloatVertexFormats.POSITION_COLOR_F);
			int count = 0;

			try(BufferedReader reader = new BufferedReader(
					new InputStreamReader(input, StandardCharsets.UTF_8), 65536)) {
				String line;
				while((line = reader.readLine()) != null) {
					if(line.isEmpty() || line.charAt(0) == '#')
						continue;
					String[] fields = line.split("\\t", -1);
					if(fields.length < 10)
						continue;
					try {
						float bMagnitude = Float.parseFloat(fields[3].trim());
						float vMagnitude = Float.parseFloat(fields[4].trim());
						float magnitude = validMagnitude(vMagnitude)
								? vMagnitude : bMagnitude;
						if(!validMagnitude(magnitude) || magnitude > magnitudeLimit)
							continue;

						String type = fields[5].trim().toUpperCase(java.util.Locale.ROOT);
						if(type.contains("*") || type.isEmpty())
							continue;

						double ra = Double.parseDouble(fields[1].trim());
						double dec = Double.parseDouble(fields[2].trim());
						float majorArcMinutes = Float.parseFloat(fields[7].trim());
						float minorArcMinutes = Float.parseFloat(fields[8].trim());
						float angularSize = deepSkyAngularSize(type, majorArcMinutes);
						float brightness = deepSkyDisplayBrightness(magnitude,
								majorArcMinutes, minorArcMinutes);
						float[] color = deepSkyColor(type);
						Vector3 pos = new SpCoord(ra, dec).getVec();
						builder.pos(pos.getX() * 99.5, pos.getY() * 99.5,
								pos.getZ() * 99.5)
								.color(color[0] * brightness, color[1] * brightness,
										color[2] * brightness, angularSize)
								.endVertex();
						count++;
					} catch(NumberFormatException ignored) {
						// A malformed row does not disable the entire catalogue.
					}
				}
			}

			builder.finishDrawing();
			VertexBufferEx buffer = new VertexBufferEx();
			buffer.upload(builder);
			StellarSky.INSTANCE.getLogger().info(
					"Loaded {} extended deep-sky objects through magnitude {}",
					count, magnitudeLimit);
			return new CatalogueBuffer(buffer, count);
		} catch(IOException | RuntimeException exception) {
			abortBuild(builder);
			throw exception;
		}
	}

	private static boolean validMagnitude(float magnitude) {
		return magnitude > 0.0f && magnitude < 90.0f;
	}

	private static float deepSkyAngularSize(String type, float majorArcMinutes) {
		if(!Float.isFinite(majorArcMinutes) || majorArcMinutes <= 0.0f)
			return 0.02f;

		// Clusters are resolved collections of stars. A multi-degree circular
		// sprite makes them look like artificial moons.
		float maxDegrees = isCluster(type) ? 1.5f : 4.0f;
		return Math.max(0.02f, Math.min(maxDegrees, majorArcMinutes / 60.0f));
	}

	private static float deepSkyDisplayBrightness(float magnitude, float majorArcMinutes,
			float minorArcMinutes) {
		double major = validAngularSize(majorArcMinutes) ? majorArcMinutes : 1.0;
		double minor = validAngularSize(minorArcMinutes) ? minorArcMinutes : major;
		double area = Math.max(1.0, Math.PI * major * minor * 0.25);

		// Use integrated magnitude per square arcminute, as Stellarium does
		// for extended sources, instead of applying total flux to every pixel.
		double surfaceMagnitude = magnitude + 2.5 * Math.log10(area);
		double surfaceFlux = Math.pow(10.0, -0.4 * (surfaceMagnitude - 6.0));
		return (float) Math.min(0.8, Math.max(0.002, 0.18 * Math.sqrt(surfaceFlux)));
	}

	private static boolean validAngularSize(float value) {
		return Float.isFinite(value) && value > 0.0f;
	}

	private static boolean isCluster(String type) {
		return type.contains("OC") || type.contains("GC") || type.contains("CL")
				|| type.contains("C+N");
	}

	private static float[] deepSkyColor(String type) {
		if(type.contains("GX") || type.equals("G") || type.contains("QSO")
				|| type.contains("IG") || type.contains("RG"))
			return new float[] { 0.74f, 0.82f, 1.0f };
		if(type.contains("GC") || type.contains("OC") || type.contains("CL"))
			return new float[] { 0.9f, 0.94f, 1.0f };
		if(type.contains("PN"))
			return new float[] { 0.42f, 1.0f, 0.82f };
		if(type.contains("HII") || type.contains("RN") || type.contains("EN")
				|| type.contains("SNR"))
			return new float[] { 1.0f, 0.48f, 0.65f };
		return new float[] { 0.55f, 0.72f, 1.0f };
	}

	static CatalogueBuffer buildMilkyWay() {
		final int longitudeSegments = 96;
		final int latitudeSegments = 48;
		BufferBuilderEx builder = VertexReferences.getBuilder();
		try {
			builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);

			for(int longitude = 0; longitude < longitudeSegments; longitude++) {
				int nextLongitude = (longitude + 1) % longitudeSegments;
				double u0 = 1.0 - (double) longitude / longitudeSegments;
				double u1 = 1.0 - (double) (longitude + 1) / longitudeSegments;
				for(int latitude = 0; latitude < latitudeSegments; latitude++) {
					double v0 = 1.0 - (double) latitude / latitudeSegments;
					double v1 = 1.0 - (double) (latitude + 1) / latitudeSegments;
					addMilkyWayVertex(builder, longitude, latitude, longitudeSegments,
							latitudeSegments, u0, v0);
					addMilkyWayVertex(builder, longitude, latitude + 1, longitudeSegments,
							latitudeSegments, u0, v1);
					addMilkyWayVertex(builder, nextLongitude, latitude + 1,
							longitudeSegments, latitudeSegments, u1, v1);
					addMilkyWayVertex(builder, nextLongitude, latitude, longitudeSegments,
							latitudeSegments, u1, v0);
				}
			}

			builder.finishDrawing();
			VertexBufferEx buffer = new VertexBufferEx();
			buffer.upload(builder);
			return new CatalogueBuffer(buffer, longitudeSegments * latitudeSegments * 4);
		} catch(RuntimeException exception) {
			abortBuild(builder);
			throw exception;
		}
	}

	private static void addMilkyWayVertex(BufferBuilderEx builder, int longitude, int latitude,
			int longitudeSegments, int latitudeSegments, double u, double v) {
		double ra = longitude * 360.0 / longitudeSegments + 90.0;
		double dec = latitude * 180.0 / latitudeSegments - 90.0;
		Vector3 pos = new SpCoord(ra, dec).getVec();
		builder.pos(pos.getX() * 100.0, pos.getY() * 100.0, pos.getZ() * 100.0)
				.tex(u, v).endVertex();
	}

	private static void abortBuild(BufferBuilderEx builder) {
		try {
			builder.finishDrawing();
		} catch(IllegalStateException ignored) {
			// The builder may already have reached finishDrawing before upload failed.
		}
		builder.reset();
	}

	static final class CatalogueBuffer {
		final VertexBufferEx buffer;
		final int count;

		CatalogueBuffer(VertexBufferEx buffer, int count) {
			this.buffer = buffer;
			this.count = count;
		}

		void delete() {
			buffer.deleteGlBuffers();
		}
	}
}
