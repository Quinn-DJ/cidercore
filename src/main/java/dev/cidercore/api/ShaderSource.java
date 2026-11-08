package dev.cidercore.api;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the GLSL for a {@linkplain PipelineRegistrar registered} render pipeline comes from.
 *
 * <p>Minecraft compiles render pipelines from a {@code ResourceManager} (resource packs), so normally a mod's
 * {@code assets/<namespace>/shaders/...} files are only visible when a resource-loader mod serves them. This family
 * deliberately depends on Fabric Loader only, so the core offers a classpath source instead: the module passes the
 * shader files' location (or just its own class as anchor, and the path is derived from the pipeline's shader
 * {@link Identifier}) and the core reads and caches the bytes. No Fabric API resource-loader is involved.</p>
 *
 * <p>Returning {@code null} from {@link #source(Identifier, ShaderType)} means "I do not provide this stage": the
 * lookup then falls through to the game's own resource-manager source, so a pipeline can mix classpath-provided and
 * resource-pack-provided shaders. A registered source takes precedence over a resource-pack override of the same
 * shader id (same as the pre-framework behaviour); if that ever matters, have the module re-register on a resource
 * reload.</p>
 *
 * <p>Not to be confused with renderpearl's {@code com.mojang.renderpearl.api.pipeline.ShaderSource}: that is the
 * game's own multi-shader source interface, this one only describes one module's GLSL.</p>
 */
@FunctionalInterface
public interface ShaderSource {
    /** Provides no source; the game's resource manager (resource packs) provides the shaders. */
    ShaderSource NONE = (shaderId, stage) -> null;

    /**
     * The GLSL source for {@code shaderId} and {@code stage}, or {@code null} to let the game's resource manager
     * provide it.
     *
     * <p>Called while the game compiles pipelines (a background reload thread on a shader reload), not per frame.
     * Implementations must be thread-safe; the classpath helpers below are.</p>
     */
    @Nullable
    String source(Identifier shaderId, ShaderType stage);

    /**
     * Reads the GLSL from {@code anchor}'s classpath at the conventional location derived from each shader id:
     * {@code /assets/<namespace>/shaders/<path><extension>} (e.g. {@code cidervista:core/vista_terrain} &rarr;
     * {@code /assets/cidervista/shaders/core/vista_terrain.vsh} / {@code .fsh}).
     *
     * <p>{@code anchor} must be a class that lives in the module's jar/classpath group (e.g. the module's
     * {@code CidervistaClient.class}); it only selects the classloader the core reads from. This is the recommended
     * form for a module that follows the {@code assets/<namespace>/shaders/...} convention. Missing files are logged
     * once and returned as {@code null} for that stage.</p>
     */
    static ShaderSource ofClasspath(final Class<?> anchor) {
        return new ClasspathShaderSource(anchor, null);
    }

    /**
     * Reads the GLSL from {@code anchor}'s classpath at an explicit base path, appending the stage extension
     * ({@code .vsh} / {@code .fsh}) per query. Use this when the assets do not follow the shader-id convention: e.g.
     * {@code ofClasspath(CidervistaClient.class, "/assets/cidervista/shaders/core/vista_terrain")}. The same base
     * path is used for every shader id of the pipeline.
     */
    static ShaderSource ofClasspath(final Class<?> anchor, final String basePath) {
        return new ClasspathShaderSource(anchor, basePath);
    }

    /**
     * Reads the GLSL at an explicit base path (no extension) using the core's own classloader. Prefer
     * {@link #ofClasspath(Class, String)} with a module class as anchor; this overload is for assets that really are
     * on the classloader core code sees (in this family: always, since Fabric Loader puts every mod on one
     * classloader and dev runs put every module on one classpath).
     */
    static ShaderSource ofClasspath(final String basePath) {
        return new ClasspathShaderSource(ShaderSource.class, basePath);
    }
}

/**
 * The classpath implementation of {@link ShaderSource}: reads each stage once, caches it (including misses) and logs
 * what it did.
 */
final class ClasspathShaderSource implements ShaderSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");

    private final Class<?> anchor;
    /** Explicit base path without extension, or {@code null} to derive it from the shader id. */
    private final @Nullable String basePath;
    /** {@code shaderId|stage} to source; a null value marks a file that was not found. */
    private final Map<String, String> cache = new HashMap<>();
    private boolean loggedServing;

    ClasspathShaderSource(final Class<?> anchor, final @Nullable String basePath) {
        this.anchor = anchor;
        this.basePath = basePath;
    }

    @Override
    public synchronized @Nullable String source(final Identifier shaderId, final ShaderType stage) {
        String key = shaderId + "|" + stage.name();
        if (!this.cache.containsKey(key)) {
            this.cache.put(key, this.load(shaderId, stage));
        }
        return this.cache.get(key);
    }

    private @Nullable String load(final Identifier shaderId, final ShaderType stage) {
        String path = this.resourcePath(shaderId, stage);
        try (InputStream stream = this.anchor.getResourceAsStream(path)) {
            if (stream == null) {
                // Not fatal to the reload: the game's own source (resource pack) may still provide it, and a missing
                // custom pipeline is only an optional-pipeline warning. Never throw out of the shader compiler.
                LOGGER.warn(
                    "Cidercore: no classpath GLSL for shader {} stage {} at {}; that pipeline will fail to compile "
                        + "unless a resource pack provides it",
                    shaderId, stage.getName(), path
                );
                return null;
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (!this.loggedServing) {
                this.loggedServing = true;
                LOGGER.info("Cidercore: serving classpath GLSL {} ({}) to the game's shader compiler", path, stage.getName());
            }
            return text;
        } catch (IOException exception) {
            LOGGER.warn("Cidercore: could not read classpath GLSL {} for shader {} stage {}", path, shaderId, stage.getName(), exception);
            return null;
        }
    }

    private String resourcePath(final Identifier shaderId, final ShaderType stage) {
        // ShaderType.idConverter() is FileToIdConverter("shaders", ".vsh"/".fsh"); using it keeps the convention in
        // one place instead of hard-coding the extensions here.
        var converter = stage.idConverter();
        if (this.basePath != null) {
            return this.basePath + converter.extension();
        }
        return "/assets/" + shaderId.getNamespace() + "/" + converter.prefix() + "/" + shaderId.getPath() + converter.extension();
    }
}
