package dev.cidercore.backend;

import dev.cidercore.api.PipelineReload;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core-owned half of the old Ciderlight {@code ShaderReload} state machine: installs a module's new pipeline
 * generation, rebuilds every render pipeline through the game's own {@code ShaderManager} reload path (the one
 * resource packs use) and rolls back if that fails.
 *
 * <p>Kept deliberately page-agnostic: it knows nothing about shader settings, screens or Metal; a module hands it a
 * {@link PipelineGeneration} and two callbacks. The property names are still the shader module's historical ones
 * ({@code ciderlight.hotReload}, {@code ciderlight.reloadMode}) so existing launch scripts and docs keep working.</p>
 */
public final class CidercorePipelineReload implements PipelineReload {
	/** -Dciderlight.hotReload=false restores the restart-to-apply behaviour. */
	private static final boolean HOT_ENABLED = Boolean.parseBoolean(System.getProperty("ciderlight.hotReload", "true"));
	/** -Dciderlight.reloadMode: auto (ShaderManager, falling back to a full resource reload), shader, or packs. */
	private static final String MODE = System.getProperty("ciderlight.reloadMode", "auto");
	private static final long SLOW_MS = 30_000L;
	private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");
	private static final CidercorePipelineReload INSTANCE = new CidercorePipelineReload();

	private boolean applying;
	private long startedAt;
	private int compiled;
	private int total;
	private boolean slowWarned;
	private @Nullable PipelineGeneration generation;
	private @Nullable Runnable onSuccess;
	private @Nullable Consumer<Throwable> onFailure;

	private CidercorePipelineReload() {
	}

	public static CidercorePipelineReload instance() {
		return INSTANCE;
	}

	@Override
	public boolean hotApplyEnabled() {
		return HOT_ENABLED;
	}

	@Override
	public boolean applying() {
		return this.applying;
	}

	@Override
	public int compiled() {
		return this.compiled;
	}

	@Override
	public int total() {
		return this.total;
	}

	@Override
	public boolean slow() {
		return this.applying && Util.getMillis() - this.startedAt > SLOW_MS;
	}

	@Override
	public void notePipelineCompiled() {
		this.compiled++;
	}

	@Override
	public void apply(final PipelineGeneration generation, final Runnable onSuccess, final Consumer<Throwable> onFailure) {
		if (!HOT_ENABLED || this.applying) {
			return;
		}
		this.applying = true;
		this.startedAt = Util.getMillis();
		this.compiled = 0;
		this.total = RenderPipelines.requiredPipelines().size() + RenderPipelines.optionalPipelines().size();
		this.slowWarned = false;
		this.generation = generation;
		this.onSuccess = onSuccess;
		this.onFailure = onFailure;
		LOGGER.info("Cidercore: applying pipeline generation ({} pipelines)", this.total);

		try {
			generation.swapIn();
		} catch (RuntimeException e) {
			finishFailure(e);
			return;
		}
		reload(game()).whenComplete((ignored, throwable) -> game().execute(() -> {
			if (throwable == null) {
				finishSuccess();
			} else {
				finishFailure(throwable);
			}
		}));
	}

	@Override
	public void tick() {
		if (this.applying && !this.slowWarned && Util.getMillis() - this.startedAt > SLOW_MS) {
			this.slowWarned = true;
			LOGGER.warn("Cidercore: applying pipeline generation is still running after {} ms; waiting for the shader compiler",
				Util.getMillis() - this.startedAt);
		}
	}

	private static Minecraft game() {
		return Minecraft.getInstance();
	}

	/**
	 * Rebuilds the pipelines through ShaderManager's reload: the same compile-and-swap path resource packs use,
	 * without reloading the packs themselves.
	 */
	private static CompletableFuture<Void> reload(final Minecraft minecraft) {
		String mode = MODE.toLowerCase(java.util.Locale.ROOT);
		if (mode.equals("packs")) {
			return minecraft.reloadResourcePacks();
		}
		CompletableFuture<Void> direct;
		try {
			ShaderManager shaderManager = minecraft.getShaderManager();
			PreparableReloadListener.SharedState sharedState = new PreparableReloadListener.SharedState(minecraft.getResourceManager());
			PreparableReloadListener.PreparationBarrier barrier = CompletableFuture::completedFuture;
			direct = shaderManager.reload(sharedState, Util.backgroundExecutor(), barrier, minecraft);
		} catch (RuntimeException e) {
			direct = CompletableFuture.failedFuture(e);
		}
		if (mode.equals("shader")) {
			return direct;
		}
		// auto: the direct path may not even start (it is public, but not an entry point the game documents). If no
		// pipeline was compiled yet, the whole resource-pack reload is tried once; that path is the game's own.
		return direct.handle((ignored, throwable) -> {
			if (throwable == null) {
				return CompletableFuture.<Void>completedFuture(null);
			}
			if (INSTANCE.compiled == 0) {
				LOGGER.warn("Cidercore: the shader-only reload could not start ({}); reloading the resource packs instead",
					throwable.toString());
				return minecraft.reloadResourcePacks();
			}
			return CompletableFuture.<Void>failedFuture(throwable);
		}).thenCompose(java.util.function.Function.identity());
	}

	private void finishSuccess() {
		// Match the side effects a full resource reload has: failed post effects are reset.
		game().gameRenderer.onResourceManagerReload(game().getResourceManager());
		PipelineGeneration generation = this.generation;
		if (generation != null) {
			generation.commitSettings();
			generation.releasePrevious();
		}
		long elapsed = Util.getMillis() - this.startedAt;
		this.applying = false;
		this.generation = null;
		Runnable success = this.onSuccess;
		this.onSuccess = null;
		this.onFailure = null;
		LOGGER.info("Cidercore: applied pipeline generation in {} ms ({} pipelines)", elapsed, this.compiled);
		if (success != null) {
			success.run();
		}
	}

	private void finishFailure(final Throwable throwable) {
		PipelineGeneration generation = this.generation;
		if (generation != null) {
			try {
				generation.swapBack();
			} catch (RuntimeException e) {
				LOGGER.warn("Cidercore: could not roll back the failed pipeline generation", e);
			}
			generation.releaseNew();
		}
		long elapsed = Util.getMillis() - this.startedAt;
		this.applying = false;
		this.slowWarned = false;
		this.generation = null;
		Consumer<Throwable> failure = this.onFailure;
		this.onSuccess = null;
		this.onFailure = null;
		LOGGER.warn("Cidercore: could not apply pipeline generation after {} ms, keeping the previous one: {}",
			elapsed, throwable.toString());
		if (failure != null) {
			failure.accept(throwable);
		}
	}
}
