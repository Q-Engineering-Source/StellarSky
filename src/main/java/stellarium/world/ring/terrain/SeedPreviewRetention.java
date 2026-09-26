package stellarium.world.ring.terrain;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.UUID;

/** Metadata-IO boundary: retire derived payloads of obsolete generator namespaces, never world data. */
public final class SeedPreviewRetention {
    public static final int MAX_NAMESPACES=512;
    private SeedPreviewRetention() {}

    public static void prepare(Path directory,PreviewCacheIdentity current) throws IOException {
        Files.createDirectories(directory);
        Path absolute=directory.toAbsolutePath().normalize();
        // Canonicalize ancestors first: Windows TEMP may use an ordinary 8.3 path alias.
        Path expected=absolute.getParent().toRealPath().resolve(absolute.getFileName()),root=directory.toRealPath();
        if(!root.equals(expected)||Files.isSymbolicLink(directory)
                ||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Linked preview namespace root");
        try(var channel=FileChannel.open(root.resolve("retention.lock"),StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            try(var lock=channel.tryLock()) {
                if(lock==null)throw new IOException("Preview namespace retention is already owned");
                var old=new ArrayList<Namespace>();
                int count=0;boolean currentExists=false;
                try(var files=Files.newDirectoryStream(root)) {
                    for(var path:files) {
                        String name=path.getFileName().toString();
                        if(name.equals("retention.lock"))continue;
                        if(++count>MAX_NAMESPACES)throw new IOException("Preview namespace metadata budget exceeded");
                        UUID generation;
                        try {generation=UUID.fromString(name);}
                        catch(IllegalArgumentException failure){throw new IOException("Unowned preview namespace: "+path,failure);}
                        if(!generation.toString().equals(name)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)
                                ||!path.toRealPath().getParent().equals(root))throw new IOException("Invalid preview namespace path: "+path);
                        PreviewCacheIdentity identity;
                        try {identity=SeedPreviewDiskCache.inspectIdentity(path);}
                        catch(NoSuchFileException missing) {
                            if(!generation.equals(current.generationId()))throw missing;
                            // Current IO initialization may have stopped after creating its lock,
                            // before publishing the first index. Only this exact empty state is safe.
                            try(var contents=Files.newDirectoryStream(path)) {
                                for(var entry:contents)if(!entry.getFileName().toString().equals("owner.lock")
                                        ||!Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS))
                                    throw new IOException("Missing current index in nonempty namespace",missing);
                            }
                            currentExists=true;continue;
                        }
                        if(!identity.serverId().equals(current.serverId())||!identity.worldId().equals(current.worldId())
                                ||identity.dimension()!=current.dimension()||!identity.generationId().equals(generation))
                            throw new IOException("Foreign preview namespace: "+path);
                        if(generation.equals(current.generationId())) {
                            if(!identity.equals(current))throw new IOException("Current preview identity mismatch");
                            currentExists=true;
                        } else old.add(new Namespace(path,identity));
                    }
                }
                if(!currentExists&&count==MAX_NAMESPACES)throw new IOException("Preview namespace metadata budget exhausted");
                // Keep small identity/lock stubs. Avoid deleting/recreating lock paths while another
                // process could acquire them; total stub count is explicitly capped above.
                for(var namespace:old) {
                    try(var cache=new SeedPreviewDiskCache(namespace.directory,namespace.identity,1,4096)) {
                        cache.invalidateRegion(Long.MIN_VALUE,Long.MIN_VALUE,Long.MAX_VALUE,Long.MAX_VALUE);
                    }
                }
            } catch(OverlappingFileLockException failure) {throw new IOException("Preview retention is already owned",failure);}
        }
    }
    private record Namespace(Path directory,PreviewCacheIdentity identity) {}
}
