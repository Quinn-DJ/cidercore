package dev.cidercore.api;

import org.jspecify.annotations.Nullable;

/**
 * A module's {@code config/<moduleId>.properties} file.
 *
 * <p>Two views, both owned by the core: {@link #saved(String)} is the file as the game started (the values this
 * session runs with until a module commits a new one), {@link #set(String, String)} updates the file immediately and
 * is what the next session starts with. Modules keep their own keys and file names; the core only supplies the
 * mechanism and the Fabric config directory.</p>
 */
public interface ConfigFile {
	/** The value the file had when the game started, or {@code null} if the key was absent. */
	@Nullable String saved(String key);

	/** Writes {@code key=value} to the file now (and into the in-session "changed" view). */
	void set(String key, String value);
}
