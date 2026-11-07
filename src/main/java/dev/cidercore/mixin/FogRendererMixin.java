package dev.cidercore.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.cidercore.backend.CidercoreFog;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the core "disable vanilla fog" toggle to the game's fog uniform lookup.
 *
 * <p>{@code FogRenderer} has a private static {@code fogEnabled}; when it is false,
 * {@code getBuffer(FogMode.WORLD)} returns the zeroed {@code emptyBuffer}, i.e. the same "no fog"
 * uniform vanilla itself uses for unfogged passes. Setting it from the core setting at the head of
 * {@code getBuffer} makes the toggle apply on the very next frame and keeps it authoritative even if
 * a debug path calls {@code toggleFog()}. The fog data is still computed by {@code setupFog}; only
 * the buffer handed to the render passes changes, so the mixin stays compatible with Ciderlight's
 * shader-side fog handling (it receives the empty "no fog" buffer).</p>
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
	@Shadow
	private static boolean fogEnabled;

	@Inject(method = "getBuffer", at = @At("HEAD"))
	private void cidercore$applyVanillaFogToggle(final CallbackInfoReturnable<GpuBufferSlice> cir) {
		fogEnabled = !CidercoreFog.disabled();
	}
}
