package dev.cidercore.mixin;

import dev.cidercore.backend.CidercorePipelines;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier;
import net.minecraft.server.packs.resources.PreparableReloadListener.SharedState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notifies the render-extension reload listeners when a {@code ShaderManager.reload} future completes (API v2).
 *
 * <p>Modules use this to clear a "my custom pipeline failed to compile, fall back for now" flag: a later resource
 * reload — or the core's own hot apply, which calls {@code ShaderManager.reload} directly — recompiles every optional
 * pipeline, so the module gets another chance. The future completes whether the reload succeeded or failed; the
 * callback itself must not throw (the registry logs once and disables a broken listener). It runs on the thread that
 * completes the reload future, which modules must treat as unspecified.</p>
 */
@Mixin(ShaderManager.class)
public class ShaderManagerReloadMixin {
    @Inject(method = "reload", at = @At("RETURN"))
    private void cidercore$notifyRenderExtensionReloadListeners(
        final SharedState currentReload,
        final Executor taskExecutor,
        final PreparationBarrier preparationBarrier,
        final Executor reloadExecutor,
        final CallbackInfoReturnable<CompletableFuture<Void>> cir
    ) {
        CompletableFuture<Void> future = cir.getReturnValue();
        if (future == null) {
            return;
        }
        future.whenComplete((ignored, throwable) -> CidercorePipelines.instance().onShaderReload());
    }
}
