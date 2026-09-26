package stellarium.world.ring.generation;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

public class RingworldGenerationSavedDataTest {
    @Test public void newWorldRequiresExplicitRequestAndOldWorldDoesNotAutoEnable() {
        assertTrue(RingworldGenerationSettings.forUnrecordedWorld(false, true).enabled());
        assertFalse(RingworldGenerationSettings.forUnrecordedWorld(false, false).enabled());
        assertFalse(RingworldGenerationSettings.forUnrecordedWorld(true, true).enabled());
        assertFalse(RingworldGenerationSettings.forUnrecordedWorld(true, false).enabled());
    }

    @Test public void enabledAndDisabledPoliciesSurviveSaveReload() {
        for (boolean enabled : new boolean[] {false, true}) {
            var original = RingworldGenerationSavedData.create(new RingworldGenerationSettings(enabled));
            assertTrue(original.isDirty());
            var tag = original.writeToNBT(new NBTTagCompound());
            var loaded = new RingworldGenerationSavedData(RingworldGenerationSavedData.DATA_NAME);
            loaded.readFromNBT(tag);
            assertEquals(original.settings(), loaded.settings());
            assertFalse(loaded.isDirty());
            assertEquals(tag, loaded.writeToNBT(new NBTTagCompound()));
        }
    }

    @Test public void uninitializedStorageCannotSilentlyProvideDisabledPolicy() {
        var data = new RingworldGenerationSavedData(RingworldGenerationSavedData.DATA_NAME);
        assertThrows(IllegalStateException.class, data::settings);
        assertThrows(IllegalStateException.class, () -> data.writeToNBT(new NBTTagCompound()));
    }

    @Test public void missingAndFutureSchemaFailWithoutReplacingCurrentSettings() {
        var data = RingworldGenerationSavedData.create(new RingworldGenerationSettings(true));
        assertThrows(IllegalArgumentException.class, () -> data.readFromNBT(new NBTTagCompound()));
        var tag = data.writeToNBT(new NBTTagCompound());
        tag.setInteger("schema", 2);
        assertThrows(IllegalArgumentException.class, () -> data.readFromNBT(tag));
        assertTrue(data.settings().enabled());
    }

    @Test public void malformedBooleanAndNumericTypesAreRejected() {
        var data = RingworldGenerationSavedData.create(new RingworldGenerationSettings(true));
        var invalidBoolean = data.writeToNBT(new NBTTagCompound());
        invalidBoolean.setByte("enabled", (byte) 2);
        assertThrows(IllegalArgumentException.class, () -> data.readFromNBT(invalidBoolean));
        var wrongType = data.writeToNBT(new NBTTagCompound());
        wrongType.setString("schema", "1");
        assertThrows(IllegalArgumentException.class, () -> data.readFromNBT(wrongType));
    }

    @Test public void changedGeometryRequiresExplicitFutureMigration() {
        for (String key : new String[] {"minZ", "maxZExclusive", "structureMarginChunks"}) {
            var data = RingworldGenerationSavedData.create(new RingworldGenerationSettings(true));
            var tag = data.writeToNBT(new NBTTagCompound());
            tag.setInteger(key, tag.getInteger(key) + 1);
            assertThrows(IllegalArgumentException.class, () -> data.readFromNBT(tag));
        }
    }
}
