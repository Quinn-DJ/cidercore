package dev.cidercore.api.frame;

/**
 * One captured terrain multidraw from the main pass. Contents addresses point at shared memory.
 *
 * <p>Produced by the core's render pass, consumed by the shader extension. Field order and meaning
 * are identical to the pre-split {@code MetalShaders.TerrainDraw}.</p>
 */
public record TerrainDraw(
	int kind, int[] shadowDescriptor, long vertexBuffer, long vertexOffset, long instanceBuffer, long instanceOffset, long indexBuffer,
	int indexType, long commands, long commandsOffset, int drawCount, long globals, long globalsOffset, long projectionContents,
	long terrainContents, long fogContents, long atlas, long atlasSampler,
	// For culling sections in the shadow passes: CPU addresses of the draw arguments and of the per-section instance
	// data (0 when they cannot be read on the CPU), how many instances are readable there, and where a section's
	// block position lies in an instance.
	long commandsAddress, long sectionsAddress, int sectionCount, int sectionStride, int sectionPosOffset
) {
}
