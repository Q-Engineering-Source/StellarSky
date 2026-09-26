package stellarium.world.ring.generation;

import static org.junit.Assert.*;

import java.io.File;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.gen.structure.template.TemplateManager;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldInfo;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Dependency evidence only: exercises vanilla failure semantics without a Minecraft world. */
public class RingworldMapStorageEvidenceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void failedWriteStillClearsDirtyAndDoesNotReachDisk() throws Exception {
        File blockedTarget = temporary.newFolder("not-a-file");
        var storage = new MapStorage(new FixtureSaveHandler(blockedTarget));
        var data = RingworldGenerationSavedData.create(new RingworldGenerationSettings(true));
        storage.setData(RingworldGenerationSavedData.DATA_NAME, data);
        storage.saveAllData();
        assertFalse(data.isDirty());
        assertTrue(blockedTarget.isDirectory());
        assertEquals(0, blockedTarget.list().length);
    }

    private record FixtureSaveHandler(File target) implements ISaveHandler {
        public File getMapFileFromName(String name) {
            return RingworldGenerationSavedData.DATA_NAME.equals(name) ? target : null;
        }
        public File getWorldDirectory() { return target.getParentFile(); }
        public WorldInfo loadWorldInfo() { throw unsupported(); }
        public void checkSessionLock() { throw unsupported(); }
        public IChunkLoader getChunkLoader(WorldProvider provider) { throw unsupported(); }
        public void saveWorldInfoWithPlayer(WorldInfo info, NBTTagCompound player) { throw unsupported(); }
        public void saveWorldInfo(WorldInfo info) { throw unsupported(); }
        public IPlayerFileData getPlayerNBTManager() { throw unsupported(); }
        public void flush() { throw unsupported(); }
        public TemplateManager getStructureTemplateManager() { throw unsupported(); }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("Fixture has no world or chunk generation");
        }
    }
}
