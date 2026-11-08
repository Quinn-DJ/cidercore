package dev.cidercore.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.cidercore.api.ShaderSource;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the module pipeline registry: registration/idempotence by location, per-stage GLSL dispatch and
 * the reload-listener contract (run after a reload, a broken listener disabled instead of throwing).
 */
class CidercorePipelinesTest {
    private static final CidercorePipelines REGISTRY = CidercorePipelines.instance();

    private static RenderPipeline pipeline(final String path) {
        Identifier shader = Identifier.fromNamespaceAndPath("cidertest", "shaders/" + path);
        return pipeline(path, shader, shader);
    }

    private static RenderPipeline pipeline(final String path, final Identifier vertexShader, final Identifier fragmentShader) {
        return RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("cidertest", path))
            .withVertexShader(vertexShader)
            .withFragmentShader(fragmentShader)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    }

    @Test
    void registersPipelineAndExposesItForTheReload() {
        RenderPipeline pipeline = pipeline("pipeline_register");
        int before = REGISTRY.registrations().size();

        REGISTRY.register(pipeline, ShaderSource.NONE);

        assertEquals(before + 1, REGISTRY.registrations().size());
        assertTrue(REGISTRY.pipelines().contains(pipeline), "the pipeline is what the RenderPipelines mixin appends");
    }

    @Test
    void registeringTheSamePipelineTwiceIsIdempotent() {
        RenderPipeline pipeline = pipeline("pipeline_idempotent");
        REGISTRY.register(pipeline, ShaderSource.NONE);
        int afterFirst = REGISTRY.registrations().size();

        REGISTRY.register(pipeline, ShaderSource.NONE);

        assertEquals(afterFirst, REGISTRY.registrations().size(), "re-registering the same pipeline must not duplicate it");
    }

    @Test
    void secondInstanceWithTheSameLocationIsRejected() {
        RenderPipeline first = pipeline("pipeline_location");
        RenderPipeline second = pipeline("pipeline_location");
        REGISTRY.register(first, ShaderSource.NONE);
        int afterFirst = REGISTRY.registrations().size();

        REGISTRY.register(second, ShaderSource.NONE);

        assertEquals(afterFirst, REGISTRY.registrations().size(), "pipeline locations are unique");
    }

    @Test
    void dispatchesGlslPerShaderIdAndStage() {
        Identifier vertexShader = Identifier.fromNamespaceAndPath("cidertest", "shaders/dispatch_vertex");
        Identifier fragmentShader = Identifier.fromNamespaceAndPath("cidertest", "shaders/dispatch_fragment");
        RenderPipeline pipeline = pipeline("pipeline_dispatch", vertexShader, fragmentShader);
        REGISTRY.register(pipeline, (shaderId, stage) -> {
            if (stage == ShaderType.VERTEX && shaderId.equals(vertexShader)) {
                return "the-vertex-source";
            }
            if (stage == ShaderType.FRAGMENT && shaderId.equals(fragmentShader)) {
                return "the-fragment-source";
            }
            return null;
        });

        assertEquals("the-vertex-source", REGISTRY.shaderSource(vertexShader, ShaderType.VERTEX));
        assertEquals("the-fragment-source", REGISTRY.shaderSource(fragmentShader, ShaderType.FRAGMENT));
        assertNull(REGISTRY.shaderSource(vertexShader, ShaderType.FRAGMENT), "vertex id is not this pipeline's fragment shader");
        assertNull(REGISTRY.shaderSource(Identifier.fromNamespaceAndPath("cidertest", "shaders/unregistered"), ShaderType.VERTEX));
    }

    @Test
    void reloadListenersRunAndABrokenOneIsDisabled() {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger brokenRuns = new AtomicInteger();
        REGISTRY.addReloadListener(runs::incrementAndGet);
        REGISTRY.addReloadListener(() -> {
            brokenRuns.incrementAndGet();
            throw new IllegalStateException("listener bug");
        });

        int before = runs.get();
        REGISTRY.onShaderReload(); // must not throw
        assertEquals(before + 1, runs.get());
        assertEquals(1, brokenRuns.get());

        REGISTRY.onShaderReload();
        assertEquals(before + 2, runs.get(), "the healthy listener keeps running");
        assertEquals(1, brokenRuns.get(), "the broken listener is logged once and disabled");
    }
}
