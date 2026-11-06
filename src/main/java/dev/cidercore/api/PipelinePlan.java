package dev.cidercore.api;

import org.jspecify.annotations.Nullable;

/**
 * What a shader extension wants the core to do with one render pipeline (or {@code null} for "compile
 * it like vanilla").
 *
 * <p>The plan carries both the classification the core used to get from {@code MetalShaders} and,
 * for the pipelines the extension replaces wholesale, the already-compiled libraries and entry
 * points. Uniform stage masks are computed by the core for replaced pipelines (both stages).</p>
 *
 * @param terrainKind       {@link PipelineFlags#KIND_NONE} or one of the terrain kinds
 * @param terrainLibrary    non-zero: replace this pipeline with this library (both entry points)
 * @param particleLibrary   non-zero: replace a lit-particle pipeline with this library
 * @param vertexEntry       entry point of {@code terrainLibrary}/{@code particleLibrary}
 * @param fragmentEntry     fragment entry point of the replaced library
 * @param alphaMode         {@link PipelineFlags#ALPHA_VANILLA} etc.
 * @param blobShadow        whether entity_snow/blob shadow pipelines are skipped (the shadow map covers them)
 * @param skyUniforms       uniform indices for the sky replacement, or {@code null}
 * @param terrainUniforms   uniform indices for captured terrain draws, or {@code null}
 * @param shadowDescriptor  shadow-pass pipeline descriptor, or {@code null}
 * @param entityUniforms    uniform indices for captured entity draws, or {@code null}
 * @param cloudUniforms     uniform indices for captured cloud draws, or {@code null}
 * @param litParticle       whether this is a lit-particle pipeline
 * @param emissiveLayer     whether this pipeline only marks light sources (glowing eyes)
 */
public record PipelinePlan(
	int terrainKind,
	long terrainLibrary,
	long particleLibrary,
	@Nullable String vertexEntry,
	@Nullable String fragmentEntry,
	int alphaMode,
	boolean blobShadow,
	int @Nullable [] skyUniforms,
	int @Nullable [] terrainUniforms,
	int @Nullable [] shadowDescriptor,
	int @Nullable [] entityUniforms,
	int @Nullable [] cloudUniforms,
	boolean litParticle,
	boolean emissiveLayer
) {
}
