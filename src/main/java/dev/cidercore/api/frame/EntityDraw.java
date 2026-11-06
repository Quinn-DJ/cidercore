package dev.cidercore.api.frame;

/**
 * One captured entity/item draw from the main pass.
 */
public record EntityDraw(
	int[] shadowDescriptor, long vertexBuffer, long vertexOffset, long indexBuffer, int indexType, int indexCount, int firstIndex,
	int vertexBase, int instanceCount, int firstInstance, long transforms, long transformsOffset, long atlas, long atlasSampler,
	boolean particle, boolean particleAtlas, boolean emissive
) {
}
