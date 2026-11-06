package dev.cidercore.backend;

import dev.cidercore.api.ConfigFile;
import dev.cidercore.platform.LoaderPaths;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and caches {@link ConfigFile}s under the Fabric config directory.
 *
 * <p>Same semantics the shader module had before the split: the file is read once when first touched (the values the
 * session runs with) and every {@link ConfigFile#set(String, String)} rewrites the file at once, so the next session
 * sees the change.</p>
 */
public final class ConfigFiles {
	private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");
	private static final Map<String, ConfigFile> FILES = new ConcurrentHashMap<>();

	private ConfigFiles() {
	}

	public static ConfigFile of(final String moduleId) {
		return FILES.computeIfAbsent(moduleId, ConfigFiles::load);
	}

	private static ConfigFile load(final String moduleId) {
		return new PropertiesConfigFile(moduleId, LoaderPaths.configDir().resolve(moduleId + ".properties"));
	}

	private static final class PropertiesConfigFile implements ConfigFile {
		private final String moduleId;
		private final Path path;
		private final Properties saved;
		private final Properties changed;

		private PropertiesConfigFile(final String moduleId, final Path path) {
			this.moduleId = moduleId;
			this.path = path;
			this.saved = read();
			this.changed = copy(this.saved);
		}

		@Override
		public synchronized @Nullable String saved(final String key) {
			return this.saved.getProperty(key);
		}

		@Override
		public synchronized void set(final String key, final String value) {
			this.changed.setProperty(key, value);
			try {
				Files.createDirectories(this.path.getParent());
				try (Writer out = Files.newBufferedWriter(this.path)) {
					this.changed.store(out, this.moduleId + " settings");
				}
			} catch (IOException e) {
				LOGGER.warn("Cidercore: could not save {}", this.path, e);
			}
		}

		private Properties read() {
			Properties properties = new Properties();
			if (Files.exists(this.path)) {
				try (Reader in = Files.newBufferedReader(this.path)) {
					properties.load(in);
				} catch (IOException e) {
					LOGGER.warn("Cidercore: could not read {}", this.path, e);
				}
			}
			return properties;
		}

		private static Properties copy(final Properties source) {
			Properties properties = new Properties();
			properties.putAll(source);
			return properties;
		}
	}
}
