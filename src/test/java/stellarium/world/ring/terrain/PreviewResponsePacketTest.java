package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import org.junit.Test;

public class PreviewResponsePacketTest {
    private final UUID nonce=UUID.randomUUID();
    private final PreviewCacheIdentity identity=new PreviewCacheIdentity(UUID.randomUUID(),UUID.randomUUID(),3,UUID.randomUUID(),1);
    private final PreviewRequestPacket hello=new PreviewRequestPacket(nonce,2,3,0,0,PreviewRequestPacket.Command.HELLO,0,0,0);
    private final PreviewRequestPacket request=new PreviewRequestPacket(nonce,2,3,7,4,PreviewRequestPacket.Command.TILE,0,-1,1);
    private SeedTerrainTile tile(){return new SeedTerrainTile(request.key(),Collections.nCopies(4096,new SeedTerrainTile.Column(SeedTerrainTile.Kind.LAND,80,80)));}
    @Test public void allRepliesRoundTripWithExplicitBounds() throws Exception {
        for(var reply:new PreviewResponsePacket[]{PreviewResponsePacket.welcome(hello,identity,7),
                PreviewResponsePacket.tile(request,identity,tile()),PreviewResponsePacket.retired(request),
                PreviewResponsePacket.unavailable(hello,PreviewResponsePacket.Reason.PREPARING),
                PreviewResponsePacket.unavailable(request,PreviewResponsePacket.Reason.REAL_OR_UNKNOWN)}) {
            var bytes=Unpooled.buffer();try{reply.encode(bytes);assertTrue(bytes.readableBytes()<=20666);assertEquals(reply,PreviewResponsePacket.decode(bytes));}
            finally{bytes.release();}
        }
    }
    @Test public void corruptPayloadAndWrongEnvelopeWorldCannotBeAccepted() throws Exception {
        var reply=PreviewResponsePacket.tile(request,identity,tile());var bytes=Unpooled.buffer();
        try {
            reply.encode(bytes);bytes.setByte(bytes.writerIndex()-1,bytes.getByte(bytes.writerIndex()-1)^1);
            assertThrows(IOException.class,()->PreviewResponsePacket.decode(bytes));
            bytes.clear();reply.encode(bytes);bytes.setInt(30,4);
            assertThrows(IOException.class,()->PreviewResponsePacket.decode(bytes));
            bytes.clear();reply.encode(bytes);bytes.writeByte(0);
            assertThrows(IOException.class,()->PreviewResponsePacket.decode(bytes));
        } finally{bytes.release();}
    }
}
