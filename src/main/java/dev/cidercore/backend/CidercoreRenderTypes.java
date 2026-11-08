package dev.cidercore.backend;

import dev.cidercore.api.RenderTypeFactory;
import dev.cidercore.mixin.RenderTypeInvoker;
import java.util.Objects;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core side of {@link RenderTypeFactory}: calls the game's package-private {@code RenderType.create(String,
 * RenderSetup)} through the {@link RenderTypeInvoker} mixin (no reflection, no access widener).
 *
 * <p>Creation is cheap object assembly — the pipeline lookup and GPU preparation happen later in
 * {@code RenderType.prepare()} on the render thread — which is why the API restricts calls to the render thread or
 * client initialisation rather than allowing arbitrary background creation.</p>
 */
public final class CidercoreRenderTypes implements RenderTypeFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");
    private static final CidercoreRenderTypes INSTANCE = new CidercoreRenderTypes();

    private CidercoreRenderTypes() {
    }

    public static CidercoreRenderTypes instance() {
        return INSTANCE;
    }

    @Override
    public RenderType create(final String name, final RenderSetup setup) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(setup, "setup");
        try {
            return RenderTypeInvoker.cidercore$createRenderType(name, setup);
        } catch (RuntimeException exception) {
            LOGGER.error("Cidercore: the game rejected the custom RenderType '{}' ({}); the caller must fall back", name, setup, exception);
            throw new IllegalStateException("Could not create RenderType '" + name + "'", exception);
        }
    }

    @Override
    public @Nullable RenderType tryCreate(final String name, final RenderSetup setup) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(setup, "setup");
        try {
            return RenderTypeInvoker.cidercore$createRenderType(name, setup);
        } catch (RuntimeException exception) {
            LOGGER.error("Cidercore: the game rejected the custom RenderType '{}' ({}); the caller must fall back", name, setup, exception);
            return null;
        }
    }
}
