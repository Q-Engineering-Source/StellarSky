package stellarium.client.ring;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import stellarium.StellarSky;
import stellarium.client.ClientSettings;
import stellarium.render.util.SamplerBindings;
import stellarium.client.ring.cloud.CloudLodGeometrySet;
import stellarium.client.ring.cloud.CloudModelHandoff;
import stellarium.world.ring.ProceduralRingModelCulling;
import stellarium.world.ring.ProceduralRingModelGeometry;
import stellarium.world.ring.ProceduralRingModelMaterial;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldStripBounds;
import stellarium.world.ring.terrain.SeedTerrainMesh;
import stellarium.world.ring.terrain.SeedTerrainTile;
import stellarium.world.ring.terrain.TerrainTileKey;
import stellarium.world.ring.terrain.TerrainCoverageMask;
import stellarium.world.ring.terrain.SeedTerrainOverlap;
import stellarium.world.ring.terrain.SeedTerrainResidency;
import stellarium.client.ring.dh.DistantHorizonsCoverageProjection;
import stellarium.client.ring.dh.DistantHorizonsDepthBridge;
import stellarium.client.ring.dh.DistantHorizonsFrameCoverage;
import stellarium.world.ring.terrain.SeedPreviewDemand;

/**
 * Cached procedural ring with cloud handoff enabled by default; camera/wind only change the rigid pose.
 */
public final class ProceduralRingModelRenderer {
    private static final ProceduralRingModelRenderer INSTANCE = new ProceduralRingModelRenderer();
    private static boolean enabled = true;
    private static boolean connected = true;
    private final ProceduralRingModelProgram program = new ProceduralRingModelProgram();
    private final RingworldMeshDistance distance = new RingworldMeshDistance(7, 8);
    private final CloudLodRasterRenderer clouds = new CloudLodRasterRenderer();
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
    private final Map<Integer, Presented> presented = new HashMap<>();
    private ThreadPoolExecutor executor;
    private Future<Prepared> pending;
    private Key requested;
    private Prepared prepared;
    private Object world, scene;
    private int vbo, cloudVbo, vao, surfaceTexture, cloudTexture;
    private long builds, uploads, frames;
    private final Map<TerrainTileKey,TerrainBuffer> terrainBuffers=new HashMap<>();
    private final SeedTerrainOverlap terrainOverlap=new SeedTerrainOverlap();
    private final SeedTerrainResidency terrainResidency=new SeedTerrainResidency();
    private Set<TerrainTileKey> terrainDrawn=Set.of();
    /** Last residency decision, reported by the diagnostics without recomputing it. */
    private SeedTerrainResidency.Selection terrainSelection;
    private long terrainEpoch;
    private long deferredSettles,deferredRetired;
    private record TerrainBuffer(SeedTerrainTile tile,SeedTerrainTile east,SeedTerrainTile north,SeedTerrainTile corner,int buffer,int count) {}

    /**
     * The early pass's resolved preview ground, held for the late settle of the same
     * optical pass. It carries no GL ownership: the reduction targets belong to
     * {@link RingworldMeshDistance} for the current render scope, and the terrain
     * buffers stay owned by this renderer's residency bookkeeping.
     */
    private record DeferredGround(RingworldCurvatureFrame frame, RingworldMeshDistance.Selection distance,
                                  int handoff, double start, double end, float rain,
                                  int viewportX,int viewportY,int viewportWidth,int viewportHeight) { }

    private record Key(double radius, double surfaceY, double cloudY, ProceduralRingModelMaterial.Settings material) { }
    private record Prepared(Key key, ProceduralRingModelGeometry geometry, ProceduralRingModelGeometry cloudGeometry,
                            ProceduralRingModelMaterial.Assets material) { }
    private record Presented(RingworldDisplaySnapshot snapshot, RingworldCurvatureFrame frame, boolean connected) { }

    private ProceduralRingModelRenderer() { }

    public static void setEnabled(boolean value) {
        setMode(value,value);
    }
    public static void setPreview() { setMode(true,false); }
    private static void setMode(boolean value, boolean handoff) {
        if (enabled!=value || connected!=handoff) {
            enabled=value; connected=handoff; INSTANCE.presented.clear(); RingworldGpuProfile.dispose();
        }
    }
    static boolean wantsConnection() { return enabled && connected; }
    public static String status() {
        return "程序化环带="+(enabled?(connected?"ON（远近衔接）":"PREVIEW（远景基线）"):"OFF")+", ready="+(INSTANCE.prepared!=null)
                +", builds="+INSTANCE.builds+", uploads="+INSTANCE.uploads+", frames="+INSTANCE.frames
                +"；clouds="+INSTANCE.clouds.status()+"；"+TerrainPreviewClient.status()+", terrainUploads="+INSTANCE.terrainBuffers.size()
                +", terrainSelected="+INSTANCE.terrainDrawn.size()
                +", deferredSettles="+INSTANCE.deferredSettles+", deferredRetiredColumns="+INSTANCE.deferredRetired
                +", "+RingworldDrawDiagnostics.statusLine();
    }

    /**
     * Null when a diagnostic pass can be sampled, otherwise the reason the command must report.
     *
     * <p>A disabled renderer never runs the pass, so arming there would leave a request that
     * nothing can complete; the command reports the state instead.</p>
     */
    static String diagnosticsUnavailable() {
        return enabled ? null : "rendererDisabled";
    }
    static boolean render(RingworldDisplaySnapshot snapshot, ClientSettings settings, float partialTicks) {
        return INSTANCE.drawCurrent(snapshot,settings,partialTicks,null,null);
    }
    static boolean renderConnected(SSCloudFrame cloudFrame, CloudLodGeometrySet meshes, ClientSettings settings, float partialTicks) {
        return INSTANCE.drawCurrent(cloudFrame.snapshot(),settings,partialTicks,cloudFrame,meshes);
    }
    static boolean presented(RingworldDisplaySnapshot snapshot, RingworldCurvatureFrame frame) {
        Presented shown=INSTANCE.presented.get(RingworldRenderSnapshots.scopeDepth());
        return enabled && shown!=null && shown.snapshot()==snapshot && shown.frame()==frame;
    }
    static boolean connectedPresented(RingworldDisplaySnapshot snapshot, RingworldCurvatureFrame frame) {
        Presented shown=INSTANCE.presented.get(RingworldRenderSnapshots.scopeDepth());
        return presented(snapshot,frame) && shown.connected();
    }
    public static void invalidate() { INSTANCE.release(); }

    private boolean drawCurrent(RingworldDisplaySnapshot snapshot, ClientSettings settings, float partialTicks,
                                SSCloudFrame cloudFrame, CloudLodGeometrySet meshes) {
        int scope=RingworldRenderSnapshots.scopeDepth();
        presented.remove(scope);
        if (!enabled) return false;
        RingworldCurvatureFrame frame=RingworldRenderSnapshots.currentDistantCurvatureFrameFor(snapshot.world(),snapshot.scene());
        if (frame==null || scope<0 || !RingworldDistantCurvature.viewportMatches(frame)) return false;
        frame.copyProjection(matrix);
        if (!RingworldBoardClipProjection.supports(matrix)) return false;
        if (world!=snapshot.world() || scene!=snapshot.scene()) {
            release(); world=snapshot.world(); scene=snapshot.scene();
        }
        double cloudY=Math.max(snapshot.airProfile().lowerY(),Math.min(snapshot.airProfile().upperY(),
                settings.ownCloudBaseY+settings.ownCloudCellHeight*settings.ownCloudLayers*0.5D));
        Key key=new Key(frame.geometry().radiusMeters(),Minecraft.getMinecraft().world.getSeaLevel(),cloudY,
                new ProceduralRingModelMaterial.Settings(settings.ownCloudSeed,
                        settings.renderOwnClouds?settings.ownCloudCoverage:0.0,settings.ownCloudErosion,2048,128));
        if (!prepare(key)) return false;
        prepareTerrain(key.radius(),key.surfaceY());
        // Only successfully uploaded buffers enter preview ownership. Cache the spatial choice;
        // camera/frustum changes do not rebuild it or turn a culled child back into a coarse parent.
        // This early pass runs before Distant Horizons submits anything, so its coverage record for
        // this frame cannot exist yet: the seed-tile self-suppression below is all that is knowable
        // here. The retirement of preview tiles against this frame's admitted DH coverage happens in
        // drawDeferredGround after DH has published it.
        var terrainCoverage=new HashMap<>(terrainOverlap.update(terrainDrawn));
        var pose=prepared.geometry().pose(frame.opticalEye().x(),frame.renderOrigin().y(),frame.renderOrigin().z(),frame.cameraX());
        float[] projection=new float[16], view=new float[16];
        frame.copyProjection(matrix); matrix.get(projection);
        frame.copyModelView(matrix); matrix.get(view);
        boolean[] visible=ProceduralRingModelCulling.visible(prepared.geometry().mesh(),pose,projection,view);
        boolean handoff=connected && cloudFrame!=null && meshes!=null;
        var cloudPose=prepared.cloudGeometry().pose(frame.opticalEye().x()-(handoff?cloudFrame.meshMotion().windOffsetBlocks():0.0D),
                frame.renderOrigin().y(),frame.renderOrigin().z(),frame.cameraX());
        boolean[] cloudVisible=handoff?ProceduralRingModelCulling.visible(prepared.cloudGeometry().mesh(),cloudPose,projection,view):null;
        viewport.clear(); GL11.glGetInteger(GL11.GL_VIEWPORT,viewport);
        int x=viewport.get(0), y=viewport.get(1), w=viewport.get(2), h=viewport.get(3);
        float rain=Minecraft.getMinecraft().world.getRainStrength(partialTicks);
        if (!Float.isFinite(rain)) throw new IllegalStateException("Procedural ring weather is not finite");
        int priorProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int priorVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int priorVbo=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        RingworldColorMaskScope masks=RingworldColorMaskScope.capture();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT|GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_LIGHTING_BIT|GL11.GL_CURRENT_BIT);
        try (MaterialBinding textures=new MaterialBinding(surfaceTexture,cloudTexture);
             var dh=RingworldDistantDepthUniforms.bindTexture()) {
            bindMesh(vbo);
            GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST); GL11.glAlphaFunc(GL11.GL_ALWAYS,0.0F);
            GL11.glDisable(GL11.GL_FOG); GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(true); GL11.glDepthFunc(GL11.GL_LEQUAL);
            program.use(frame,pose,Math.max(0.0F,Math.min(1.0F,rain)));
            program.previewFootprints(frame,terrainDrawn);
            double start=CloudModelHandoff.farStart(settings.ownCloudCellSize), end=CloudModelHandoff.farEnd(settings.ownCloudCellSize);
            int layer=handoff?1:0;
            program.material(layer,handoff?CloudModelHandoff.GROUND_PROXY_START:0.1D*key.radius(),start,end);
            var groundPass=new GroundPass(visible,terrainCoverage,layer,key.radius(),start,end,w,h);
            RingworldDrawDiagnostics.frameBegun();
            if(RingworldDrawDiagnostics.sampling())
                captureDiagnostics(frame,prepared.geometry(),visible,terrainDrawn,terrainSelection,w,h,key.radius());
            try (var timing=RingworldGpuProfile.measure(RingworldGpuProfile.Stage.MODEL_TOTAL)) {
                // Reductions only. The preview must not reach the visible colour attachment or the
                // own-media distance contract before this frame's DH coverage exists, but the far
                // cloud sheet still needs the ground's exact high/low keys to reject fragments the
                // ring ground precedes, so the geometry is submitted here exactly as before.
                var ground=distance.reduce(w,h,groundPass);
                if (handoff) try (var groundBinding=distance.bind(ground)) {
                    clouds.render(cloudFrame,meshes,frame,rain,ground,(stage,high,low,offsetX,offsetY)-> {
                        bindMesh(cloudVbo);
                        program.use(frame,cloudPose,rain);
                        program.material(2,start,start,end);
                        program.stage(stage,offsetX,offsetY,w,h);
                        drawMesh(prepared.cloudGeometry(),cloudVisible);
                    });
                }
                RingworldRenderSnapshots.captureDeferredPreview(new DeferredGround(frame,ground,layer,start,end,
                        rain,x,y,w,h));
            }
        } finally {
            OpenGlHelper.glUseProgram(priorProgram);
            GL30.glBindVertexArray(priorVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,priorVbo);
            GL11.glPopAttrib(); masks.close();
        }
        frames++;
        presented.put(scope,new Presented(snapshot,frame,handoff));
        return true;
    }

    /**
     * Settles this pass's preview ground colour after Distant Horizons and near terrain
     * have drawn.
     *
     * <p>Only here can the frame's admitted DH coverage be read, because the coverage
     * record is published when DH's own submission and main-view composition have both
     * returned. The mask retires preview tiles whose columns are real, so REAL_AIR -
     * which has no depth geometry to cover an obsolete preview - leaves no false ground.
     * Near terrain needs no mask: it already wrote the depth this pass tests against.</p>
     *
     * <p>Returns false without touching GL when this pass has no payload, when the
     * payload belongs to another optical pass or viewport, or when the reduction
     * targets no longer describe this frame.</p>
     */
    static boolean drawDeferredGround(RingworldCurvatureFrame frame) {
        boolean settled = INSTANCE.settleDeferredGround(frame);
        // Polling happens once per optical pass; it never blocks and never finishes a query.
        RingworldDrawDiagnostics.tick();
        return settled;
    }

    private boolean settleDeferredGround(RingworldCurvatureFrame frame) {
        if (!enabled) return false;
        // The settle's re-reduce (if DH retirement arrived) and colour pass are recorded as
        // late submissions, so an early key sample is never reported as the late selection.
        RingworldDrawDiagnostics.phaseLate();
        Object payload=RingworldRenderSnapshots.currentDeferredPreview();
        if (!(payload instanceof DeferredGround deferred) || deferred.frame()!=frame) return false;
        if (prepared==null || world==null || scene==null) return false;
        var snapshot=RingworldRenderSnapshots.current();
        if (snapshot==null || snapshot.world()!=world || snapshot.scene()!=scene) return false;
        viewport.clear(); GL11.glGetInteger(GL11.GL_VIEWPORT,viewport);
        if (viewport.get(0)!=deferred.viewportX()||viewport.get(1)!=deferred.viewportY()
                ||viewport.get(2)!=deferred.viewportWidth()||viewport.get(3)!=deferred.viewportHeight()) return false;
        if (!RingworldDistantCurvature.viewportMatches(frame)) return false;
        frame.copyProjection(matrix);
        if (!RingworldBoardClipProjection.supports(matrix)) return false;
        int x=deferred.viewportX(), y=deferred.viewportY(), w=deferred.viewportWidth(), h=deferred.viewportHeight();
        // The program binder rewrites the shared matrix with the far-plane removed
        // projection, so culling must read the frozen call matrices first.
        float[] proj=new float[16], mv=new float[16];
        frame.copyProjection(matrix); matrix.get(proj);
        frame.copyModelView(matrix); matrix.get(mv);
        var pose=prepared.geometry().pose(frame.opticalEye().x(),frame.renderOrigin().y(),frame.renderOrigin().z(),frame.cameraX());
        boolean[] visible=ProceduralRingModelCulling.visible(prepared.geometry().mesh(),pose,proj,mv);
        var terrainCoverage=new HashMap<>(terrainOverlap.update(terrainDrawn));
        var retirement=dhRetirementMasks(terrainDrawn,RingworldRenderSnapshots.currentDistantCoverage(),frame);
        int retired=0;
        for(var entry:retirement.entrySet()) {
            retired+=entry.getValue().coveredColumns();
            terrainCoverage.merge(entry.getKey(),entry.getValue(),TerrainCoverageMask::union);
        }
        int priorProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int priorVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int priorVbo=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        RingworldColorMaskScope masks=RingworldColorMaskScope.capture();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT|GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_LIGHTING_BIT|GL11.GL_CURRENT_BIT);
        try (MaterialBinding textures=new MaterialBinding(surfaceTexture,cloudTexture);
             var dh=RingworldDistantDepthUniforms.bindTexture();
             var board=RingworldBoardMeshDistance.bindSelected();
             var ownMedia=RingworldOwnMediaDepthUniforms.bindTextureOrNull()) {
            // This settle is a separate render stage from the early own-media pass: the DH
            // depth borrow taken there and the selected-board borrow taken by the cloud
            // renderer both ended with their own scopes, while program.use() below enables
            // exactly those uniforms (unit three; units five and six). Borrow both again for
            // this draw, so its board and DH distance comparisons read this pass's textures
            // instead of whatever those units still hold, and let the resource block restore
            // every unit in reverse order. Both borrows are no-ops when this frame has no
            // matching capture, which leaves those uniforms inactive.
            GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST); GL11.glAlphaFunc(GL11.GL_ALWAYS,0.0F);
            GL11.glDisable(GL11.GL_FOG); GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(true); GL11.glDepthFunc(GL11.GL_LEQUAL);
            program.use(frame,pose,deferred.rain());
            program.previewFootprints(frame,terrainDrawn);
            int layer=deferred.handoff();
            double radius=frame.geometry().radiusMeters();
            var groundPass=new GroundPass(visible,terrainCoverage,layer,radius,deferred.start(),deferred.end(),w,h);
            var selection=deferred.distance();
            try (var timing=RingworldGpuProfile.measure(RingworldGpuProfile.Stage.PREVIEW_SETTLE)) {
                if(!retirement.isEmpty()) {
                    // Retirement changes which preview cells exist, so the distance keys and the
                    // colour pass must be re-derived from the same region ownership. Reusing the
                    // early reduction here would let a retired nearer cell reject a kept one and
                    // punch a hole, which is exactly the inconsistency this stage must avoid.
                    selection=distance.reduce(w,h,groundPass);
                }
                distance.renderColor(selection,x,y,groundPass);
            }
            // The late colour submission has ended, so the armed pass is complete and its
            // results may now be polled asynchronously. Without this the state would stay in
            // SAMPLING and no query result would ever be read.
            RingworldDrawDiagnostics.frameFinished();
            deferredSettles++;
            deferredRetired=retired;
        } finally {
            OpenGlHelper.glUseProgram(priorProgram);
            GL30.glBindVertexArray(priorVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,priorVbo);
            GL11.glPopAttrib(); masks.close();
        }
        return true;
    }

    /**
     * The colour stage's expected own-media/DH/board captures, for the diagnostics report.
     *
     * <p>Absent captures are reported as -1 so a null id can never be mistaken for a bound
     * texture; the diagnostic compares each against the unit's real binding id.</p>
     */
    private static String expectedColorBindingState() {
        var dh=DistantHorizonsDepthBridge.current();
        var media=RingworldOwnMediaOcclusion.current();
        var board=RingworldBoardMeshDistance.current();
        return RingworldDrawDiagnostics.colorGlState(dh==null?-1:dh.textureId(),
                media==null?-1:media.textureId(),board==null?-1:board.high(),board==null?-1:board.low());
    }

    /** Bounded scope facts of the armed pass, harvested only while a request is sampling. */
    private void captureDiagnostics(RingworldCurvatureFrame frame,ProceduralRingModelGeometry geometry,boolean[] visible,
                                    Set<TerrainTileKey> drawn,SeedTerrainResidency.Selection selection,
                                    int width,int height,double radius) {
        var player=Minecraft.getMinecraft().player;
        RingworldDrawDiagnostics.scope("eye="+numbers(frame.opticalEye().x(),frame.opticalEye().y(),frame.opticalEye().z())
                +" renderOrigin="+numbers(frame.renderOrigin().x(),frame.renderOrigin().y(),frame.renderOrigin().z())
                +" player="+(player==null?"none":numbers(player.posX,player.posY,player.posZ))
                +" viewport="+width+"x"+height+" radiusM="+numbers(radius));
        var state=TerrainPreviewClient.snapshot(world);
        // Levels and tile widths come from the actual request/selection sets: the refinement
        // can descend below the base demand levels, so any fixed "mode" constant here would
        // misreport the scope it is supposed to explain.
        int wantedMinLevel=Integer.MAX_VALUE, wantedMaxLevel=-1;
        long wantedMaxWidth=0L;
        for(var key:state.wanted()) {
            wantedMinLevel=Math.min(wantedMinLevel,key.level());
            wantedMaxLevel=Math.max(wantedMaxLevel,key.level());
            wantedMaxWidth=Math.max(wantedMaxWidth,64L<<key.level());
        }
        int selectedMinLevel=Integer.MAX_VALUE, selectedMaxLevel=-1;
        long selectedMaxWidth=0L;
        for(var key:drawn) {
            selectedMinLevel=Math.min(selectedMinLevel,key.level());
            selectedMaxLevel=Math.max(selectedMaxLevel,key.level());
            selectedMaxWidth=Math.max(selectedMaxWidth,64L<<key.level());
        }
        RingworldDrawDiagnostics.terrain("wanted="+state.wanted().size()+" received="+state.tiles().size()
                +" resident="+(selection==null?0:selection.resident().size())+" selected="+drawn.size()
                +" epoch="+state.epoch()
                +" wantedLevels="+(wantedMaxLevel<0?"none":wantedMinLevel+"-"+wantedMaxLevel)
                +" wantedTileWidthMaxBlocks="+wantedMaxWidth
                +" selectedLevels="+(selectedMaxLevel<0?"none":selectedMinLevel+"-"+selectedMaxLevel)
                +" selectedTileWidthMaxBlocks="+selectedMaxWidth
                +" proxyStripZ=["+RingworldStripBounds.BOARD_MIN_Z+","+RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE+")");
        int visibleBatches=0,first=-1,last=-1; long visibleVerts=0L;
        for(int i=0;i<visible.length;i++)if(visible[i]) {
            visibleBatches++;
            visibleVerts+=geometry.mesh().batch(i).vertexCount();
            if(first<0)first=i;
            last=i;
        }
        RingworldDrawDiagnostics.proxy("segmentsVisible="+visibleBatches+"/"+geometry.mesh().batchCount()
                +" firstVisibleBatch="+first+" lastVisibleBatch="+last+" visibleSegmentVerts="+visibleVerts);
        RingworldDrawDiagnostics.tiles(diagnosticTileLines(frame.opticalEye().x(),frame.opticalEye().y(),
                frame.opticalEye().z(),drawn),drawn.size());
    }

    /** One line per selected drawable tile, bounded by the diagnostics budget. */
    private List<String> diagnosticTileLines(double eyeX,double eyeY,double eyeZ,Set<TerrainTileKey> drawn) {
        var byKey=new HashMap<TerrainTileKey,TerrainBuffer>();
        for(var terrain:terrainBuffers.values())byKey.put(terrain.tile().key(),terrain);
        var lines=new java.util.ArrayList<String>();
        for(var key:drawn) {
            var terrain=byKey.get(key);
            if(terrain==null)continue;
            int empty=0,land=0,ocean=0;
            double minDistance=Double.POSITIVE_INFINITY,maxDistance=0.0;
            int stride=SeedTerrainTile.sampleStride(key),strideZ=SeedTerrainTile.sampleStrideZ(key);
            long spacing=1L<<key.level();
            for(int x=0;x<64;x+=stride)for(int z=0;z<64;z+=strideZ) {
                var column=terrain.tile().columns().get(x*64+z);
                switch(column.kind()) {
                    case EMPTY -> empty++;
                    case LAND -> land++;
                    case OCEAN -> ocean++;
                }
                if(column.kind()==SeedTerrainTile.Kind.EMPTY)continue;
                double worldX=key.minBlockX()+x*spacing, worldZ=key.minBlockZ()+z*spacing;
                double dx=worldX-eyeX, dy=column.groundTop()-eyeY, dz=worldZ-eyeZ;
                double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
                minDistance=Math.min(minDistance,distance);
                maxDistance=Math.max(maxDistance,distance);
            }
            lines.add(RingworldDrawDiagnostics.PREFIX+" tile key=E"+key.worldEpoch()+" L"+key.level()
                    +" x="+key.x()+" z="+key.z()
                    +" boundsX=["+key.minBlockX()+","+(key.minBlockX()+(64L<<key.level()))+")"
                    +" boundsZ=["+key.minBlockZ()+","+(key.minBlockZ()+(64L<<key.level()))+")"
                    +" colsEmpty="+empty+" colsLand="+land+" colsOcean="+ocean
                    +" bufferVerts="+terrain.count()+" tris="+(terrain.count()/3)
                    +" sampledAnchorRangeM=["+(empty==4096?"none":numbers(minDistance)+","+numbers(maxDistance))+"]");
        }
        return lines;
    }

    private static String numbers(double... values) {
        var text=new StringBuilder();
        for(int i=0;i<values.length;i++) {
            if(i>0)text.append(',');
            text.append(String.format(java.util.Locale.ROOT,"%.3f",values[i]));
        }
        return text.toString();
    }

    /**
     * This frame's admitted Distant Horizons retirement mask for the preview tiles that
     * are actually drawable.
     *
     * <p>Only the current optical frame's completed record is consulted, and only through
     * {@link DistantHorizonsCoverageProjection}, which admits a column solely from a live
     * submitted VBO set plus a completed composite. Neither a previous frame, nor raw DH
     * data existing on disk, can produce a mask here.</p>
     */
    static Map<TerrainTileKey,TerrainCoverageMask> dhRetirementMasks(Set<TerrainTileKey> drawn,
            DistantHorizonsFrameCoverage.Snapshot coverage,Object frame) {
        if(drawn.isEmpty()||coverage==null||frame==null)return Map.of();
        long epoch=drawn.iterator().next().worldEpoch();
        for(var key:drawn)if(key.worldEpoch()!=epoch)throw new IllegalArgumentException("Mixed preview worlds");
        var patches=DistantHorizonsCoverageProjection.currentPatches(epoch,coverage,frame);
        if(patches.isEmpty())return Map.of();
        var masks=new HashMap<TerrainTileKey,TerrainCoverageMask>();
        for(var tileKey:drawn)masks.put(tileKey,TerrainCoverageMask.project(tileKey,patches));
        return Map.copyOf(masks);
    }

    private static int drawTerrain(TerrainBuffer terrain,TerrainCoverageMask mask) {        int submitted=0;
        if(mask==null||mask.coveredColumns()==0) {GL11.glDrawArrays(GL11.GL_TRIANGLES,0,terrain.count());return terrain.count();}
        int vertex=0,start=0;
        int stride=SeedTerrainMesh.stride(terrain.tile().key());
        int strideZ=SeedTerrainTile.sampleStrideZ(terrain.tile().key());
        for(int x=0;x<64;x+=stride)for(int z=0;z<64;z+=strideZ) {
            int index=x*64+z;
            if(terrain.tile().columns().get(index).kind()==SeedTerrainTile.Kind.EMPTY)continue;
            boolean covered=true;
            for(int dx=0;dx<stride;dx++)for(int dz=0;dz<strideZ;dz++)covered&=mask.covers(x+dx,z+dz);
            if(covered) {
                if(vertex>start){GL11.glDrawArrays(GL11.GL_TRIANGLES,start,vertex-start);submitted+=vertex-start;}
                start=vertex+6;
            }
            vertex+=6;
        }
        if(vertex>start){GL11.glDrawArrays(GL11.GL_TRIANGLES,start,vertex-start);submitted+=vertex-start;}
        return submitted;
    }
    private void prepareTerrain(double radius,double summaryY) {
        var state=TerrainPreviewClient.snapshot(world);
        if(state.epoch()!=terrainEpoch) {
            terrainSelection=null;
            for(var terrain:terrainBuffers.values())GL15.glDeleteBuffers(terrain.buffer());
            terrainBuffers.clear();terrainResidency.clear();terrainOverlap.clear();terrainDrawn=Set.of();terrainEpoch=state.epoch();
        }
        if(terrainEpoch==0)return;
        var tiles=state.tiles();
        var sources=new HashMap<TerrainTileKey,SeedTerrainTile>();
        for(var terrain:terrainBuffers.values())sources.put(terrain.tile().key(),terrain.tile());
        for(var tile:tiles)sources.put(tile.key(),tile);
        // One bounded mesh upload per frame; camera motion never rebuilds a tile.
        for(var tile:tiles) {
            var previous=terrainBuffers.get(tile.key());
            var key=tile.key();
            var east=key.level()>=8?null:sources.get(new TerrainTileKey(key.worldEpoch(),key.level(),key.x()+1,key.z()));
            var north=key.level()>=8?null:sources.get(new TerrainTileKey(key.worldEpoch(),key.level(),key.x(),key.z()+1));
            var corner=key.level()>=8?null:sources.get(new TerrainTileKey(key.worldEpoch(),key.level(),key.x()+1,key.z()+1));
            if(previous!=null&&previous.tile()==tile&&previous.east()==east&&previous.north()==north&&previous.corner()==corner)continue;
            float[] data=SeedTerrainMesh.build(tile,radius,summaryY,east,north,corner);
            var vertices=BufferUtils.createFloatBuffer(data.length);vertices.put(data).flip();
            int binding=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),buffer=GL15.glGenBuffers();
            if(buffer==0)throw new IllegalStateException("Cannot allocate seed terrain buffer");
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,vertices,GL15.GL_STATIC_DRAW);
                if(GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER,GL15.GL_BUFFER_SIZE)!=data.length*Float.BYTES)
                    throw new IllegalStateException("Incomplete seed terrain buffer upload");
            }
            catch(RuntimeException|Error failure){GL15.glDeleteBuffers(buffer);throw failure;}
            finally {GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,binding);}
            terrainBuffers.put(tile.key(),new TerrainBuffer(tile,east,north,corner,buffer,data.length/16));
            if(previous!=null)GL15.glDeleteBuffers(previous.buffer());
            break;
        }
        var selection=terrainResidency.update(terrainEpoch,state.wanted(),terrainBuffers.keySet(),Minecraft.getMinecraft().player.posX);
        terrainSelection=selection;
        terrainBuffers.entrySet().removeIf(entry->{
            if(selection.resident().contains(entry.getKey()))return false;
            GL15.glDeleteBuffers(entry.getValue().buffer());return true;
        });
        terrainDrawn=selection.drawable();
    }
    private boolean prepare(Key key) {
        if (!key.equals(requested)) {
            if(requested==null||key.radius()!=requested.radius()||key.surfaceY()!=requested.surfaceY())
                terrainBuffers.entrySet().removeIf(entry->{
                    if(entry.getKey().level()<8)return false;
                    GL15.glDeleteBuffers(entry.getValue().buffer());return true;
                });
            if (pending!=null) pending.cancel(true);
            if (executor==null) executor=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),task->{
                Thread thread=new Thread(task,"StellarSky-ring-model"); thread.setDaemon(true); return thread;
            });
            executor.purge();
            pending=executor.submit(()->new Prepared(key,new ProceduralRingModelGeometry(key.radius(),key.surfaceY(),
                    RingworldStripBounds.BOARD_MIN_Z,RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE,
                    ProceduralRingModelGeometry.cloudSegments(key.radius())),
                    new ProceduralRingModelGeometry(key.radius(),key.cloudY(),RingworldStripBounds.BOARD_MIN_Z,
                            RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE,ProceduralRingModelGeometry.cloudSegments(key.radius())),
                    ProceduralRingModelMaterial.generate(key.material())));
            requested=key; builds++;
        }
        if (pending!=null && pending.isDone()) {
            Prepared ready;
            try { ready=pending.get(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while admitting ring model",failure); }
            catch (ExecutionException failure) { throw new IllegalStateException("Procedural ring generation failed",failure.getCause()); }
            upload(ready);
            prepared=ready; pending=null; uploads++;
            StellarSky.INSTANCE.getLogger().info("SS procedural ring ready: radius={} m, segments={}, vertices={}, meshBytes={}, materialBaseBytes={}, builds={}, uploads={}",
                    key.radius(),ready.geometry().angularSegments(),ready.geometry().mesh().vertexCount(),ready.geometry().mesh().byteCount(),ready.material().byteSize(),builds,uploads);
        }
        return prepared!=null && prepared.key().equals(key);
    }

    private void upload(Prepared ready) {
        int previous=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int buffer=0, cloudBuffer=0, vertexArray=0, ground=0, clouds=0;
        try {
            buffer=GL15.glGenBuffers(); vertexArray=GL30.glGenVertexArrays();
            if (buffer==0 || vertexArray==0) throw new IllegalStateException("Cannot allocate procedural ring mesh");
            FloatBuffer vertices=BufferUtils.createFloatBuffer(ready.geometry().mesh().floatCount());
            ready.geometry().mesh().writeTo(vertices); vertices.flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer); GL15.glBufferData(GL15.GL_ARRAY_BUFFER,vertices,GL15.GL_STATIC_DRAW);
            if (GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER,GL15.GL_BUFFER_SIZE)!=ready.geometry().mesh().byteCount())
                throw new IllegalStateException("Procedural ring mesh upload size differs");
            cloudBuffer=GL15.glGenBuffers();
            if (cloudBuffer==0) throw new IllegalStateException("Cannot allocate far cloud mesh");
            FloatBuffer cloudVertices=BufferUtils.createFloatBuffer(ready.cloudGeometry().mesh().floatCount());
            ready.cloudGeometry().mesh().writeTo(cloudVertices); cloudVertices.flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,cloudBuffer); GL15.glBufferData(GL15.GL_ARRAY_BUFFER,cloudVertices,GL15.GL_STATIC_DRAW);
            if (GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER,GL15.GL_BUFFER_SIZE)!=ready.cloudGeometry().mesh().byteCount())
                throw new IllegalStateException("Far cloud mesh upload size differs");
            ground=uploadTexture(ready.material().surfaceRgba8Buffer(),ready.material().width(),ready.material().height());
            clouds=uploadTexture(ready.material().cloudRgba8Buffer(),ready.material().width(),ready.material().height());
            deleteAssets(); vbo=buffer; cloudVbo=cloudBuffer; vao=vertexArray; surfaceTexture=ground; cloudTexture=clouds;
            buffer=cloudBuffer=vertexArray=ground=clouds=0;
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,previous);
            if (buffer!=0) GL15.glDeleteBuffers(buffer);
            if (cloudBuffer!=0) GL15.glDeleteBuffers(cloudBuffer);
            if (vertexArray!=0) GL30.glDeleteVertexArrays(vertexArray);
            if (ground!=0) GL11.glDeleteTextures(ground);
            if (clouds!=0) GL11.glDeleteTextures(clouds);
        }
    }

    private static int uploadTexture(ByteBuffer source,int width,int height) {
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int pbo=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] names={GL11.GL_UNPACK_ALIGNMENT,GL11.GL_UNPACK_ROW_LENGTH,GL11.GL_UNPACK_SKIP_PIXELS,GL11.GL_UNPACK_SKIP_ROWS};
        int[] values=new int[names.length];
        for (int i=0;i<names.length;i++) values[i]=GL11.glGetInteger(names[i]);
        int texture=GL11.glGenTextures(); boolean complete=false;
        if (texture==0) throw new IllegalStateException("Cannot allocate procedural ring texture");
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
            for (int i=0;i<names.length;i++) GL11.glPixelStorei(names[i],i==0?1:0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            ByteBuffer direct=BufferUtils.createByteBuffer(source.remaining()); direct.put(source).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,width,height,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,direct);
            if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH)!=width
                    || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT)!=height)
                throw new IllegalStateException("Procedural ring texture upload size differs");
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            complete=true; return texture;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous); GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,pbo);
            for (int i=0;i<names.length;i++) GL11.glPixelStorei(names[i],values[i]);
            if (!complete) GL11.glDeleteTextures(texture);
        }
    }

    private void release() {
        // Reclaims diagnostic queries; an armed request that never sampled survives teardown.
        RingworldDrawDiagnostics.onRendererRelease();
        terrainResidency.clear();terrainDrawn=Set.of();terrainEpoch=0;
        deferredSettles=0;deferredRetired=0;
        terrainOverlap.clear();
        for(var terrain:terrainBuffers.values())GL15.glDeleteBuffers(terrain.buffer());
        terrainBuffers.clear();
        presented.clear();
        if (pending!=null) pending.cancel(true);
        if (executor!=null) executor.shutdownNow();
        pending=null; executor=null; requested=null; prepared=null; world=scene=null;
        deleteAssets(); program.dispose(); distance.dispose(); clouds.dispose();
    }
    private void deleteAssets() {
        if (vbo!=0) GL15.glDeleteBuffers(vbo);
        if (cloudVbo!=0) GL15.glDeleteBuffers(cloudVbo);
        if (vao!=0) GL30.glDeleteVertexArrays(vao);
        if (surfaceTexture!=0) GL11.glDeleteTextures(surfaceTexture);
        if (cloudTexture!=0) GL11.glDeleteTextures(cloudTexture);
        vbo=cloudVbo=vao=surfaceTexture=cloudTexture=0;
    }
    private void bindMesh(int buffer) {
        GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer);
        attribute(0,3,0); attribute(1,3,3); attribute(2,4,6); attribute(3,4,10); attribute(4,2,14);
    }
    private static int drawMesh(ProceduralRingModelGeometry geometry, boolean[] visible) {
        int submitted=0;
        for (int i=0;i<visible.length;i++) if (visible[i]) {
            var batch=geometry.mesh().batch(i);
            GL11.glDrawArrays(GL11.GL_TRIANGLES,batch.firstVertex(),batch.vertexCount());
            submitted+=batch.vertexCount();
        }
        return submitted;
    }
    private static void attribute(int index,int count,int offset) {
        GL20.glVertexAttribPointer(index,count,GL11.GL_FLOAT,false,ProceduralRingModelGeometry.FLOATS_PER_VERTEX*Float.BYTES,(long)offset*Float.BYTES);
        GL20.glEnableVertexAttribArray(index);
    }
    /**
     * The preview ground's per-stage submission, shared by the early reduction and the late
     * settle so both distance keys and final colour always describe the same region ownership.
     */
    private final class GroundPass implements RingworldMeshDistance.Pass {
        private final boolean[] visible;
        private final Map<TerrainTileKey,TerrainCoverageMask> coverage;
        private final int layer,width,height;
        private final double radius,start,end;

        private GroundPass(boolean[] visible,Map<TerrainTileKey,TerrainCoverageMask> coverage,int layer,
                           double radius,double start,double end,int width,int height) {
            this.visible=visible;this.coverage=coverage;this.layer=layer;this.radius=radius;
            this.start=start;this.end=end;this.width=width;this.height=height;
        }

        @Override public void draw(int stage,int high,int low,int offsetX,int offsetY) {
            boolean diag=RingworldDrawDiagnostics.sampling();
            if(diag)RingworldDrawDiagnostics.beginQuery(stage);
            long submittedVerts=0L, submittedMeshes=0L;
            try {
                bindMesh(vbo);
                program.material(layer,layer==1?CloudModelHandoff.GROUND_PROXY_START:0.1D*radius,start,end);
                program.stage(stage,offsetX,offsetY,width,height);
                int meshVerts=drawMesh(prepared.geometry(),visible);
                if(meshVerts>0){submittedVerts+=meshVerts;submittedMeshes++;}
                program.material(3,0,start,end);
                for(var terrain:terrainBuffers.values()) {
                    if(!terrainDrawn.contains(terrain.tile().key()))continue;
                    program.terrainLevel(terrain.tile().key().level());
                    bindMesh(terrain.buffer());
                    int terrainVerts=drawTerrain(terrain,coverage.get(terrain.tile().key()));
                    if(terrainVerts>0){submittedVerts+=terrainVerts;submittedMeshes++;}
                }
            } finally {
                // The query must be closed even when a submission path throws, or the target
                // stays owned by an open query and every later diagnostic reports SKIPPED.
                if(diag){
                    // Read the colour stage's own GL state only after every normal binding
                    // above has been made, so the report describes the state the draw used.
                    if(stage==RingworldDrawDiagnostics.COLOR)RingworldDrawDiagnostics.colorBinding(expectedColorBindingState());
                    RingworldDrawDiagnostics.endStage(stage,submittedMeshes,submittedVerts);
                }
            }
        }
    }

    private static final class MaterialBinding implements AutoCloseable {
        private final int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int[] textures=new int[2], samplers=new int[2];
        private MaterialBinding(int ground,int cloud) {
            for (int i=0;i<2;i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+i);
                textures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); samplers[i]=SamplerBindings.get(i);
            }
            for (int i=0;i<2;i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+i); GL11.glBindTexture(GL11.GL_TEXTURE_2D,i==0?ground:cloud); GL33.glBindSampler(i,0);
            }
            GL13.glActiveTexture(active);
        }
        @Override public void close() {
            for (int i=0;i<2;i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+i); GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]); GL33.glBindSampler(i,samplers[i]);
            }
            GL13.glActiveTexture(active);
        }
    }
}
