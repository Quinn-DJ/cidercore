package dev.cidercore;

import dev.cidercore.api.CidercoreApi;
import dev.cidercore.api.Translations;
import dev.cidercore.backend.CidercoreModules;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point for the core mod.
 *
 * <p>B0 skeleton: this only boots the mod and logs the API version. The module registry and the
 * Metal backend bootstrap arrive in later batches (see PLAN.md §4.3).</p>
 */
public final class CidercoreClient implements ClientModInitializer {
	public static final String MOD_ID = "cidercore";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		Translations.register(MOD_ID, CidercoreClient.class);
		LOGGER.info("Cidercore initialised (API version {})", CidercoreApi.API_VERSION);
		CidercoreModules.initialise();
	}
}
