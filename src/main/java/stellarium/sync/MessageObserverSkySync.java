package stellarium.sync;

import io.netty.buffer.ByteBuf;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraft.util.ResourceLocation;
import stellarium.StellarSky;
import stellarium.api.observer.ObserverSkyContext;
import stellarium.world.ObserverSkyState;
import stellarium.world.StellarScene;

/**
 * A per-player observer context. Never broadcast this packet: two players in
 * the same dimension may intentionally have different local skies.
 */
public final class MessageObserverSkySync implements IMessage {
	private ObserverSkyContext context;

	public MessageObserverSkySync() {
	}

	public MessageObserverSkySync(ObserverSkyContext context) {
		this.context = context;
	}

	@Override
	public void fromBytes(ByteBuf buffer) {
		int dimension = buffer.readInt();
		ResourceLocation system = new ResourceLocation(ByteBufUtils.readUTF8String(buffer));
		ResourceLocation body = new ResourceLocation(ByteBufUtils.readUTF8String(buffer));
		ResourceLocation frame = new ResourceLocation(ByteBufUtils.readUTF8String(buffer));
		this.context = new ObserverSkyContext(dimension, system, body, frame,
				buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
	}

	@Override
	public void toBytes(ByteBuf buffer) {
		buffer.writeInt(context.getDimension());
		ByteBufUtils.writeUTF8String(buffer, context.getSystemId().toString());
		ByteBufUtils.writeUTF8String(buffer, context.getBodyId().toString());
		ByteBufUtils.writeUTF8String(buffer, context.getFrameId().toString());
		buffer.writeDouble(context.getLatitude());
		buffer.writeDouble(context.getLongitude());
		buffer.writeDouble(context.getAltitude());
	}

	public static final class Handler implements IMessageHandler<MessageObserverSkySync, IMessage> {
		@Override
		public IMessage onMessage(final MessageObserverSkySync message, MessageContext context) {
			StellarSky.PROXY.addScheduledTask(new Runnable() {
				@Override
				public void run() {
					ObserverSkyState.setClientContext(message.context);
					World clientWorld = StellarSky.PROXY.getDefWorld();
					if(clientWorld != null && clientWorld.provider.getDimension() == message.context.getDimension()) {
						StellarScene scene = StellarScene.getScene(clientWorld);
						if(scene != null)
							scene.setDynamicLocation(message.context.getLatitude(), message.context.getLongitude());
					}
				}
			});
			return null;
		}
	}
}
