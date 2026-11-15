package dev.cidercore.api;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Core-side colored block-light service (API v3). The core owns the world data, the Metal volume and the propagation;
 * Ciderlight only samples the resulting 3D texture.
 */
public interface ColoredLights {
	/** Registers/overrides an exact block source. Later registrations win over tags and defaults. */
	void registerBlock(Block block, LightColor color, float strength);

	/** Registers a tag source; exact block registrations take precedence. */
	void registerTag(TagKey<Block> tag, LightColor color, float strength);

	/** Marks a block as a colored-light filter (stained glass, water, ice, leaves). */
	void registerFilter(Block block, LightColor tint, float transmission);

	/** Adds a dynamic source provider (held light implemented by Ciderlight; M3 extends this). */
	void addEntityLightProvider(EntityLightProvider provider);

	/** Enables/disables the feature without tearing down the Metal volume; disabled frames sample the warm fallback. */
	void setEnabled(boolean enabled);

	/** Scales the chroma blend (0..2); 1 is the tuned look. */
	void setStrength(float strength);

	/** Propagation iteration count (quality); clamped to a safe range by the core. */
	void setIterations(int iterations);

	/** Volume dimensions (quality); rounded to multiples of 8 and clamped by the core. Live resize. */
	void setVolumeSize(int x, int y, int z);

	/** True when the feature is enabled and a Metal device/volume is initialized. */
	boolean available();

	/** The 3D volume texture handle, or 0 when unavailable. */
	long textureHandle();

	/** A clamp/linear sampler for the volume, or 0 when unavailable. */
	long samplerHandle();

	/** Called once per frame by Ciderlight before it binds the volume; updates sources/propagation if needed. */
	void prepareFrame(Vec3 cameraPos, float partialTick);

	/**
	 * FrameData values, in the order the shader expects: origin relative to the camera (xyz), enabled, inverse size
	 * (xyz), color strength, transmission, fade. Call after {@link #prepareFrame(Vec3, float)}.
	 */
	float[] frameParams();

	/**
	 * Dynamic colored point lights near the camera, for short-range brightness that vanilla block light does not
	 * carry (dropped items, burning/glowing entities). Each entry is seven floats: world x/y/z, rgb, intensity.
	 * Returns the number written (at most eight); {@code out} must have room for 8 * 7 floats.
	 */
	int dynamicLights(Vec3 cameraPos, float[] out);

	/** Debug/API query: sampled chroma at a world position, or {@code null} when outside the volume. */
	@Nullable LightSample sample(Vec3 worldPos);

	/** A sampled color: normalized chroma and transmission. */
	record LightSample(float r, float g, float b, float transmission) {
	}
}
