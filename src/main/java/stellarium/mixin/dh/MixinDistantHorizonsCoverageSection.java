package stellarium.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.dh.DistantHorizonsColumnCoverage;
import stellarium.client.ring.dh.DistantHorizonsCoverageBuffer;
import stellarium.client.ring.dh.DistantHorizonsCoverageTransfer;
import stellarium.client.ring.dh.DistantHorizonsCoverageTree;

/** DH3.3 only. Carries metadata, never changes DH generation, mesh data, visibility or GL state. */
@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection", remap = false)
public abstract class MixinDistantHorizonsCoverageSection {
    @Shadow @Final public long pos;
    @Shadow @Final private IDhClientLevel clientLevel;
    @Shadow @Final private LodQuadTree quadTree;
    @Unique private final DistantHorizonsCoverageTransfer.Lease stellarium$coverageLease = new DistantHorizonsCoverageTransfer.Lease();
    @Unique private final ThreadLocal<DistantHorizonsCoverageTransfer.Build> stellarium$building = new ThreadLocal<>();

    @WrapMethod(method = "lambda$uploadRenderDataToGpuAsync$1(Ljava/util/concurrent/CompletableFuture;)V",
            remap = false, require = 1, allow = 1)
    private void stellarium$bindExactBuild(CompletableFuture<Void> task, Operation<Void> original) {
        var previous = stellarium$building.get();
        var generation = ((DistantHorizonsCoverageTree) quadTree).stellarium$coverageGeneration();
        stellarium$building.set(stellarium$coverageLease.begin(clientLevel, pos, task, generation));
        try { original.call(task); }
        finally {
            if (previous == null) stellarium$building.remove();
            else stellarium$building.set(previous);
        }
    }

    @Redirect(method = "getRenderSourceForPos(JLcom/seibel/distanthorizons/core/enums/EDhDirection;)Lcom/seibel/distanthorizons/core/dataObjects/render/ColumnRenderSource;",
            at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/dataObjects/transformers/FullDataToRenderDataTransformer;transformFullDataToRenderSource(Lcom/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV2;Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;)Lcom/seibel/distanthorizons/core/dataObjects/render/ColumnRenderSource;"),
            require = 1, allow = 1)
    private ColumnRenderSource stellarium$copyPrimaryBeforeSourceClose(FullDataSourceV2 source, IClientLevelWrapper level,
                                                                      long requestedPos, EDhDirection direction) {
        var build = stellarium$building.get();
        DistantHorizonsColumnCoverage input = direction == null && source != null && build != null && build.isCurrent()
                ? DistantHorizonsColumnCoverage.capture(source, level.getMinHeight(), level.getMaxHeight()) : null;
        var converted = FullDataToRenderDataTransformer.transformFullDataToRenderSource(source, level);
        if (input != null && converted != null) build.capture(input, !converted.isEmpty());
        return converted;
    }

    @Redirect(method = "uploadToGpuAsync(Ljava/util/concurrent/CompletableFuture;Ljava/util/ArrayList;Ljava/util/ArrayList;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodBufferContainer;tryMakeAndUploadBuffersAsync(JLcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;Ljava/util/ArrayList;Ljava/util/ArrayList;)Ljava/util/concurrent/CompletableFuture;"),
            require = 1, allow = 1)
    private CompletableFuture<LodBufferContainer> stellarium$attachBeforePublication(long position, IClientLevelWrapper level,
                                                                                    ArrayList<ByteBuffer> opaque, ArrayList<ByteBuffer> transparent) {
        var build = stellarium$building.get();
        var future = LodBufferContainer.tryMakeAndUploadBuffersAsync(position, level, opaque, transparent);
        if (build == null) return future;
        // DH registers its publication callback after this call returns; the tag must precede that callback.
        return future.thenApply(container -> {
            if (container != null && container.buffersUploaded) {
                ((DistantHorizonsCoverageBuffer) container).stellarium$coverageAttachment().attach(build);
            }
            return container;
        });
    }

    @Inject(method = "close()V", at = @At("HEAD"), require = 1, allow = 1)
    private void stellarium$invalidateSection(CallbackInfo ci) { stellarium$coverageLease.close(); }
}
