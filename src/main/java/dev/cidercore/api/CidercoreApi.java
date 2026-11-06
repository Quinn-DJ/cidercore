package dev.cidercore.api;

import dev.cidercore.backend.CidercorePipelineReload;

/**
 * Entry point of the public {@code dev.cidercore.api} surface.
 *
 * <p>Children declare {@code depends: {"cidercore": ">=... <..."}} in their fabric.mod.json and should also check
 * {@link #API_VERSION} at runtime, failing with a clean log message instead of a mixin crash on mismatch. See
 * PLAN.md §2.4 for the staged API versions.</p>
 */
public final class CidercoreApi {
	/** Bumped on every breaking change to the surface below. */
	public static final int API_VERSION = 1;

	private CidercoreApi() {
	}

	/** The core-owned pipeline reload machinery (hot apply, progress, rollback). */
	public static PipelineReload pipelineReload() {
		return CidercorePipelineReload.instance();
	}

	/** The core settings hub: module-contributed pages, key binds and config files. */
	public static Settings settings() {
		return dev.cidercore.backend.CidercoreSettings.instance();
	}

	/** A module's {@code config/<moduleId>.properties} file, created/read on first use. */
	public static ConfigFile config(final String moduleId) {
		return dev.cidercore.backend.ConfigFiles.of(moduleId);
	}
}
