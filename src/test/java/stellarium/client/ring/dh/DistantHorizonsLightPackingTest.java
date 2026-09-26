package stellarium.client.ring.dh;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses the real DH quad encoder, not the misleading skyLight/blockLight shader names. */
public class DistantHorizonsLightPackingTest {
    @Test public void actualDhVerticesStoreSkyLowAndBlockHigh() throws Exception {
        var rows = new ArrayList<String>();
        try (var buffers = new PhantomArrayListCheckout(LodQuadBuilder.ARRAY_LIST_POOL)) {
          for (int sky = 0; sky < 16; sky++) for (int block = 0; block < 16; block++) {
            try (var builder = LodQuadBuilder.getBuilder(false, null)) {
                builder.addQuadUp((short) 0, (short) 0, (short) 0, (short) 1,
                        0xffffffff, (short) 0, (byte) 0, (byte) sky, (byte) block);
                var vertices = builder.makeOpaqueVertexBuffers(buffers);
                assertEquals(1, vertices.size());
                int meta = vertices.getFirst().getShort(6) & 255;
                assertEquals(sky, meta & 15);
                assertEquals(block, meta >>> 4);
                rows.add(sky + "," + block + "," + meta);
            }
          }
        }
        Files.write(Path.of("build/dh-light-vertices.csv"), rows);
    }
}
