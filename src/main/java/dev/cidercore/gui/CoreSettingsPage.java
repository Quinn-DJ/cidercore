package dev.cidercore.gui;

import dev.cidercore.api.SettingsPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Settings-hub view of {@link CoreSettingsScreen}: adds a "Cidercore..." button to Video Settings. */
public final class CoreSettingsPage implements SettingsPage {
	private static final Component TITLE = Component.translatable("cidercore.settings.title");
	private static final Component BUTTON = Component.translatable("cidercore.settings.button");
	private static final Component TOOLTIP = Component.translatable("cidercore.settings.button.tooltip");

	@Override
	public Component title() {
		return TITLE;
	}

	@Override
	public Component buttonLabel() {
		return BUTTON;
	}

	@Override
	public Component buttonTooltip() {
		return TOOLTIP;
	}

	@Override
	public boolean available() {
		return true;
	}

	@Override
	public void open(final Minecraft minecraft, final @Nullable Screen parent) {
		minecraft.setScreenAndShow(new CoreSettingsScreen(parent, minecraft.options));
	}
}
