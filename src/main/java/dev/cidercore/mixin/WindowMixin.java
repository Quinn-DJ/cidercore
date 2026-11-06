package dev.cidercore.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.cidercore.backend.HitchTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the hitch trace when the game window gains or loses focus: keys released meanwhile never reach the game. */
@Mixin(Window.class)
public class WindowMixin {
    @Inject(method = "onFocus", at = @At("HEAD"))
    private void ciderlight$traceFocus(final boolean focused, final CallbackInfo ci) {
        if (HitchTrace.ENABLED) {
            HitchTrace.focus(focused);
        }
    }
}
