package dev.cidercore.backend;

import dev.cidercore.api.CidercoreApi;
import dev.cidercore.api.CidercoreContext;
import dev.cidercore.api.CidercoreModule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root-driven module lifecycle: discovers child modules through the {@code cidercore:module} entrypoint, initialises
 * them in {@link CidercoreModule#order()} order, ticks them and shuts them down in reverse order.
 *
 * <p>Fabric has no parent/child mod object; this is the root side of the family contract. Modules never rely on Fabric
 * Loader's own initialisation order — the core calls them explicitly. A module that throws during initialise is
 * logged and skipped so one broken child cannot take the renderer down.</p>
 */
public final class CidercoreModules {
	public static final String ENTRYPOINT = "cidercore:module";

	private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");
	private static final CidercoreContext CONTEXT = new Context();

	private static @Nullable List<CidercoreModule> modules;

	private CidercoreModules() {
	}

	public static synchronized void initialise() {
		if (modules != null) {
			return;
		}
		List<CidercoreModule> found = new ArrayList<>(FabricLoader.getInstance().getEntrypoints(ENTRYPOINT, CidercoreModule.class));
		found.sort(Comparator.comparingInt(CidercoreModule::order));
		List<CidercoreModule> active = new ArrayList<>();
		LOGGER.info("Cidercore: initialising {} child module(s)", found.size());
		for (CidercoreModule module : found) {
			try {
				module.initialise(CONTEXT);
				active.add(module);
				LOGGER.info("Cidercore: module '{}' initialised (order {})", module.id(), module.order());
			} catch (RuntimeException e) {
				LOGGER.error("Cidercore: module '{}' failed to initialise; disabling it", module.id(), e);
			}
		}
		modules = List.copyOf(active);
	}

	public static void tick() {
		List<CidercoreModule> loaded = modules;
		if (loaded == null) {
			return;
		}
		for (CidercoreModule module : loaded) {
			try {
				module.tick();
			} catch (RuntimeException e) {
				LOGGER.error("Cidercore: module '{}' failed in tick", module.id(), e);
			}
		}
	}

	public static void onResourceReload() {
		List<CidercoreModule> loaded = modules;
		if (loaded == null) {
			return;
		}
		for (CidercoreModule module : loaded) {
			try {
				module.onResourceReload();
			} catch (RuntimeException e) {
				LOGGER.error("Cidercore: module '{}' failed on resource reload", module.id(), e);
			}
		}
	}

	public static synchronized void shutdown() {
		List<CidercoreModule> loaded = modules;
		if (loaded == null) {
			return;
		}
		modules = null;
		for (int i = loaded.size() - 1; i >= 0; i--) {
			CidercoreModule module = loaded.get(i);
			try {
				module.shutdown();
				LOGGER.info("Cidercore: module '{}' shut down", module.id());
			} catch (RuntimeException e) {
				LOGGER.error("Cidercore: module '{}' failed to shut down", module.id(), e);
			}
		}
	}

	private static final class Context implements CidercoreContext {
		@Override
		public int apiVersion() {
			return CidercoreApi.API_VERSION;
		}

		@Override
		public void log(final String message) {
			LOGGER.info("Cidercore module: {}", message);
		}
	}
}
