package stellarium.world.ring.terrain;

import java.util.Objects;
import java.util.UUID;
import io.netty.buffer.ByteBuf;

/** Fixed-size C2S request. No seed, generator settings or client-supplied persistent identity. */
public record PreviewRequestPacket(UUID nonce,long clientGeneration,int dimension,long epoch,long sequence,
                                   Command command,int level,long x,long z) {
    public enum Command { HELLO, TILE, RELEASE }
    public static final int BYTES=70;
    private static final int MAGIC=0x53535051;
    public PreviewRequestPacket {
        Objects.requireNonNull(nonce);Objects.requireNonNull(command);
        if(clientGeneration<=0)throw new IllegalArgumentException("Invalid client generation");
        if(command==Command.HELLO) {
            if(epoch!=0||sequence!=0||level!=0||x!=0||z!=0)throw new IllegalArgumentException("Noncanonical hello");
        } else {
            if(sequence<=0)throw new IllegalArgumentException("Invalid request sequence");
            var key=new TerrainTileKey(epoch,level,x,z);
            long step=1L<<level;
            if(key.minBlockX()<Integer.MIN_VALUE||key.minBlockZ()<Integer.MIN_VALUE
                    ||key.minBlockX()+63*step>Integer.MAX_VALUE||key.minBlockZ()+63*step>Integer.MAX_VALUE)
                throw new IllegalArgumentException("Unsupported terrain coordinates");
        }
    }
    public TerrainTileKey key() {
        if(command==Command.HELLO)throw new IllegalStateException("Hello has no tile");
        return new TerrainTileKey(epoch,level,x,z);
    }
    public void encode(ByteBuf out) {
        out.writeInt(MAGIC).writeByte(1).writeByte(command.ordinal());
        out.writeLong(nonce.getMostSignificantBits()).writeLong(nonce.getLeastSignificantBits());
        out.writeLong(clientGeneration).writeInt(dimension).writeLong(epoch).writeLong(sequence);
        out.writeInt(level).writeLong(x).writeLong(z);
    }
    public static PreviewRequestPacket decode(ByteBuf in) {
        if(in.readableBytes()!=BYTES)throw new IllegalArgumentException("Wrong preview request length");
        if(in.readInt()!=MAGIC||in.readUnsignedByte()!=1)throw new IllegalArgumentException("Unsupported preview protocol");
        int kind=in.readUnsignedByte();if(kind>=Command.values().length)throw new IllegalArgumentException("Unknown preview command");
        var nonce=new UUID(in.readLong(),in.readLong());
        return new PreviewRequestPacket(nonce,in.readLong(),in.readInt(),in.readLong(),in.readLong(),
                Command.values()[kind],in.readInt(),in.readLong(),in.readLong());
    }
}
