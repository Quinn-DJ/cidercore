package dev.cidercore.api;

import com.mojang.renderpearl.api.commands.RenderPass;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.jspecify.annotations.Nullable;

/**
 * Frame-level render stages a module can hook into, for work that must happen outside vanilla's own draw calls.
 *
 * <p>The core's {@code LevelRenderer} mixin runs the registered listeners at two points of one level frame, on the
 * render thread:</p>
 *
 * <ul>
 *   <li>{@link Hook#BEFORE_FRAME} — at the return of {@code LevelRenderer#submitFeatures(LevelRenderState,
 *       SubmitNodeCollector, boolean)}, i.e. after vanilla has submitted this frame's render states but <em>before</em>
 *       the frame graph executes. Use it to prepare/upload GPU resources (buffer creation and uploads are not allowed
 *       inside a {@link RenderPass}) and to submit custom geometry through the {@link SubmitNodeCollector}, so it is
 *       drawn by the normal feature/voxel passes with the frame's depth buffer.</li>
 *   <li>{@link Hook#AFTER_OPAQUE} — at the return of {@code LevelRenderer#executeSolid(ChunkSectionsToRender,
 *       PreparedFrame, RenderPass)}, inside the frame graph's main render pass, after opaque terrain and opaque
 *       features have been drawn and before translucents. Use it to record draw calls with the provided
 *       {@link RenderPass} (its depth buffer is the main world depth).</li>
 * </ul>
 *
 * <p><b>Ordering and failure.</b> Listeners run in ascending {@link #register(Hook, int, Listener) priority} order;
 * equal priorities keep registration order. A listener that throws is logged once and disabled — it never breaks the
 * frame or the other listeners.</p>
 *
 * <p><b>Threads.</b> Both hooks run on the render thread only. Registration may happen on the client-init thread
 * before the first frame; the registry is thread-safe. Listeners are cleared when the game shuts down only in the
 * sense that they live as long as the process — unregister ones that are no longer valid (e.g. a world change) if
 * needed.</p>
 */
public interface FrameStage {
    /** The two frame points the core injects; see the class documentation for when each runs. */
    enum Hook {
        /** Before the frame graph executes: prepare GPU resources and/or submit geometry nodes. */
        BEFORE_FRAME,
        /** Inside the main render pass after opaque geometry, before translucents: record draw calls. */
        AFTER_OPAQUE
    }

    /**
     * What a listener gets. Which fields are set depends on {@link #hook()}:
     * {@code BEFORE_FRAME} has a {@link #submitNodeCollector()} (and no render pass), {@code AFTER_OPAQUE} has a
     * {@link #renderPass()} (and no collector). {@link #levelRenderState()} is always the current frame's state.
     */
    record Context(
        Hook hook,
        LevelRenderState levelRenderState,
        @Nullable SubmitNodeCollector submitNodeCollector,
        @Nullable RenderPass renderPass
    ) {
        public Context {
            Objects.requireNonNull(hook, "hook");
            Objects.requireNonNull(levelRenderState, "levelRenderState");
            if (hook == Hook.BEFORE_FRAME && submitNodeCollector == null) {
                throw new IllegalArgumentException("BEFORE_FRAME needs the frame's SubmitNodeCollector");
            }
            if (hook == Hook.AFTER_OPAQUE && renderPass == null) {
                throw new IllegalArgumentException("AFTER_OPAQUE needs the frame's RenderPass");
            }
        }
    }

    /** One frame-stage callback. Runs on the render thread; keep it short and do not throw. */
    @FunctionalInterface
    interface Listener {
        void onFrame(Context context);
    }

    /**
     * Registers {@code listener} for {@code hook}. Lower {@code priority} values run first; equal priorities run in
     * registration order. Registering the same listener object again for the same hook is a no-op.
     */
    void register(Hook hook, int priority, Listener listener);

    /** Registers at the default priority ({@code 0}); see {@link #register(Hook, int, Listener)}. */
    default void register(final Hook hook, final Listener listener) {
        register(hook, 0, listener);
    }

    /** Removes a listener from one hook; {@code false} when it was not registered. */
    boolean unregister(Hook hook, Listener listener);
}
