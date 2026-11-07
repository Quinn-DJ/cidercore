package dev.cidercore.gui;

import dev.cidercore.backend.CidercoreFog;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The core's own settings page, opened from the "Cidercore..." button that the settings hub adds to
 * Video Settings. It currently hosts the family-wide "Disable Vanilla Fog" switch.
 *
 * <p>Labels are literal components with spaces because this mod family has no Fabric API
 * resource-loader, so a translation key without a loaded lang file would be shown raw.</p>
 */
public final class CoreSettingsScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.literal("Cidercore Settings");
	private static final Component FOG_TOOLTIP = Component.literal(
		"Removes vanilla environmental and render-distance fog (the zeroed fog buffer vanilla uses for unfogged "
			+ "passes). Applies immediately; saved in config/cidercore.properties.");

	public CoreSettingsScreen(final @Nullable Screen lastScreen, final Options options) {
		super(lastScreen, options, TITLE);
	}

	@Override
	protected void addOptions() {
		OptionInstance<Boolean> disableFog = OptionInstance.createBoolean(
			"Disable Vanilla Fog",
			value -> Tooltip.create(FOG_TOOLTIP),
			CidercoreFog.disabled(),
			CidercoreFog::setDisabled
		);
		this.list.addBig(disableFog);
	}
}
