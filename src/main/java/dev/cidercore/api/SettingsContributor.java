package dev.cidercore.api;

import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * A module's contribution to the core settings hub: its settings pages, key bindings and whether it has staged a
 * change that needs the restart notice. Contributed through the {@code cidercore:settings} entrypoint, so the core
 * can collect it without a registration call or an initialisation-order dependency.
 */
public interface SettingsContributor {
	/** The module's mod id, e.g. {@code "ciderlight"}; also its config file name. */
	String moduleId();

	/** Pages the core adds to Video Settings under their own buttons. */
	default List<SettingsPage> pages() {
		return List.of();
	}

	/** Key mapping groups the core appends to the game's key mappings. */
	default List<KeybindGroup> keybinds() {
		return List.of();
	}

	/** Whether a staged change needs the "restart required" notice in Video Settings. */
	default boolean restartRequired() {
		return false;
	}

	/** Called once per client tick by the core's Minecraft mixin. */
	default void tick(final Minecraft minecraft) {
	}
}
