package dev.cidercore.backend;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.cidercore.api.PipelineRegistrar;
import dev.cidercore.api.ShaderSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core side of {@link PipelineRegistrar}: the registry of module pipelines and their GLSL sources, plus the reload
 * listeners the {@code ShaderManager} mixin notifies.
 *
 * <p>Two mixins read from this class: the existing {@code RenderPipelinesMixin} appends {@link #pipelines()} to
 * {@code RenderPipelines.optionalPipelines()}, and {@code ShaderManagerConfigsMixin} answers
 * {@code ShaderManager.Configs.getShader} from {@link #shaderSource(Identifier, ShaderType)}. Both read immutable
 * snapshots, so reader threads never see a half-registered pipeline.</p>
 *
 * <p>Registration happens during client initialisation, before the first shader reload; {@code getShader} is queried
 * from the shader compiler's executor and {@code optionalPipelines} from the render thread, hence the synchronized
 * writer / volatile snapshot split.</p>
 */
public final class CidercorePipelines implements PipelineRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");
    private static final CidercorePipelines INSTANCE = new CidercorePipelines();

    /** One registered pipeline and where its GLSL comes from. */
    public record Registration(RenderPipeline pipeline, ShaderSource source) {
    }

    private final Map<Identifier, Registration> registrations = new LinkedHashMap<>();
    private final List<Runnable> reloadListeners = new ArrayList<>();
    /** Immutable snapshots for the render/shader-compile threads; rebuilt on registration. */
    private volatile List<Registration> snapshot = List.of();
    private volatile List<RenderPipeline> pipelineSnapshot = List.of();

    private CidercorePipelines() {
    }

    public static CidercorePipelines instance() {
        return INSTANCE;
    }

    @Override
    public synchronized void register(final RenderPipeline pipeline, final ShaderSource source) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(source, "source");
        Identifier location = pipeline.getLocation();
        Registration existing = this.registrations.get(location);
        if (existing != null) {
            // Idempotent: the same pipeline registered twice (e.g. a re-run initialiser) is not an error.
            if (existing.pipeline() != pipeline) {
                LOGGER.warn(
                    "Cidercore: ignoring render pipeline {}: another pipeline instance claimed that location first; "
                        + "render pipeline locations must be unique",
                    location
                );
            }
            return;
        }
        this.registrations.put(location, new Registration(pipeline, source));
        this.snapshot = List.copyOf(this.registrations.values());
        List<RenderPipeline> pipelines = new ArrayList<>(this.registrations.size());
        for (Registration registration : this.snapshot) {
            pipelines.add(registration.pipeline());
        }
        this.pipelineSnapshot = List.copyOf(pipelines);
        LOGGER.info("Cidercore: registered render pipeline {} (optional; GLSL from {})", location, describeSource(source));
    }

    @Override
    public synchronized void addReloadListener(final Runnable listener) {
        this.reloadListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** The registered pipelines, in registration order; what the {@code RenderPipelines} mixin appends. */
    public List<RenderPipeline> pipelines() {
        return this.pipelineSnapshot;
    }

    /** The registered pipelines and their sources, in registration order (diagnostics/tests). */
    public List<Registration> registrations() {
        return this.snapshot;
    }

    /**
     * The classpath/module GLSL for one shader id and stage, or {@code null} when no registered pipeline claims it (the
     * game's resource manager then provides it). Called by {@code ShaderManagerConfigsMixin}.
     */
    public @Nullable String shaderSource(final Identifier shaderId, final ShaderType stage) {
        for (Registration registration : this.snapshot) {
            Identifier pipelineShader = registration.pipeline().getShaders().get(stage);
            if (pipelineShader != null && pipelineShader.equals(shaderId)) {
                String source = registration.source().source(shaderId, stage);
                if (source != null) {
                    return source;
                }
            }
        }
        return null;
    }

    /**
     * Notifies every registered reload listener after a {@code ShaderManager.reload} future completed (successfully or
     * not). A listener that throws is logged once and disabled, so a broken module cannot take the reload or the game
     * down; see {@link PipelineRegistrar#addReloadListener(Runnable)}.
     */
    public void onShaderReload() {
        List<Runnable> listeners;
        synchronized (this) {
            listeners = List.copyOf(this.reloadListeners);
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException exception) {
                synchronized (this) {
                    this.reloadListeners.remove(listener);
                }
                LOGGER.error("Cidercore: a render-extension shader-reload listener failed and was disabled", exception);
            }
        }
    }

    private static String describeSource(final ShaderSource source) {
        return source == ShaderSource.NONE ? "the resource manager" : "the module's shader source";
    }
}
