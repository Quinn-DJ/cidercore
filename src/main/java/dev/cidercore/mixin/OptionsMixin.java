package dev.cidercore.mixin;

import dev.cidercore.api.CidercoreApi;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the module key bindings to the game's key mappings before Options reads the saved ones, so they appear under
 * Controls &gt; Key Binds, can be rebound and are saved in options.txt like the vanilla keys. Also makes Video Settings
 * show its "restart required" notice when a module says a staged change needs a restart.
 *
 * <p>This used to be Ciderlight's OptionsMixin; the module contributions now come from the core settings hub.</p>
 */
@Mixin(Options.class)
public class OptionsMixin {
	@Shadow
	@Final
	@Mutable
	private KeyMapping[] keyMappings;

	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;load()V"))
	private void cidercore$addModuleKeys(final CallbackInfo ci) {
		KeyMapping[] added = CidercoreApi.settings().keybindMappings();
		if (added.length == 0) {
			return;
		}
		KeyMapping[] mappings = Arrays.copyOf(this.keyMappings, this.keyMappings.length + added.length);
		System.arraycopy(added, 0, mappings, this.keyMappings.length, added.length);
		this.keyMappings = mappings;
	}

	@Inject(method = "isRestartRequiredToApplyVideoSettings", at = @At("RETURN"), cancellable = true)
	private void cidercore$modulesNeedRestart(final CallbackInfoReturnable<Boolean> cir) {
		if (CidercoreApi.settings().restartRequired()) {
			cir.setReturnValue(true);
		}
	}
}
