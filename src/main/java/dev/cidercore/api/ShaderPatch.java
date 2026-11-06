package dev.cidercore.api;

/**
 * A shader module's MSL after the extension patched it.
 *
 * @param source       the (possibly patched) MSL source the core compiles into the pipeline's library
 * @param entryPoint   the entry point to use (usually the original one)
 * @param extraLibrary an already-built library for a variant of this shader (e.g. the hand shine
 *                     fragment), or {@code 0} when there is none; the core owns and releases it
 */
public record ShaderPatch(String source, String entryPoint, long extraLibrary) {
	public static ShaderPatch unchanged(final String source, final String entryPoint) {
		return new ShaderPatch(source, entryPoint, 0L);
	}
}
