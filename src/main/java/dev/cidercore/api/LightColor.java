package dev.cidercore.api;

/**
 * A linear RGB color used for colored block light. Components are normally in {@code [0, 1]} but values above 1
 * are allowed for very bright sources; the shader normalizes the result to a chroma in v1.
 */
public record LightColor(float r, float g, float b) {
	public static final LightColor WARM = new LightColor(1.0F, 0.66F, 0.30F);

	public LightColor {
		if (!Float.isFinite(r) || !Float.isFinite(g) || !Float.isFinite(b)) {
			throw new IllegalArgumentException("LightColor components must be finite");
		}
	}

	/** Packs the color into the 0xRRGGBB form used by config files. */
	public int packed() {
		int ri = Math.clamp(Math.round(this.r * 255.0F), 0, 255);
		int gi = Math.clamp(Math.round(this.g * 255.0F), 0, 255);
		int bi = Math.clamp(Math.round(this.b * 255.0F), 0, 255);
		return (ri << 16) | (gi << 8) | bi;
	}
}
