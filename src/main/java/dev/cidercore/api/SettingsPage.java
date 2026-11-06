package dev.cidercore.api;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * One settings screen contributed by a module. The core lists it as a button in Video Settings and opens it; the
 * module owns the screen itself.
 */
public interface SettingsPage {
	/** Screen title (for logging/hub lists; the module's own screen shows it too). */
	Component title();

	/** Label of the button the core adds to Video Settings, e.g. {@code "Ciderlight..."}. */
	Component buttonLabel();

	/** Tooltip of that button. */
	Component buttonTooltip();

	/** Whether the page can be opened in this session (e.g. the renderer is active). */
	boolean available();

	/** Opens the page; {@code parent} is the screen to return to. */
	void open(Minecraft minecraft, @Nullable Screen parent);
}
