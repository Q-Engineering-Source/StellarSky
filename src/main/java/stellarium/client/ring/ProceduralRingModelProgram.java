package stellarium.client.ring;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.ToIntFunction;
import java.util.Collection;
import stellarium.world.ring.terrain.TerrainTileKey;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import stellarium.world.ring.ProceduralRingModelGeometry.Pose;
import stellarium.client.ring.dh.DistantHorizonsLocalLight;

/** Fixed mesh/material shader, with current-frame optics and independent nearest-hit selection. */
final class ProceduralRingModelProgram {
    private int program, rotation, origin, projection, radius, eye, viewport, pass, high, low, surface, cloud, weather;
    private int layer, minimum, farBand, groundHigh, groundLow;
    private RingworldDistantDepthUniforms distant;
    private RingworldBoardDepthUniforms board;
    private RingworldCurvedRayUniforms curved;
    /** This pass's own opaque media, used only to keep the late ground behind it. */
    private RingworldOwnMediaDepthUniforms ownMedia;
    private int lightMode;
    private int originX;
    private int previewCount;
    private final int[] previewBounds=new int[48];
    private final int[] previewLevels=new int[48];
    private int terrainLevel;
    private final int[] lightUniforms=new int[6];
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

    void use(RingworldCurvatureFrame frame, Pose pose, float rain) {
        if (program == 0) create();
        OpenGlHelper.glUseProgram(program);
        split4(rotation, pose.cos(), pose.sin());
        split4(origin, frame.renderOrigin().y(), frame.renderOrigin().z());
        float[] xOrigin=RingworldCurvedRayUniforms.split(frame.renderOrigin().x());
        GL20.glUniform2f(originX,xOrigin[0],xOrigin[1]);
        float[] r = RingworldCurvedRayUniforms.split(frame.geometry().radiusMeters());
        GL20.glUniform2f(radius,r[0],r[1]);
        GL20.glUniform3f(eye,frame.cameraX(),frame.cameraY(),frame.cameraZ());
        frame.copyProjection(matrix);
        RingworldBoardClipProjection.removeFarPlane(matrix);
        GL20.glUniformMatrix4(projection,false,matrix);
        GL20.glUniform1i(high,7); GL20.glUniform1i(low,8);
        GL20.glUniform1i(surface,0); GL20.glUniform1i(cloud,1);
        GL20.glUniform1f(weather,rain);
        distant.upload(); board.upload();
        curved.upload(frame);
        // The early reductions and the late settle share this program: during the
        // early pass no capture exists yet, so this stays inactive and the ground
        // keys are unchanged.
        ownMedia.uploadFailClosed();
        var snapshot=RingworldRenderSnapshots.current();
        GL20.glUniform1i(lightMode,0);
        if(snapshot!=null&&frame.belongsTo(snapshot.world(),snapshot.scene())) {
            var light=DistantHorizonsLocalLight.from(snapshot,frame.renderOrigin());
            uploadLight(0,light.band());uploadLight(2,light.bounds());uploadLight(4,light.direction());
            GL20.glUniform1i(lightMode,light.mode());
        }
    }

    void stage(int stage, int x, int y, int width, int height) {
        GL20.glUniform1i(pass,stage);
        GL20.glUniform4f(viewport,x,y,width,height);
    }

    void previewFootprints(RingworldCurvatureFrame frame,Collection<TerrainTileKey> keys) {
        if(keys.size()>previewBounds.length)throw new IllegalArgumentException("Preview footprint budget exceeded");
        int index=0;
        for(var key:keys) {
            double width=64L<<key.level();
            double x=key.minBlockX()-frame.renderOrigin().x(),z=key.minBlockZ()-frame.renderOrigin().z();
            GL20.glUniform4f(previewBounds[index],(float)x,(float)z,(float)(x+width),(float)(z+width));
            GL20.glUniform1i(previewLevels[index++],key.level());
        }
        GL20.glUniform1i(previewCount,index);
    }
    void terrainLevel(int level) {GL20.glUniform1i(terrainLevel,level);}

    void material(int value, double minimumDistance, double start, double end) {
        GL20.glUniform1i(layer,value);
        GL20.glUniform1f(minimum,(float)minimumDistance);
        GL20.glUniform2f(farBand,(float)start,(float)end);
        GL20.glUniform1i(high,value==2?9:7); GL20.glUniform1i(low,value==2?10:8);
        GL20.glUniform1i(groundHigh,7); GL20.glUniform1i(groundLow,8);
    }

    void bindUniforms(ToIntFunction<String> lookup) {
        rotation=required(lookup,"uModelRotation"); origin=required(lookup,"uModelOriginYZ");
        originX=required(lookup,"uModelOriginX");
        projection=required(lookup,"uModelClipProjection"); radius=required(lookup,"uSSRadiusHiLo");
        eye=required(lookup,"uSSEyeRelative"); viewport=required(lookup,"uModelViewport");
        pass=required(lookup,"uModelPass"); high=required(lookup,"uModelHigh"); low=required(lookup,"uModelLow");
        surface=required(lookup,"uModelSurface"); cloud=required(lookup,"uModelCloud"); weather=required(lookup,"uModelWeather");
        layer=required(lookup,"uModelLayer"); minimum=required(lookup,"uModelMinimum"); farBand=required(lookup,"uModelFarBand");
        groundHigh=required(lookup,"uModelGroundHigh"); groundLow=required(lookup,"uModelGroundLow");
        distant=new RingworldDistantDepthUniforms(lookup); board=new RingworldBoardDepthUniforms(lookup);
        curved=new RingworldCurvedRayUniforms(lookup);
        ownMedia=new RingworldOwnMediaDepthUniforms(lookup);
        lightMode=required(lookup,"uModelLightMode");
        previewCount=required(lookup,"uModelPreviewCount");
        for(int i=0;i<previewBounds.length;i++)previewBounds[i]=required(lookup,"uModelPreviewBounds["+i+"]");
        terrainLevel=required(lookup,"uModelTerrainLevel");
        for(int i=0;i<previewLevels.length;i++)previewLevels[i]=required(lookup,"uModelPreviewLevels["+i+"]");
        String[] names={"BandHigh","BandLow","BoundsHigh","BoundsLow","DirectionHigh","DirectionLow"};
        for(int i=0;i<names.length;i++)lightUniforms[i]=required(lookup,"uModelLight"+names[i]);
    }

    private void uploadLight(int offset,double[] values) {
        float[] high=new float[4],low=new float[4];
        for(int i=0;i<4;i++){var split=RingworldCurvedRayUniforms.split(values[i]);high[i]=split[0];low[i]=split[1];}
        GL20.glUniform4f(lightUniforms[offset],high[0],high[1],high[2],high[3]);
        GL20.glUniform4f(lightUniforms[offset+1],low[0],low[1],low[2],low[3]);
    }

    void dispose() { if (program!=0) OpenGlHelper.glDeleteProgram(program); program=0; }

    private void create() {
        int vertex=0, fragment=0, candidate=0;
        try {
            vertex=compile("model.vert",OpenGlHelper.GL_VERTEX_SHADER);
            fragment=compile("model.frag",OpenGlHelper.GL_FRAGMENT_SHADER);
            candidate=OpenGlHelper.glCreateProgram();
            if (candidate==0) throw new IllegalStateException("Cannot allocate procedural ring program");
            OpenGlHelper.glAttachShader(candidate,vertex); OpenGlHelper.glAttachShader(candidate,fragment);
            OpenGlHelper.glLinkProgram(candidate);
            if (OpenGlHelper.glGetProgrami(candidate,OpenGlHelper.GL_LINK_STATUS)==GL11.GL_FALSE)
                throw new IllegalStateException("Cannot link procedural ring model: "+OpenGlHelper.glGetProgramInfoLog(candidate,32768));
            GL20.glDetachShader(candidate,vertex); GL20.glDetachShader(candidate,fragment);
            int linked=candidate;
            bindUniforms(name -> OpenGlHelper.glGetUniformLocation(linked,name));
            program=candidate; candidate=0;
        } finally {
            if (candidate!=0) OpenGlHelper.glDeleteProgram(candidate);
            if (vertex!=0) OpenGlHelper.glDeleteShader(vertex);
            if (fragment!=0) OpenGlHelper.glDeleteShader(fragment);
        }
    }

    private static int compile(String name,int type) {
        int shader=OpenGlHelper.glCreateShader(type);
        if (shader==0) throw new IllegalStateException("Cannot allocate procedural ring shader "+name);
        try {
            byte[] source=RingworldShaderSource.readCurved(new ResourceLocation("stellarium","shaders/ringworld/"+name));
            ByteBuffer buffer=BufferUtils.createByteBuffer(source.length); buffer.put(source).flip();
            OpenGlHelper.glShaderSource(shader,buffer); OpenGlHelper.glCompileShader(shader);
            if (OpenGlHelper.glGetShaderi(shader,OpenGlHelper.GL_COMPILE_STATUS)==GL11.GL_FALSE)
                throw new IllegalStateException("Cannot compile procedural ring "+name+": "+OpenGlHelper.glGetShaderInfoLog(shader,32768));
            return shader;
        } catch (RuntimeException | Error failure) { OpenGlHelper.glDeleteShader(shader); throw failure; }
    }
    private static int required(ToIntFunction<String> lookup,String name) {
        int location=lookup.applyAsInt(name);
        if (location<0) throw new IllegalStateException("Procedural ring uniform missing: "+name);
        return location;
    }
    private static void split4(int location,double a,double b) {
        float[] x=RingworldCurvedRayUniforms.split(a), y=RingworldCurvedRayUniforms.split(b);
        GL20.glUniform4f(location,x[0],x[1],y[0],y[1]);
    }
}
