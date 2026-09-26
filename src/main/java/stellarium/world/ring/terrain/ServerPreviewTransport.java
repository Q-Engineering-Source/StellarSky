package stellarium.world.ring.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import stellarium.StellarSky;
import stellarium.world.ring.terrain.TerrainPreviewTrace.BusySite;
import stellarium.world.ring.terrain.TerrainPreviewTrace.RealOrUnknownCause;
import stellarium.world.ring.terrain.TerrainPreviewTrace.ServerGate;

/** Bounded network ingress; all session and player access occurs on the server thread. */
public final class ServerPreviewTransport {
    public static final ServerPreviewTransport INSTANCE=new ServerPreviewTransport(new MinecraftGateway());
    private final Gateway gateway;
    private final ArrayBlockingQueue<Incoming> incoming=new ArrayBlockingQueue<>(256);
    private final Map<Object,Peer> peers=new IdentityHashMap<>();
    private final Map<TerrainTileKey,Shared> shared=new HashMap<>();
    private volatile boolean running;
    private long ticks;
    ServerPreviewTransport(Gateway gateway) {this.gateway=Objects.requireNonNull(gateway);}
    public void start() { incoming.clear(); ticks=0; running=true; }
    public void stop() {
        running=false;incoming.clear();
        for(var peer:peers.values())release(peer);
        peers.clear();shared.clear();
    }
    public void offer(Object connection,PreviewRequestPacket packet) {
        // A full ingress queue drops requests; clients retry with bounded backoff.
        if(running) {var accepted=incoming.offer(new Incoming(connection,packet));TerrainPreviewTrace.serverIngress(true,accepted);}
        else TerrainPreviewTrace.serverIngress(false,false);
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if(!running||event.phase!=TickEvent.Phase.END)return;
        advance();
    }
    void advance() {
        if(!running)return;
        ticks++;
        for(var entry:new ArrayList<>(peers.entrySet())) {
            var connection=entry.getKey();var peer=entry.getValue();
            var state=gateway.connection(connection);
            if(state==null||state.world()!=peer.world) {
                peers.remove(connection);release(peer);
            } else {
                for(var lease:new ArrayList<>(peer.leases.values()))
                    if(ticks-lease.touched>=1200) {TerrainPreviewTrace.serverLeaseExpired();remove(peer,lease.request.key());}
            }
        }
        for(int count=0;count<32;count++) {
            var next=incoming.poll();if(next==null)break;
            try { accept(next); }
            catch(RuntimeException failure) {
                gateway.failed("Terrain preview request failed",failure);
                send(next.connection,PreviewResponsePacket.unavailable(next.packet,PreviewResponsePacket.Reason.FAILED));
                TerrainPreviewTrace.serverSent(PreviewResponsePacket.Type.UNAVAILABLE,PreviewResponsePacket.Reason.FAILED);
            }
        }
        int sent=0;
        for(var entry:peers.entrySet()) {
            var peer=entry.getValue();
            for(var lease:new ArrayList<>(peer.leases.values())) {
                if(sent>=8) {TerrainPreviewTrace.serverSilentDrop(ServerGate.RESPONSE_BUDGET);return;}
                if(!lease.shared.future.isDone())continue;
                try {
                    var publication=lease.shared.future.join();
                    // The three sub-conditions are extracted for the trace; evaluation order and values are unchanged.
                    var published=publication.isPresent();
                    var usable=published&&lease.shared.view.session().usable();
                    boolean current=published&&usable&&lease.shared.view.session().current(publication.get());
                    if(!current) {
                        if(!published)TerrainPreviewTrace.serverRealOrUnknown(RealOrUnknownCause.NO_PUBLICATION);
                        else if(!usable)TerrainPreviewTrace.serverRealOrUnknown(RealOrUnknownCause.SESSION_UNUSABLE);
                        else TerrainPreviewTrace.serverRealOrUnknown(RealOrUnknownCause.NOT_CURRENT);
                        send(entry.getKey(),lease.sent?PreviewResponsePacket.retired(lease.request)
                                :PreviewResponsePacket.unavailable(lease.request,PreviewResponsePacket.Reason.REAL_OR_UNKNOWN));
                        remove(peer,lease.request.key());sent++;
                        if(lease.sent)TerrainPreviewTrace.serverSent(PreviewResponsePacket.Type.RETIRED,null);
                        else TerrainPreviewTrace.serverSent(PreviewResponsePacket.Type.UNAVAILABLE,PreviewResponsePacket.Reason.REAL_OR_UNKNOWN);
                    } else if(!lease.sent) {
                        send(entry.getKey(),PreviewResponsePacket.tile(lease.request,lease.shared.view.identity(),publication.get().tile()));
                        TerrainPreviewTrace.serverSent(PreviewResponsePacket.Type.TILE,null);
                        lease.sent=true;sent++;
                    }
                } catch(RuntimeException failure) {
                    gateway.failed("Terrain preview publication failed",failure);
                    send(entry.getKey(),PreviewResponsePacket.unavailable(lease.request,PreviewResponsePacket.Reason.FAILED));
                    TerrainPreviewTrace.serverSent(PreviewResponsePacket.Type.UNAVAILABLE,PreviewResponsePacket.Reason.FAILED);
                    remove(peer,lease.request.key());sent++;
                }
            }
        }
        int leases=0;for(var peer:peers.values())leases+=peer.leases.size();
        TerrainPreviewTrace.serverGauges(peers.size(),leases,shared.size(),incoming.size());
    }
    private void accept(Incoming next) {
        var connection=next.connection;var packet=next.packet;
        var state=gateway.connection(connection);
        if(state==null||state.dimension()!=packet.dimension()) {TerrainPreviewTrace.serverSilentDrop(ServerGate.DIM_OR_CONNECTION);return;}
        var world=state.world();var peer=peers.get(connection);
        if(packet.command()==PreviewRequestPacket.Command.HELLO) {
            if(peer!=null&&(packet.clientGeneration()<peer.generation
                    ||packet.clientGeneration()==peer.generation&&!packet.nonce().equals(peer.nonce))) {
                TerrainPreviewTrace.serverSilentDrop(ServerGate.STALE_HELLO);return;
            }
            if(peer==null||packet.clientGeneration()>peer.generation) {
                if(peer!=null)release(peer);
                if(peer==null&&peers.size()>=64) {
                    TerrainPreviewTrace.serverBusy(BusySite.PEER_CAP);
                    send(connection,PreviewResponsePacket.unavailable(packet,PreviewResponsePacket.Reason.BUSY));
                    TerrainPreviewTrace.serverSent(PreviewResponsePacket.Type.UNAVAILABLE,PreviewResponsePacket.Reason.BUSY);return;
                }
                peer=new Peer(world,packet.nonce(),packet.clientGeneration());peers.put(connection,peer);
            }
            var view=gateway.prepare(world);
            if(view.isPresent()) {
                peer.view=view.get();send(connection,PreviewResponsePacket.welcome(packet,view.get().identity(),view.get().epoch()));
                TerrainPreviewTrace.serverHello();
            } else {
                var reason=gateway.unavailableReason(world);
                send(connection,PreviewResponsePacket.unavailable(packet,reason));
                TerrainPreviewTrace.serverSent(PreviewResponsePacket.Type.UNAVAILABLE,reason);
            }
            return;
        }
        if(peer==null||peer.view==null||peer.world!=world||!peer.nonce.equals(packet.nonce())
                ||peer.generation!=packet.clientGeneration()||peer.view.epoch()!=packet.epoch()||packet.sequence()<=peer.sequence) {
            TerrainPreviewTrace.serverSilentDrop(ServerGate.LEASE_GATE);return;
        }
        peer.sequence=packet.sequence();
        if(packet.command()==PreviewRequestPacket.Command.RELEASE) {remove(peer,packet.key());return;}
        var key=packet.key();
        long width=64L<<key.level();
        double centerX=key.minBlockX()+width*0.5,centerZ=key.minBlockZ()+width*0.5;
        if(key.level()>12||Math.abs(centerX-state.x())>width*8.0
                ||Math.abs(centerZ-state.z())>width*8.0||peer.leases.size()>=SeedPreviewDemand.MAX_TILES&&!peer.leases.containsKey(key)) {
            TerrainPreviewTrace.serverBusy(BusySite.RANGE_OR_LEASE_CAP);
            send(connection,PreviewResponsePacket.unavailable(packet,PreviewResponsePacket.Reason.BUSY));
            TerrainPreviewTrace.serverSent(PreviewResponsePacket.Type.UNAVAILABLE,PreviewResponsePacket.Reason.BUSY);return;
        }
        var existing=peer.leases.get(key);
        if(existing!=null) {existing.request=packet;existing.sent=false;existing.touched=ticks;return;}
        var tile=shared.get(key);
        if(tile==null) {
            if(shared.size()>=256) {
                TerrainPreviewTrace.serverBusy(BusySite.SHARED_CAP);
                send(connection,PreviewResponsePacket.unavailable(packet,PreviewResponsePacket.Reason.BUSY));
                TerrainPreviewTrace.serverSent(PreviewResponsePacket.Type.UNAVAILABLE,PreviewResponsePacket.Reason.BUSY);return;
            }
            var request=peer.view.session().request(key);
            if(request.isEmpty()) {
                TerrainPreviewTrace.serverBusy(BusySite.SESSION_REFUSED);
                send(connection,PreviewResponsePacket.unavailable(packet,PreviewResponsePacket.Reason.BUSY));
                TerrainPreviewTrace.serverSent(PreviewResponsePacket.Type.UNAVAILABLE,PreviewResponsePacket.Reason.BUSY);return;
            }
            tile=new Shared(peer.view,request.get().result().toCompletableFuture());shared.put(key,tile);
        }
        tile.references++;peer.leases.put(key,new Lease(packet,tile,ticks));
    }
    private void release(Peer peer) { for(var key:new ArrayList<>(peer.leases.keySet()))remove(peer,key); }
    private void remove(Peer peer,TerrainTileKey key) {
        var lease=peer.leases.remove(key);if(lease==null)return;
        if(--lease.shared.references==0) {
            shared.remove(key,lease.shared);
            if(lease.shared.view.session().usable())lease.shared.view.session().release(key);
        }
    }
    private void send(Object connection,PreviewResponsePacket packet) {
        gateway.send(connection,packet);
    }
    private record Incoming(Object connection,PreviewRequestPacket packet) {}
    private static final class Peer {
        final Object world;final UUID nonce;final long generation;
        long sequence;ServerPreviewRuntime.View view;
        final Map<TerrainTileKey,Lease> leases=new HashMap<>();
        Peer(Object world,UUID nonce,long generation){this.world=world;this.nonce=nonce;this.generation=generation;}
    }
    private static final class Shared {
        final ServerPreviewRuntime.View view;final CompletableFuture<Optional<SeedPreviewAdmission.Publication>> future;
        int references;
        Shared(ServerPreviewRuntime.View view,CompletableFuture<Optional<SeedPreviewAdmission.Publication>> future){this.view=view;this.future=future;}
    }
    private static final class Lease {
        PreviewRequestPacket request;final Shared shared;boolean sent;long touched;
        Lease(PreviewRequestPacket request,Shared shared,long touched){this.request=request;this.shared=shared;this.touched=touched;}
    }

    /** Game-thread boundary, allowing the same bounded transport to run against disposable sessions. */
    interface Gateway {
        Connection connection(Object connection);
        Optional<ServerPreviewRuntime.View> prepare(Object world);
        PreviewResponsePacket.Reason unavailableReason(Object world);
        void send(Object connection,PreviewResponsePacket packet);
        void failed(String message,RuntimeException failure);
    }
    record Connection(Object world,int dimension,double x,double z) {}
    private static final class MinecraftGateway implements Gateway {
        @Override public Connection connection(Object value) {
            var connection=(NetHandlerPlayServer)value;
            if(!connection.netManager.isChannelOpen()||connection.player.connection!=connection)return null;
            var player=connection.player;
            return new Connection(player.getServerWorld(),player.dimension,player.posX,player.posZ);
        }
        @Override public Optional<ServerPreviewRuntime.View> prepare(Object world) {
            return ServerPreviewRuntime.INSTANCE.prepare((WorldServer)world);
        }
        @Override public PreviewResponsePacket.Reason unavailableReason(Object world) {
            return ServerPreviewRuntime.INSTANCE.unavailableReason((WorldServer)world);
        }
        @Override public void send(Object value,PreviewResponsePacket packet) {
            var connection=(NetHandlerPlayServer)value;
            StellarSky.INSTANCE.getNetworkManager().sendTerrainPreview(connection.player,packet);
        }
        @Override public void failed(String message,RuntimeException failure) {
            StellarSky.INSTANCE.getLogger().warn(message,failure);
        }
    }
}
