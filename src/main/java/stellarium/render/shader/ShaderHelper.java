package stellarium.render.shader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.commons.io.IOUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import com.google.common.collect.Maps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.lib.math.Vector3;
import stellarium.StellarSky;

public class ShaderHelper {
	private static ShaderHelper instance = new ShaderHelper();

	public static ShaderHelper getInstance() {
		return instance;
	}

	private Map<String, ShaderObject> objectMap = Maps.newHashMap();
	private ShaderObject current = null;
	private int prevShader = 0;

	private ContextCapabilities contextcapabilities = GLContext.getCapabilities();

	/** Builds shader program. Gives <code>null</code> if it fails. */
	public @Nullable IShaderObject buildShader(String id, ResourceLocation vertloc, ResourceLocation fragloc) {
		int vertShader = 0, fragShader = 0, programObject = 0;
		StellarSky.INSTANCE.getLogger().info("Setting up a shader program with ID {}", id);
		try {
			vertShader = createShader(vertloc, OpenGlHelper.GL_VERTEX_SHADER);
			fragShader = createShader(fragloc, OpenGlHelper.GL_FRAGMENT_SHADER);
			if(vertShader == 0 || fragShader == 0) return null;
			programObject = OpenGlHelper.glCreateProgram();
			if(programObject == 0) return null;
			OpenGlHelper.glAttachShader(programObject, vertShader);
			OpenGlHelper.glAttachShader(programObject, fragShader);
			OpenGlHelper.glLinkProgram(programObject);
			if(OpenGlHelper.glGetProgrami(programObject, OpenGlHelper.GL_LINK_STATUS) == GL11.GL_FALSE) {
				StellarSky.INSTANCE.getLogger().error("Failed to link shader {}: {}", id, getLogInfo(programObject));
				return null;
			}
			detachShader(programObject, vertShader);
			detachShader(programObject, fragShader);
			ShaderObject object = new ShaderObject(programObject);
			ShaderObject previous = objectMap.put(id, object);
			programObject = 0;
			if(previous != null) OpenGlHelper.glDeleteProgram(previous.programId);
			return object;
		} finally {
			if(programObject != 0) OpenGlHelper.glDeleteProgram(programObject);
			if(vertShader != 0) OpenGlHelper.glDeleteShader(vertShader);
			if(fragShader != 0) OpenGlHelper.glDeleteShader(fragShader);
		}
	}

	private static void detachShader(int program, int shader) {
		if(OpenGlHelper.openGL21) GL20.glDetachShader(program, shader);
		else ARBShaderObjects.glDetachObjectARB(program, shader);
	}

	private void bindShader(ShaderObject object){
		if(this.current == null)
			this.prevShader = OpenGlHelper.openGL21 ? GlStateManager.glGetInteger(GL20.GL_CURRENT_PROGRAM)
					: ARBShaderObjects.glGetHandleARB(ARBShaderObjects.GL_PROGRAM_OBJECT_ARB);

		if(this.current != object) {
			this.current = object;

			//Use program
			OpenGlHelper.glUseProgram(object.programId);
		}		
	}

	private void releaseShader(ShaderObject object) {
		if(this.current == object)
			this.releaseCurrentShader();
	}

	public void releaseCurrentShader(){
		if(this.current == null)
			return;
		this.current = null;

		//Use empty program
		OpenGlHelper.glUseProgram(this.prevShader);
	}

	/** Requires a uniform active in the linked program, not merely declared in source text. */
	public IUniformField requireField(IShaderObject shader, String fieldName) {
		if(!(shader instanceof ShaderObject object))
			throw new IllegalArgumentException("Shader was not built by ShaderHelper");
		if(OpenGlHelper.glGetUniformLocation(object.programId, fieldName) < 0)
			throw new IllegalStateException("Linked shader lacks required uniform: " + fieldName);
		return object.getField(fieldName);
	}


	private int createShader(ResourceLocation location, int shaderType) {
		int shader = 0;
		boolean compiled = false;
		if(location == null)
			return 0;

		try {
			// Close resources before allocating a GL shader, so an I/O close failure
			// cannot orphan a successfully compiled handle before ownership transfers.
			byte[] abyte = readShaderBytes(location);
			ByteBuffer bytebuffer = BufferUtils.createByteBuffer(abyte.length);
			bytebuffer.put(abyte);
			bytebuffer.position(0);

			//Creates the shader object
			shader = OpenGlHelper.glCreateShader(shaderType);
			if(shader == 0) return 0;

			//Provide source to the shader
			OpenGlHelper.glShaderSource(shader, bytebuffer);

			//Compiles the shader
			OpenGlHelper.glCompileShader(shader);

			//Check compile
			if (OpenGlHelper.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
				StellarSky.INSTANCE.getLogger().error("Failed to compile a shader code on location {}", location);
				StellarSky.INSTANCE.getLogger().error(OpenGlHelper.glGetShaderInfoLog(shader, 32768));
				return 0;
			}

			compiled = true;
			return shader;
		}
		catch(IOException exc) {
			throw new IllegalStateException("Unable to read shader " + location, exc);
		} finally {
			if(!compiled && shader != 0) OpenGlHelper.glDeleteShader(shader);
		}
	}

	private static byte[] readShaderBytes(ResourceLocation location) throws IOException {
		try(IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(location);
			BufferedInputStream stream = new BufferedInputStream(resource.getInputStream())) {
			return IOUtils.toByteArray(stream);
		}
	}

	private static String getLogInfo(int programObject) {
		return OpenGlHelper.glGetProgramInfoLog(programObject, 32768);
	}

	private class ShaderObject implements IShaderObject {
		private int programId;
		private Map<String, ShaderUniform> uniformMap = Maps.newHashMap();

		public ShaderObject(int programId) {
			this.programId = programId;
		}

		@Override
		public void bindShader() {
			ShaderHelper.this.bindShader(this);
		}

		@Override
		public void releaseShader() {
			ShaderHelper.this.releaseShader(this);
		}

		@Override
		public IUniformField getField(String fieldName) {
			if(uniformMap.containsKey(fieldName))
				return uniformMap.get(fieldName);

			//Gets the location
			int location = OpenGlHelper.glGetUniformLocation(this.programId, fieldName);
			if(location == -1)
				StellarSky.INSTANCE.getLogger().error(String.format("Invalid field %s has claimed while loading shaders!", fieldName));

			ShaderUniform uniform = new ShaderUniform(location);
			uniformMap.put(fieldName, uniform);
			return uniform;
		}
	}

	private class ShaderUniform implements IUniformField {
		int location;

		public ShaderUniform(int location) {
			this.location = location;
		}

		@Override
		public void setInteger(int val) {
			OpenGlHelper.glUniform1i(this.location, val);
		}

		@Override
		public void setDouble(double val) {
			//Temporal, since openglhelper does not expose something important (below)
			if (!contextcapabilities.OpenGL21)
				ARBShaderObjects.glUniform1fARB(this.location, (float) val);
			else
				GL20.glUniform1f(this.location, (float) val);
		}

		@Override
		public void setSpCoord(SpCoord val) {
			//Temporal, since openglhelper does not expose something important (below)
			if (!contextcapabilities.OpenGL21)
				ARBShaderObjects.glUniform2fARB(this.location, (float)val.x, (float)val.y);
			else
				GL20.glUniform2f(this.location, (float)val.x, (float)val.y);
		}

		@Override
		public void setVector3(Vector3 val) {
			//Temporal, since openglhelper does not expose something important (below)
			if (!contextcapabilities.OpenGL21)
				ARBShaderObjects.glUniform3fARB(this.location, (float)val.getX(), (float)val.getY(), (float)val.getZ());
			else
				GL20.glUniform3f(this.location, (float)val.getX(), (float)val.getY(), (float)val.getZ());
		}

		@Override
		public void setDouble2(double x, double y) {
			if (!contextcapabilities.OpenGL21)
				ARBShaderObjects.glUniform2fARB(this.location, (float)x, (float)y);
			else
				GL20.glUniform2f(this.location, (float)x, (float)y);
		}

		@Override
		public void setDouble3(double x, double y, double z) {
			//Temporal, since openglhelper does not expose something important (below)
			if (!contextcapabilities.OpenGL21)
				ARBShaderObjects.glUniform3fARB(this.location, (float)x, (float)y, (float)z);
			else
				GL20.glUniform3f(this.location, (float)x, (float)y, (float)z);
		}

		@Override
		public void setDouble4(double red, double green, double blue, double alpha) {
			//Temporal, since openglhelper does not expose something important (below)
			if (!contextcapabilities.OpenGL21)
				ARBShaderObjects.glUniform4fARB(this.location, (float)red, (float)green, (float)blue, (float)alpha);
			else
				GL20.glUniform4f(this.location, (float)red, (float)green, (float)blue, (float)alpha);
		}
	}

}
