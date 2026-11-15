package dev.cidercore.api;

import dev.cidercore.backend.CidercoreFrameStages;
import dev.cidercore.backend.CidercorePipelines;
import dev.cidercore.backend.CidercorePipelineReload;
import dev.cidercore.backend.CidercoreRenderTypes;

/**
 * Entry point of the public {@code dev.cidercore.api} surface.
 *
 * <p>Children declare {@code depends: {"cidercore": ">=... <..."}} in their fabric.mod.json and should also check
 * {@link #API_VERSION} (and, when using individual v2 features, {@link #supports(int)}) at runtime, failing with a
 * clean log message instead of a mixin crash on mismatch. See PLAN.md §2.4 for the staged API versions.</p>
 *
 * <p>API history: v1 added the settings hub and the pipeline reload machinery; <b>v2</b> added the render-extension
 * surface ({@link #pipelines()}, {@link #renderTypes()}, {@link #frameStages()}) that lets a module register custom
 * renderpearl pipelines with their GLSL, create custom {@code RenderType}s and hook the level frame. <b>v3</b> adds
 * the colored-light service ({@link #coloredLights()}) and intentionally bumps the API version: a v2 child does not
 * have the new surface and is rejected, so Cidercore 0.3.0 must ship with rebuilt Ciderlight/Cidervista.</p>
 */
public final class CidercoreApi {
    /** Bumped on every breaking change to the surface below. */
    public static final int API_VERSION = 3;

    /** {@link #supports(int)} bit: the module pipeline registry ({@link #pipelines()}). */
    public static final int CAPABILITY_PIPELINE_REGISTRAR = 1;
    /** {@link #supports(int)} bit: custom {@code RenderType} creation ({@link #renderTypes()}). */
    public static final int CAPABILITY_RENDER_TYPE_FACTORY = 1 << 1;
    /** {@link #supports(int)} bit: the level frame stages ({@link #frameStages()}). */
    public static final int CAPABILITY_FRAME_STAGES = 1 << 2;
    /** {@link #supports(int)} bit: module lang files are merged into the game's language ({@link Translations}). */
    public static final int CAPABILITY_TRANSLATIONS = 1 << 3;
    /** {@link #supports(int)} bit: the colored-light service ({@link #coloredLights()}); v3 always has it. */
    public static final int CAPABILITY_COLORED_LIGHTS = 1 << 4;

    /** Every capability this core implements; see {@link #supports(int)}. */
    public static final int CAPABILITIES = CAPABILITY_PIPELINE_REGISTRAR | CAPABILITY_RENDER_TYPE_FACTORY | CAPABILITY_FRAME_STAGES
        | CAPABILITY_TRANSLATIONS | CAPABILITY_COLORED_LIGHTS;

    private CidercoreApi() {
    }

    /**
     * Whether this core implements every bit in {@code capabilityMask} (a combination of the
     * {@code CAPABILITY_*} constants). Modules that need one v2 feature should check its bit before using it and
     * disable just that path with a clean log message otherwise.
     */
    public static boolean supports(final int capabilityMask) {
        return (CAPABILITIES & capabilityMask) == capabilityMask;
    }

    /** The core-owned pipeline reload machinery (hot apply, progress, rollback). */
    public static PipelineReload pipelineReload() {
        return CidercorePipelineReload.instance();
    }

    /** The core settings hub: module-contributed pages, key binds and config files. */
    public static Settings settings() {
        return dev.cidercore.backend.CidercoreSettings.instance();
    }

    /** A module's {@code config/<moduleId>.properties} file, created/read on first use. */
    public static ConfigFile config(final String moduleId) {
        return dev.cidercore.backend.ConfigFiles.of(moduleId);
    }

    // ---- API v2: render extensions ----

    /**
     * Registers custom render pipelines and their GLSL with the game's shader reload; see {@link PipelineRegistrar}.
     * Register during client initialisation, before the first shader reload.
     */
    public static PipelineRegistrar pipelines() {
        return CidercorePipelines.instance();
    }

    /** Creates custom {@code RenderType}s through the game's package-private factory; see {@link RenderTypeFactory}. */
    public static RenderTypeFactory renderTypes() {
        return CidercoreRenderTypes.instance();
    }

    /** Hooks the level frame (before the frame graph, and after opaque geometry inside the main pass). */
    public static FrameStage frameStages() {
        return CidercoreFrameStages.instance();
    }

    // ---- API v3: colored light ----

    /**
     * The core-side colored-light service. The surface is present whenever API v3 is, so modules using it must still
     * check {@link #CAPABILITY_COLORED_LIGHTS} only for optional sub-features. Ciderlight calls this from its frame
     * extension and binds {@link ColoredLights#textureHandle()} into the terrain/particle/entity shaders.
     */
    public static ColoredLights coloredLights() {
        return dev.cidercore.backend.ColoredLightsService.instance();
    }
}
