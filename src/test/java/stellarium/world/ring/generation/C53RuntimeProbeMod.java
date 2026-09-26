package stellarium.world.ring.generation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import stellarium.world.ring.terrain.SeedPreviewSession;
import stellarium.world.ring.terrain.ServerPreviewRuntime;
import stellarium.world.ring.terrain.TerrainTileKey;
import stellarium.world.ring.terrain.PreviewRequestPacket;
import stellarium.world.ring.terrain.PreviewResponsePacket;
import stellarium.world.ring.terrain.SeedTerrainTile;

/** Uses real Forge ticks and one explicitly generated disposable chunk to verify event invalidation. */
@Mod(modid="c53runtimeprobe",name="C53 runtime preview probe",version="1",acceptableRemoteVersions="*")
public class C53RuntimeProbeMod {
    private WorldServer world;
    private ServerPreviewRuntime.View view;
    private SeedPreviewSession.Request request;
    private int ticks,phase,loadedBefore;
    @Mod.EventHandler public void started(FMLServerStartedEvent event) {
        if(!Files.exists(Path.of("c53-runtime.request")))return;
        world=FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0);
        if(!world.getWorldInfo().getWorldName().equals("disposable-ring-world"))throw new IllegalStateException("Disposable world required");
        if(!(world.getChunkProvider().chunkGenerator instanceof RingworldChunkGenerator))throw new IllegalStateException("Enabled ring required");
        loadedBefore=world.getChunkProvider().getLoadedChunkCount();
        MinecraftForge.EVENT_BUS.register(this);
        ServerPreviewRuntime.INSTANCE.prepare(world);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void tick(TickEvent.WorldTickEvent event) throws Exception {
        if(event.world!=world||event.phase!=TickEvent.Phase.END)return;
        if(++ticks>600)throw new AssertionError("Normal runtime preview did not complete");
        if(phase==0) {
            var ready=ServerPreviewRuntime.INSTANCE.prepare(world); if(ready.isEmpty())return;
            view=ready.get(); request=view.session().request(new TerrainTileKey(view.epoch(),0,256,0)).orElseThrow();phase=1;return;
        }
        if(!request.result().toCompletableFuture().isDone())return;
        var result=request.result().toCompletableFuture().join();
        if(phase==1) {
            var publication=result.orElseThrow();
            if(!view.session().current(publication))throw new AssertionError("Runtime publication stale before real generation");
            if(world.getChunkProvider().getLoadedChunkCount()!=loadedBefore)throw new AssertionError("Preview loaded chunks");
            world.getChunkProvider().provideChunk(1024,0);
            if(view.session().current(publication))throw new AssertionError("Actual chunk load did not revoke preview");
            request=view.session().request(new TerrainTileKey(view.epoch(),0,256,0)).orElseThrow();phase=2;return;
        }
        if(phase==2) {
            if(result.isPresent())throw new AssertionError("Real chunk was replaced with preview again");
            request=view.session().request(new TerrainTileKey(view.epoch(),8,2,0)).orElseThrow();phase=3;return;
        }
        var far=result.orElseThrow().tile();
        long empty=far.columns().stream().filter(column->column.kind()==SeedTerrainTile.Kind.EMPTY).count();
        long expected=phase==3?2048:3968;
        if(empty!=expected)throw new AssertionError("Far ring/Space split differs at phase "+phase+": "+empty);
        if(world.getChunkProvider().getLoadedChunkCount()!=loadedBefore+1)throw new AssertionError("Far preview generated real chunks");
        var wire=PreviewResponsePacket.tile(new PreviewRequestPacket(UUID.randomUUID(),1,0,view.epoch(),1,
                PreviewRequestPacket.Command.TILE,far.key().level(),far.key().x(),far.key().z()),view.identity(),far);
        var bytes=Unpooled.buffer();
        try {wire.encode(bytes);if(!wire.equals(PreviewResponsePacket.decode(bytes)))throw new AssertionError("Actual far tile wire mismatch");}
        finally {bytes.release();}
        if(phase==3) {
            request=view.session().request(new TerrainTileKey(view.epoch(),12,1,-1)).orElseThrow();phase=4;return;
        }
        if(phase==4) {
            request=view.session().request(new TerrainTileKey(view.epoch(),12,1,0)).orElseThrow();phase=5;return;
        }
        if(world.getChunkProvider().getLoadedChunk(1024,0)==null)throw new AssertionError("Diagnostic real chunk missing");
        Files.writeString(Path.of("c53-runtime-results.txt"),
                "normalForgeTicks=true environmentIdentity=true previewColumns=4096 farColumns=4096 farSpaceColumns=2048 widestLevel=12 widestNegativeSpace=3968 widestPositiveSpace=3968 farWireRoundTrip=true realChunkEventRevoked=true realReadmissionRejected=true ticks="+ticks
                        +" loadedBefore="+loadedBefore+" loadedAfterExplicitGeneration="+world.getChunkProvider().getLoadedChunkCount()+"\n",StandardCharsets.UTF_8);
        MinecraftForge.EVENT_BUS.unregister(this);world=null;
    }
}
