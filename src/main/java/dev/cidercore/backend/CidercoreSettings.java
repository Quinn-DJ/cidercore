package dev.cidercore.backend;

import dev.cidercore.api.ConfigFile;
import dev.cidercore.api.KeybindGroup;
import dev.cidercore.api.Settings;
import dev.cidercore.api.SettingsContributor;
import dev.cidercore.api.SettingsPage;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core implementation of the settings hub. Modules contribute through the {@code cidercore:settings} entrypoint;
 * the list is collected lazily on first use (Options construction, the Video Settings button row, or the client tick),
 * so modules do not need to register in a particular initialisation order.
 */
public final class CidercoreSettings implements Settings {
	public static final String ENTRYPOINT = "cidercore:settings";

	private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");
	private static final CidercoreSettings INSTANCE = new CidercoreSettings();

	private @Nullable List<SettingsContributor> contributors;

	private CidercoreSettings() {
	}

	public static CidercoreSettings instance() {
		return INSTANCE;
	}

	@Override
	public List<SettingsContributor> contributors() {
		List<SettingsContributor> loaded = this.contributors;
		if (loaded == null) {
			loaded = List.copyOf(FabricLoader.getInstance().getEntrypoints(ENTRYPOINT, SettingsContributor.class));
			this.contributors = loaded;
			LOGGER.info("Cidercore: settings hub loaded {} contributor(s), {} page(s), {} key binding(s)",
				loaded.size(), pages().size(), keybindMappings().length);
		}
		return loaded;
	}

	@Override
	public List<SettingsPage> pages() {
		List<SettingsPage> pages = new ArrayList<>();
		for (SettingsContributor contributor : contributors()) {
			pages.addAll(contributor.pages());
		}
		return List.copyOf(pages);
	}

	@Override
	public KeyMapping[] keybindMappings() {
		List<KeyMapping> mappings = new ArrayList<>();
		for (SettingsContributor contributor : contributors()) {
			for (KeybindGroup group : contributor.keybinds()) {
				mappings.addAll(List.of(group.mappings()));
			}
		}
		return mappings.toArray(KeyMapping[]::new);
	}

	@Override
	public boolean restartRequired() {
		for (SettingsContributor contributor : contributors()) {
			if (contributor.restartRequired()) {
				return true;
			}
		}
		return false;
	}

	@Override
	public ConfigFile config(final String moduleId) {
		return ConfigFiles.of(moduleId);
	}

	@Override
	public void tick(final Minecraft minecraft) {
		for (SettingsContributor contributor : contributors()) {
			for (KeybindGroup group : contributor.keybinds()) {
				group.tick(minecraft);
			}
			contributor.tick(minecraft);
		}
	}
}
