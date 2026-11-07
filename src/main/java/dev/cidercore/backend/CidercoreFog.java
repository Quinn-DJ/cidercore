package dev.cidercore.backend;

import dev.cidercore.api.CidercoreApi;
import dev.cidercore.api.ConfigFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core-owned toggle for vanilla environmental fog (2B family quality of life).
 *
 * <p>Minecraft 26.3's {@code FogRenderer} keeps a private static {@code fogEnabled} flag: when it is
 * false, {@code getBuffer(FogMode.WORLD)} returns the zeroed "no fog" uniform buffer, which is also
 * the path vanilla uses for passes that must not be fogged. {@code CidercoreFog} persists the user's
 * choice in {@code config/cidercore.properties} and the {@code FogRenderer} mixin forces that static
 * flag to the chosen value before every fog-buffer lookup, so toggling applies immediately (no
 * restart and no resource reload).</p>
 *
 * <p>The value is a core session setting: {@link #setDisabled(boolean)} updates the in-memory flag
 * first and rewrites the config file, so the next launch starts with the same choice. The feature is
 * intentionally core-level (not a child module) because it touches the shared vanilla fog path and
 * must also work when only cidercore is installed.</p>
 */
public final class CidercoreFog {
	private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");
	private static final String CONFIG_KEY = "disableVanillaFog";

	private static final ConfigFile CONFIG = CidercoreApi.config("cidercore");
	private static volatile boolean disabled = readSaved();

	static {
		if (disabled) {
			LOGGER.info("Cidercore: vanilla fog disabled in config/cidercore.properties");
		}
	}

	private CidercoreFog() {
	}

	/** True when vanilla environmental/render-distance fog must be suppressed. */
	public static boolean disabled() {
		return disabled;
	}

	/** Applies the toggle immediately and persists it for the next session. */
	public static void setDisabled(final boolean value) {
		if (disabled == value) {
			return;
		}
		disabled = value;
		CONFIG.set(CONFIG_KEY, Boolean.toString(value));
		LOGGER.info("Cidercore: vanilla fog {}", value ? "disabled" : "enabled");
	}

	private static boolean readSaved() {
		return Boolean.parseBoolean(CONFIG.saved(CONFIG_KEY));
	}
}
