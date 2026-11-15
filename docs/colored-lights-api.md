# Cidercore colored-light API (v3)

API v3 adds the colored-light service. `CidercoreApi.API_VERSION` is **3**; a child compiled against v2 is rejected
by the existing version guard and must be rebuilt against Cidercore 0.3.0. See `CHANGELOG.md`.

## Capability

`CidercoreApi.CAPABILITY_COLORED_LIGHTS` (`1 << 4`) is advertised for optional sub-features. API v3 guarantees the
method and service exist; the bit is useful when a future core advertises the service but not every feature.

## Surface

```java
CidercoreApi.coloredLights() -> ColoredLights
```

| Method | Contract |
| --- | --- |
| `registerBlock(Block, LightColor, float strength)` | Exact block source. Later exact registrations win; JSON/defaults below. |
| `registerTag(TagKey<Block>, LightColor, float strength)` | Tag source; exact block registrations win. |
| `registerFilter(Block, LightColor tint, float transmission)` | Colored-light filter (stained glass, water, ice, leaves). API beats JSON/default. |
| `addEntityLightProvider(EntityLightProvider)` | Reserved for custom dynamic providers; built-in held/dropped/burning/glowing sources already run in the core. |
| `setEnabled(boolean)` | Feature toggle; disabled frames sample the warm fallback, volume resources stay. |
| `setStrength(float)` | Chroma blend 0..2; 1 is tuned. |
| `setIterations(int)` | Propagation steps, clamped 4..64. |
| `setVolumeSize(int x, int y, int z)` | Volume dimensions; rounded to multiples of 8 and clamped. Resizes live. |
| `available()` | Feature enabled and GPU volume initialized. |
| `textureHandle()` / `samplerHandle()` | 3D RGBA16F volume and clamp/linear sampler for the renderer. Do not release. |
| `prepareFrame(Vec3 cameraPos, float partialTick)` | Called once per frame by Ciderlight before binding; updates sources/propagation. |
| `frameParams()` | 12 floats: origin-relative xyz, enabled, inverse size xyz, strength, transmission, fade, reserved. |
| `dynamicLights(Vec3 cameraPos, float[] out)` | Up to 8 dynamic point lights; 7 floats each (world xyz, rgb, intensity). |
| `sample(Vec3)` | Reserved debug/query hook; currently returns `null` (GPU readback not implemented). |

## Threads and lifecycle

- Register from client init/resource reload; the registry is thread-safe.
- `prepareFrame`, `textureHandle`, `samplerHandle` and `frameParams` are called on the render thread.
- `setVolumeSize` may release and reallocate GPU resources; old handles are released after the next submit. Call it
  before `prepareFrame` in the frame that changes quality.
- `Ciderlight` calls `setEnabled`/`setStrength`/`setIterations`/`setVolumeSize` each frame from its settings; the core
  only acts when a value changes.

## Precedence

1. Exact `registerBlock` (and `registerFilter`) from a mod.
2. `config/ciderlight-lights.json`.
3. Built-in defaults.

JSON is loaded once on first world join; no hot reload yet. Shapes, colors and the `filters` entry are documented in
the workspace `docs/colored-lighting-design.md` §8.2.

## Debug

`-Dciderlight.coloredLightsDebug=true` logs volume size, source count, step count and propagation CPU time every 5 s.

## Ciderlight contract

Ciderlight binds the volume at texture/sampler slot 1 in terrain and particle shaders and slot 9 in the entity
composite. Its `colored_block_light()` helper normalizes RGB radiance to chroma and multiplies the vanilla block-light
brightness; disabled/outside-volume samples fall back to `(1.0, 0.66, 0.30)`.
