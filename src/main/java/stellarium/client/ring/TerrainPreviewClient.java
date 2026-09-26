package stellarium.client.ring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import stellarium.StellarSky;
import stellarium.world.ring.RingworldClockClientState;
import stellarium.world.ring.terrain.PreviewCacheIdentity;
import stellarium.world.ring.terrain.PreviewClientInbox;
import stellarium.world.ring.terrain.PreviewRequestPacket;
import stellarium.world.ring.terrain.PreviewResponsePacket;
import stellarium.world.ring.terrain.SeedTerrainTile;
import stellarium.world.ring.terrain.SeedPreviewDemand;
import stellarium.world.ring.terrain.SeedPreviewRefinement;
import stellarium.world.ring.terrain.TerrainPreviewTrace;
import stellarium.world.ring.terrain.TerrainPreviewTrace.ClientGate;
import stellarium.world.ring.terrain.TerrainPreviewTrace.ClockGate;
import stellarium.world.ring.terrain.TerrainTileKey;

/** Client-owner bounded demand and immutable display data; never interprets retirement as drawable coverage. */
public final class TerrainPreviewClient {
    private static World world;
    private static Object connection;
    private static UUID nonce;
    private static long generation,epoch,sequence,tick,nextHello;
    private static PreviewCacheIdentity identity;
    private static String availability="DISCONNECTED";
    private static final Map<TerrainTileKey,Pending> pending=new LinkedHashMap<>();
    private static final Map<TerrainTileKey,SeedTerrainTile> tiles=new LinkedHashMap<>();
    private static final Set<TerrainTileKey> retired=new LinkedHashSet<>();
    private static List<TerrainTileKey> demand=List.of();
    private static final SeedPreviewRefinement refinement=new SeedPreviewRefinement();
    private TerrainPreviewClient() {}
    public record RenderSnapshot(long epoch,List<SeedTerrainTile> tiles,List<TerrainTileKey> wanted) {
        public RenderSnapshot {tiles=List.copyOf(tiles);wanted=List.copyOf(wanted);}
    }
    public static List<SeedTerrainTile> tiles(Object renderWorld) {
        return snapshot(renderWorld).tiles();
    }
    public static RenderSnapshot snapshot(Object renderWorld) {
        var minecraft=Minecraft.getMinecraft();
        if(world==null||renderWorld!=world||world!=minecraft.world||connection!=minecraft.getConnection()||minecraft.player==null||epoch==0)
            return new RenderSnapshot(0,List.of(),List.of());
        return new RenderSnapshot(epoch,List.copyOf(tiles.values()),demand);
    }
    public static String status() {return "terrain="+availability+", terrainEpoch="+epoch+", received="+tiles.size()+", requests="+pending.size()+", retired="+retired.size()+", inboxOverflow="+PreviewClientInbox.overflowCount();}
    public static boolean retired(TerrainTileKey key) { return retired.contains(key); }
    public static void tick() {
        var minecraft=Minecraft.getMinecraft();
        if(world!=minecraft.world||connection!=minecraft.getConnection()) {
            world=minecraft.world;connection=minecraft.getConnection();generation++;
            nonce=UUID.randomUUID();epoch=0;sequence=0;nextHello=0;identity=null;
            availability=world==null?"DISCONNECTED":"PREPARING";
            pending.clear();tiles.clear();retired.clear();demand=List.of();refinement.clear();PreviewClientInbox.clear();
        }
        tick++;
        if(world==null||connection==null||minecraft.player==null||!StellarSky.INSTANCE.existOnServer()) {
            TerrainPreviewTrace.clientTickIdle();return;
        }
        for(int i=0;i<16;i++) {
            var entry=PreviewClientInbox.poll();if(entry==null)break;
            // Trace-only mirror of the clock gate below; the gate itself is unchanged.
            var receipt=entry.receipt();
            var traceClockCurrent=RingworldClockClientState.isCurrent(receipt);
            var traceClockTicket=traceClockCurrent&&StellarSky.PROXY.isCurrentRingworldClockConnection(receipt.ticket());
            if(receipt==null)TerrainPreviewTrace.clientClockGate(ClockGate.NULL_RECEIPT);
            else if(!traceClockCurrent||!traceClockTicket)TerrainPreviewTrace.clientClockGate(ClockGate.STALE_TICKET);
            if(!RingworldClockClientState.isCurrent(entry.receipt())
                    ||!StellarSky.PROXY.isCurrentRingworldClockConnection(entry.receipt().ticket()))continue;
            accept(entry.packet());
        }
        if(epoch==0) {
            if(tick>=nextHello) {send(new PreviewRequestPacket(nonce,generation,world.provider.getDimension(),0,0,
                    PreviewRequestPacket.Command.HELLO,0,0,0));nextHello=tick+100;}
            TerrainPreviewTrace.clientProgress(tick,pending.size(),tiles.size(),retired.size());
            return;
        }
        var wanted=refinement.plan(SeedPreviewDemand.around(epoch,(long)Math.floor(minecraft.player.posX)),
                minecraft.player.posX,minecraft.player.posZ);
        demand=wanted;
        for(var key:new ArrayList<>(pending.keySet()))if(!wanted.contains(key)) {
            send(request(key,PreviewRequestPacket.Command.RELEASE));TerrainPreviewTrace.clientSend(true);
            pending.remove(key);tiles.remove(key);retired.remove(key);
        }
        int requested=0;
        for(var key:wanted) {
            var previous=pending.get(key);
            if(previous!=null&&tick<previous.retryAt)continue;
            if(requested++>=2)break;
            var packet=request(key,PreviewRequestPacket.Command.TILE);
            pending.put(key,new Pending(packet.sequence(),tick+400));send(packet);
            TerrainPreviewTrace.clientSend(false);
            if(previous!=null)TerrainPreviewTrace.clientRetry();
        }
        TerrainPreviewTrace.clientProgress(tick,pending.size(),tiles.size(),retired.size());
    }
    private static void accept(PreviewResponsePacket packet) {
        if(!packet.nonce().equals(nonce)||packet.clientGeneration()!=generation||packet.dimension()!=world.provider.getDimension()) {
            TerrainPreviewTrace.clientAcceptDrop(ClientGate.ENVELOPE);return;
        }
        if(packet.type()==PreviewResponsePacket.Type.UNAVAILABLE&&packet.sequence()==0) {
            availability=packet.reason().name();
            if(packet.reason()==PreviewResponsePacket.Reason.UNSUPPORTED||packet.reason()==PreviewResponsePacket.Reason.FAILED)nextHello=tick+1200;
            TerrainPreviewTrace.clientResponse(PreviewResponsePacket.Type.UNAVAILABLE,packet.reason(),true);
            return;
        }
        if(packet.type()==PreviewResponsePacket.Type.WELCOME) {
            if(epoch!=0&&(epoch!=packet.epoch()||!identity.equals(packet.identity()))) {
                TerrainPreviewTrace.clientAcceptDrop(ClientGate.WELCOME_CONFLICT);return;
            }
            epoch=packet.epoch();identity=packet.identity();availability="READY";
            TerrainPreviewTrace.clientResponse(PreviewResponsePacket.Type.WELCOME,null,false);return;
        }
        if(packet.epoch()!=epoch||packet.key()==null) {TerrainPreviewTrace.clientAcceptDrop(ClientGate.EPOCH_OR_NULL_KEY);return;}
        var request=pending.get(packet.key());
        if(request==null||request.sequence!=packet.sequence()) {TerrainPreviewTrace.clientAcceptDrop(ClientGate.NO_PENDING_OR_SEQUENCE);return;}
        switch(packet.type()) {
            case TILE -> {
                if(identity.equals(packet.identity())) {
                    // Lease renewal must not rebuild an unchanged GPU mesh every twenty seconds.
                    if(!packet.tile().equals(tiles.get(packet.key())))tiles.put(packet.key(),packet.tile());
                    retired.remove(packet.key());
                    TerrainPreviewTrace.clientResponse(PreviewResponsePacket.Type.TILE,null,false);
                } else TerrainPreviewTrace.clientAcceptDrop(ClientGate.TILE_IDENTITY);
            }
            case RETIRED -> {
                retired.add(packet.key());refinement.reject(packet.key());
                TerrainPreviewTrace.clientRejected(true);
                TerrainPreviewTrace.clientResponse(PreviewResponsePacket.Type.RETIRED,null,false);
            }
            case UNAVAILABLE -> {
                pending.put(packet.key(),new Pending(packet.sequence(),tick+400));
                if(packet.reason()==PreviewResponsePacket.Reason.REAL_OR_UNKNOWN) {
                    refinement.reject(packet.key());TerrainPreviewTrace.clientRejected(false);
                }
                TerrainPreviewTrace.clientResponse(PreviewResponsePacket.Type.UNAVAILABLE,packet.reason(),false);
            }
            default -> { }
        }
    }
    private static PreviewRequestPacket request(TerrainTileKey key,PreviewRequestPacket.Command command) {
        return new PreviewRequestPacket(nonce,generation,world.provider.getDimension(),epoch,++sequence,command,key.level(),key.x(),key.z());
    }
    private static void send(PreviewRequestPacket packet) {StellarSky.INSTANCE.getNetworkManager().requestTerrainPreview(packet);}
    private record Pending(long sequence,long retryAt) {}
}
