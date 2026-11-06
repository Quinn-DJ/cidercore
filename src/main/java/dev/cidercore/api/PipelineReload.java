package dev.cidercore.api;

import java.util.function.Consumer;

/**
 * The core-owned pipeline reload machinery: rebuilding render pipelines through the game's own shader reload path
 * (the one resource packs use) without restarting the game, with progress, fallback and rollback.
 *
 * <p>This used to be the middle half of Ciderlight's {@code ShaderReload}. The shader module still decides
 * <em>when</em> to reload and builds the new shader generation; the core owns the reload/rollback state machine and
 * the game-side {@code ShaderManager} call. Modules plug in through {@link PipelineGeneration} callbacks, so the core
 * never needs to know what a "shader generation" is.</p>
 */
public interface PipelineReload {
	/** One pipeline swap prepared by a module. */
	interface PipelineGeneration {
		/** Installs the new generation and commits its settings (render thread). */
		void swapIn();

		/** Puts the previous generation back after a failed reload (render thread). */
		void swapBack();

		/** Commits the module's staged settings once the reload succeeded (called before releasePrevious). */
		default void commitSettings() {
		}

		/** Schedules the previous generation's release once the GPU is done with in-flight work. */
		void releasePrevious();

		/** Schedules the failed generation's release once the GPU is done with in-flight work. */
		void releaseNew();
	}

	/** Whether hot apply is enabled ({@code -Dciderlight.hotReload=false} restores restart-to-apply). */
	boolean hotApplyEnabled();

	/** Whether a reload is under way (the GameRenderer mixin skips the world draw while it is). */
	boolean applying();

	/** Pipelines compiled by the current reload so far. */
	int compiled();

	/** Pipelines the current reload should compile in total. */
	int total();

	/** Whether the current reload has been running longer than expected (the settings page says so). */
	boolean slow();

	/** Called for every pipeline the game rebuilds during a reload. */
	void notePipelineCompiled();

	/**
	 * Applies {@code generation}: swaps it in, rebuilds every render pipeline through the game's reload path and
	 * releases the previous generation on success or rolls back on failure. Must be called on the render thread; the
	 * callbacks run on it too.
	 */
	void apply(PipelineGeneration generation, Runnable onSuccess, Consumer<Throwable> onFailure);

	/** Called once per client tick from the core's Minecraft mixin: warns once when a reload is unusually slow. */
	void tick();
}
