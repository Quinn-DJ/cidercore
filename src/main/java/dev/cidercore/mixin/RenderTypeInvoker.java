package dev.cidercore.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Gives the core access to the package-private {@code RenderType.create(String, RenderSetup)} factory, which the
 * public {@code RenderTypes} class does not expose for mod pipelines. Used by
 * {@link dev.cidercore.backend.CidercoreRenderTypes} behind {@link dev.cidercore.api.RenderTypeFactory}.
 *
 * <p>An accessor/invoker mixin rather than reflection: it is transformed by the game's own mixin service, so the
 * backend needs no {@code setAccessible} and module code cannot trip over the JDK's encapsulation rules.</p>
 */
@Mixin(RenderType.class)
public interface RenderTypeInvoker {
    /** Calls {@code RenderType.create(name, setup)}. */
    @Invoker("create")
    static RenderType cidercore$createRenderType(final String name, final RenderSetup setup) {
        throw new AssertionError("Mixin accessor stub: RenderTypeInvoker.cidercore$createRenderType");
    }
}
