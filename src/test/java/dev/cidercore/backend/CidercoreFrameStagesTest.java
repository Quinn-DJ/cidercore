package dev.cidercore.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cidercore.api.FrameStage;
import dev.cidercore.api.FrameStage.Hook;
import dev.cidercore.api.FrameStage.Listener;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the frame-stage registry: priority/registration ordering, context contents per hook, idempotent
 * registration, and the "a throwing listener is disabled, not fatal" contract.
 */
class CidercoreFrameStagesTest {
    private static final CidercoreFrameStages STAGES = CidercoreFrameStages.instance();
    private static final LevelRenderState STATE = new LevelRenderState();

    private static SubmitNodeCollector collector() {
        return (SubmitNodeCollector)Proxy.newProxyInstance(
            CidercoreFrameStagesTest.class.getClassLoader(), new Class<?>[]{SubmitNodeCollector.class}, (proxy, method, args) -> null
        );
    }

    private static RenderPass renderPass() {
        return (RenderPass)Proxy.newProxyInstance(
            CidercoreFrameStagesTest.class.getClassLoader(), new Class<?>[]{RenderPass.class}, (proxy, method, args) -> null
        );
    }

    @Test
    void runsListenersInPriorityThenRegistrationOrder() {
        List<String> order = new ArrayList<>();
        Listener a = context -> order.add("a");
        Listener b = context -> order.add("b");
        Listener c = context -> order.add("c");
        Listener d = context -> order.add("d");
        try {
            STAGES.register(Hook.BEFORE_FRAME, 10, a);
            STAGES.register(Hook.BEFORE_FRAME, -5, b);
            STAGES.register(Hook.BEFORE_FRAME, 0, c);
            STAGES.register(Hook.BEFORE_FRAME, 0, d);

            STAGES.dispatchBeforeFrame(STATE, collector());

            assertEquals(List.of("b", "c", "d", "a"), order, "lower priority first, registration order breaks ties");
        } finally {
            STAGES.unregister(Hook.BEFORE_FRAME, a);
            STAGES.unregister(Hook.BEFORE_FRAME, b);
            STAGES.unregister(Hook.BEFORE_FRAME, c);
            STAGES.unregister(Hook.BEFORE_FRAME, d);
        }
    }

    @Test
    void beforeFramePassesTheCollectorAndAfterOpaqueTheRenderPass() {
        LevelRenderState beforeState = STATE;
        SubmitNodeCollector collector = collector();
        List<FrameStage.Context> before = new ArrayList<>();
        Listener beforeListener = before::add;
        try {
            STAGES.register(Hook.BEFORE_FRAME, 0, beforeListener);
            STAGES.dispatchBeforeFrame(beforeState, collector);
        } finally {
            STAGES.unregister(Hook.BEFORE_FRAME, beforeListener);
        }
        assertEquals(1, before.size());
        FrameStage.Context beforeContext = before.getFirst();
        assertEquals(Hook.BEFORE_FRAME, beforeContext.hook());
        assertSame(beforeState, beforeContext.levelRenderState());
        assertSame(collector, beforeContext.submitNodeCollector());
        assertNull(beforeContext.renderPass());

        RenderPass pass = renderPass();
        List<FrameStage.Context> after = new ArrayList<>();
        Listener afterListener = after::add;
        try {
            STAGES.register(Hook.AFTER_OPAQUE, 0, afterListener);
            STAGES.dispatchAfterOpaque(STATE, pass);
        } finally {
            STAGES.unregister(Hook.AFTER_OPAQUE, afterListener);
        }
        assertEquals(1, after.size());
        FrameStage.Context afterContext = after.getFirst();
        assertEquals(Hook.AFTER_OPAQUE, afterContext.hook());
        assertSame(STATE, afterContext.levelRenderState());
        assertSame(pass, afterContext.renderPass());
        assertNull(afterContext.submitNodeCollector());
    }

    @Test
    void registeringTheSameListenerTwiceForOneHookRunsItOnce() {
        AtomicInteger runs = new AtomicInteger();
        Listener listener = context -> runs.incrementAndGet();
        try {
            STAGES.register(Hook.BEFORE_FRAME, 0, listener);
            STAGES.register(Hook.BEFORE_FRAME, 100, listener);
            STAGES.dispatchBeforeFrame(STATE, collector());
        } finally {
            STAGES.unregister(Hook.BEFORE_FRAME, listener);
        }
        assertEquals(1, runs.get());
    }

    @Test
    void unregisterReportsWhetherItRemovedSomething() {
        Listener listener = context -> {
        };
        STAGES.register(Hook.AFTER_OPAQUE, 0, listener);
        assertTrue(STAGES.unregister(Hook.AFTER_OPAQUE, listener));
        assertFalse(STAGES.unregister(Hook.AFTER_OPAQUE, listener));
    }

    @Test
    void aThrowingListenerIsDisabledInsteadOfBreakingTheFrame() {
        AtomicInteger brokenRuns = new AtomicInteger();
        AtomicInteger healthyRuns = new AtomicInteger();
        Listener broken = context -> {
            brokenRuns.incrementAndGet();
            throw new IllegalStateException("listener bug");
        };
        Listener healthy = context -> healthyRuns.incrementAndGet();
        try {
            STAGES.register(Hook.AFTER_OPAQUE, 0, broken);
            STAGES.register(Hook.AFTER_OPAQUE, 1, healthy);

            STAGES.dispatchAfterOpaque(STATE, renderPass()); // must not throw
            assertEquals(1, brokenRuns.get());
            assertEquals(1, healthyRuns.get());

            STAGES.dispatchAfterOpaque(STATE, renderPass());
            assertEquals(1, brokenRuns.get(), "the broken listener is logged once and disabled");
            assertEquals(2, healthyRuns.get(), "the other listeners keep running");
        } finally {
            STAGES.unregister(Hook.AFTER_OPAQUE, healthy);
            STAGES.unregister(Hook.AFTER_OPAQUE, broken);
        }
    }

    @Test
    void contextRejectsMissingHookData() {
        assertThrows(NullPointerException.class, () -> new FrameStage.Context(Hook.BEFORE_FRAME, null, collector(), null));
        assertThrows(IllegalArgumentException.class, () -> new FrameStage.Context(Hook.BEFORE_FRAME, STATE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new FrameStage.Context(Hook.AFTER_OPAQUE, STATE, null, null));
    }
}
