package dev.cidercore.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cidercore.backend.CidercoreFrameStages;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the module render-extension frame stages (API v2), see {@link dev.cidercore.api.FrameStage}.
 *
 * <p>Both hooks are returns of vanilla's own methods, so they need no @Redirect and cannot clash with other mods'
 * injections into the same methods (Ciderlight redirects inside {@code submitEntities}, which runs within
 * {@code submitFeatures} but is a different target method):</p>
 *
 * <ul>
 *   <li>{@code submitFeatures} return: after vanilla has turned this frame's level state into submitted render nodes,
 *       before the frame graph is built/executed — the place to prepare/upload GPU resources and to submit custom
 *       geometry through the {@link SubmitNodeCollector}.</li>
 *   <li>{@code executeSolid} return: inside the main render pass's "solid" frame-graph pass, after opaque terrain and
 *       features are drawn and before translucents; the passed {@link RenderPass} carries the main depth buffer, so
 *       modules can record their own draws there. {@code executeSolid} runs on the render thread within the same
 *       {@code LevelRenderer#render} call as {@code submitFeatures}, so the {@link LevelRenderState} field shadowed
 *       here is the matching frame state.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFrameStageMixin {
    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "submitFeatures", at = @At("RETURN"))
    private void cidercore$beforeFrame(
        final LevelRenderState levelRenderState,
        final SubmitNodeCollector submitNodeCollector,
        final boolean renderOutline,
        final CallbackInfo callback
    ) {
        CidercoreFrameStages.instance().dispatchBeforeFrame(levelRenderState, submitNodeCollector);
    }

    @Inject(method = "executeSolid", at = @At("RETURN"))
    private void cidercore$afterOpaque(
        final ChunkSectionsToRender chunkSectionsToRender,
        final PreparedFrame preparedFrame,
        final RenderPass renderPass,
        final CallbackInfo callback
    ) {
        CidercoreFrameStages.instance().dispatchAfterOpaque(this.levelRenderState, renderPass);
    }
}
