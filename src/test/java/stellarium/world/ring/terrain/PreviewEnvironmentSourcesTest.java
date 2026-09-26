package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import net.minecraftforge.fml.common.MCPDummyContainer;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.InjectedModContainer;
import org.junit.Test;

public class PreviewEnvironmentSourcesTest {
    @Test public void injectedMcpUsesDefiningArtifactInsteadOfPlaceholderPath() {
        var metadata=new ModMetadata();metadata.modId="mcp";metadata.version="9.42";
        var definition=new MCPDummyContainer(metadata);
        var wrapped=new InjectedModContainer(definition,new File("deliberately-missing-minecraft.jar"));
        var source=ServerPreviewRuntime.artifactSource(wrapped);
        assertTrue(Files.isRegularFile(source));
        assertEquals(ServerPreviewRuntime.artifactSource(definition),source);
    }
    @Test public void jarCodeSourceResolvesItsContainerWithoutOpeningZipFilesystem() throws Exception {
        var path=Path.of("test source with spaces.jar").toAbsolutePath();
        var jar=URI.create("jar:"+path.toUri()+"!/net/minecraft/").toURL();
        assertEquals(path,ServerPreviewRuntime.codeSourcePath(jar));
        assertThrows(java.io.IOException.class,()->ServerPreviewRuntime.codeSourcePath(URI.create("https://invalid.example/code.jar").toURL()));
    }
}
