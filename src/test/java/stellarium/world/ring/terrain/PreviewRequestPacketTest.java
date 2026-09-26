package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import org.junit.Test;

public class PreviewRequestPacketTest {
    private final UUID nonce=UUID.randomUUID();
    @Test public void helloTileAndReleaseRoundTripAtExactBound() {
        for(var command:PreviewRequestPacket.Command.values()) {
            var request=command==PreviewRequestPacket.Command.HELLO
                    ?new PreviewRequestPacket(nonce,1,-3,0,0,command,0,0,0)
                    :new PreviewRequestPacket(nonce,4,-3,7,12,command,2,-1,8);
            var bytes=Unpooled.buffer();
            try {request.encode(bytes);assertEquals(PreviewRequestPacket.BYTES,bytes.readableBytes());assertEquals(request,PreviewRequestPacket.decode(bytes));}
            finally {bytes.release();}
        }
    }
    @Test public void malformedLengthVersionAndCommandAreRejectedBeforeUse() {
        var request=new PreviewRequestPacket(nonce,1,0,0,0,PreviewRequestPacket.Command.HELLO,0,0,0);
        var bytes=Unpooled.buffer();
        try {
            request.encode(bytes);bytes.writeByte(0);
            assertThrows(IllegalArgumentException.class,()->PreviewRequestPacket.decode(bytes));
            bytes.writerIndex(PreviewRequestPacket.BYTES);bytes.setByte(4,2);
            assertThrows(IllegalArgumentException.class,()->PreviewRequestPacket.decode(bytes));
            bytes.readerIndex(0);bytes.setByte(4,1);bytes.setByte(5,255);
            assertThrows(IllegalArgumentException.class,()->PreviewRequestPacket.decode(bytes));
        } finally {bytes.release();}
    }
    @Test public void invalidGenerationEpochCoordinatesAndHelloFieldsAreRejected() {
        var hello=PreviewRequestPacket.Command.HELLO;var tile=PreviewRequestPacket.Command.TILE;
        assertThrows(IllegalArgumentException.class,()->new PreviewRequestPacket(nonce,0,0,0,0,hello,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new PreviewRequestPacket(nonce,1,0,1,0,hello,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new PreviewRequestPacket(nonce,1,0,0,1,tile,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new PreviewRequestPacket(nonce,1,0,1,1,tile,25,0,0));
        assertThrows(IllegalArgumentException.class,()->new PreviewRequestPacket(nonce,1,0,1,1,tile,0,100_000_000,0));
    }
}
