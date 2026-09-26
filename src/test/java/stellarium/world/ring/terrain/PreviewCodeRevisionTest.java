package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PreviewCodeRevisionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void orderAndLocationDoNotChangeIdentityButContentsAndVersionDo() throws Exception {
        var root=temporary.getRoot().toPath();
        var a=Files.write(root.resolve("a.jar"),new byte[]{1,2,3});var b=Files.write(root.resolve("b.jar"),new byte[]{4,5});
        var first=new PreviewCodeRevision.Artifact("a","1",a);var second=new PreviewCodeRevision.Artifact("b","1",b);
        var original=PreviewCodeRevision.capture(List.of(first,second));
        assertArrayEquals(original,PreviewCodeRevision.capture(List.of(second,first)));
        var copy=Files.copy(a,root.resolve("copied.jar"));
        assertArrayEquals(original,PreviewCodeRevision.capture(List.of(new PreviewCodeRevision.Artifact("a","1",copy),second)));
        assertFalse(Arrays.equals(original,PreviewCodeRevision.capture(List.of(new PreviewCodeRevision.Artifact("a","2",a),second))));
        Files.write(a,new byte[]{1,2,4});assertFalse(Arrays.equals(original,PreviewCodeRevision.capture(List.of(first,second))));
    }
    @Test public void missingDirectoryDuplicateAndEmptyInputsAreRejected() throws Exception {
        var root=temporary.getRoot().toPath();
        assertThrows(IOException.class,()->PreviewCodeRevision.capture(List.of()));
        assertThrows(IOException.class,()->PreviewCodeRevision.capture(List.of(new PreviewCodeRevision.Artifact("a","1",root))));
        assertThrows(IOException.class,()->PreviewCodeRevision.capture(List.of(new PreviewCodeRevision.Artifact("a","1",root.resolve("missing")))));
        var file=Files.write(root.resolve("a.jar"),new byte[]{1});var artifact=new PreviewCodeRevision.Artifact("a","1",file);
        assertThrows(IOException.class,()->PreviewCodeRevision.capture(List.of(artifact,artifact)));
    }
}
