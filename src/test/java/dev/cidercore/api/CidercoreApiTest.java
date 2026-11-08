package dev.cidercore.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The versioning contract modules check at runtime: API version and the v2 capability bits. */
class CidercoreApiTest {
    @Test
    void apiVersionIsTwo() {
        assertEquals(2, CidercoreApi.API_VERSION, "API v2 is the render-extension surface");
    }

    @Test
    void advertisesEveryV2Capability() {
        assertTrue(CidercoreApi.supports(CidercoreApi.CAPABILITY_PIPELINE_REGISTRAR));
        assertTrue(CidercoreApi.supports(CidercoreApi.CAPABILITY_RENDER_TYPE_FACTORY));
        assertTrue(CidercoreApi.supports(CidercoreApi.CAPABILITY_FRAME_STAGES));
        assertTrue(
            CidercoreApi.supports(
                CidercoreApi.CAPABILITY_PIPELINE_REGISTRAR | CidercoreApi.CAPABILITY_RENDER_TYPE_FACTORY | CidercoreApi.CAPABILITY_FRAME_STAGES
            ),
            "a combined mask is supported when every bit in it is"
        );
        assertEquals(0, CidercoreApi.CAPABILITIES & ~(CidercoreApi.CAPABILITY_PIPELINE_REGISTRAR
            | CidercoreApi.CAPABILITY_RENDER_TYPE_FACTORY | CidercoreApi.CAPABILITY_FRAME_STAGES), "no unknown capability bits");
    }

    @Test
    void rejectsUnknownCapabilities() {
        assertFalse(CidercoreApi.supports(1 << 30), "a module must not assume a capability this core does not have");
        assertFalse(CidercoreApi.supports(CidercoreApi.CAPABILITY_PIPELINE_REGISTRAR | (1 << 30)), "one missing bit fails the mask");
    }

    @Test
    void v2AccessorsAreStableSingletons() {
        assertNotNull(CidercoreApi.pipelines());
        assertNotNull(CidercoreApi.renderTypes());
        assertNotNull(CidercoreApi.frameStages());
        assertSame(CidercoreApi.pipelines(), CidercoreApi.pipelines());
        assertSame(CidercoreApi.renderTypes(), CidercoreApi.renderTypes());
        assertSame(CidercoreApi.frameStages(), CidercoreApi.frameStages());
    }
}
