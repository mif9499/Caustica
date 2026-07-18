package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.rt.water.BiomeWaterColors;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CausticaMod implements ModInitializer {
	public static final String MOD_ID = "caustica";
	public static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

	@Override
	public void onInitialize() {
		// Register every setting (applying TOML file values) and write a default config on first run.
		CausticaConfig.ensureRegistered();
		CausticaConfig.saveIfMissing();
		// Per-biome water absorption overrides: write the default template on first run, then load.
		BiomeWaterColors.saveDefaultIfMissing();
		BiomeWaterColors.load();
		LOGGER.info("Caustica initialized (common); config: {}", CausticaConfig.configPath());
	}
}
