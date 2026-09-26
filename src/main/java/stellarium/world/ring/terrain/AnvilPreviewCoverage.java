package stellarium.world.ring.terrain;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import stellarium.world.ring.terrain.TerrainPreviewTrace.CoverageUnknownCause;

/** Read-only persisted Anvil occupancy, never complete world coverage without live/pending chunk evidence. */
public final class AnvilPreviewCoverage {
    private static final Pattern REGION_NAME=Pattern.compile("r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca");
    private static final int MAX_DIRECTORY_ENTRIES=4096;
    private final Path regions;
    private final int maxRegions;
    public AnvilPreviewCoverage(Path regions, int maxRegions) {
        if (maxRegions < 1 || maxRegions > 1024) throw new IllegalArgumentException("Invalid region-query budget");
        this.regions = regions.toAbsolutePath().normalize(); this.maxRegions = maxRegions;
    }
    public SeedPreviewAdmission.Coverage query(TerrainTileKey key) throws IOException {
        long width = 64L << key.level();
        long minCx = Math.floorDiv(key.minBlockX(), 16), minCz = Math.floorDiv(key.minBlockZ(), 16);
        long maxCx = Math.floorDiv(key.minBlockX() + width - 1, 16), maxCz = Math.floorDiv(key.minBlockZ() + width - 1, 16);
        long minRx = Math.floorDiv(minCx, 32), minRz = Math.floorDiv(minCz, 32);
        long maxRx = Math.floorDiv(maxCx, 32), maxRz = Math.floorDiv(maxCz, 32);
        long nx = maxRx - minRx + 1, nz = maxRz - minRz + 1;
        if (nx > maxRegions || nz > maxRegions || nx * nz > maxRegions)
            return sparseQuery(minCx,minCz,maxCx,maxCz,minRx,minRz,maxRx,maxRz);
        for (long rx = minRx; rx <= maxRx; rx++) for (long rz = minRz; rz <= maxRz; rz++) {
            var result=region(rx,rz,minCx,minCz,maxCx,maxCz);
            if(result!=SeedPreviewAdmission.Coverage.NO_REAL_DATA)return result;
        }
        return SeedPreviewAdmission.Coverage.NO_REAL_DATA;
    }
    private SeedPreviewAdmission.Coverage sparseQuery(long minCx,long minCz,long maxCx,long maxCz,
            long minRx,long minRz,long maxRx,long maxRz) throws IOException {
        var before=directory();
        if(before==null) {
            TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.DISK_DIRECTORY);
            return SeedPreviewAdmission.Coverage.UNKNOWN;
        }
        int visited=0;
        for(var entry:before) {
            if(entry.x<minRx||entry.x>maxRx||entry.z<minRz||entry.z>maxRz)continue;
            if(++visited>maxRegions) {
                TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.DISK_REGION_BUDGET);
                return SeedPreviewAdmission.Coverage.UNKNOWN;
            }
            var result=region(entry.x,entry.z,minCx,minCz,maxCx,maxCz);
            if(result!=SeedPreviewAdmission.Coverage.NO_REAL_DATA)return result;
        }
        // Directory mutation is not absence. Live/pending coverage is still merged by the owner.
        var after=directory();
        var mutated=!before.equals(after);
        if(mutated)TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.DISK_MUTATED);
        return mutated?SeedPreviewAdmission.Coverage.UNKNOWN:SeedPreviewAdmission.Coverage.NO_REAL_DATA;
    }
    private Set<Region> directory() throws IOException {
        var result=new HashSet<Region>();int count=0;
        try(var files=Files.newDirectoryStream(regions)) {
            for(var file:files) {
                if(++count>MAX_DIRECTORY_ENTRIES)return null;
                var match=REGION_NAME.matcher(file.getFileName().toString());
                if(!match.matches())continue;
                try {result.add(new Region(Long.parseLong(match.group(1)),Long.parseLong(match.group(2))));}
                catch(NumberFormatException malformed){throw new IOException("Invalid Anvil region name: "+file,malformed);}
            }
        } catch(NoSuchFileException absent) {return Set.of();}
        return result;
    }
    private record Region(long x,long z) {}
    private SeedPreviewAdmission.Coverage region(long rx,long rz,long minCx,long minCz,long maxCx,long maxCz) throws IOException {
            Path file = regions.resolve("r." + rx + "." + rz + ".mca");
            try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                long size = channel.size();
                if (size < 8192 || size % 4096 != 0) throw new IOException("Invalid Anvil region size: " + file);
                byte[] first = header(channel), second = header(channel);
                if (!Arrays.equals(first, second) || size != channel.size()) {
                    TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.DISK_UNSTABLE);
                    return SeedPreviewAdmission.Coverage.UNKNOWN;
                }
                var offsets = ByteBuffer.wrap(first);
                int startX = (int)Math.max(0, minCx - rx * 32), endX = (int)Math.min(31, maxCx - rx * 32);
                int startZ = (int)Math.max(0, minCz - rz * 32), endZ = (int)Math.min(31, maxCz - rz * 32);
                for (int z = startZ; z <= endZ; z++) for (int x = startX; x <= endX; x++) {
                    int location = offsets.getInt(4 * (x + z * 32));
                    if (location == 0) continue;
                    long sector = location >>> 8, count = location & 255;
                    if (sector < 2 || count == 0 || (sector + count) * 4096L > size) {
                        throw new IOException("Invalid Anvil chunk location: " + file);
                    }
                    return SeedPreviewAdmission.Coverage.REAL_DATA;
                }
            } catch (NoSuchFileException absent) {
                // No region file proves only persisted absence; the caller must merge unsaved/live evidence.
            }
        return SeedPreviewAdmission.Coverage.NO_REAL_DATA;
    }
    private static byte[] header(FileChannel channel) throws IOException {
        var header = ByteBuffer.allocate(4096); channel.position(0);
        while (header.hasRemaining()) if (channel.read(header) < 0) throw new IOException("Truncated Anvil header");
        return header.array();
    }
}
