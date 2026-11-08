package dev.cidercore.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the classpath GLSL provider: shader-id path derivation, per-stage dispatch, caching, misses and
 * the explicit-base-path form. The shader assets live in {@code src/test/resources/assets/cidertest/shaders/core}.
 */
class ShaderSourceTest {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("cidertest", "core/test_shader");
    private static final Identifier MISSING = Identifier.fromNamespaceAndPath("cidertest", "core/not_there");

    @Test
    void servesBothStagesFromTheConventionalClasspathLocation() {
        ShaderSource source = ShaderSource.ofClasspath(ShaderSourceTest.class);
        assertEquals("// test vertex shader\nvoid main() {}\n", source.source(SHADER, ShaderType.VERTEX));
        assertEquals("// test fragment shader\nvoid main() {}\n", source.source(SHADER, ShaderType.FRAGMENT));
    }

    @Test
    void cachesTheSourcePerStage() {
        ShaderSource source = ShaderSource.ofClasspath(ShaderSourceTest.class);
        String first = source.source(SHADER, ShaderType.VERTEX);
        assertSame(first, source.source(SHADER, ShaderType.VERTEX), "classpath sources are read once and cached");
    }

    @Test
    void missingAssetsReturnNullInsteadOfThrowing() {
        ShaderSource source = ShaderSource.ofClasspath(ShaderSourceTest.class);
        assertNull(source.source(MISSING, ShaderType.VERTEX), "a missing classpath shader must not break the reload");
        assertNull(source.source(MISSING, ShaderType.VERTEX), "the miss is cached too");
    }

    @Test
    void supportsAnExplicitBasePath() {
        ShaderSource source = ShaderSource.ofClasspath(ShaderSourceTest.class, "/assets/cidertest/shaders/core/test_shader");
        assertEquals("// test vertex shader\nvoid main() {}\n", source.source(SHADER, ShaderType.VERTEX));
        assertEquals("// test fragment shader\nvoid main() {}\n", source.source(SHADER, ShaderType.FRAGMENT));
    }

    @Test
    void noneProvidesNothing() {
        assertNull(ShaderSource.NONE.source(SHADER, ShaderType.VERTEX));
        assertNull(ShaderSource.NONE.source(SHADER, ShaderType.FRAGMENT));
    }
}
