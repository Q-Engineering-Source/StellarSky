package stellarium.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import net.minecraftforge.common.config.Configuration;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;
import stellarium.CommonProxy;
import stellarium.IProxy;
import stellarium.StellarSky;
import stellarium.stellars.StellarManager;

public class ServerSettingsDayLengthTest {
    @Test
    public void managerRestorationPreservesLockAndRemoteAuthorityInsteadOfUsingAnUnlockedSnapshot() {
        IProxy previous = StellarSky.PROXY;
        CommonProxy proxy = new CommonProxy();
        Configuration config = new Configuration();
        proxy.getServerSettings().setupConfig(config, "serverconfig");
        proxy.getServerSettings().loadFromConfig(config, "serverconfig");
        StellarSky.PROXY = proxy;
        try {
            NBTTagCompound old = new NBTTagCompound();
            old.setDouble("day", 24000.0);
            old.setBoolean("locked", true);
            StellarManager locked = new StellarManager("locked-day-test");
            locked.syncFromNBT(old, false);
            assertEquals(24000.0, locked.getSettings().day, 0.0);

            old.setBoolean("locked", false);
            StellarManager unlocked = new StellarManager("unlocked-day-test");
            unlocked.syncFromNBT(old, false);
            assertEquals(1_728_000.0, unlocked.getSettings().day, 0.0);

            StellarManager remote = new StellarManager("remote-day-test");
            remote.syncFromNBT(old, true);
            assertEquals(24000.0, remote.getSettings().day, 0.0);
        } finally {
            StellarSky.PROXY = previous;
        }
    }

    @Test
    public void explicitOldConfigurationAndSavedDayLengthRemainUnchanged() {
        Configuration config = new Configuration();
        config.get("serverconfig", "Day_Length", 24000.0).set(24000.0);
        ServerSettings settings = new ServerSettings();
        settings.setupConfig(config, "serverconfig");
        settings.loadFromConfig(config, "serverconfig");
        assertEquals(24000.0, settings.day, 0.0);

        NBTTagCompound saved = new NBTTagCompound();
        settings.writeToNBT(saved);
        ServerSettings restored = new ServerSettings();
        restored.readFromNBT(saved);
        assertEquals(24000.0, restored.day, 0.0);
        assertEquals(24000.0, ((ServerSettings) restored.copy()).day, 0.0);
    }

    @Test
    public void modlessFallbackStillUsesMinecraftDayLength() {
        ServerSettings settings = new ServerSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "serverconfig");
        settings.loadFromConfig(config, "serverconfig");
        settings.setDefault();
        assertEquals(24000.0, settings.day, 0.0);
        NBTTagCompound fallback = new NBTTagCompound();
        settings.writeToNBT(fallback);
        assertEquals(24000.0, fallback.getDouble("day"), 0.0);
    }

    @Test
    public void newConfigurationUsesATwentyFourHourDayWithoutSlowingTickCadence() {
        ServerSettings settings = new ServerSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "serverconfig");
        settings.loadFromConfig(config, "serverconfig");

        assertEquals(1_728_000.0, settings.day, 0.0);
        assertEquals(72_000.0, settings.day / 24.0, 0.0);
        assertEquals(1.0, settings.timeMultiplier, 0.0);
        assertFalse(settings.systemTimeSync);
    }
}
