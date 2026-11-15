package dev.cidercore.api;

import net.minecraft.world.phys.Vec3;

/**
 * Optional dynamic light source provider. API v3 only consumes held sources in Ciderlight v1; dropped items, burning
 * entities and glowing entities are added in M3 through this same interface.
 */
@FunctionalInterface
public interface EntityLightProvider {
	/**
	 * Called on the client tick/render thread with the camera position; return {@code null} when nothing is lit.
	 * The returned list may be reused by the caller during this frame only.
	 */
	LightSource[] lights(Vec3 cameraPos, float partialTick);

	/** One dynamic light: world position, color and intensity (0..1). */
	record LightSource(Vec3 position, LightColor color, float intensity) {
	}
}
