package dev.cidercore.api;

import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * The core's settings hub: collected module contributions plus the per-module config files.
 */
public interface Settings {
	/** All contributors, in entrypoint order. */
	List<SettingsContributor> contributors();

	/** All contributed pages. */
	List<SettingsPage> pages();

	/** All contributed key mappings (appended to Options' array before it loads). */
	KeyMapping[] keybindMappings();

	/** Whether any module has staged a change that needs the restart notice. */
	boolean restartRequired();

	/** A module's config file; see {@link ConfigFile}. */
	ConfigFile config(String moduleId);

	/** Ticks every contributed keybind group and contributor. */
	void tick(Minecraft minecraft);
}
