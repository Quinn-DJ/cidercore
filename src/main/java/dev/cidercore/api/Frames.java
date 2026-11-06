package dev.cidercore.api;

import org.jspecify.annotations.Nullable;

/**
 * Access to the currently active frame extension, for code that cannot reach the backend device
 * (e.g. the GameRenderer mixin that swaps in the smaller world target).
 */
public final class Frames {
	private static volatile @Nullable FrameExtension active;

	private Frames() {
	}

	public static @Nullable FrameExtension active() {
		return active;
	}

	public static void setActive(final @Nullable FrameExtension extension) {
		active = extension;
	}
}
