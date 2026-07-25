package stellarium.render.extended;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL11;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.lib.math.Vector3;
import stellarium.StellarSky;
import stellarium.StellarSkyReferences;
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
	private static final double MAS_TO_RAD = Math.PI / (180.0 * 3600000.0);
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
			builder.begin(GL11.GL_POINTS, FloatVertexFormats.POSITION_COLOR_MOTION_F);
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
			double motionX = data.getInt(offset + 20) / 1000.0 * MAS_TO_RAD;
			double motionY = data.getInt(offset + 24) / 1000.0 * MAS_TO_RAD;
			double motionZ = data.getInt(offset + 28) / 1000.0 * MAS_TO_RAD;
			builder.pos(x / length * RENDER_DEPTH, y / length * RENDER_DEPTH,
					z / length * RENDER_DEPTH);
			builder.color(color.r / 255.0f, color.g / 255.0f, color.b / 255.0f, brightness);
			builder.generic((float) (motionX * RENDER_DEPTH),
					(float) (motionY * RENDER_DEPTH), (float) (motionZ * RENDER_DEPTH));
			builder.endVertex();
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

	static DeepSkyCatalogue loadDeepSky(float magnitudeLimit) throws IOException {
		InputStream input = ExtendedCatalogueLoader.class.getResourceAsStream(
				"/assets/stellarium/catalog/dso_catalog.txt");
		if(input == null)
			throw new IOException("Missing extended deep-sky catalogue");

		Map<String, BufferBuilderEx> builders = new LinkedHashMap<>();
		Map<String, Integer> counts = new LinkedHashMap<>();
		try {
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
						int positionAngle = parseInt(fields, 9, 0);
						float brightness = deepSkyDisplayBrightness(magnitude,
								majorArcMinutes, minorArcMinutes);
						float[] color = deepSkyColor(type);
						String texture = "";
						BufferBuilderEx builder = builders.get(texture);
						if(builder == null) {
							builder = new BufferBuilderEx(1 << 20);
							builder.begin(GL11.GL_QUADS, FloatVertexFormats.POSITION_TEX_COLOR_F);
							builders.put(texture, builder);
							counts.put(texture, 0);
						}
						appendDeepSkyBillboard(builder, ra, dec, positionAngle,
								type, majorArcMinutes, minorArcMinutes, color, brightness);
						counts.put(texture, counts.get(texture) + 1);
						count++;
					} catch(NumberFormatException ignored) {
						// A malformed row does not disable the entire catalogue.
					}
				}
			}

			List<DeepSkyBatch> batches = new ArrayList<>();
			for(Map.Entry<String, BufferBuilderEx> entry : builders.entrySet()) {
				BufferBuilderEx builder = entry.getValue();
				builder.finishDrawing();
				VertexBufferEx buffer = new VertexBufferEx();
				buffer.upload(builder);
				batches.add(new DeepSkyBatch(buffer, counts.get(entry.getKey()),
						textureLocation(entry.getKey())));
			}
			StellarSky.INSTANCE.getLogger().info(
					"Loaded {} extended deep-sky objects through magnitude {}",
					count, magnitudeLimit);
			return new DeepSkyCatalogue(batches, count);
		} catch(IOException | RuntimeException exception) {
			for(BufferBuilderEx builder : builders.values())
				abortBuild(builder);
			throw exception;
		}
	}

	private static int parseInt(String[] fields, int index, int fallback) {
		if(index >= fields.length)
			return fallback;
		try {
			return Integer.parseInt(fields[index].trim());
		} catch(NumberFormatException ignored) {
			return fallback;
		}
	}

	private static ResourceLocation textureLocation(String texture) {
		if(texture == null || texture.isEmpty())
			return null;
		return new ResourceLocation(StellarSkyReferences.RESOURCE_ID, "dso/" + texture + ".png");
	}

	private static void appendDeepSkyBillboard(BufferBuilderEx builder, double raDegrees,
			double decDegrees, int positionAngle, String type, float majorArcMinutes,
			float minorArcMinutes, float[] color, float brightness) {
		double ra = Math.toRadians(raDegrees);
		double dec = Math.toRadians(decDegrees);
		double cosRa = Math.cos(ra), sinRa = Math.sin(ra);
		double cosDec = Math.cos(dec), sinDec = Math.sin(dec);
		Vector3 center = new Vector3(cosDec * cosRa, cosDec * sinRa, sinDec);
		Vector3 east = new Vector3(-sinRa, cosRa, 0.0);
		Vector3 north = new Vector3(-sinDec * cosRa, -sinDec * sinRa, cosDec);

		double major = angularRadiusRadians(majorArcMinutes, type, false);
		double minor = angularRadiusRadians(minorArcMinutes, type, true);
		double angle = Math.toRadians(positionAngle);
		// Position angle is measured from north toward east in the
		// Stellarium catalogue.
		Vector3 majorAxis = new Vector3(north).scale(Math.cos(angle))
				.add(new Vector3(east).scale(Math.sin(angle)));
		Vector3 minorAxis = new Vector3(north).scale(-Math.sin(angle))
				.add(new Vector3(east).scale(Math.cos(angle)));
		majorAxis.scale(major * 100.0);
		minorAxis.scale(minor * 100.0);
		center.scale(100.0);

		float[] texture = textureCoordinates(type);
		addDeepSkyVertex(builder, center, majorAxis, minorAxis, -1.0, -1.0,
				texture[0], texture[1], color, brightness);
		addDeepSkyVertex(builder, center, majorAxis, minorAxis, 1.0, -1.0,
				texture[2], texture[1], color, brightness);
		addDeepSkyVertex(builder, center, majorAxis, minorAxis, 1.0, 1.0,
				texture[2], texture[3], color, brightness);
		addDeepSkyVertex(builder, center, majorAxis, minorAxis, -1.0, 1.0,
				texture[0], texture[3], color, brightness);
	}

	private static float angularRadiusRadians(float arcMinutes, String type, boolean minor) {
		double value = validAngularSize(arcMinutes) ? arcMinutes : 1.0;
		if(minor && !validAngularSize(arcMinutes))
			value = 1.0;
		double maxDegrees = isCluster(type) ? 1.5 : 4.0;
		// Stellarium stores the full major/minor diameter in arcminutes;
		// billboard axes are radii.
		return (float) Math.toRadians(Math.max(0.005, Math.min(maxDegrees, value / 120.0)));
	}

	private static float[] textureCoordinates(String type) {
		return new float[] {0.0f, 0.0f, 1.0f, 1.0f};
	}

	private static void addDeepSkyVertex(BufferBuilderEx builder, Vector3 center,
			Vector3 majorAxis, Vector3 minorAxis, double majorSign, double minorSign,
			float u, float v, float[] color, float brightness) {
		Vector3 position = new Vector3(center)
				.add(new Vector3(majorAxis).scale(majorSign))
				.add(new Vector3(minorAxis).scale(minorSign));
		builder.pos(position).tex(u, v)
				.color(color[0] * brightness, color[1] * brightness,
						color[2] * brightness, brightness)
				.endVertex();
	}

	static DeepSkyCatalogue loadDeepSkyImages() throws IOException {
		InputStream input = ExtendedCatalogueLoader.class.getResourceAsStream(
				"/assets/stellarium/dso/textures.json");
		if(input == null)
			throw new IOException("Missing Stellarium deep-sky texture manifest");

		JsonObject root;
		try(InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
			root = new JsonParser().parse(reader).getAsJsonObject();
		}

		JsonArray tiles = root.getAsJsonArray("subTiles");
		if(tiles == null)
			throw new IOException("Stellarium deep-sky texture manifest has no subTiles");

		List<DeepSkyBatch> batches = new ArrayList<>(tiles.size());
		int polygonCount = 0;
		for(JsonElement tileElement : tiles) {
			if(!tileElement.isJsonObject())
				continue;
			JsonObject tile = tileElement.getAsJsonObject();
			if(!tile.has("imageUrl") || !tile.has("worldCoords"))
				continue;
			String imageUrl = tile.get("imageUrl").getAsString();
			if(imageUrl.contains("..") || imageUrl.indexOf('/') >= 0
					|| imageUrl.indexOf('\\') >= 0)
				continue;

			JsonArray worldPolygons = tile.getAsJsonArray("worldCoords");
			JsonArray texturePolygons = tile.getAsJsonArray("textureCoords");
			if(worldPolygons == null || texturePolygons == null
					|| worldPolygons.size() != texturePolygons.size())
				continue;

			BufferBuilderEx builder = new BufferBuilderEx(65536);
			try {
				builder.begin(GL11.GL_QUADS, FloatVertexFormats.POSITION_TEX_COLOR_F);
				float brightness = imageBrightness(tile);
				int batchPolygons = 0;
				Bounds bounds = new Bounds();
				for(int polygon = 0; polygon < worldPolygons.size(); polygon++) {
					JsonArray world = worldPolygons.get(polygon).getAsJsonArray();
					JsonArray texture = texturePolygons.get(polygon).getAsJsonArray();
					if(world.size() != 4 || texture.size() != 4)
						continue;
					appendSkyImagePolygon(builder, world, texture, brightness, bounds);
					batchPolygons++;
				}
				if(batchPolygons == 0) {
					abortBuild(builder);
					continue;
				}
				builder.finishDrawing();
				VertexBufferEx buffer = new VertexBufferEx();
				buffer.upload(builder);
				ResourceLocation texture = new ResourceLocation(
						StellarSkyReferences.RESOURCE_ID, "dso/" + imageUrl);
				batches.add(new DeepSkyBatch(buffer, batchPolygons, texture, bounds));
				polygonCount += batchPolygons;
			} catch(RuntimeException exception) {
				abortBuild(builder);
				throw exception;
			}
		}

		StellarSky.INSTANCE.getLogger().info(
				"Loaded {} Stellarium deep-sky images with {} spherical polygons",
				batches.size(), polygonCount);
		return new DeepSkyCatalogue(batches, polygonCount);
	}

	private static float imageBrightness(JsonObject tile) {
		if(!tile.has("maxBrightness"))
			return 0.35f;
		double surfaceMagnitude = tile.get("maxBrightness").getAsDouble();
		double luminance = 2.0 * 2025000.0
				* Math.exp(-0.92103 * (surfaceMagnitude + 12.12331))
				/ ((1.0 / 60.0) * (1.0 / 60.0));
		return (float) Math.min(1.0, adaptLuminanceScaled(luminance, 1.0));
	}

	/**
	 * Devlin tone adaptation used by Stellarium. A dark-sky adaptation
	 * luminance of 1 cd/m2 keeps the bundled image set calibrated without
	 * lifting every RGB texture's black background.
	 */
	private static double adaptLuminanceScaled(double luminance,
			double worldAdaptationLuminance) {
		double displayAdaptationLuminance = 50.0;
		double alphaWorld = 0.4 * Math.log10(worldAdaptationLuminance) + 1.619;
		double betaWorld = 6.1642;
		double logDisplay = Math.log10(displayAdaptationLuminance);
		double alphaDisplay = 0.4 * logDisplay + 1.619;
		double betaDisplay = -0.4 * logDisplay * logDisplay
				+ 0.218 * logDisplay + 6.1642;
		double exponent = alphaWorld / alphaDisplay;
		double scale = Math.pow(10.0,
				(betaWorld - betaDisplay) / alphaDisplay) / (Math.PI * 0.0001);
		return Math.pow(luminance * Math.PI * 0.0001, exponent) * scale / 100.0;
	}

	private static void appendSkyImagePolygon(BufferBuilderEx builder, JsonArray world,
			JsonArray texture, float brightness, Bounds bounds) {
		Vector3[] positions = new Vector3[4];
		double[][] uv = new double[4][2];
		for(int vertex = 0; vertex < 4; vertex++) {
			JsonArray coordinate = world.get(vertex).getAsJsonArray();
			positions[vertex] = new SpCoord(coordinate.get(0).getAsDouble(),
					coordinate.get(1).getAsDouble()).getVec();
			JsonArray textureCoordinate = texture.get(vertex).getAsJsonArray();
			uv[vertex][0] = textureCoordinate.get(0).getAsDouble();
			uv[vertex][1] = textureCoordinate.get(1).getAsDouble();
		}

		int subdivisions = 1;
		for(int edge = 0; edge < 4; edge++) {
			double dot = Math.max(-1.0, Math.min(1.0,
					positions[edge].dot(positions[(edge + 1) % 4])));
			subdivisions = Math.max(subdivisions,
					(int) Math.ceil(Math.toDegrees(Math.acos(dot)) / 4.0));
		}
		subdivisions = Math.min(16, subdivisions);

		for(int y = 0; y < subdivisions; y++) {
			double v0 = (double) y / subdivisions;
			double v1 = (double) (y + 1) / subdivisions;
			for(int x = 0; x < subdivisions; x++) {
				double u0 = (double) x / subdivisions;
				double u1 = (double) (x + 1) / subdivisions;
				addSkyImageVertex(builder, positions, uv, u0, v0, brightness, bounds);
				addSkyImageVertex(builder, positions, uv, u1, v0, brightness, bounds);
				addSkyImageVertex(builder, positions, uv, u1, v1, brightness, bounds);
				addSkyImageVertex(builder, positions, uv, u0, v1, brightness, bounds);
			}
		}
	}

	private static void addSkyImageVertex(BufferBuilderEx builder, Vector3[] positions,
			double[][] uv, double u, double v, float brightness, Bounds bounds) {
		Vector3 position = bilinearVector(positions, u, v).normalize().scale(99.25);
		bounds.include(position);
		double textureU = bilinear(uv[0][0], uv[1][0], uv[2][0], uv[3][0], u, v);
		double textureV = bilinear(uv[0][1], uv[1][1], uv[2][1], uv[3][1], u, v);
		builder.pos(position).tex(textureU, textureV)
				.color(brightness, brightness, brightness, 1.0f).endVertex();
	}

	private static Vector3 bilinearVector(Vector3[] positions, double u, double v) {
		return new Vector3(
				bilinear(positions[0].getX(), positions[1].getX(),
						positions[2].getX(), positions[3].getX(), u, v),
				bilinear(positions[0].getY(), positions[1].getY(),
						positions[2].getY(), positions[3].getY(), u, v),
				bilinear(positions[0].getZ(), positions[1].getZ(),
						positions[2].getZ(), positions[3].getZ(), u, v));
	}

	private static double bilinear(double c00, double c10, double c11, double c01,
			double u, double v) {
		return c00 * (1.0 - u) * (1.0 - v) + c10 * u * (1.0 - v)
				+ c11 * u * v + c01 * (1.0 - u) * v;
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
		final int longitudeSegments = 192;
		final int latitudeSegments = 96;
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

	static final class DeepSkyCatalogue {
		final List<DeepSkyBatch> batches;
		final int count;

		DeepSkyCatalogue(List<DeepSkyBatch> batches, int count) {
			this.batches = batches;
			this.count = count;
		}

		void delete() {
			for(DeepSkyBatch batch : batches)
				batch.buffer.deleteGlBuffers();
		}
	}

	static final class DeepSkyBatch {
		final VertexBufferEx buffer;
		final int count;
		final ResourceLocation texture;
		final Bounds bounds;

		DeepSkyBatch(VertexBufferEx buffer, int count, ResourceLocation texture) {
			this(buffer, count, texture, null);
		}

		DeepSkyBatch(VertexBufferEx buffer, int count, ResourceLocation texture,
				Bounds bounds) {
			this.buffer = buffer;
			this.count = count;
			this.texture = texture;
			this.bounds = bounds;
		}
	}

	static final class Bounds {
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;

		void include(Vector3 vector) {
			minX = Math.min(minX, vector.getX());
			minY = Math.min(minY, vector.getY());
			minZ = Math.min(minZ, vector.getZ());
			maxX = Math.max(maxX, vector.getX());
			maxY = Math.max(maxY, vector.getY());
			maxZ = Math.max(maxZ, vector.getZ());
		}
	}
}
