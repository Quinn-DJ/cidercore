package dev.cidercore.api;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import org.jspecify.annotations.Nullable;

/**
 * Classifies and patches the render pipelines the backend compiles, for the shader module.
 *
 * <p>In the pre-split mod this logic lived in {@code MetalShaders} and its results were read by
 * {@code MetalRenderPipeline} directly. The core now asks the transformer instead, keeping the
 * backend free of shader-specific knowledge.</p>
 */
public interface PipelineTransformer {
	/**
	 * Classifies one pipeline. Return {@code null} to compile it like vanilla.
	 *
	 * <p>When the plan replaces the pipeline wholesale, {@link PipelinePlan#terrainLibrary()} /
	 * {@link PipelinePlan#particleLibrary()} must already be compiled through
	 * {@link MetalDeviceApi#createLibrary(String)}.</p>
	 */
	@Nullable PipelinePlan plan(MetalDeviceApi device, BackendRenderPipeline.CreateInfo info);

	/**
	 * Patches one translated shader module before it is compiled. Return {@code null} to leave it
	 * untouched.
	 */
	default @Nullable ShaderPatch patchShader(
		final MetalDeviceApi device, final String pipelineName, final ShaderType stage, final String source, final String entryPoint
	) {
		return null;
	}

	/** Called after the core built a pipeline from this transformer's plan (e.g. to start prebuilds). */
	default void onPipelineBuilt(final MetalDeviceApi device, final BackendRenderPipeline pipeline) {
	}
}
