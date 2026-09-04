package stellarium.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.UUID;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import stellarium.world.ring.RingworldClockSample;

public class MessageRingworldClockSyncTest {
    @Test
    public void discontinuityUsesOnlyCanonicalZeroOrOneBytes() {
        ByteBuf payload = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(
                "000000072f4ce7e461924c7cb3cbb6da6399b0a90000000000000001000000000000303901"));
        ByteBuf encoded = Unpooled.buffer();
        try {
            MessageRingworldClockSync decoded = new MessageRingworldClockSync();
            decoded.fromBytes(payload);
            assertTrue(decoded.sample().discontinuousBefore());
            decoded.toBytes(encoded);
            assertEquals("000000072f4ce7e461924c7cb3cbb6da6399b0a90000000000000001000000000000303901",
                    ByteBufUtil.hexDump(encoded));
            for (int invalid : new int[] {2, 127, 128, 255}) {
                payload.setByte(36, invalid);
                payload.readerIndex(0);
                assertEquals("Invalid ringworld clock discontinuity flag",
                        assertThrows(IllegalArgumentException.class,
                                () -> new MessageRingworldClockSync().fromBytes(payload)).getMessage());
            }
        } finally {
            payload.release();
            encoded.release();
        }
    }

    @Test
    public void fixedCodecCarriesTheExactCommittedSampleInNetworkOrder() {
        RingworldClockSample sample = new RingworldClockSample(7,
                UUID.fromString("2f4ce7e4-6192-4c7c-b3cb-b6da6399b0a9"), 1L, 12_345L, false);
        MessageRingworldClockSync message = new MessageRingworldClockSync(sample);
        ByteBuf encoded = Unpooled.buffer();
        ByteBuf reencoded = Unpooled.buffer();
        try {
            message.toBytes(encoded);
            assertEquals("000000072f4ce7e461924c7cb3cbb6da6399b0a90000000000000001000000000000303900",
                    ByteBufUtil.hexDump(encoded));
            assertEquals(37, encoded.readableBytes());

            MessageRingworldClockSync decoded = new MessageRingworldClockSync();
            decoded.fromBytes(encoded);
            assertEquals(sample, decoded.sample());
            decoded.toBytes(reencoded);
            assertEquals(37, reencoded.readableBytes());
            assertEquals("000000072f4ce7e461924c7cb3cbb6da6399b0a90000000000000001000000000000303900",
                    ByteBufUtil.hexDump(reencoded));
        } finally {
            encoded.release();
            reencoded.release();
        }
    }

    @Test
    public void fixedCodecRejectsTrailingOrInvalidSequenceBytes() {
        ByteBuf trailing = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(
                "000000072f4ce7e461924c7cb3cbb6da6399b0a9000000000000000100000000000030390000"));
        try {
            try {
                new MessageRingworldClockSync().fromBytes(trailing);
                fail("Expected trailing byte rejection");
            } catch (IllegalArgumentException expected) {
                assertEquals("Unexpected ringworld clock payload length", expected.getMessage());
            }
        } finally {
            trailing.release();
        }

        ByteBuf truncated = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(
                "000000072f4ce7e461924c7cb3cbb6da6399b0a9000000000000000100000000000030"));
        try {
            try {
                new MessageRingworldClockSync().fromBytes(truncated);
                fail("Expected truncated payload rejection");
            } catch (IllegalArgumentException expected) {
                assertEquals("Unexpected ringworld clock payload length", expected.getMessage());
            }
        } finally {
            truncated.release();
        }

        ByteBuf zeroSequence = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(
                "000000072f4ce7e461924c7cb3cbb6da6399b0a90000000000000000000000000000303900"));
        try {
            try {
                new MessageRingworldClockSync().fromBytes(zeroSequence);
                fail("Expected invalid sequence rejection");
            } catch (IllegalArgumentException expected) {
                assertEquals("sequence must be positive", expected.getMessage());
            }
        } finally {
            zeroSequence.release();
        }
    }
}
