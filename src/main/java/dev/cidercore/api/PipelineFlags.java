package dev.cidercore.api;

/**
 * Constants shared by the core pipeline compiler and the shader extension.
 *
 * <p>These used to live on Ciderlight's {@code MetalShaders}; the core needs them now, the shader
 * module still interprets them.</p>
 */
public final class PipelineFlags {
	/** Not a pipeline Ciderlight replaces: the core compiles it from the vanilla shaders. */
	public static final int KIND_NONE = 0;
	public static final int KIND_SOLID = 1;
	public static final int KIND_CUTOUT = 2;
	public static final int KIND_TRANSLUCENT = 3;

	/** First uniform slot the frame extension fills by itself in the main pass. */
	public static final int FIRST_FRAME_SLOT = 6;

	/** Fixed-function blending as vanilla asks for it. */
	public static final int ALPHA_VANILLA = 0;
	/** Translucent terrain blends in its own shader; the written alpha is the shader's marker. */
	public static final int ALPHA_FROM_SHADER = 1;
	/** Alpha 0 tells the composite pass the pixel is already lit. */
	public static final int ALPHA_ZERO = 2;

	private PipelineFlags() {
	}
}
