package stellarium.world.ring.terrain;

import java.nio.file.Path;
import java.net.URL;
import java.net.JarURLConnection;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.MCPDummyContainer;
import net.minecraftforge.fml.common.MinecraftDummyContainer;
import net.minecraftforge.fml.common.InjectedModContainer;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import stellarium.StellarSky;
import stellarium.world.ring.generation.RingworldChunkGenerator;

/** Lazy per-world runtime: normal tick/unload consumers, bounded metadata IO and no client dependencies. */
public final class ServerPreviewRuntime {
    public static final ServerPreviewRuntime INSTANCE=new ServerPreviewRuntime();
    private static final AtomicLong EPOCHS=new AtomicLong();
    private final Map<WorldServer,Handle> worlds=new IdentityHashMap<>();
    private MinecraftServer server;
    private ThreadPoolExecutor metadata;
    private CompletableFuture<byte[]> environment;
    private final ArrayList<CompletableFuture<Void>> closing=new ArrayList<>();
    private ServerPreviewRuntime() {}

    public void start(MinecraftServer server) {
        if(this.server!=null)throw new IllegalStateException("Preview runtime already started");
        this.server=server;
        metadata=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{
            var thread=new Thread(r,"StellarSky-preview-metadata");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    /** Request-side entry point; returns empty while preparation is pending or unsupported. */
    public Optional<View> prepare(WorldServer world) {
        checkThread();
        closing.removeIf(CompletableFuture::isDone);
        if(closing.size()>=64)return Optional.empty();
        if(world.getMinecraftServer()!=server)throw new IllegalArgumentException("Foreign preview server");
        if(!(world.getChunkProvider().chunkGenerator instanceof RingworldChunkGenerator))return Optional.empty();
        var handle=worlds.get(world);
        if(handle==null) {
            if(worlds.size()>=16)return Optional.empty();
            handle=new Handle(world,EPOCHS.incrementAndGet()); worlds.put(world,handle);
            if(environment==null) {
                try {
                    var artifacts=Loader.instance().getActiveModList().stream().map(mod->
                            new PreviewCodeRevision.Artifact(mod.getModId(),mod.getVersion(),
                                    artifactSource(mod))).toList();
                    environment=work(()->{
                        try{return PreviewCodeRevision.capture(artifacts);}catch(Exception failure){throw new CompletionException(failure);}
                    });
                }catch(RuntimeException failure){environment=CompletableFuture.failedFuture(failure);}
            }
        }
        return handle.view();
    }
    public PreviewResponsePacket.Reason unavailableReason(WorldServer world) {
        checkThread();
        if(!(world.getChunkProvider().chunkGenerator instanceof RingworldChunkGenerator))return PreviewResponsePacket.Reason.UNSUPPORTED;
        var handle=worlds.get(world);
        if(handle!=null&&(handle.failed||handle.closed))return PreviewResponsePacket.Reason.FAILED;
        return PreviewResponsePacket.Reason.PREPARING;
    }
    @SubscribeEvent public void tick(TickEvent.WorldTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||event.world.isRemote||!(event.world instanceof WorldServer world)||server==null)return;
        var handle=worlds.get(world); if(handle==null)return;
        try{handle.tick();}catch(RuntimeException failure){handle.fail(failure);}
    }
    @SubscribeEvent public void unload(WorldEvent.Unload event) {
        if(event.getWorld().isRemote||!(event.getWorld() instanceof WorldServer world))return;
        var handle=worlds.remove(world); if(handle!=null)closing.add(handle.close().toCompletableFuture());
    }
    public void stop() {
        if(server==null)return;
        checkThread(); for(var handle:new ArrayList<>(worlds.values()))closing.add(handle.close().toCompletableFuture()); worlds.clear();
        metadata.shutdown();
        try {
            CompletableFuture.allOf(closing.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);
            if(!metadata.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("Preview metadata did not stop");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();StellarSky.INSTANCE.getLogger().error("Preview stop interrupted",interrupted);}
        catch(Exception failure){StellarSky.INSTANCE.getLogger().error("Preview stop did not drain cleanly",failure);}
        closing.clear(); server=null; environment=null;
    }
    private <T> CompletableFuture<T> work(Supplier<T> operation) {
        try{return CompletableFuture.supplyAsync(operation,metadata);}
        catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}
    }
    static Path artifactSource(ModContainer mod) {
        // Dummy containers (including injected coremods) inherit a placeholder source, not their code artifact.
        // Use their concrete defining class; Minecraft's metadata container represents WorldServer's code.
        ModContainer definition=mod instanceof InjectedModContainer injected?injected.wrappedContainer:mod;
        Class<?> builtin=definition.getClass()==MinecraftDummyContainer.class?WorldServer.class
                :definition instanceof DummyModContainer&&definition.getClass()!=DummyModContainer.class?definition.getClass():null;
        if(builtin!=null) {
            try {return codeSourcePath(Objects.requireNonNull(builtin.getProtectionDomain().getCodeSource()).getLocation());}
            catch(IOException|URISyntaxException invalid){throw new IllegalStateException("Invalid built-in code source",invalid);}
        }
        return Objects.requireNonNull(mod.getSource(),"Missing source for "+mod.getModId()).toPath();
    }
    static Path codeSourcePath(URL location) throws IOException,URISyntaxException {
        int depth=0;
        while(location.getProtocol().equals("jar")) {
            if(++depth>4)throw new IOException("Nested code source exceeds bound");
            location=((JarURLConnection)location.openConnection()).getJarFileURL();
        }
        if(!location.getProtocol().equals("file"))throw new IOException("Nonlocal preview code source");
        return Path.of(location.toURI());
    }
    private void checkThread() {
        if(server==null||!server.isCallingFromMinecraftThread())throw new IllegalStateException("Off preview server owner");
    }
    public record View(long epoch,PreviewCacheIdentity identity,SeedPreviewSession session) {}
    private record Prepared(PreviewCacheIdentity identity,Path directory) {}
    private final class Handle {
        final WorldServer world; final long epoch;
        CompletableFuture<Prepared> preparation; CompletableFuture<Void> ready;
        Prepared prepared; SeedPreviewIo io; ServerPreviewCoverage coverage; SeedPreviewSession session;
        OverworldAsyncColumnSource columns;
        boolean closed,failed;
        CompletionStage<Void> closingStage;
        Handle(WorldServer world,long epoch){this.world=world;this.epoch=epoch;}
        Optional<View> view(){return !closed&&!failed&&ready!=null&&ready.isDone()&&!ready.isCompletedExceptionally()
                ?Optional.of(new View(epoch,prepared.identity,session)):Optional.empty();}
        void tick() {
            if(closed||failed)return;
            if(preparation==null) {
                if(!environment.isDone())return;
                var fingerprint=((OverworldFingerprintSource)world.getChunkProvider().chunkGenerator)
                        .stellarium$capturePreviewFingerprint(environment.join());
                Path installation=server.getFile("stellarsky-preview/authority").toPath();
                Path worldRoot=world.getSaveHandler().getWorldDirectory().toPath().resolve("data/stellarsky-preview");
                int dimension=world.provider.getDimension();
                preparation=work(()->{
                    try {
                        var serverId=PreviewIdentityAuthority.persistentId(installation);
                        var identity=PreviewIdentityAuthority.issue(worldRoot.resolve("authority"),serverId,dimension,2,fingerprint);
                        Path generations=worldRoot.resolve("tiles").resolve("dim-"+dimension);
                        SeedPreviewRetention.prepare(generations,identity);
                        return new Prepared(identity,generations.resolve(identity.generationId().toString()));
                    }catch(Exception failure){throw new CompletionException(failure);}
                });
                return;
            }
            if(session==null) {
                if(!preparation.isDone())return; prepared=preparation.join();
                io=new SeedPreviewIo(prepared.directory,prepared.identity,epoch,512,256);
                coverage=new ServerPreviewCoverage(world,epoch,io,this::fail);
                var generator=(RingworldChunkGenerator)world.getChunkProvider().chunkGenerator;
                columns=new OverworldAsyncColumnSource(generator);
                session=new SeedPreviewSession(epoch,generator,columns,io,coverage,64);
                coverage.attach(session); ready=io.submit(()->(Void)null).toCompletableFuture();
            }
            if(!ready.isDone())return; ready.join();
            coverage.tick(); session.tick(64,1_000_000);
        }
        void fail(Throwable failure) {
            if(failed)return; failed=true;
            StellarSky.INSTANCE.getLogger().error("Seed preview disabled for dimension {}",world.provider.getDimension(),failure);
            close();
        }
        CompletionStage<Void> close() {
            if(closed)return closingStage;closed=true;
            if(coverage!=null)coverage.close();
            CompletionStage<Void> drained=session!=null?session.closeAsync():io!=null?io.closeAsync():CompletableFuture.completedFuture(null);
            if(columns!=null)columns.close();
            closingStage=drained;
            drained.whenComplete((ignored,failure)->{
                if(failure!=null)StellarSky.INSTANCE.getLogger().error("Seed preview close failed",failure);
            });
            if(preparation!=null)preparation.whenComplete((ignored,failure)->{
                if(failure!=null&&!failed)StellarSky.INSTANCE.getLogger().error("Seed preview preparation ended after unload",failure);
            });
            return closingStage;
        }
    }
}
