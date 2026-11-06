package dev.cidercore.api;

/**
 * A child module of the Cider family.
 *
 * <p>Discovered through the {@code cidercore:module} entrypoint and initialised by the core in
 * {@link #order()} order — children never rely on Fabric Loader's own initialisation order.
 * Skeleton for B0; the registry that calls this arrives in B4 (PLAN.md §4.3).</p>
 */
public interface CidercoreModule {
	/** The module's mod id, e.g. {@code "ciderlight"} or {@code "cidervista"}. */
	String id();

	/** Deterministic initialisation order; lower runs first. */
	default int order() {
		return 1000;
	}

	/** Called once the core backend and API are ready. */
	void initialise(CidercoreContext context);

	/** Called after Minecraft reloads resources (resource packs, shaders). */
	default void onResourceReload() {
	}

	/** Called once per client tick. */
	default void tick() {
	}

	/** Called when the game shuts down or the module is disabled. */
	default void shutdown() {
	}
}
