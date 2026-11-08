package dev.cidercore.api;

/**
 * Handed to each {@link CidercoreModule} once the core is initialised.
 *
 * <p>The context is the module's handle on the core: version/logging, the settings hub, the pipeline reload machinery
 * and (API v2) the render extensions — module pipeline registration, {@code RenderType} creation and frame stages. The
 * default methods delegate to the corresponding {@link CidercoreApi} entry point, so a module may use either.</p>
 */
public interface CidercoreContext {
	/** The API version this core implements; see {@link CidercoreApi#API_VERSION}. */
	int apiVersion();

	/** Logs through the core's logger, prefixed with the caller's module id. */
	void log(String message);

	/** The settings hub (module pages, key binds, config files). */
	default Settings settings() {
		return CidercoreApi.settings();
	}

	/** The core-owned pipeline reload machinery. */
	default PipelineReload pipelineReload() {
		return CidercoreApi.pipelineReload();
	}

	/** The module pipeline registry (API v2); see {@link PipelineRegistrar}. */
	default PipelineRegistrar pipelines() {
		return CidercoreApi.pipelines();
	}

	/** Custom {@code RenderType} creation (API v2); see {@link RenderTypeFactory}. */
	default RenderTypeFactory renderTypes() {
		return CidercoreApi.renderTypes();
	}

	/** The level frame stages (API v2); see {@link FrameStage}. */
	default FrameStage frameStages() {
		return CidercoreApi.frameStages();
	}
}
