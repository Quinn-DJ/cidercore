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
 * <p>Labels are translation keys; the core's {@code ClientLanguageMixin} merges the module lang files into the game's
 * language (this family does not use Fabric API's resource loader), and the entries are in
 * {@code assets/cidercore/lang}.</p>
 */
public final class CoreSettingsScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.translatable("cidercore.settings.title");
	private static final Component FOG_TOOLTIP = Component.translatable("cidercore.option.disable_vanilla_fog.tooltip");

	public CoreSettingsScreen(final @Nullable Screen lastScreen, final Options options) {
		super(lastScreen, options, TITLE);
	}

	@Override
	protected void addOptions() {
		OptionInstance<Boolean> disableFog = OptionInstance.createBoolean(
			"cidercore.option.disable_vanilla_fog",
			value -> Tooltip.create(FOG_TOOLTIP),
			CidercoreFog.disabled(),
			CidercoreFog::setDisabled
		);
		this.list.addBig(disableFog);
	}
}
