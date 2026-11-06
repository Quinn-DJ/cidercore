package dev.cidercore.api;

/**
 * Handed to each {@link CidercoreModule} once the core is initialised.
 *
 * <p>B0 skeleton: only version and logging are exposed. Backend/services/settings/frames/pipelines
 * are added as the API is grown in B1/B3/B4/B6 (PLAN.md §2.4).</p>
 */
public interface CidercoreContext {
	/** The API version this core implements; see {@link CidercoreApi#API_VERSION}. */
	int apiVersion();

	/** Logs through the core's logger, prefixed with the caller's module id. */
	void log(String message);
}
