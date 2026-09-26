package stellarium.world.ring.terrain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.Set;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.gen.layer.GenLayer;
import net.minecraftforge.common.BiomeManager;
import stellarium.mixin.PreviewBiomeLayersAccess;
import stellarium.mixin.PreviewLayerBaseAccess;
import stellarium.mixin.PreviewLayerBiomeAccess;
import stellarium.mixin.PreviewLayerEdgeAccess;
import stellarium.mixin.PreviewLayerHillsAccess;
import stellarium.mixin.PreviewLayerRiverMixAccess;
import stellarium.world.ring.generation.SpaceBiome;
import stellarium.world.ring.generation.RingworldBiomeProvider;

/** Exact bounded vanilla GenLayer state; unknown providers/layers/biome classes are not admitted. */
public final class VanillaBiomeFingerprint {
    private static final Set<String> BIOMES=Set.of("BiomeForestMutated","BiomePlains","BiomeMesa","BiomeVoid",
            "BiomeSavannaMutated","BiomeEnd","BiomeSwamp","BiomeBeach","BiomeSnow","BiomeJungle",
            "BiomeTaiga","BiomeHills","BiomeDesert","BiomeRiver","BiomeStoneBeach","BiomeSavanna",
            "BiomeHell","BiomeOcean","BiomeMushroomIsland","BiomeForest");
    private static final Set<String> LAYERS=Set.of("GenLayerDeepOcean","GenLayerIsland","GenLayerRiver",
            "GenLayerShore","GenLayerRareBiome","GenLayerEdge","GenLayerZoom","GenLayerFuzzyZoom",
            "GenLayerSmooth","GenLayerVoronoiZoom","GenLayerRemoveTooMuchOcean","GenLayerBiome",
            "GenLayerHills","GenLayerRiverInit","GenLayerAddSnow","GenLayerBiomeEdge","GenLayerRiverMix",
            "GenLayerAddIsland","GenLayerAddMushroomIsland");
    private final MessageDigest digest;
    private final IdentityHashMap<GenLayer,Integer> visited=new IdentityHashMap<>();
    private final Set<GenLayer> active=Collections.newSetFromMap(new IdentityHashMap<>());
    private VanillaBiomeFingerprint() {
        try {digest=MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException impossible){throw new ExceptionInInitializerError(impossible);}
    }
    public static byte[] capture(BiomeProvider provider) {
        if(provider instanceof RingworldBiomeProvider ring)return ring.previewFingerprint();
        if(provider.getClass()!=BiomeProvider.class || !(provider instanceof PreviewBiomeLayersAccess access))
            throw new UnsupportedOperationException("Unadmitted biome provider");
        var state=new VanillaBiomeFingerprint(); state.string("vanilla-genlayer-state-v1");
        state.layer(access.stellarium$genBiomes()); state.layer(access.stellarium$biomeIndexLayer());
        int count=0;
        for(var biome:Biome.REGISTRY) {
            if(++count>1024)throw new UnsupportedOperationException("Biome registry exceeds preview bound");
            String simple=biome.getClass().getSimpleName();
            if(!(BIOMES.contains(simple)&&biome.getClass().getName().equals("net.minecraft.world.biome."+simple)) && biome.getClass()!=SpaceBiome.class)
                throw new UnsupportedOperationException("Unadmitted biome class: "+biome.getClass().getName());
            state.integer(Biome.getIdForBiome(biome)); state.string(biome.getRegistryName().toString());
            state.string(biome.getClass().getName()); state.string(biome.getBiomeClass().getName());
            state.scalar(biome.getBaseHeight()); state.scalar(biome.getHeightVariation());
            state.string(biome.getTempCategory().name()); state.integer(biome.isSnowyBiome()?1:0);
            var mutation=Biome.getMutationForBiome(biome); state.integer(mutation==null?-1:Biome.getIdForBiome(mutation));
        }
        state.integer(count); return state.digest.digest();
    }
    public static byte[] ring(BiomeProvider delegate,int minZ,int maxZ,int spaceId) {
        var state=new VanillaBiomeFingerprint(); state.string("ring-biome-policy-v1");
        state.integer(minZ);state.integer(maxZ);state.integer(spaceId);state.digest.update(capture(delegate));
        return state.digest.digest();
    }
    private void layer(GenLayer layer) {
        if(layer==null){integer(-1);return;}
        if(active.contains(layer))throw new UnsupportedOperationException("Cyclic biome graph");
        var existing=visited.get(layer);
        if(existing!=null){integer(existing);return;}
        if(visited.size()>=256)throw new UnsupportedOperationException("Biome graph exceeds preview bound");
        String simple=layer.getClass().getSimpleName();
        if(!LAYERS.contains(simple)||!layer.getClass().getName().equals("net.minecraft.world.gen.layer."+simple))
            throw new UnsupportedOperationException("Unadmitted biome layer: "+layer.getClass().getName());
        int id=visited.size(); visited.put(layer,id); active.add(layer); integer(id); string(simple);
        var base=(PreviewLayerBaseAccess)layer;
        number(base.stellarium$baseSeed()); number(base.stellarium$worldGenSeed());
        // chunkSeed is scratch state reset by initChunkSeed for each output coordinate.
        layer(base.stellarium$parent());
        if(layer instanceof PreviewLayerHillsAccess hills)layer(hills.stellarium$riverLayer());
        if(layer instanceof PreviewLayerRiverMixAccess river){layer(river.stellarium$biomePatternGeneratorChain());layer(river.stellarium$riverPatternGeneratorChain());}
        if(layer instanceof PreviewLayerEdgeAccess edge)string(edge.stellarium$mode().name());
        if(layer instanceof PreviewLayerBiomeAccess biome) {
            var settings=biome.stellarium$settings(); integer(settings==null?0:1);
            if(settings!=null){integer(settings.fixedBiome);integer(settings.biomeSize);integer(settings.riverSize);}
            var lists=biome.stellarium$biomes(); var types=BiomeManager.BiomeType.values();
            if(lists.length!=types.length)throw new UnsupportedOperationException("Unknown biome list layout");
            for(int i=0;i<lists.length;i++) {
                var list=lists[i]; if(list==null||list.size()>1024)throw new UnsupportedOperationException("Unbounded biome weights");
                integer(BiomeManager.isTypeListModded(types[i])?1:0); integer(list.size());
                for(var entry:list){integer(Biome.getIdForBiome(entry.biome));integer(entry.itemWeight);}
            }
        }
        active.remove(layer);
    }
    private void integer(int value){digest.update(ByteBuffer.allocate(4).putInt(value).array());}
    private void number(long value){digest.update(ByteBuffer.allocate(8).putLong(value).array());}
    private void scalar(float value){if(!Float.isFinite(value))throw new IllegalStateException("Non-finite biome scalar");integer(Float.floatToIntBits(value));}
    private void string(String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);integer(bytes.length);digest.update(bytes);}
}
