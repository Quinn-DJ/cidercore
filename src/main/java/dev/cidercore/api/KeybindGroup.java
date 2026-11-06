package dev.cidercore.api;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * A group of key mappings a module contributes. The core appends them to the game's key mapping array before Options
 * loads, so they appear under Controls &gt; Key Binds, can be rebound and are saved in options.txt like vanilla keys,
 * and ticks the group once per client tick.
 */
public interface KeybindGroup {
	/** The mappings, created before {@code Options.load()} runs so saved keys are restored. */
	KeyMapping[] mappings();

	/** Called once per client tick; handle consumed clicks here. */
	default void tick(final Minecraft minecraft) {
	}
}
