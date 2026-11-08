package dev.cidercore.api;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/**
 * Lets a module hand custom render pipelines and their GLSL to the core, so they are compiled by the game's own shader
 * reload (the one resource packs use) instead of by a private {@code RenderPipelines.register} call or a mixin.
 *
 * <p>Registered pipelines are appended to {@code RenderPipelines.optionalPipelines()}: the game compiles them together
 * with vanilla's optional pipelines on every shader reload (startup, resource reload and the core's hot apply), and a
 * compile failure is a logged warning rather than a fatal "failed to load required shaders" reload error. A module
 * that cannot compile its GLSL therefore keeps running and can fall back to a simpler draw path.</p>
 *
 * <p><b>Timing.</b> Register during client initialisation (a {@code ClientModInitializer} or a
 * {@link CidercoreModule#initialise(CidercoreContext)}), i.e. before the game's first shader reload: pipelines are only
 * picked up by a reload, so a too-late registration is compiled from the next resource reload on. Registration and
 * the shader-source queries happen on different threads (client init vs. the reload executor), so implementations are
 * thread-safe; the core's are.</p>
 *
 * <p><b>Idempotence.</b> Registration is keyed by {@link RenderPipeline#getLocation()}: registering the same pipeline
 * (or another instance with the same location) again is a no-op and never duplicates the pipeline in the reload
 * list. Registering two different pipelines with the same location is a module bug and logged.</p>
 *
 * <p>See {@code cidercore/docs/render-extension-api.md} for the full contract, including the Cidervista migration
 * recipe this API was designed for.</p>
 */
public interface PipelineRegistrar {
    /**
     * Registers {@code pipeline} for the game's shader reload and {@code source} for its GLSL.
     *
     * <p>The pipeline must have its vertex and fragment shader ids set ({@link RenderPipeline#getShaders()}); each
     * stage is compiled from the source the {@code source} returns for that id/stage, or from the game's resource
     * manager when it returns {@code null} (use {@link ShaderSource#NONE} for "resource packs only"). A pipeline whose
     * shaders cannot be found fails as an optional pipeline: a warning in the log and no draw path, never a crash of
     * the reload.</p>
     *
     * <p>Call before the first shader reload; see the class documentation. Idempotent by pipeline location.</p>
     */
    void register(RenderPipeline pipeline, ShaderSource source);

    /**
     * Registers {@code pipeline} with GLSL read from the classpath at the conventional
     * {@code /assets/<namespace>/shaders/<path><extension>} location derived from its shader ids;
     * {@code anchor} is a class from the module's jar/classpath group (e.g. its client initialiser class), see
     * {@link ShaderSource#ofClasspath(Class)}.
     */
    default void register(final RenderPipeline pipeline, final Class<?> anchor) {
        register(pipeline, ShaderSource.ofClasspath(anchor));
    }

    /**
     * Registers {@code pipeline} with GLSL read from the classpath at an explicit base path (without the
     * {@code .vsh}/{@code .fsh} extension), see {@link ShaderSource#ofClasspath(Class, String)}.
     */
    default void register(final RenderPipeline pipeline, final Class<?> anchor, final String shaderBasePath) {
        register(pipeline, ShaderSource.ofClasspath(anchor, shaderBasePath));
    }

    /**
     * Adds a listener that runs after a shader reload's future completes — whether the reload succeeded or threw.
     *
     * <p>The intended use is retrying a fallback after a failed custom-pipeline compile: a later resource reload (or
     * the core's hot apply) recompiles everything, and the listener can clear the module's "my pipeline is missing"
     * flag. The callback runs on the thread that completes the reload future (normally the render thread, but treat it
     * as unspecified and only set flags/post to the client thread). A listener that throws is logged once and
     * disabled; it never breaks the reload or the frame.</p>
     */
    void addReloadListener(Runnable listener);
}
