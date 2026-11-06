package dev.cidercore.api.frame;

/**
 * One captured cloud draw from the main pass: vertices are generated from the CloudFaces buffer, as
 * in vanilla.
 */
public record CloudDraw(
	long indexBuffer, int indexType, int indexCount, int firstIndex, int vertexBase, long transforms, long transformsOffset,
	long info, long infoOffset, long faces
) {
}
