package dev.cidercore.api;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;

/**
 * Creates custom {@link RenderType}s for a module.
 *
 * <p>{@code RenderType.create(String, RenderSetup)} is package-private in 26.3 and the public {@code RenderTypes} class
 * only hands out vanilla's instances, so a mod cannot build its own render type without reaching into
 * {@code net.minecraft.client.renderer.rendertype}. The core wraps that private factory behind a mixin invoker (no
 * reflection, no access widener): a module registers its pipeline (see {@link PipelineRegistrar}), builds a
 * {@link RenderSetup} for it and creates the render type.</p>
 *
 * <p><b>Threads and timing.</b> Call on the render thread, or from client initialisation before the first frame — the
 * same place vanilla sets up render types. Lazy creation during the first frame that needs the render type is fine
 * (that is what the Cidervista L0 spike does). Do not call from a background worker. Creation itself does no GPU work;
 * the type is prepared (texture bindings resolved, pipeline looked up) by vanilla per frame, which is also why a render
 * type stays valid across resource reloads: it holds locations/pipelines, not textures.</p>
 *
 * <p>Failures are not fatal to the game: {@link #create} logs and rethrows, {@link #tryCreate} logs and returns
 * {@code null} so a module can keep its documented fallback path (e.g. Cidervista's {@code debug_quads}).</p>
 */
public interface RenderTypeFactory {
    /**
     * Creates a render type through the game's package-private factory.
     *
     * @throws IllegalStateException when the game rejects the name/setup; the cause is logged with both
     */
    RenderType create(String name, RenderSetup setup);

    /**
     * Like {@link #create} but returns {@code null} instead of throwing when the game rejects the name/setup, so the
     * caller can fall back. The failure is still logged.
     */
    @Nullable
    RenderType tryCreate(String name, RenderSetup setup);
}
