# Cidercore render-extension API (v2)

Status: implemented in Phase S / B6 (cidercore 0.2.0 line). Consumers: **cidervista** (S2 migration); future
modules. The API is additive to v1: `CidercoreApi.pipelineReload()`, `settings()`, `config(...)` are unchanged.

This document is the contract for `dev.cidercore.api` v2. It covers registration order, threads, failure/fallback
semantics and the pre-framework (temporary mixin/reflection) behaviour each part replaces.

---

## 1. Version and capabilities

```java
if (CidercoreApi.API_VERSION < 2) {
    Cidervista.LOGGER.warn("Cidervista needs cidercore API v2 for custom pipelines; disabled");
    // keep the mod's own primitive fallback; do not use pipelines()/renderTypes()/frameStages()
}
```

| Constant | Meaning |
| --- | --- |
| `CidercoreApi.API_VERSION` | Surface version. **2** is the first render-extension version. |
| `CAPABILITY_PIPELINE_REGISTRAR` | `CidercoreApi.pipelines()` behaves as documented here. |
| `CAPABILITY_RENDER_TYPE_FACTORY` | `CidercoreApi.renderTypes()` behaves as documented here. |
| `CAPABILITY_FRAME_STAGES` | `CidercoreApi.frameStages()` behaves as documented here. |
| `CAPABILITIES` / `supports(int mask)` | Combined mask and the check for it (`supports(CAP_X \| CAP_Y)`). |

Prefer checking the capability bit of the feature actually used, so a future core can drop one feature without
disabling a module entirely. `CidercoreContext` mirrors the three accessors as default methods.

---

## 2. `PipelineRegistrar` — custom render pipelines + GLSL

### 2.1 What it replaces

Cidervista's Phase-L temporary solution (`RenderPipelinesMixin`, `ShaderManagerConfigsMixin`,
`ShaderManagerReloadMixin`) hooked `RenderPipelines#optionalPipelines`, `ShaderManager.Configs#getShader` and
`ShaderManager#reload` to inject its pipelines, classpath GLSL and retry hook. The registrar is the supported
version of exactly that: the modules register, the core owns the mixins.

### 2.2 Registering

```java
private static final RenderPipeline VISTA_TERRAIN = RenderPipeline.builder()
    .withLocation(Identifier.fromNamespaceAndPath(Cidervista.MOD_ID, "vista_terrain"))
    .withVertexShader(Identifier.fromNamespaceAndPath(Cidervista.MOD_ID, "core/vista_terrain"))
    .withFragmentShader(Identifier.fromNamespaceAndPath(Cidervista.MOD_ID, "core/vista_terrain"))
    // bind groups, vertex format, color/depth state ... as before
    .build();

@Override
public void onInitializeClient() {
    CidercoreApi.pipelines().register(VISTA_TERRAIN, CidervistaClient.class);
}
```

* **When:** during client initialisation (a `ClientModInitializer`, or a `CidercoreModule#initialise`), i.e. before the
  game's first shader reload. Pipelines are picked up by a reload; a registration that happens after one waits for the
  next reload (resource reload, or the core's hot apply).
* **Idempotent by location:** registering the same pipeline twice is a no-op; a second, different instance claiming an
  already registered `Identifier` is rejected and logged (locations must be unique).
* Registered pipelines are appended to `RenderPipelines.optionalPipelines()` after vanilla's entries and the
  "shadowed-name" re-additions, deduplicated by identity. So:
  * the game's normal shader reload compiles them (no manual `GpuDevice.compilePipeline` call);
  * **a compile failure is a warning, not a fatal reload error** — the module falls back, the game keeps running.
* The API only registers pipelines; how they are drawn (a `RenderType`, a `RenderPass`) is the module's job, see
  §3 and §4.

### 2.3 `ShaderSource` — GLSL delivery without Fabric API

`register(pipeline, source)` takes a `dev.cidercore.api.ShaderSource` (not renderpearl's interface of the same name):
`source(shaderId, stage)` returns the GLSL for exactly that pipeline shader id and stage, or `null` to let the game's
resource manager (resource packs) provide it. The per-id/per-stage dispatch is what lets a pipeline have different
vertex and fragment shader files and lets the game's `ShaderManager` consult it exactly like its own source.

Classpath helpers (the recommended form for this mod family, which has no Fabric API resource-loader):

```java
// Conventional location derived from the pipeline's shader ids:
//   cidervista:core/vista_terrain -> /assets/cidervista/shaders/core/vista_terrain.vsh|.fsh
CidercoreApi.pipelines().register(VISTA_TERRAIN, CidervistaClient.class);

// Explicit base path (no extension), when assets do not follow that convention:
CidercoreApi.pipelines().register(VISTA_TERRAIN, CidervistaClient.class,
    "/assets/cidervista/shaders/core/vista_terrain");
```

* `anchor` is a class from the module's jar/classpath group (its classloader is used to read the resources); it does
  not have to be the pipeline class.
* Sources are read once per id/stage and cached. A missing classpath file is **logged (warn) and returns `null`**: the
  lookup falls through to the resource manager and the shader compiler reports its normal "couldn't find source" for
  the optional pipeline. This never throws into the shader reload.
* Precedence: a registered source answers first; resource-pack overrides of the same shader id are not seen while the
  registration provides it (same as Cidervista's temporary mixin did). Use `ShaderSource.NONE` for
  `(id, stage) -> null` if a module provides its GLSL through real resources instead.
* Threads: `ShaderSource#source` is called from the shader compiler's executor during a reload; helper
  implementations are synchronized. Custom implementations must be thread-safe.
* **Several pipelines may share one shader id** (Cidervista's four pipelines all use
  `cidervista:core/vista_terrain`): each registration is looked up per id/stage and the first registered source that
  returns non-null wins. Registering the same `ShaderSource` convention for all four yields the same text, so this is
  harmless; if sources differ for one id, make them agree (the game compiles that id once per stage).
* **Includes are not part of v2.** `#include` files are still resolved by the game from resource packs only; modules
  should inline what they need or ship a resource pack. (A later API version can extend the source to includes.)

### 2.4 `addReloadListener(Runnable)`

```java
CidercoreApi.pipelines().addReloadListener(vistaRenderer::onShaderReload);
```

* Run after **the `ShaderManager.reload` future completes**, whether the reload succeeded or failed. This is the retry
  hook: a module whose custom pipeline was missing/failed keeps its fallback active, and the next reload (resource
  reload or hot apply) gives the pipeline another chance — the listener clears the module's "unavailable" flag.
* The callback runs on the thread that completes the reload future (usually the render thread, treat it as
  unspecified): set flags / post to the client thread, do not run heavy work.
* A listener that throws is logged once and disabled; it never breaks the reload, other listeners, or the frame.
  Listeners live for the process lifetime; modules cannot remove them in v2 (register once at init).

---

## 3. `RenderTypeFactory` — controlled `RenderType.create`

`RenderType.create(String, RenderSetup)` is package-private in 26.3 and the public `RenderTypes` class only exposes
vanilla render types. The core calls it through the `RenderTypeInvoker` mixin (no reflection, no access widener).

```java
RenderSetup setup = RenderSetup.builder(VistaPipelines.VISTA_TERRAIN)
    .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS)
    .createRenderSetup();

RenderType type = CidercoreApi.renderTypes().tryCreate("cidervista_vista_terrain", setup);
if (type == null) {
    // keep the module's fallback path; the failure is already logged
}
```

| Method | On failure |
| --- | --- |
| `create(name, setup)` | logs the cause and throws `IllegalStateException` |
| `tryCreate(name, setup)` | logs the cause and returns `null` (fallback-friendly) |

* **Threads/timing:** render thread, or client initialisation before the first frame; lazy creation during the first
  frame that needs the type is also fine (the Cidervista L0 spike does that). Not from background workers.
* Creation is cheap object assembly; GPU work happens later in vanilla's `RenderType.prepare()`. The type holds the
  pipeline + texture locations, not textures, so it stays valid across resource reloads.
* `setup` carries the pipeline and texture bindings; the pipeline must be registered (§2) and compiled by the current
  reload, otherwise the first `prepare()` will fail to find it — keep the module's "pipeline compiled?" fallback.

---

## 4. `FrameStage` — level-frame hooks

Two hooks, injected as returns of vanilla methods in `LevelRenderer` (returns are of the form
`@Inject(method = "...", at = @At("RETURN"))`, so they compose with other mods' injections into the same class):

| Hook | Vanilla point | Context fields | Use for |
| --- | --- | --- | --- |
| `BEFORE_FRAME` | `LevelRenderer#submitFeatures(LevelRenderState, SubmitNodeCollector, boolean)` return — after all of vanilla's render states were turned into submitted draw nodes, **before** the frame graph executes | `levelRenderState`, `submitNodeCollector` | Create/upload GPU buffers (not allowed inside a render pass), submit custom geometry nodes so the normal passes draw them with the frame's depth buffer |
| `AFTER_OPAQUE` | `LevelRenderer#executeSolid(ChunkSectionsToRender, PreparedFrame, RenderPass)` return — inside the frame graph's main render pass, after opaque terrain/features, before translucents | `levelRenderState`, `renderPass` | Record own draws with the main `RenderPass` (its depth attachment is the world depth buffer) |

```java
CidercoreApi.frameStages().register(FrameStage.Hook.BEFORE_FRAME, 0, context -> {
    vistaVoxelRenderer.prepare(context.levelRenderState());
    vistaRenderer.submit(context.levelRenderState(), context.submitNodeCollector());
});

CidercoreApi.frameStages().register(FrameStage.Hook.AFTER_OPAQUE, 0,
    context -> vistaVoxelRenderer.draw(context.renderPass()));
```

* **Ordering:** ascending `priority`; equal priorities run in registration order (use it to order modules/fog vs
  geometry). Registering the same listener twice for a hook is a no-op. `unregister` is available for listeners whose
  validity ends (e.g. world change) — not needed for process-lifetime listeners.
* **Context:** `BEFORE_FRAME` always has a non-null `submitNodeCollector` and a null `renderPass`; `AFTER_OPAQUE` the
  other way around. `levelRenderState` is always the current frame's state — for `AFTER_OPAQUE` the core reads
  `LevelRenderer`'s own per-frame field (the same `LevelRenderState` object `submitFeatures` was given), not a static
  capture, so the two hooks of one frame always see the same state. `executeSolid` runs on the render thread within
  the same `LevelRenderer#render` call as `submitFeatures`.
* **Threads:** render thread only. Registration may happen on the client-init thread before the first frame.
* **Timing is part of the contract (S0 C5):** keep `BEFORE_FRAME` work at `submitFeatures` RETURN. Cidervista's V3
  LOD selection reads `GameRenderer.mainRenderTarget()` for its viewport, and that target is only the smaller
  render-scale target inside the world-draw window that starts after `submitFeatures` (the core's `GameRendererMixin`
  swaps it per frame). Moving `prepare` to a client tick or a pre-frame point silently reverts LOD selection to window
  resolution. `AFTER_OPAQUE` (`executeSolid` RETURN) works in both transparency modes (classic and improved OIT) —
  verified against the vanilla frame graph in the S0 baseline.
* **Failure:** a throwing listener is logged once and disabled; it cannot break the frame or the remaining listeners.
* **State discipline:** a hook runs inside vanilla's render flow — leave the `PoseStack`/scissor/viewport/RenderPass
  state as you found it (use `pushDebugGroup`/`popDebugGroup`, restore your own state). The `RenderPass` in
  `AFTER_OPAQUE` is open: do not end it, do not start another pass on the same encoder.
* Not covered yet (v3 candidates): main-pass hooks, fog/depth uniform slices, camera-relative matrices as first-class
  context, `RenderPass` descriptor inspection, timestamp queries.

---

## 5. Core implementation (mixins added by v2)

All in `dev.cidercore.mixin`, registered in `cidercore.mixins.json` (`client`):

| Mixin | Target | Purpose |
| --- | --- | --- |
| `RenderPipelinesMixin` (extended) | `RenderPipelines#register` HEAD, `#optionalPipelines` RETURN | Remembers vanilla pipelines (old behaviour: shadowed names compiled up front) **and** appends `CidercorePipelines` without duplicates |
| `ShaderManagerConfigsMixin` | `ShaderManager.Configs#getShader` HEAD | Answers registered shader ids/stages from module sources; other lookups untouched |
| `ShaderManagerReloadMixin` | `ShaderManager#reload` RETURN | Notifies reload listeners when the returned future completes (success or failure) |
| `RenderTypeInvoker` | `RenderType` (accessor mixin) | `@Invoker` for package-private `RenderType.create` |
| `LevelRendererFrameStageMixin` | `LevelRenderer#submitFeatures` RETURN, `#executeSolid` RETURN | Dispatches `BEFORE_FRAME` / `AFTER_OPAQUE` |

Coexistence:

* **Ciderlight's `LevelRendererMixin`** injects `@Redirect` into `LevelRenderer#submitEntities` (first-person own
  body). `submitEntities` is called from within `submitFeatures`, but it is a different target method than the two
  return hooks, so both mixins apply and neither can cancel the other.
* **Ciderlight's shader/pipeline path** (`FrameExtension`, `PipelineTransformer`, hot apply) is untouched: the shader
  module patches vanilla pipelines and never registers pipelines through the registrar. The registrar only adds
  *optional* pipelines, so its presence does not change which vanilla pipelines compile.
* **During S2 migration**, cidervista's temporary `RenderPipelinesMixin`/`ShaderManagerConfigsMixin`/
  `ShaderManagerReloadMixin`/`LevelRendererMixin` are deleted; until then they coexist with the core mixins (different
  mixin classes, same targets, return/cancellable injections chain safely). Do not register the same pipeline both
  ways at the same time.

---

## 6. S2 migration recipe (Cidervista)

### 6.0 Hard rules from the S0 baseline (must hold, in this order)

* **C4 — one commit:** register the `FrameStage` listeners **and** delete the old
  `dev.cidervista.mixin.LevelRendererMixin` (plus the other three temporary mixins and their `cidervista.mixins.json`
  entries) in the *same* change. If both exist, `prepare`/`submit`/`draw` run twice per frame (no crash, but double
  uploads and a double draw); the core FrameStage mixin and the temporary one are independent injections, so neither
  cancels the other.
* **C5 — keep `BEFORE_FRAME` at `submitFeatures` RETURN** (see §4): the 2B/V3 provider reads the render-scale
  `mainRenderTarget` inside that window. Do not move the prepare path to client ticks.
* **C1 — leave the core mixin as the only `optionalPipelines` provider.** The core mixin keeps the copy-then-append,
  cancellable-RETURN shape; both providers (core + temporary Cidervista one) are append-only and order-independent,
  which is what made the pre-migration baseline work. After S2 only the core one remains.
* **C12 — gate the module:** bump `cidervista/fabric.mod.json`'s `cidercore` dependency to `>=0.2.0` **and** check at
  init, keeping the data/capture layers alive when the render extension is unavailable:

  ```java
  boolean renderExtensions = CidercoreApi.API_VERSION >= 2
      && CidercoreApi.supports(CidercoreApi.CAPABILITY_PIPELINE_REGISTRAR
          | CidercoreApi.CAPABILITY_RENDER_TYPE_FACTORY | CidercoreApi.CAPABILITY_FRAME_STAGES);
  if (!renderExtensions) {
      Cidervista.LOGGER.warn("Cidervista: cidercore render-extension API v2 unavailable; keeping the fallback render path "
          + "(data/capture still active)");
  }
  ```

  A core that predates v2 then degrades cleanly instead of failing at a mixin/class boundary.
* **C2/C3 — transition period is safe:** the core shader-source and reload mixins and Cidervista's temporary ones
  answer the same ids / attach to the same reload future; they are idempotent until S2 removes the temporary ones.
  The core reload listener also fires on the core's hot apply (`CidercorePipelineReload` calls `ShaderManager.reload`
  directly), so the retry semantics survive migration.

### 6.1 Steps

1. **Delete** `dev.cidervista.mixin.RenderPipelinesMixin`, `ShaderManagerConfigsMixin`, `ShaderManagerReloadMixin`,
   `LevelRendererMixin`; remove them from `cidervista.mixins.json`. Keep the mod's data/capture/model mixins
   (`ClientLevelMixin`, `ClientPacketListenerMixin`, `MinecraftMixin`, `ModelManagerMixin`).
2. **Pipelines + GLSL** — in `CidervistaClient#onInitializeClient()` (or a `CidercoreModule`), after the version check:
   `CidercoreApi.pipelines().register(pipeline, CidervistaClient.class)` for
   `VISTA_TERRAIN`, `VOXEL_OPAQUE`, `VOXEL_CUTOUT`, `VOXEL_TRANSLUCENT`. This replaces the classpath GLSL handling of
   `VistaShaderSource` (delete its mixin dependency; the class can be removed).
3. **RenderType** — replace the reflection in `VistaRenderTypes`:
   `CidercoreApi.renderTypes().tryCreate("cidervista_vista_terrain", setup)`; keep the existing null → fallback
   handling (`TryCreate` returns null, no exception to catch).
4. **Frame hooks** — replace `LevelRendererMixin`:
   * `LevelRendererFrameStageMixin`'s `BEFORE_FRAME` == old `submitFeatures` RETURN:
     `VistaVoxelRenderer.prepare(state)` + `VistaRenderer.submit(state, collector)`.
   * `AFTER_OPAQUE` == old `executeSolid` RETURN: `VistaVoxelRenderer.draw(renderPass)`.
   * registration order/priority 0 preserves the old relative order (prepare before submit; draw after opaque).
5. **Reload hook** — replace `ShaderManagerReloadMixin` with
   `CidercoreApi.pipelines().addReloadListener(VistaRenderer::onShaderReload)` (+ the voxel renderer's reset).
6. **Version guard** — check `CidercoreApi.API_VERSION >= 2` and/or the capability bits; if absent, log once and keep
   the Phase-1 `debug_quads`/flat-colour path; never throw at init.
7. **Verify** — `:cidervista:test` unchanged; `:dev:runClient -PnoCiderlight -Pworld=vista_test -Pautoshot=...`
   pictures identical to before migration; no `Couldn't find source`, no new mixin warnings; the four custom pipelines
   appear in shader-reload logs as optional pipelines.

---

## 7. Failure and fallback summary

| Situation | Core behaviour | Module responsibility |
| --- | --- | --- |
| GLSL asset missing from classpath | `ShaderSource` warns, returns null; the game's compiler reports the optional pipeline as failed (warning) | keep the fallback path; retry via `addReloadListener` |
| Custom pipeline fails to compile | optional => warning, reload continues, other pipelines fine | detect via render-type/pipeline availability and fall back |
| `RenderType.create` rejects name/setup | `tryCreate` logs and returns null / `create` logs and throws | treat null as "use fallback" |
| A frame-stage listener throws | logged once, listener disabled; frame continues | fix the listener; never rely on exceptions for control flow |
| A reload listener throws | logged once, listener disabled; reload continues | fix the listener |
| API version too old (module newer than core) | core keeps working | check `API_VERSION`/capabilities and disable the feature cleanly |
| Resource-pack reload | registered pipelines recompiled; reload listeners run (success or failure) | no action needed; re-resolve sprite/material caches if the module caches resources |

## 8. Tests

Headless unit tests live in `cidercore/src/test/java` (`:cidercore:test`, JUnit 5):

* `dev.cidercore.api.CidercoreApiTest` — `API_VERSION == 2`, capability bits, stable accessors;
* `dev.cidercore.api.ShaderSourceTest` — conventional/explicit classpath paths, per-stage dispatch, caching, misses
  return null, `ShaderSource.NONE`;
* `dev.cidercore.backend.CidercorePipelinesTest` — registration, idempotence by pipeline/location, per-stage source
  dispatch, reload listeners + broken-listener disable;
* `dev.cidercore.backend.CidercoreFrameStagesTest` — priority then registration order, context contents per hook,
  idempotent registration, unregister, broken-listener disable, context validation.

These do not load a game or a GPU; they use the Minecraft classes from the compile classpath only.

## 9. S0 baseline cross-check (minimum API surface A–G)

The S0 conflict/baseline report (`/tmp/s0-baseline/S0-report.md` §4) derived the minimum S2 surface from Cidervista's
temporary implementations. This implementation covers each item; the S3 matrix checks the runtime side.

| # | S0 requirement | v2 coverage |
| --- | --- | --- |
| A | 4 pipelines in the optional compile set, idempotent, optional, registered before first reload | `PipelineRegistrar#register` + `CidercorePipelines` + `RenderPipelinesMixin` append (§2) |
| B | GLSL from module classpath by shader id+stage, safe per-stage dispatch, thread-safe | `ShaderSource` + `ShaderManagerConfigsMixin` (§2.3); shared-id case documented there |
| C | custom `RenderType` without reflection, throw/null variants | `RenderTypeFactory#create/tryCreate` + `RenderTypeInvoker` (§3) |
| D | two frame hooks (`LevelRenderState`+`SubmitNodeCollector` / `LevelRenderState`+`RenderPass`), priority, exception isolation | `FrameStage` + `LevelRendererFrameStageMixin` (§4) |
| E | retry after a shader reload (success **and** failure, incl. hot apply) | `addReloadListener` + `ShaderManagerReloadMixin` (§2.4) |
| F | `API_VERSION=2` + capability bits + clean degradation | §1, gating snippet in §6.0 (C12) |
| G | thread/timing contract: register before first reload; render-thread callbacks; thread-safe registries | documented per API above; registration is before the first `ShaderManager.reload` in client init |

Items the core API deliberately does **not** cover (stay out of scope for S2; S3/S4 decisions): the fog policy
(C7 — `disableVanillaFog` zeroes the vanilla FOG UBO; the API does not touch fog), render-scale 67% sharpness
(C8 — Ciderlight `-Dciderlight.quality` knob), Ciderlight shadow/reflection capture of custom pipelines (C9 — needs
the future host API), translucent voxel ordering relative to the Ciderlight opaque snapshot (C10 — S3 look test),
and the `shaders on ⇒ classic transparency` constraint (C6 — Ciderlight/`GameRendererMixin` side, unchanged by v2).

S3 log anchors for this API: `Cidercore: registered render pipeline <id> (optional; ...)` for each of the four
pipelines, `Cidercore: serving classpath GLSL ...` for their shader, no `Mixin apply`/`InvalidInjectionException`, and
the core reload listener firing after both a resource reload and a Ciderlight hot apply.
