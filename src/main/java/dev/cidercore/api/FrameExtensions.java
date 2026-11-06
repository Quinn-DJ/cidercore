package dev.cidercore.api;

import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Discovers the shader module's {@link FrameExtension} through the {@code cidercore:frame_extension}
 * entrypoint.
 *
 * <p>The backend is created during game startup, possibly before client mod initialisers run, so
 * the core cannot depend on a registration call; entrypoints are safe to query at device creation
 * time (Fabric Loader has resolved all mods by then). No entrypoint means "core only": the backend
 * runs with vanilla pipelines and the extension hooks are skipped.</p>
 */
public final class FrameExtensions {
	public static final String ENTRYPOINT = "cidercore:frame_extension";

	private FrameExtensions() {
	}

	public static @Nullable FrameExtension create(final MetalDeviceApi device) {
		List<FrameExtensionFactory> factories = FabricLoader.getInstance().getEntrypoints(ENTRYPOINT, FrameExtensionFactory.class);
		for (FrameExtensionFactory factory : factories) {
			FrameExtension extension = factory.create(device);
			if (extension != null) {
				return extension;
			}
		}
		return null;
	}
}
