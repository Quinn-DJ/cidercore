package dev.cidercore.api;

/**
 * Fabric entrypoint placed in a child mod's {@code fabric.mod.json} under
 * {@value FrameExtensions#ENTRYPOINT}. The core calls it when the Metal device is created.
 */
@FunctionalInterface
public interface FrameExtensionFactory {
	FrameExtension create(MetalDeviceApi device);
}
