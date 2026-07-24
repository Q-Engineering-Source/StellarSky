package stellarium.sync;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraft.world.World;
import stellarium.StellarSky;
import stellarium.time.StellarSkyTime;

public final class MessageTimeMultiplierSync implements IMessage {
	private int dimension;
	private double multiplier;
	private boolean systemTimeSync;
	private int systemTimeOffsetMinutes;
	private int syncIntervalSeconds;

	public MessageTimeMultiplierSync() {
	}

	public MessageTimeMultiplierSync(int dimension, double multiplier, boolean systemTimeSync, int systemTimeOffsetMinutes,
			int syncIntervalSeconds) {
		this.dimension = dimension;
		this.multiplier = multiplier;
		this.systemTimeSync = systemTimeSync;
		this.systemTimeOffsetMinutes = systemTimeOffsetMinutes;
		this.syncIntervalSeconds = syncIntervalSeconds;
	}

	@Override
	public void fromBytes(ByteBuf buffer) {
		this.dimension = buffer.readInt();
		this.multiplier = buffer.readDouble();
		this.systemTimeSync = buffer.readBoolean();
		this.systemTimeOffsetMinutes = buffer.readInt();
		this.syncIntervalSeconds = buffer.readInt();
	}

	@Override
	public void toBytes(ByteBuf buffer) {
		buffer.writeInt(this.dimension);
		buffer.writeDouble(this.multiplier);
		buffer.writeBoolean(this.systemTimeSync);
		buffer.writeInt(this.systemTimeOffsetMinutes);
		buffer.writeInt(this.syncIntervalSeconds);
	}

	public static final class MessageTimeMultiplierSyncHandler
			implements IMessageHandler<MessageTimeMultiplierSync, IMessage> {
		@Override
		public IMessage onMessage(final MessageTimeMultiplierSync message, MessageContext context) {
			StellarSky.PROXY.addScheduledTask(new Runnable() {
				@Override
				public void run() {
					StellarSkyTime.setClientTimeState(message.dimension, message.multiplier,
							message.systemTimeSync, message.systemTimeOffsetMinutes, message.syncIntervalSeconds);
					World clientWorld = StellarSky.PROXY.getDefWorld();
					if(clientWorld != null && clientWorld.provider.getDimension() == message.dimension)
						StellarSkyTime.resetSystemTimeCorrection(clientWorld);
				}
			});
			return null;
		}
	}
}
