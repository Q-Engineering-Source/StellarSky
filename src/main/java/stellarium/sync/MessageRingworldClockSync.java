package stellarium.sync;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import stellarium.StellarSky;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldClockClientState;
import stellarium.world.ring.RingworldClockSample;

/** Fixed-width S2C transport for an already committed server frame time. */
public final class MessageRingworldClockSync implements IMessage {
    private RingworldClockSample sample;

    public MessageRingworldClockSync() {
    }

    public MessageRingworldClockSync(RingworldClockSample sample) {
        this.sample = sample;
    }

    public RingworldClockSample sample() {
        return sample;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        if (buffer.readableBytes() != 37) {
            throw new IllegalArgumentException("Unexpected ringworld clock payload length");
        }
        int dimension = buffer.readInt();
        UUID generation = new UUID(buffer.readLong(), buffer.readLong());
        long sequence = buffer.readLong();
        long worldTime = buffer.readLong();
        byte discontinuous = buffer.readByte();
        if (discontinuous != 0 && discontinuous != 1) {
            throw new IllegalArgumentException("Invalid ringworld clock discontinuity flag");
        }
        sample = new RingworldClockSample(dimension, generation, sequence, worldTime, discontinuous == 1);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(sample.dimension());
        buffer.writeLong(sample.generation().getMostSignificantBits());
        buffer.writeLong(sample.generation().getLeastSignificantBits());
        buffer.writeLong(sample.sequence());
        buffer.writeLong(sample.worldTime());
        buffer.writeByte(sample.discontinuousBefore() ? 1 : 0);
    }

    public static final class Handler implements IMessageHandler<MessageRingworldClockSync, IMessage> {
        @Override
        public IMessage onMessage(MessageRingworldClockSync message, MessageContext context) {
            if (context.side != Side.CLIENT) {
                return null;
            }
            RingworldClockClientState.Receipt receipt = RingworldClockClientState.captureReceipt(context.netHandler);
            if (receipt == null) {
                return null;
            }
            StellarSky.PROXY.addScheduledTask(() -> applyOnClientThread(receipt, message.sample));
            return null;
        }

        private static void applyOnClientThread(RingworldClockClientState.Receipt receipt, RingworldClockSample sample) {
            if (!RingworldClockClientState.isCurrent(receipt)
                    || !StellarSky.PROXY.isCurrentRingworldClockConnection(receipt.ticket())) {
                return;
            }
            World clientWorld = StellarSky.PROXY.getDefWorld();
            if (clientWorld == null || !clientWorld.isRemote
                    || clientWorld.provider.getDimension() != sample.dimension()) {
                return;
            }
            StellarScene scene = StellarScene.getScene(clientWorld);
            if (scene == null) {
                return;
            }
            RingworldClockSample previous = scene.getRingworldClockSample();
            if (!scene.acceptRingworldClockSample(sample, receipt)) {
                return;
            }
            if (previous == null && StellarSky.INSTANCE.getLogger().isDebugEnabled()) {
                StellarSky.INSTANCE.getLogger().debug("Accepted ringworld clock dimension={} generation={} sequence={} worldTime={} discontinuousBefore={}",
                        sample.dimension(), sample.generation(), sample.sequence(), sample.worldTime(), sample.discontinuousBefore());
            } else if (StellarSky.INSTANCE.getLogger().isTraceEnabled()) {
                StellarSky.INSTANCE.getLogger().trace("Accepted ringworld clock dimension={} generation={} sequence={} worldTime={} discontinuousBefore={}",
                        sample.dimension(), sample.generation(), sample.sequence(), sample.worldTime(), sample.discontinuousBefore());
            }
        }
    }
}
