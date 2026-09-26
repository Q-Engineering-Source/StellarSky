package stellarium.world.ring.generation;

import net.minecraft.world.gen.structure.StructureStart;

/** Admission for NEW starts only; never removes persisted indexes or mutates components. */
public final class RingworldStructureAdmission {
    private static final RingworldGenerationPolicy POLICY = new RingworldGenerationPolicy();
    private RingworldStructureAdmission() {}

    public static boolean allowsStart(RingworldBiomeProvider biomes, int chunkX, int chunkZ) {
        return POLICY.allowsStructureStart(chunkZ) && !biomes.isSpaceChunk(chunkX, chunkZ);
    }

    public static boolean allowsNewStructure(RingworldBiomeProvider biomes, StructureStart start) {
        if (!start.isSizeableStructure() || start.getComponents().isEmpty()) return false;
        for (var component : start.getComponents()) {
            var box = component.getBoundingBox();
            if (box == null) throw new IllegalStateException("Structure component has no bounds");
            if (!POLICY.containsStructure(box.minZ, box.maxZ)) return false;
            if (box.minX > box.maxX) throw new IllegalArgumentException("Invalid structure X bounds");
            // Chunk-aligned Space is the supported biome contract; components can span several cells.
            for (int z = Math.floorDiv(box.minZ, 16); z <= Math.floorDiv(box.maxZ, 16); z++) {
                for (int x = Math.floorDiv(box.minX, 16); x <= Math.floorDiv(box.maxX, 16); x++) {
                    if (biomes.isSpaceChunk(x, z)) return false;
                }
            }
        }
        return true;
    }
}
