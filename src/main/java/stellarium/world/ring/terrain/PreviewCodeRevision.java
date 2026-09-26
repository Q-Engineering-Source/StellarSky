package stellarium.world.ring.terrain;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Private environment namespace. Files are hashed on the metadata worker, never the gameplay thread. */
public final class PreviewCodeRevision {
    public record Artifact(String id, String version, Path source) {
        public Artifact { Objects.requireNonNull(id); Objects.requireNonNull(version); Objects.requireNonNull(source); }
    }
    private PreviewCodeRevision() {}
    public static byte[] capture(List<Artifact> artifacts) throws IOException {
        if(artifacts.isEmpty()||artifacts.size()>256)throw new IOException("Invalid preview environment size");
        MessageDigest digest;
        try {digest=MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException impossible){throw new ExceptionInInitializerError(impossible);}
        string(digest,"stellarsky-preview-environment-v1");
        var ids=new HashSet<String>(); long total=0;
        for(var artifact:artifacts.stream().sorted(Comparator.comparing(Artifact::id)).toList()) {
            if(!ids.add(artifact.id))throw new IOException("Duplicate environment artifact ID");
            if(!Files.isRegularFile(artifact.source,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Preview requires a packaged environment artifact: "+artifact.id+" at "+artifact.source);
            long size=Files.size(artifact.source);
            total=Math.addExact(total,size);
            if(size>512L*1024*1024||total>2L*1024*1024*1024)throw new IOException("Preview environment exceeds digest budget");
            string(digest,artifact.id); string(digest,artifact.version);
            digest.update(ByteBuffer.allocate(8).putLong(size).array());
            long read=0; var buffer=new byte[65536];
            try(var input=Files.newInputStream(artifact.source)) {
                int count;
                while((count=input.read(buffer))!=-1) {
                    read+=count; if(read>size)throw new IOException("Environment artifact changed while hashing");
                    digest.update(buffer,0,count);
                }
            }
            if(read!=size||Files.size(artifact.source)!=size)throw new IOException("Environment artifact changed while hashing");
        }
        return digest.digest();
    }
    private static void string(MessageDigest digest,String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>4096)throw new IllegalArgumentException("Oversized environment identity");
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
}
