package dev.cidercore.backend;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cidercore.api.FrameStage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core side of {@link FrameStage}: an ordered listener registry per hook, driven by the core's
 * {@code LevelRenderer} mixin.
 *
 * <p>Registration is synchronized (client init), dispatch reads volatile immutable arrays (render thread, every
 * frame): no locks or allocation in the frame path when a hook has no listeners, and no allocation of the
 * {@link Context} at all in that case. A listener that throws is logged once and removed from its hook so a broken
 * module cannot spam the log or interrupt the frame; see {@link FrameStage}.</p>
 */
public final class CidercoreFrameStages implements FrameStage {
    private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");
    private static final Listener[] EMPTY = new Listener[0];
    private static final CidercoreFrameStages INSTANCE = new CidercoreFrameStages();

    /** One registered listener; {@code sequence} breaks priority ties in registration order. */
    private record Entry(int priority, long sequence, Listener listener) {
    }

    private final Map<Hook, List<Entry>> entries = new EnumMap<>(Hook.class);
    /** Dispatching snapshots, rewritten under the lock, read without one on the render thread. */
    private volatile Listener[] beforeFrame = EMPTY;
    private volatile Listener[] afterOpaque = EMPTY;
    private long nextSequence;

    private CidercoreFrameStages() {
        for (Hook hook : Hook.values()) {
            this.entries.put(hook, new ArrayList<>());
        }
    }

    public static CidercoreFrameStages instance() {
        return INSTANCE;
    }

    @Override
    public synchronized void register(final Hook hook, final int priority, final Listener listener) {
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(listener, "listener");
        List<Entry> list = this.entries.get(hook);
        for (Entry entry : list) {
            if (entry.listener() == listener) {
                return; // idempotent: same listener registered twice for one hook
            }
        }
        list.add(new Entry(priority, this.nextSequence++, listener));
        list.sort(Comparator.comparingInt(Entry::priority).thenComparingLong(Entry::sequence));
        this.rebuildSnapshot(hook);
    }

    @Override
    public synchronized boolean unregister(final Hook hook, final Listener listener) {
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(listener, "listener");
        boolean removed = this.entries.get(hook).removeIf(entry -> entry.listener() == listener);
        if (removed) {
            this.rebuildSnapshot(hook);
        }
        return removed;
    }

    /** How many listeners a hook currently has (diagnostics/tests). */
    public synchronized int listenerCount(final Hook hook) {
        return this.entries.get(hook).size();
    }

    // ---- called by the LevelRenderer mixin on the render thread ----

    /** {@link Hook#BEFORE_FRAME}: {@code LevelRenderer#submitFeatures} return. */
    public void dispatchBeforeFrame(final LevelRenderState levelRenderState, final SubmitNodeCollector submitNodeCollector) {
        Listener[] listeners = this.beforeFrame;
        if (listeners.length == 0) {
            return;
        }
        this.dispatch(Hook.BEFORE_FRAME, listeners, new Context(Hook.BEFORE_FRAME, levelRenderState, submitNodeCollector, null));
    }

    /** {@link Hook#AFTER_OPAQUE}: {@code LevelRenderer#executeSolid} return, inside the main render pass. */
    public void dispatchAfterOpaque(final LevelRenderState levelRenderState, final RenderPass renderPass) {
        Listener[] listeners = this.afterOpaque;
        if (listeners.length == 0) {
            return;
        }
        this.dispatch(Hook.AFTER_OPAQUE, listeners, new Context(Hook.AFTER_OPAQUE, levelRenderState, null, renderPass));
    }

    private void dispatch(final Hook hook, final Listener[] listeners, final Context context) {
        for (Listener listener : listeners) {
            try {
                listener.onFrame(context);
            } catch (RuntimeException exception) {
                this.disable(hook, listener);
                LOGGER.error("Cidercore: a frame-stage listener for {} failed and was disabled", hook, exception);
            }
        }
    }

    private synchronized void disable(final Hook hook, final Listener listener) {
        if (this.entries.get(hook).removeIf(entry -> entry.listener() == listener)) {
            this.rebuildSnapshot(hook);
        }
    }

    private void rebuildSnapshot(final Hook hook) {
        List<Entry> list = this.entries.get(hook);
        Listener[] listeners = new Listener[list.size()];
        for (int i = 0; i < listeners.length; i++) {
            listeners[i] = list.get(i).listener();
        }
        switch (hook) {
            case BEFORE_FRAME -> this.beforeFrame = listeners;
            case AFTER_OPAQUE -> this.afterOpaque = listeners;
        }
    }
}
