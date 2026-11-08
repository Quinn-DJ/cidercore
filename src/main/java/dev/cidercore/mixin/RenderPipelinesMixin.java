package dev.cidercore.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.cidercore.backend.CidercorePipelines;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Has every registered pipeline compiled while resources load, including the render-extension pipelines modules
 * register through {@link CidercorePipelines} (API v2).
 *
 * <p>Minecraft keeps its list by name, and some pipelines share one (the plain, glint and special-glint item_cutout):
 * only the last of each name was compiled up front, the others on the render thread the first time something drew with
 * them, which for the item in the player's hand is the first frame of the world. Those shadowed pipelines are re-added
 * here. Module pipelines are appended too (deduplicated by identity against vanilla's required/optional lists and
 * against each other), so they are compiled by the game's normal shader-reload path; because they are
 * <em>optional</em>, a compile failure is a logged warning instead of a fatal resource-reload error.</p>
 */
@Mixin(RenderPipelines.class)
public class RenderPipelinesMixin {
    /** Every pipeline passed to register, in order. Made on first use: register runs from the class initializer. */
    @Unique private static @Nullable List<RenderPipeline> cidercore$registered;

    @Inject(method = "register(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;", at = @At("HEAD"))
    private static void cidercore$remember(final RenderPipeline pipeline, final CallbackInfoReturnable<RenderPipeline> cir) {
        if (cidercore$registered == null) {
            cidercore$registered = new ArrayList<>();
        }
        cidercore$registered.add(pipeline);
    }

    /**
     * Adds the shadowed vanilla pipelines and then the module-registered pipelines to the optional list. The returning
     * list is copied first; the identity set keeps the append idempotent (a pipeline already in required/optional is
     * never added twice, and registering the same location twice is already prevented at registration).
     */
    @Inject(method = "optionalPipelines", at = @At("RETURN"), cancellable = true)
    private static void cidercore$addExtraPipelines(final CallbackInfoReturnable<List<RenderPipeline>> cir) {
        Set<RenderPipeline> listed = Collections.newSetFromMap(new IdentityHashMap<>());
        listed.addAll(RenderPipelines.requiredPipelines());
        listed.addAll(cir.getReturnValue());
        List<RenderPipeline> all = new ArrayList<>(cir.getReturnValue());
        if (cidercore$registered != null) {
            for (RenderPipeline pipeline : cidercore$registered) {
                if (listed.add(pipeline)) {
                    all.add(pipeline);
                }
            }
        }
        for (RenderPipeline pipeline : CidercorePipelines.instance().pipelines()) {
            if (listed.add(pipeline)) {
                all.add(pipeline);
            }
        }
        cir.setReturnValue(all);
    }
}
