package dev.cidercore.mixin;

import dev.cidercore.api.CidercoreApi;
import dev.cidercore.api.SettingsPage;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds one button per module settings page under the graphics preset in Video Settings (where Ciderlight's button used
 * to be); each opens the page its module contributed to the core settings hub.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	private VideoSettingsScreenMixin(final Screen lastScreen, final Options options, final Component title) {
		super(lastScreen, options, title);
	}

	/**
	 * Just before qualityOptions is called: addOptions adds the graphics preset and then the quality options, so this
	 * lands between them however many rows come before.
	 */
	@Inject(
		method = "addOptions",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/options/VideoSettingsScreen;qualityOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;")
	)
	private void cidercore$addModuleSettingsButtons(final CallbackInfo ci) {
		for (SettingsPage page : CidercoreApi.settings().pages()) {
			if (!page.available()) {
				continue;
			}
			this.list.addBig(Button.builder(page.buttonLabel(), button -> page.open(this.minecraft, this))
				.tooltip(Tooltip.create(page.buttonTooltip()))
				.build());
		}
	}
}
