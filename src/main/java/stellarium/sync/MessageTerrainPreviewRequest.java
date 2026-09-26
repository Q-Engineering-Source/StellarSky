package stellarium.sync;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import stellarium.world.ring.terrain.PreviewRequestPacket;
import stellarium.world.ring.terrain.ServerPreviewTransport;

public final class MessageTerrainPreviewRequest implements IMessage {
    private PreviewRequestPacket packet;
    public MessageTerrainPreviewRequest() {}
    public MessageTerrainPreviewRequest(PreviewRequestPacket packet) { this.packet=packet; }
    @Override public void fromBytes(ByteBuf in) { packet=PreviewRequestPacket.decode(in); }
    @Override public void toBytes(ByteBuf out) { packet.encode(out); }
    public static final class Handler implements IMessageHandler<MessageTerrainPreviewRequest,IMessage> {
        @Override public IMessage onMessage(MessageTerrainPreviewRequest message,MessageContext context) {
            ServerPreviewTransport.INSTANCE.offer(context.getServerHandler(),message.packet);
            return null;
        }
    }
}
