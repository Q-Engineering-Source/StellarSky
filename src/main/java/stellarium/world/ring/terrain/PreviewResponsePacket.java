package stellarium.world.ring.terrain;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import io.netty.buffer.ByteBuf;

/** Bounded S2C envelope; TILE carries only the existing seed-free preview codec. */
public record PreviewResponsePacket(Type type,UUID nonce,long clientGeneration,int dimension,long epoch,long sequence,
                                    PreviewCacheIdentity identity,TerrainTileKey key,SeedTerrainTile tile,Reason reason) {
    public enum Type { WELCOME, TILE, RETIRED, UNAVAILABLE }
    public enum Reason { PREPARING, UNSUPPORTED, BUSY, REAL_OR_UNKNOWN, FAILED }
    private static final int MAGIC=0x53535052,BASE_BYTES=70;
    public PreviewResponsePacket {
        Objects.requireNonNull(type);Objects.requireNonNull(nonce);
        if(clientGeneration<=0)throw new IllegalArgumentException("Invalid response generation");
        if(type==Type.WELCOME) {
            if(epoch<=0||sequence!=0||identity==null||key!=null||tile!=null||reason!=null)
                throw new IllegalArgumentException("Invalid welcome");
        } else if(type==Type.TILE) {
            if(epoch<=0||sequence<=0||identity==null||key==null||tile==null||reason!=null||!key.equals(tile.key()))
                throw new IllegalArgumentException("Invalid tile response");
        } else if(type==Type.RETIRED) {
            if(epoch<=0||sequence<=0||key==null||identity!=null||tile!=null||reason!=null)
                throw new IllegalArgumentException("Invalid retirement");
        } else {
            if(reason==null||identity!=null||tile!=null||epoch<0||sequence<0||(sequence==0)!=(key==null))
                throw new IllegalArgumentException("Invalid unavailable response");
        }
        if(identity!=null&&identity.dimension()!=dimension)throw new IllegalArgumentException("Foreign identity dimension");
        if(key!=null&&key.worldEpoch()!=epoch)throw new IllegalArgumentException("Foreign tile epoch");
    }
    public static PreviewResponsePacket welcome(PreviewRequestPacket request,PreviewCacheIdentity identity,long epoch) {
        return new PreviewResponsePacket(Type.WELCOME,request.nonce(),request.clientGeneration(),identity.dimension(),epoch,0,identity,null,null,null);
    }
    public static PreviewResponsePacket tile(PreviewRequestPacket request,PreviewCacheIdentity identity,SeedTerrainTile tile) {
        return new PreviewResponsePacket(Type.TILE,request.nonce(),request.clientGeneration(),identity.dimension(),tile.key().worldEpoch(),request.sequence(),identity,tile.key(),tile,null);
    }
    public static PreviewResponsePacket unavailable(PreviewRequestPacket request,Reason reason) {
        return new PreviewResponsePacket(Type.UNAVAILABLE,request.nonce(),request.clientGeneration(),request.dimension(),request.epoch(),request.sequence(),
                null,request.command()==PreviewRequestPacket.Command.HELLO?null:request.key(),null,reason);
    }
    public static PreviewResponsePacket retired(PreviewRequestPacket request) {
        return new PreviewResponsePacket(Type.RETIRED,request.nonce(),request.clientGeneration(),request.dimension(),request.epoch(),request.sequence(),null,request.key(),null,null);
    }
    public void encode(ByteBuf out) throws IOException {
        out.writeInt(MAGIC).writeByte(1).writeByte(type.ordinal());
        uuid(out,nonce);out.writeLong(clientGeneration).writeInt(dimension).writeLong(epoch).writeLong(sequence);
        out.writeInt(key==null?0:key.level()).writeLong(key==null?0:key.x()).writeLong(key==null?0:key.z());
        switch(type) {
            case WELCOME -> identity(out,identity);
            case TILE -> out.writeBytes(SeedPreviewCodec.encode(identity,tile));
            case RETIRED -> { }
            case UNAVAILABLE -> out.writeByte(reason.ordinal());
        }
    }
    public static PreviewResponsePacket decode(ByteBuf in) throws IOException {
        int size=in.readableBytes();
        if(size<BASE_BYTES||size>BASE_BYTES+SeedPreviewCodec.ENCODED_BYTES)throw new IOException("Wrong preview response length");
        if(in.readInt()!=MAGIC||in.readUnsignedByte()!=1)throw new IOException("Unsupported preview response protocol");
        int kind=in.readUnsignedByte();if(kind>=Type.values().length)throw new IOException("Unknown preview response");
        Type type=Type.values()[kind];
        int expected=BASE_BYTES+switch(type){case WELCOME->56;case TILE->SeedPreviewCodec.ENCODED_BYTES;case RETIRED->0;case UNAVAILABLE->1;};
        if(size!=expected)throw new IOException("Noncanonical preview response length");
        UUID nonce=uuid(in);long generation=in.readLong();int dimension=in.readInt();long epoch=in.readLong(),sequence=in.readLong();
        int level=in.readInt();long x=in.readLong(),z=in.readLong();
        try {
            TerrainTileKey key=sequence==0?null:new TerrainTileKey(epoch,level,x,z);
            if(key==null&&(level!=0||x!=0||z!=0))throw new IOException("Unexpected response tile address");
            PreviewCacheIdentity identity=null;SeedTerrainTile tile=null;Reason reason=null;
            switch(type) {
                case WELCOME -> identity=identity(in);
                case TILE -> {
                    var bytes=new byte[SeedPreviewCodec.ENCODED_BYTES];in.readBytes(bytes);
                    try(var header=new DataInputStream(new ByteArrayInputStream(bytes))) {
                        header.skipNBytes(8);identity=SeedPreviewCodec.readIdentity(header);
                    }
                    tile=SeedPreviewCodec.decode(identity,epoch,bytes);
                }
                case RETIRED -> { }
                case UNAVAILABLE -> {int code=in.readUnsignedByte();if(code>=Reason.values().length)throw new IOException("Unknown unavailable reason");reason=Reason.values()[code];}
            }
            return new PreviewResponsePacket(type,nonce,generation,dimension,epoch,sequence,identity,key,tile,reason);
        }catch(IllegalArgumentException|ArithmeticException malformed){throw new IOException("Invalid preview response",malformed);}
    }
    private static void uuid(ByteBuf out,UUID value){out.writeLong(value.getMostSignificantBits()).writeLong(value.getLeastSignificantBits());}
    private static UUID uuid(ByteBuf in){return new UUID(in.readLong(),in.readLong());}
    private static void identity(ByteBuf out,PreviewCacheIdentity value){uuid(out,value.serverId());uuid(out,value.worldId());out.writeInt(value.dimension());uuid(out,value.generationId());out.writeInt(value.samplerVersion());}
    private static PreviewCacheIdentity identity(ByteBuf in){return new PreviewCacheIdentity(uuid(in),uuid(in),in.readInt(),uuid(in),in.readInt());}
}
