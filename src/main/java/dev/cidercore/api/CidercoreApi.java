package dev.cidercore.api;

/**
 * Entry point of the public {@code dev.cidercore.api} surface.
 *
 * <p>Children declare {@code depends: {"cidercore": ">=... <..."}} in their fabric.mod.json and
 * should also check {@link #API_VERSION} at runtime, failing with a clean log message instead of a
 * mixin crash on mismatch. See PLAN.md §2.4 for the staged API versions.</p>
 */
public final class CidercoreApi {
	/** Bumped on every breaking change to the surface below. */
	public static final int API_VERSION = 1;

	private CidercoreApi() {
	}
}
