package stellarium.common;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Property;
import stellarapi.api.lib.config.INBTConfig;
import stellarapi.api.lib.config.SimpleHierarchicalNBTConfig;
import stellarapi.api.lib.config.property.ConfigProperty;
import stellarapi.api.lib.config.property.ConfigPropertyDouble;
import stellarapi.api.lib.config.property.ConfigPropertyBoolean;
import stellarapi.api.lib.config.property.ConfigPropertyInteger;
import stellarium.stellars.layer.StellarLayerRegistry;

public class ServerSettings extends SimpleHierarchicalNBTConfig {
	public static final int DEFAULT_DAY_LENGTH_TICKS = 1_728_000;
	private static final String TIME_MULTIPLIER_KEY = "Time_Multiplier";

	public double day, year;
	public int yearOffset, dayOffset;
	public double tickOffset;
	public double timeMultiplier;
	public boolean systemTimeSync;
	public int systemTimeSyncIntervalSeconds;

	private ConfigPropertyDouble propDay, propYear;
	private ConfigPropertyInteger propYearOffset, propDayOffset;
	private ConfigPropertyDouble propTickOffset;
	private ConfigPropertyDouble propTimeMultiplier;
	private ConfigPropertyBoolean propSystemTimeSync;
	private ConfigPropertyInteger propSystemTimeSyncInterval;
	//private ConfigPropertyInteger propStartingYear, propClockDateOffset;
	public ConfigPropertyDouble propAxialTilt, propPrecession;

	public ServerSettings() {
		StellarLayerRegistry.getInstance().composeSettings(this);

		// Removed Server_Enabled.
		// On the server it's useless considering that all features are disabled.
		// On the client, it should obey what server want.
        this.propDay = new ConfigPropertyDouble("Day_Length", "day", DEFAULT_DAY_LENGTH_TICKS);
        this.propYear = new ConfigPropertyDouble("Year_Length", "year", 365.25);
        this.propYearOffset = new ConfigPropertyInteger("Year_Offset", "yearOffset", 0);
        this.propDayOffset = new ConfigPropertyInteger("Day_Offset", "dayOffset", 0);
        this.propTickOffset = new ConfigPropertyDouble("Tick_Offset", "tickOffset", 16000.0);
        this.propTimeMultiplier = new ConfigPropertyDouble(TIME_MULTIPLIER_KEY, "timeMultiplier", 1.0);
        this.propSystemTimeSync = new ConfigPropertyBoolean("System_Time_Sync", "systemTimeSync", false);
        this.propSystemTimeSyncInterval = new ConfigPropertyInteger(
        		"System_Time_Sync_Interval_Seconds", "systemTimeSyncIntervalSeconds", 60);

        //this.propStartingYear = new ConfigPropertyInteger("Starting_Year", "startingYear", 1);
        //this.propClockDateOffset = new ConfigPropertyInteger("Clock_Date_Offset", "clockDateOffset", 0);

        this.propAxialTilt = new ConfigPropertyDouble("Axial_Tilt", "axialTilt", 23.5);
        this.propPrecession = new ConfigPropertyDouble("Precession", "precession", 0.0);

		this.addConfigProperty(this.propDay);
		this.addConfigProperty(this.propYear);
		this.addConfigProperty(this.propYearOffset);
		this.addConfigProperty(this.propDayOffset);
		this.addConfigProperty(this.propTickOffset);
		this.addConfigProperty(this.propTimeMultiplier);
		this.addConfigProperty(this.propSystemTimeSync);
		this.addConfigProperty(this.propSystemTimeSyncInterval);
		this.addConfigProperty(this.propAxialTilt);
		this.addConfigProperty(this.propPrecession);
	}

	@Override
	public void setupConfig(Configuration config, String category) {
		config.setCategoryComment(category, "Configurations for server modifications.");
		config.setCategoryLanguageKey(category, "config.category.server");
		config.setCategoryRequiresWorldRestart(category, true);

		migrateTimeMultiplier(config, category);
		
		super.setupConfig(config, category);
        
        propDay.setComment("Solar day length in world ticks. Default: 1728000 ticks per day, "
                + "72000 per hour (24 hours at 20 TPS with Time_Multiplier=1). "
                + "System_Time_Sync uses its separate civil-clock mapping.");
        propDay.setRequiresWorldRestart(true);
        propDay.setLanguageKey("config.property.server.daylength");
        
        propYear.setComment("Length of a year, in a day.");
        propYear.setRequiresWorldRestart(true);
        propYear.setLanguageKey("config.property.server.yearlength");

       	propYearOffset.setComment("Year offset on world starting time.");
       	propYearOffset.setRequiresWorldRestart(true);
       	propYearOffset.setLanguageKey("config.property.server.yearoffset");

       	propDayOffset.setComment("Day offset on world starting time.");
       	propDayOffset.setRequiresWorldRestart(true);
       	propDayOffset.setLanguageKey("config.property.server.dayoffset");

       	propTickOffset.setComment("Tick offset on world starting time.");
       	propTickOffset.setRequiresWorldRestart(true);
       	propTickOffset.setLanguageKey("config.property.server.tickoffset");

		propTimeMultiplier.setComment("Default B3M-style time scale for new dimensions. "
				+ "2 makes a day twice as long, 0.5 twice as fast, 0 pauses, and negative values reverse time.");
		propTimeMultiplier.setMinValue(-20);
		propTimeMultiplier.setMaxValue(72);
		propTimeMultiplier.setRequiresWorldRestart(false);

		propSystemTimeSync.setComment("Synchronize daylight and seasonal time with the server system clock.");
		propSystemTimeSync.setRequiresWorldRestart(false);

		propSystemTimeSyncInterval.setComment("Seconds between wall-clock corrections in system-time mode.");
		propSystemTimeSyncInterval.setMinValue(1);
		propSystemTimeSyncInterval.setMaxValue(3600);
		propSystemTimeSyncInterval.setRequiresWorldRestart(false);

        propAxialTilt.setComment("Axial tilt in degrees. Always 0.0 when Server_Enabled is false.");
        propAxialTilt.setRequiresWorldRestart(true);
        propAxialTilt.setLanguageKey("config.property.server.axialtilt");

       	propPrecession.setComment("Precession in degrees per year.");
       	propPrecession.setRequiresWorldRestart(true);
       	propPrecession.setLanguageKey("config.property.server.precession");
	}

	static void migrateTimeMultiplier(Configuration config, String category) {
		if(!config.hasKey(category, TIME_MULTIPLIER_KEY))
			return;

		ConfigCategory configCategory = config.getCategory(category);
		Property oldProperty = configCategory.get(TIME_MULTIPLIER_KEY);
		if(oldProperty == null || oldProperty.getType() == Property.Type.DOUBLE)
			return;

		double value = 1.0;
		try {
			double parsed = Double.parseDouble(oldProperty.getString());
			if(!Double.isNaN(parsed) && !Double.isInfinite(parsed))
				value = parsed;
		} catch(NumberFormatException ignored) {
		}

		configCategory.remove(TIME_MULTIPLIER_KEY);
		configCategory.put(TIME_MULTIPLIER_KEY,
				new Property(TIME_MULTIPLIER_KEY, Double.toString(value), Property.Type.DOUBLE));
	}

	@Override
	public void loadFromConfig(Configuration config, String category) {
       	super.loadFromConfig(config, category);
       	this.setValues();
	}
	
	/**Default for servers without Stellar Sky*/
	public void setDefault() {
		for(ConfigProperty property : this.listProperties)
			property.setAsDefault();
		// A server without StellarSky still uses Minecraft's original daylight period.
		propDay.setDouble(24000.0);
    	propAxialTilt.setDouble(0.0);
    	propTickOffset.setDouble(17500.0);
    	this.tickOffset = 17500.0;
    	
    	this.setValues();
	}
	
	private void setValues() {
		this.day = propDay.getDouble();
       	this.year = propYear.getDouble();
       	this.yearOffset = propYearOffset.getInt();
       	this.dayOffset = propDayOffset.getInt();
       	this.tickOffset = propTickOffset.getDouble();
		this.timeMultiplier = propTimeMultiplier.getDouble();
		this.systemTimeSync = propSystemTimeSync.getBoolean();
		this.systemTimeSyncIntervalSeconds = propSystemTimeSyncInterval.getInt();
	}

	
	public void readFromNBT(NBTTagCompound compound) {
       	super.readFromNBT(compound);
       	
       	this.day = propDay.getDouble();
       	this.year = propYear.getDouble();
       	this.yearOffset = propYearOffset.getInt();
       	this.dayOffset = propDayOffset.getInt();
       	this.tickOffset = propTickOffset.getDouble();
		this.timeMultiplier = propTimeMultiplier.getDouble();
		this.systemTimeSync = propSystemTimeSync.getBoolean();
		this.systemTimeSyncIntervalSeconds = propSystemTimeSyncInterval.getInt();
	}

	@Override
	public INBTConfig copy() {
		ServerSettings settings = new ServerSettings();
		settings.day = this.day;
		settings.year = this.year;
		settings.yearOffset = this.yearOffset;
		settings.dayOffset = this.dayOffset;
		settings.tickOffset = this.tickOffset;
		settings.timeMultiplier = this.timeMultiplier;
		settings.systemTimeSync = this.systemTimeSync;
		settings.systemTimeSyncIntervalSeconds = this.systemTimeSyncIntervalSeconds;
		this.applyCopy(settings);
		return settings;
	}
}
