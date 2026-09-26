package stellarium.sync;

import java.io.IOException;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import stellarium.world.ring.terrain.PreviewResponsePacket;
import stellarium.world.ring.terrain.PreviewClientInbox;
import stellarium.world.ring.terrain.TerrainPreviewTrace;
import stellarium.world.ring.RingworldClockClientState;

public final class MessageTerrainPreviewResponse implements IMessage {
    private PreviewResponsePacket packet;
    public MessageTerrainPreviewResponse() {}
    public MessageTerrainPreviewResponse(PreviewResponsePacket packet) { this.packet=packet; }
    @Override public void fromBytes(ByteBuf in) {
        try { packet=PreviewResponsePacket.decode(in); }
        catch(IOException failure) { throw new DecoderException("Invalid terrain preview",failure); }
    }
    @Override public void toBytes(ByteBuf out) {
        try { packet.encode(out); }
        catch(IOException failure) { throw new EncoderException("Cannot encode terrain preview",failure); }
    }
    public static final class Handler implements IMessageHandler<MessageTerrainPreviewResponse,IMessage> {
        @Override public IMessage onMessage(MessageTerrainPreviewResponse message,MessageContext context) {
            var receipt=RingworldClockClientState.captureReceipt(context.netHandler);
            if(receipt!=null) PreviewClientInbox.offer(receipt,message.packet);
            else TerrainPreviewTrace.clientInboxNullReceipt();
            return null;
        }
    }
}
