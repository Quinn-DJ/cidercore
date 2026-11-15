package dev.cidercore.mixin;

import dev.cidercore.backend.ColoredLightsService;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the colored-light volume dirty when a client-side block change lands inside it. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "setBlocksDirty", at = @At("HEAD"))
	private void cidercore$coloredLightBlockChanged(final BlockPos pos, final BlockState oldState, final BlockState newState,
	                                                final CallbackInfo ci) {
		ColoredLightsService.onBlockChanged(pos);
	}
}
