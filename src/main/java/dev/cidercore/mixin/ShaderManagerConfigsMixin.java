package dev.cidercore.mixin;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.cidercore.backend.CidercorePipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves module-registered GLSL to the game's shader compiler (API v2).
 *
 * <p>{@code ShaderManager.Configs} loads shader sources from the {@code ResourceManager} only; this family depends on
 * Fabric Loader without Fabric API, so a module's {@code assets/<namespace>/shaders/...} are otherwise invisible
 * (Cidervista used a temporary mixin for exactly this before the collaboration phase). The injection answers only the
 * shader ids a module registered through {@link CidercorePipelines}; every other lookup, and every lookup for which the
 * registered {@code ShaderSource} returns {@code null}, falls through untouched to the resource manager. It never
 * throws into the reload: a missing classpath file is logged and behaves like "not provided".</p>
 */
@Mixin(ShaderManager.Configs.class)
public class ShaderManagerConfigsMixin {
    @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
    private void cidercore$provideRegisteredShaderSource(
        final Identifier id, final ShaderType type, final CallbackInfoReturnable<String> cir
    ) {
        String source = CidercorePipelines.instance().shaderSource(id, type);
        if (source != null) {
            cir.setReturnValue(source);
        }
    }
}
