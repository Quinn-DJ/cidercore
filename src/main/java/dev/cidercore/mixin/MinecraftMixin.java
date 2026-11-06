package dev.cidercore.mixin;

import dev.cidercore.Benchmark;
import dev.cidercore.api.CidercoreApi;
import dev.cidercore.backend.FramePacer;
import dev.cidercore.backend.HitchTrace;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-tick/per-frame core work: the dev benchmark, frame pacing, pipeline-reload progress, module key binds and the
 * hitch trace. Moved here from Ciderlight when the backend became the core; module key handling now goes through the
 * core settings hub.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void cidercore$tickBegin(final CallbackInfo ci) {
		Minecraft minecraft = (Minecraft)(Object)this;
		if (Benchmark.enabled()) {
			Benchmark.onTick(minecraft);
		}
		if (Benchmark.enabled() || Benchmark.autoMoves()) {
			Benchmark.skipBackupPrompt(minecraft);
		}
		FramePacer.worldTick(minecraft.level != null, minecraft.gui.screen() == null);
		CidercoreApi.pipelineReload().tick();
		CidercoreApi.settings().tick(minecraft);
		if (HitchTrace.ENABLED) {
			HitchTrace.tickStart(minecraft.level != null, minecraft.gui.screen() == null);
			if (!Benchmark.autoMoves()) {
				HitchTrace.movementTick(minecraft);
			}
		}
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void cidercore$tickEnd(final CallbackInfo ci) {
		if (HitchTrace.ENABLED) {
			HitchTrace.tickEnd();
		}
	}

	@Inject(method = "runTick", at = @At("HEAD"))
	private void cidercore$frameBegin(final boolean advanceGameTime, final CallbackInfo ci) {
		Minecraft minecraft = (Minecraft)(Object)this;
		if (Benchmark.enabled()) {
			Benchmark.onFrame(minecraft);
		}
		if (Benchmark.autoMoves()) {
			Benchmark.autoMove(minecraft);
		}
	}
}
