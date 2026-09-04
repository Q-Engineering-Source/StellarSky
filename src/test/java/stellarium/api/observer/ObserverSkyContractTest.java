package stellarium.api.observer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import stellarium.sync.MessageObserverSkySync;

public class ObserverSkyContractTest {
    @Test
    public void clampsLatitudeAndNormalizesLongitude() {
        ObserverSkyContext context = new ObserverSkyContext(7,
                new ResourceLocation("test", "system"),
                new ResourceLocation("test", "body"),
                new ResourceLocation("test", "frame"),
                120.0, -181.0, 512.25);

        assertEquals(90.0, context.getLatitude(), 0.0);
        assertEquals(179.0, context.getLongitude(), 0.0);
        assertEquals(512.25, context.getAltitude(), 0.0);
    }

    @Test
    public void sameDimensionObserversRemainDistinctValues() {
        ObserverSkyContext first = ObserverSkyContext.dimensionDefault(0, 37.5, 0.0, 0.0);
        ObserverSkyContext second = ObserverSkyContext.dimensionDefault(0, -52.5, 180.0, 5000.0);

        assertNotEquals(first, second);
        assertEquals(first.getDimension(), second.getDimension());
    }

    @Test
    public void resolverPriorityTransformsThePreviousResult() {
        ResourceLocation highId = new ResourceLocation("m3a2", "observer_high");
        ResourceLocation lowId = new ResourceLocation("m3a2", "observer_low");
        ObserverSkyContext fallback = ObserverSkyContext.dimensionDefault(0, 0.0, 0.0, 0.0);
        try {
            ObserverSkyResolvers.register(lowId, 10, (world, observer, previous) ->
                    ObserverSkyContext.dimensionDefault(previous.getDimension(),
                            previous.getLatitude() + 2.0, previous.getLongitude() + 20.0,
                            previous.getAltitude() + 200.0));
            ObserverSkyResolvers.register(highId, 100, (world, observer, previous) ->
                    ObserverSkyContext.dimensionDefault(previous.getDimension(),
                            previous.getLatitude() + 1.0, previous.getLongitude() + 10.0,
                            previous.getAltitude() + 100.0));

            ObserverSkyContext result = ObserverSkyResolvers.resolve(null, null, fallback);

            assertEquals(3.0, result.getLatitude(), 0.0);
            assertEquals(30.0, result.getLongitude(), 0.0);
            assertEquals(300.0, result.getAltitude(), 0.0);
        } finally {
            ObserverSkyResolvers.unregister(highId);
            ObserverSkyResolvers.unregister(lowId);
        }
    }

    @Test
    public void registeringTheSameResolverIdReplacesThePreviousEntry() {
        ResourceLocation id = new ResourceLocation("m3a2", "observer_replace");
        ObserverSkyContext fallback = ObserverSkyContext.dimensionDefault(0, 0.0, 0.0, 0.0);
        try {
            ObserverSkyResolvers.register(id, 1, (world, observer, previous) ->
                    ObserverSkyContext.dimensionDefault(0, 10.0, 20.0, 30.0));
            ObserverSkyResolvers.register(id, 2, (world, observer, previous) ->
                    ObserverSkyContext.dimensionDefault(0, -10.0, -20.0, -30.0));

            ObserverSkyContext result = ObserverSkyResolvers.resolve(null, null, fallback);

            assertEquals(-10.0, result.getLatitude(), 0.0);
            assertEquals(340.0, result.getLongitude(), 0.0);
            assertEquals(-30.0, result.getAltitude(), 0.0);
        } finally {
            ObserverSkyResolvers.unregister(id);
        }
    }

    @Test
    public void nullResolverResultPreservesThePreviousContext() {
        ResourceLocation id = new ResourceLocation("m3a2", "observer_null");
        ObserverSkyContext fallback = ObserverSkyContext.dimensionDefault(4, 12.0, 34.0, 56.0);
        try {
            ObserverSkyResolvers.register(id, 1, (world, observer, previous) -> null);

            assertEquals(fallback, ObserverSkyResolvers.resolve(null, null, fallback));
        } finally {
            ObserverSkyResolvers.unregister(id);
        }
    }

    @Test
    public void observerPacketPreservesTheWireFieldOrderAndValues() {
        ObserverSkyContext context = new ObserverSkyContext(-7,
                new ResourceLocation("m3a2", "system"),
                new ResourceLocation("m3a2", "body"),
                new ResourceLocation("m3a2", "frame"),
                -45.25, 270.5, 1234.75);
        ByteBuf buffer = Unpooled.buffer();
        new MessageObserverSkySync(context).toBytes(buffer);

        assertEquals(-7, buffer.readInt());
        assertEquals("m3a2:system", ByteBufUtils.readUTF8String(buffer));
        assertEquals("m3a2:body", ByteBufUtils.readUTF8String(buffer));
        assertEquals("m3a2:frame", ByteBufUtils.readUTF8String(buffer));
        assertEquals(-45.25, buffer.readDouble(), 0.0);
        assertEquals(270.5, buffer.readDouble(), 0.0);
        assertEquals(1234.75, buffer.readDouble(), 0.0);
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    public void observerPacketDecodeEncodeRoundTripIsByteExact() {
        ObserverSkyContext context = new ObserverSkyContext(3,
                new ResourceLocation("m3a2", "system_roundtrip"),
                new ResourceLocation("m3a2", "body_roundtrip"),
                new ResourceLocation("m3a2", "frame_roundtrip"),
                12.5, -30.0, -64.0);
        ByteBuf encoded = Unpooled.buffer();
        new MessageObserverSkySync(context).toBytes(encoded);
        ByteBuf expected = encoded.copy();

        MessageObserverSkySync decoded = new MessageObserverSkySync();
        decoded.fromBytes(encoded);
        ByteBuf reencoded = Unpooled.buffer();
        decoded.toBytes(reencoded);

        assertEquals(expected, reencoded);
    }
}
