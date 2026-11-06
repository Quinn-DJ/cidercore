package dev.cidercore.api;

import org.jspecify.annotations.Nullable;

/**
 * The shader-visible face of the Metal backend device.
 *
 * <p>This is the minimal escape hatch the shader module needs while it still lives in the same
 * repository family: the current frame's command buffer, the device context handle, library
 * creation for its own shader sources, depth-state caching and deferred resource release. Later API
 * versions grow this into the full host API (see PLAN.md §2.4); it deliberately does not expose the
 * renderpearl backend interfaces themselves.</p>
 */
public interface MetalDeviceApi {
	/** The Metal device name, e.g. {@code "Apple M3"}. */
	String deviceName();

	/** The MTLDevice / context handle. */
	long context();

	/** Compiles MSL into a Metal library owned by the device. */
	long createLibrary(String mslSource);

	/** Cached depth/stencil state handle for the given renderpearl compare function and write flag. */
	long depthState(int compare, boolean write);

	/** Releases {@code release} once the GPU is done with the submits recorded so far. */
	void releaseAfterSubmit(Runnable release);

	/** The current frame's MTLCommandBuffer handle. */
	long currentFrame();

	/** The extension for this device, or {@code null} in core-only mode. */
	@Nullable FrameExtension frameExtension();

	/** Installs (or clears, with {@code null}) the extension; the caller owns nothing. */
	void setFrameExtension(@Nullable FrameExtension extension);
}
