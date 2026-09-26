package stellarium.world.ring.generation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import stellarium.world.ring.terrain.OverworldDensitySource;
import stellarium.world.ring.terrain.OverworldColumnSource;
import stellarium.world.ring.terrain.SeedTerrainSource;
import stellarium.world.ring.terrain.SeedPreviewQueue;
import stellarium.world.ring.terrain.SeedTerrainTile;
import stellarium.world.ring.terrain.TerrainTileKey;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import stellarium.world.ring.terrain.OverworldFingerprintSource;
import stellarium.world.ring.terrain.PreviewIdentityAuthority;
import stellarium.world.ring.terrain.SeedPreviewDiskCache;
import stellarium.world.ring.terrain.SeedTerrainChunk;
import stellarium.world.ring.terrain.SeedPreviewIo;
import stellarium.world.ring.terrain.SeedPreviewSession;
import stellarium.world.ring.terrain.ServerPreviewCoverage;
import net.minecraft.world.WorldServer;
import java.util.concurrent.TimeUnit;
import stellarium.world.ring.terrain.OverworldAsyncColumnSource;
import java.util.Random;
import net.minecraft.world.gen.NoiseGeneratorOctaves;
import stellarium.world.ring.terrain.PreviewNoiseState;
import stellarium.world.ring.terrain.VanillaBiomeFingerprint;
import net.minecraft.world.biome.BiomeProvider;

/** Disposable CPU reference only; the production sampler never allocates ChunkPrimer. */
@Mod(modid="c53densityprobe",name="C53 density CPU probe",version="1",acceptableRemoteVersions="*")
public class C53DensityProbeMod {
    @Mod.EventHandler public void started(FMLServerStartedEvent event) throws Exception {
        if(Files.exists(Path.of("c53-preview-queue.request"))) { previewQueue(); return; }
        if(Files.exists(Path.of("c53-async-column.request"))) { verifyAsyncColumnQueue(); return; }
        if(!Files.exists(Path.of("c53-density.request"))) return;
        var world=FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0);
        if(!world.getWorldInfo().getWorldName().equals("disposable-ring-world")) throw new IllegalStateException("Disposable world required");
        if(!(world.getChunkProvider().chunkGenerator instanceof ChunkGeneratorOverworld generator)) throw new IllegalStateException("Vanilla generator required");
        var sampler=(OverworldDensitySource)generator;
        var biomeBefore=VanillaBiomeFingerprint.capture(world.getBiomeProvider());
        verifyNoiseState();
        int before=world.getChunkProvider().getLoadedChunkCount();
        verifyIdentityAndCache(generator, sampler);
        verifySession(world, sampler);
        verifyColumnSampler(generator, sampler);
        var lines=new ArrayList<String>();
        lines.add("seed="+world.getSeed()+" generator="+generator.getClass().getName()+" loadedBefore="+before);
        for(int chunkX:new int[]{1024,2048}) {
            var reference=new ChunkPrimer();
            generator.setBlocksInChunk(chunkX,0,reference);
            long start=System.nanoTime();
            var sample=sampler.stellarium$sampleDensity(chunkX,0);
            long nanos=System.nanoTime()-start;
            int maxError=0,land=0,min=256,max=0;
            for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
                int expected=0;
                for(int y=255;y>=0;y--) if(reference.getBlockState(x,y,z).getBlock()==Blocks.STONE) {expected=y+1;break;}
                var surface=sample.surface(x,z);
                maxError=Math.max(maxError,Math.abs(expected-surface.baseGroundTop()));
                if(surface.land())land++;
                min=Math.min(min,surface.visibleTop()); max=Math.max(max,surface.visibleTop());
            }
            var after=new ChunkPrimer(); generator.setBlocksInChunk(chunkX,0,after);
            for(int x=0;x<16;x++) for(int z=0;z<16;z++) for(int y=0;y<256;y++)
                if(reference.getBlockState(x,y,z)!=after.getBlockState(x,y,z)) throw new AssertionError("Generator changed after sampling");
            if(maxError>1) throw new AssertionError("Base interpolation error="+maxError);
            lines.add("chunkX="+chunkX+" chunkZ=0 sampleNanos="+nanos+" minVisible="+min+" maxVisible="+max
                    +" landColumns="+land+" maxBaseError="+maxError+" referenceUnchanged=true");
        }
        int after=world.getChunkProvider().getLoadedChunkCount();
        if(before!=after)throw new AssertionError("Preview changed loaded chunks");
        if(!Arrays.equals(biomeBefore,VanillaBiomeFingerprint.capture(world.getBiomeProvider())))
            throw new AssertionError("Biome scratch state changed persistent fingerprint");
        try { VanillaBiomeFingerprint.capture(new BiomeProvider() {}); throw new AssertionError("Unknown provider admitted"); }
        catch(UnsupportedOperationException expected) { lines.add("biomeSamplingStable=true unknownProviderRejected=true"); }
        lines.add("loadedAfter="+after+" noChunkCountChange=true");
        Files.write(Path.of("c53-density-results.txt"),lines);
    }

    private void verifyNoiseState() throws Exception {
        var first=new NoiseGeneratorOctaves(new Random(123),4);
        var same=new NoiseGeneratorOctaves(new Random(123),4);
        var different=new NoiseGeneratorOctaves(new Random(124),4);
        var initial=noiseDigest(first);
        if(!Arrays.equals(initial,noiseDigest(same))||Arrays.equals(initial,noiseDigest(different)))
            throw new AssertionError("Noise state identity is not deterministic/sensitive");
        first.generateNoiseOctaves(null,4,5,6,5,33,5,1,1,1);
        if(!Arrays.equals(initial,noiseDigest(first)))throw new AssertionError("Output sampling changed noise identity");
        Files.writeString(Path.of("c53-noise-results.txt"),
                "sameSeedEqual=true differentStateDifferent=true samplingStable=true\n",StandardCharsets.UTF_8);
    }

    private void verifyColumnSampler(ChunkGeneratorOverworld generator, OverworldDensitySource reference) throws Exception {
        var source=(OverworldColumnSource)generator;
        var context=source.stellarium$createColumnContext();
        int comparisons=0;
        for(int chunkX:new int[]{-2048,-1024,1024,2048})
            for(int chunkZ:new int[]{-511,0,511}) {
                var biomes=source.stellarium$captureColumnBiomes(chunkX,chunkZ);
                var column=context.sample(chunkX,chunkZ,biomes);
                var expected=reference.stellarium$sampleDensity(chunkX,chunkZ).surface(0,0);
                if(column.groundTop()!=expected.baseGroundTop() || column.visibleTop()!=expected.visibleTop()
                        || (column.kind()==SeedTerrainTile.Kind.LAND)!=expected.land())
                    throw new AssertionError("Single-column density differs at " + chunkX + "," + chunkZ
                            + ": " + column + " / " + expected);
                comparisons++;
            }
        Files.writeString(Path.of("c53-column-results.txt"),
                "seedLinkedSingleColumnEqual=true comparisons="+comparisons+"\n",StandardCharsets.UTF_8);
    }

    private void verifyAsyncColumnQueue() throws Exception {
        var world=FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0);
        if(!world.getWorldInfo().getWorldName().equals("disposable-ring-world"))
            throw new IllegalStateException("Disposable world required");
        if(!(world.getChunkProvider().chunkGenerator instanceof ChunkGeneratorOverworld generator))
            throw new IllegalStateException("Vanilla generator required");
        var ring=(RingworldChunkGenerator)RingworldChunkGenerator.wrap(world,generator,
                new RingworldBiomeProvider(world.getBiomeProvider(),SpaceBiomeRegistry.space()),true);
        int loadedBefore=world.getChunkProvider().getLoadedChunkCount();
        var owner=Thread.currentThread();
        var calls=new AtomicInteger();
        long maxTickNanos=0,start=System.nanoTime();
        try(var columns=new OverworldAsyncColumnSource(ring);
                var queue=new SeedPreviewQueue(1,ring,(cx,cz)->{
            if(Thread.currentThread()!=owner)throw new AssertionError("Biome capture left owner thread");
            calls.incrementAndGet();
            return columns.sample(cx,cz);
        },1)) {
            var ticket=queue.request(new TerrainTileKey(1,6,0,0)).orElseThrow();
            long deadline=start+TimeUnit.SECONDS.toNanos(120);
            while(!ticket.result().toCompletableFuture().isDone()) {
                if(System.nanoTime()>deadline)throw new AssertionError("Async column tile stalled");
                long tickStart=System.nanoTime();queue.tick(64,1_000_000);
                maxTickNanos=Math.max(maxTickNanos,System.nanoTime()-tickStart);
                Thread.sleep(1);
            }
            var tile=ticket.result().toCompletableFuture().join();
            if(calls.get()!=4096 || tile.columns().size()!=4096
                    || tile.columns().stream().anyMatch(column->column.kind()==SeedTerrainTile.Kind.EMPTY))
                throw new AssertionError("Async tile omitted an interior sample");
            if(loadedBefore!=world.getChunkProvider().getLoadedChunkCount())
                throw new AssertionError("Async preview loaded real chunks");
            Files.writeString(Path.of("c53-async-column-results.txt"),
                    "columns=4096 calls="+calls.get()+" ownerBiomeCapture=true loadedChunksUnchanged=true"
                            +" elapsedMillis="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)
                            +" maxTickNanos="+maxTickNanos+"\n",StandardCharsets.UTF_8);
        }
    }
    private byte[] noiseDigest(NoiseGeneratorOctaves noise) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        ((PreviewNoiseState)noise).stellarium$appendNoiseState(digest); return digest.digest();
    }

    private void verifySession(WorldServer world, OverworldDensitySource sampler) throws Exception {
        var root=Path.of("c53-session-fixture");
        var server=PreviewIdentityAuthority.persistentId(root.resolve("server"));
        var fingerprint=MessageDigest.getInstance("SHA-256").digest("diagnostic-session-only".getBytes(StandardCharsets.UTF_8));
        var identity=PreviewIdentityAuthority.issue(root.resolve("world"),server,0,1,fingerprint);
        var io=new SeedPreviewIo(root.resolve("tiles"),identity,1,8,64);
        var coverage=new ServerPreviewCoverage(world,1,io);
        var calls=new AtomicInteger();
        var session=new SeedPreviewSession(1,(x,z)->{calls.incrementAndGet();return new SeedTerrainChunk.Density(sampler.stellarium$sampleDensity(x,z));},io,coverage,8);
        coverage.attach(session);
        try {
            var loaded=world.getChunkProvider().getLoadedChunks().iterator().next();
            var realKey=TerrainTileKey.atBlock(1,0,(long)loaded.x*16,(long)loaded.z*16);
            var real=session.request(realKey).orElseThrow();
            drive(session,coverage,real);
            if(real.result().toCompletableFuture().join().isPresent()||calls.get()!=0)throw new AssertionError("Loaded chunk admitted preview");
            var key=new TerrainTileKey(1,0,256,0);
            var request=session.request(key).orElseThrow(); drive(session,coverage,request);
            var publication=request.result().toCompletableFuture().join().orElseThrow();
            if(!session.current(publication)||calls.get()!=16)throw new AssertionError("Session source path failed");
            // Exact event path; no real terrain is generated by this diagnostic.
            var eventChunk=new net.minecraft.world.chunk.Chunk(world,1024,0);
            coverage.chunkLoaded(new net.minecraftforge.event.world.ChunkEvent.Load(eventChunk));
            if(session.current(publication))throw new AssertionError("Real event retained old publication");
            Files.writeString(Path.of("c53-session-results.txt"),
                    "liveChunkRejected=true persistedAbsenceQueried=true sourceCalls=16 columns=4096 realEventRevoked=true\n",StandardCharsets.UTF_8);
        } finally {
            coverage.close(); session.closeAsync().toCompletableFuture().get(10,TimeUnit.SECONDS);
        }
    }
    private void drive(SeedPreviewSession session,ServerPreviewCoverage coverage,SeedPreviewSession.Request request) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!request.result().toCompletableFuture().isDone()) {
            if(System.nanoTime()>deadline)throw new AssertionError("Session diagnostic stalled");
            coverage.tick(); session.tick(64,1_000_000); Thread.sleep(1);
        }
    }

    private void verifyIdentityAndCache(ChunkGeneratorOverworld generator, OverworldDensitySource sampler) throws Exception {
        // Explicit diagnostic-only environment revision, never admission for a production modpack.
        var revision=MessageDigest.getInstance("SHA-256").digest(
                "c53-disposable-density-authority-fixture-v1".getBytes(StandardCharsets.UTF_8));
        var capture=(OverworldFingerprintSource)generator;
        var fingerprint=capture.stellarium$capturePreviewFingerprint(revision);
        if(!Arrays.equals(fingerprint,capture.stellarium$capturePreviewFingerprint(revision)))
            throw new AssertionError("Live settings fingerprint unstable");
        var root=Path.of("c53-authority-fixture");
        var serverId=PreviewIdentityAuthority.persistentId(root.resolve("server"));
        var identity=PreviewIdentityAuthority.issue(root.resolve("world"),serverId,0,1,fingerprint);
        if(!identity.equals(PreviewIdentityAuthority.issue(root.resolve("world"),serverId,0,1,fingerprint)))
            throw new AssertionError("Authority changed on reopen");
        SeedTerrainTile tile;
        try(var queue=new SeedPreviewQueue(1,(x,z)->new SeedTerrainChunk.Density(sampler.stellarium$sampleDensity(x,z)),1)) {
            var ticket=queue.request(new TerrainTileKey(1,0,256,0)).orElseThrow();
            int steps=0;
            while(queue.pending()>0) {
                if(++steps>2000)throw new AssertionError("Authority fixture queue stalled");
                queue.tick(32,1_000_000L);
            }
            tile=ticket.result().toCompletableFuture().join();
        }
        try(var cache=new SeedPreviewDiskCache(root.resolve("tiles"),identity,1,1)) {
            if(!cache.write(cache.beginWrite(tile.key()).orElseThrow(),tile))throw new AssertionError("Cache write rejected");
        }
        var rebound=new TerrainTileKey(2,0,256,0);
        try(var cache=new SeedPreviewDiskCache(root.resolve("tiles"),identity,2,1)) {
            var loaded=cache.read(rebound).orElseThrow();
            if(!loaded.columns().equals(tile.columns()))throw new AssertionError("Cached terrain changed");
            if(!loaded.key().equals(rebound))throw new AssertionError("Cache epoch not rebound");
        }
        revision[0]^=1;
        var rotated=PreviewIdentityAuthority.issue(root.resolve("world"),serverId,0,1,
                capture.stellarium$capturePreviewFingerprint(revision));
        if(identity.generationId().equals(rotated.generationId()))throw new AssertionError("Pipeline revision did not rotate");
        try {
            try(var ignored=new SeedPreviewDiskCache(root.resolve("tiles"),rotated,2,1)) {
                throw new AssertionError("Old namespace was accepted");
            }
        } catch(java.io.IOException expected) {
            // The old valid cache belongs to a different generation and must remain intact.
        }
        Files.writeString(Path.of("c53-authority-results.txt"),
                "liveCapture=true stableReopen=true columns=4096 epochRebind=true generationRotation=true oldCacheRejected=true\n",
                StandardCharsets.UTF_8);
    }

    private void previewQueue() throws Exception {
        var world=FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0);
        if(!world.getWorldInfo().getWorldName().equals("disposable-ring-world")) throw new IllegalStateException("Disposable world required");
        if(!(world.getChunkProvider().chunkGenerator instanceof SeedTerrainSource source)) throw new IllegalStateException("Ring preview source required");
        var fingerprintSource=(OverworldFingerprintSource)source;
        var revision=MessageDigest.getInstance("SHA-256").digest("ring-pipeline-fixture-v1".getBytes(StandardCharsets.UTF_8));
        var fingerprint=fingerprintSource.stellarium$capturePreviewFingerprint(revision);
        int before=world.getChunkProvider().getLoadedChunkCount();
        var calls=new AtomicInteger();
        var lines=new ArrayList<String>();
        try(var queue=new SeedPreviewQueue(1,(x,z)->{calls.incrementAndGet();return source.sample(x,z);},2)) {
            var interior=queue.request(new TerrainTileKey(1,0,256,0)).orElseThrow();
            var exterior=queue.request(new TerrainTileKey(1,0,256,128)).orElseThrow();
            int steps=0,maxColumns=0; long maxNanos=0;
            while(queue.pending()>0) {
                if(++steps>2000) throw new AssertionError("Queue failed to make bounded progress");
                long start=System.nanoTime(); int count=queue.tick(32,1_000_000L);
                maxNanos=Math.max(maxNanos,System.nanoTime()-start); maxColumns=Math.max(maxColumns,count);
                if(count>32)throw new AssertionError("Column budget exceeded");
            }
            var inside=interior.result().toCompletableFuture().join();
            var outside=exterior.result().toCompletableFuture().join();
            if(inside.columns().stream().anyMatch(c->c.kind()==SeedTerrainTile.Kind.EMPTY))throw new AssertionError("Interior preview missing");
            if(outside.columns().stream().anyMatch(c->c.kind()!=SeedTerrainTile.Kind.EMPTY))throw new AssertionError("Space preview contains terrain");
            if(calls.get()!=32)throw new AssertionError("Unexpected repeated chunk samples "+calls.get());
            lines.add("tiles=2 columns=8192 sourceCalls="+calls.get()+" steps="+steps+" maxColumnsPerStep="+maxColumns+" maxStepNanos="+maxNanos);
            lines.add("interiorNonEmpty=4096 exteriorEmpty=4096");
        }
        int after=world.getChunkProvider().getLoadedChunkCount();
        if(before!=after)throw new AssertionError("Queue loaded chunks");
        lines.add("loadedBefore="+before+" loadedAfter="+after);
        if(!Arrays.equals(fingerprint,fingerprintSource.stellarium$capturePreviewFingerprint(revision)))
            throw new AssertionError("Ring pipeline identity changed after sampling");
        lines.add("ringPipelineCaptured=true samplingStable=true");
        Files.write(Path.of("c53-preview-queue-results.txt"),lines);
    }
}
