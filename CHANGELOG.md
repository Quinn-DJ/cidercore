# Changelog

## 0.3.0 — unreleased

### Breaking

- **`CidercoreApi.API_VERSION` 2 → 3 (breaking, in 0.3.0).** Modules compiled against API v2 will
  be rejected by the existing `context.apiVersion() != CidercoreApi.API_VERSION` guard and must be rebuilt
  against Cidercore 0.3.0. This includes **Ciderlight** and **Cidervista**; mixed 0.2.x / 0.3.0
  installations will not load the child modules.
- **New required surface: `CidercoreApi.coloredLights()`.** The new `ColoredLights` service, `LightColor`,
  `LightSample` and `EntityLightProvider` types are part of API v3. The `CAPABILITY_COLORED_LIGHTS` bit is still
  advertised for optional sub-features and capability probing, but API version 3 guarantees that
  `coloredLights()` exists.
- **Distribution rule:** ship Cidercore 0.3.0 together with rebuilt Ciderlight and Cidervista. The children's
  `fabric.mod.json` / standalone-build dependency must require Cidercore `>=0.3.0`.

### Added

- Colored block-light engine (M1): core-side per-block color registry, a camera-local 3D radiance volume,
  Metal compute injection/propagation, and terrain sampling in Ciderlight. M1 covers static vanilla block
  sources and a warm fallback; filters, dynamic held/entity sources and the quality presets follow.
- `ColoredLights` API v3: `registerBlock`, `registerTag`, `registerFilter`, `addEntityLightProvider`,
  `setEnabled`, `setStrength`, `setIterations`, `textureHandle`, `samplerHandle`, `prepareFrame`, `frameParams`, `sample`.
- M2 increments: held-light sources, material/occupancy volume, stained-glass/water/ice/leaf filters, block-update
  dirty hook, propagation quality (64×32×64 / 96×48×96 / 128×64×128 volumes with 16/32/48 steps), and
  terrain/particle/entity sampling.
- M3 initial dynamic sources: dropped items, burning entities, and glow squid/allay/magma cube/blaze contribute
  chroma to the volume and RGB point-light brightness (`dynamicLights`), sharing the eight burst-light slots with
  explosions.
- `config/ciderlight-lights.json` block/tag/filter overrides (loaded at world join; exact API registrations win;
  no hot reload). Format is documented in the workspace design doc.
- API contract in `docs/colored-lights-api.md`; `-Dciderlight.coloredLightsDebug=true` logs volume/source/steps/
  propagation CPU statistics.
- See the workspace design document `docs/colored-lighting-design.md` and the support matrix
  `docs/colored-lights-sources.md`.
