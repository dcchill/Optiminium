# Deep Research Progress

## Current benchmark read

- Last benchmark-bearing scene-v2 run: OFF 64.1 avg FPS, 30.4 1% low, 28.1 worst-frame FPS, 8.05 avg GPU ms, 15.29 worst GPU ms.
- ON: 64.9 avg FPS, 31.1 1% low, 26.7 worst-frame FPS, 8.12 avg GPU ms, 9.57 worst GPU ms.
- Net: +0.8 avg FPS, +0.7 1% low FPS, -1.4 worst-frame FPS, +0.07 avg GPU ms, -5.72 ms worst GPU time.
- Active gains came from culling: 4,525 hidden particles, 191,634 culled block entities, 3,116 culled entities.
- Raw scene metrics now look trustworthy: OFF/ON both reported `rawVisibleBlockEntities=266`, `maxRawVisibleBlockEntities=266`, and `denseSceneFrames=0` with the 512 dense threshold.
- Temporal significance counters are now populated in the benchmark scene line: OFF `reused=30760,proxy=244542,culled=133806`; ON `reused=31160,proxy=247722,culled=135546`.
- The log exposed GPU timer query collisions (`Begin query ... while query 5 ... is active`), so GPU timing needed a root-cause fix before trusting future GPU deltas.
- The 2026-06-25 08:03 benchmark log is stale for the current code: it reports `scene-v2`, has no normalized CPU line, and has no significance reason counters. It should not be used to validate the current jar.
- The 2026-06-25 08:34 `latest.log` has no benchmark lines. It only shows startup, immediate shutdown, and a shutdown-time `TextureManager.close` `ConcurrentModificationException`, so it cannot validate scene-v3.
- The 2026-06-25 08:43 scene-v3 run regressed with Optiminium ON: OFF 64.1 avg FPS / 29.2 1% low / 18.3 worst-frame FPS, ON 54.1 avg FPS / 15.6 1% low / 10.3 worst-frame FPS. ON also showed `gpuScale=0.85`, `particleScale=0.85`, `blockEntityScale=0.85`, `pendingGpuUploads=498`, and 206 raw-spike adaptive activations despite `denseSceneFrames=0`.

## Done

- Added the deep research report: `docs/deep-research-report.md`.
- Added the modern rendering roadmap: `docs/modern-rendering-roadmap.md`.
- Added render-pipeline counters for layer switches, binds, uploads, translucent/particle/terrain frames, and suspected stalls.
- Added default-off experimental renderer gate.
- Added default-off experimental upload stall limiter.
- Fixed dense-scene metric direction so raw scene complexity is intended to come from visible render sections before Optiminium culling.
- Moved raw scene sampling onto `LevelRenderer.renderLevel` as well as compile-section pacing, so OFF/ON benchmarks are not dependent on chunk compilation activity.
- Bumped the benchmark format marker to `scene-v3`. `build/libs/optiminium-1.0.jar` must emit `Optiminium benchmark scene-v3` and the `Optiminium benchmark scene OFF[...] ON[...]` line; `build/libs/modid-1.0.jar` is stale and should not be used for validation.
- Started Temporal Significance Virtualization with default-off measurement only:
  - `experimentalTemporalSignificance`
  - raw-visible block-entity significance band counters
  - `significanceBands=full/throttled/reused/proxy/culled` diagnostic output
- Added a dedicated benchmark scene line that prints raw/significance metrics for OFF and ON without digging through diagnostics.
- Verified scene-v2 raw metrics in the latest log: nonzero raw counts, dense threshold behavior, and significance band reporting.
- Replaced frame-wide `GL_TIME_ELAPSED` GPU timer queries with timestamp-pair queries so Optiminium timing does not nest with Minecraft's active GL queries.
- Fixed `renderedBlockEntitiesAfterCulling` on the actual renderer invocation path: modded renderers count from `BlockEntityRenderDispatcher.setupAndRender`, while Optiminium-wrapped vanilla renderers count only after their skip check passes.
- Added `renderedBlockEntitiesThisRun` so benchmark output has a cumulative rendered count to compare with cumulative `culledBlockEntitiesThisRun`; `renderedBlockEntitiesAfterCulling` remains the last sampled frame count.
- Expanded Visual Significance into a scoring pass for block entities using distance, camera direction, looked-at target, recent interaction, importance, repeated sightings, frame pressure, and camera stability.
- Visual Significance now also records client-rendered entities and particle create decisions through existing culling paths, using the same measurement-only band/reason counters.
- Safety rule added for dangerous/important entities in client render culling: projectiles and falling blocks are protected from entity render culling and classified as important visual work.
- Existing entity and block-entity culling now asks Visual Significance for protection before skipping directly looked-at, recently interacted, nearby, or important visuals.
- Recent-interaction protection now has a permanent never-interacted default, so pre-frame safety checks do not accidentally protect unseen block entities.
- Stopped raw frame spikes from lowering render/upload budgets in non-dense scenes; adaptive scaling now stays at full scale unless `denseSceneActive` is true.
- Disabled cloud/weather pass cancellation. Whole-pass skipping caused visible flicker and was not worth keeping as a default-on optimization.
- Tightened adaptive quality again: it now activates only for dense scenes or sustained frame pressure, not one-off raw/pacing spikes. Visual Significance reads a separate sustained/dense pressure signal instead of raw adaptive spikes.
- Moved non-wrapped block-entity rendered counting to `BlockEntityRenderDispatcher.setupAndRender` entry; Optiminium-wrapped vanilla renderers still count only after their culling check passes.
- Added `nearestSignificanceDistance` so `fullBecauseNearby=0` can be distinguished between no nearby candidates and a classification bug.
- Added per-frame normalized render metrics:
  - `textureBindsPerFrame`
  - `shaderBindsPerFrame`
  - `renderLayerSwitchesPerFrame`
  - `bufferUploadsPerFrame`
- Added `Optiminium benchmark significance summary` explaining full-quality, throttled, reused, proxied, and culled buckets with their reason counts.
- Expanded Visual Significance measurement-only classification:
  - nearby/important/looked-at/recent objects report FULL
  - medium-distance objects report THROTTLED
  - stable farther objects report REUSED
  - far repeated objects report PROXY
  - offscreen or over-budget objects report CULLED
- Tightened reason semantics: `fullBecauseRecentlyInteracted` now requires use/attack input on the targeted block, and `culledBecauseOffscreen` uses camera direction instead of distance alone.
- Added significance reason counters:
  - `fullBecauseNearby`
  - `fullBecauseImportant`
  - `fullBecauseLookedAt`
  - `fullBecauseRecentlyInteracted`
  - `throttledBecauseDistance`
  - `throttledBecauseFramePressure`
  - `reusedBecauseStable`
  - `reusedBecauseCameraStable`
  - `proxyBecauseFarRepeated`
  - `proxyBecauseLowScreenSize`
  - `culledBecauseOffscreen`
  - `culledBecauseBudget`
  - `culledBecauseTiny`
  - `culledBecauseLowSignificance`
- Added Visual Significance CPU diagnostics:
  - `significanceCpuMs`
  - `worstSignificanceCpuMs`
  - `mostCommonReason`
- Added `Optiminium benchmark significance` summary with band totals, significance CPU, estimated saved block-entity/particle/entity renders, and the most common significance reason.
- Added normalized benchmark CPU output:
  - `avgCpuMs` as the render-frame interval
  - `renderThreadCpuMs` from JVM thread CPU time when available
  - `optiminiumCpuMs`
  - `estimatedExternalCpuMs`
  - `gpuWaitOrStallMs` as interval time not accounted for by render-thread CPU time
  - `avgGpuMs`
  - `cpuTiming` to show whether JVM thread CPU timing was available
- Verified `./gradlew compileJava` after the Visual Significance safety/default fixes; only existing `EventBusSubscriber.Bus.MOD` deprecation warnings remained.

## Needs Immediate Verification

- Re-run the same benchmark after the GPU timestamp-query fix.
- Treat any `scene-v2` benchmark output as stale.
- Confirm the log no longer contains `Begin query ... while query ... is active`.
- Confirm the run reaches the benchmark instead of shutting down during startup/resource reload.
- Confirm `Optiminium benchmark scene-v3` reports nonzero raw scene metrics and populated `significanceBands`.
- Confirm ON diagnostics stay at `gpuScale=1.00`, `particleScale=1.00`, `blockEntityScale=1.00`, and no upload backlog in non-dense scenes.
- Confirm clouds/weather no longer flicker when Optiminium is ON.
- Confirm `renderedBlockEntitiesAfterCulling` is nonzero when block entities render.
- Confirm `renderedBlockEntitiesThisRun` is nonzero when any block entities rendered during the benchmark phase.
- If `fullBecauseNearby` is still zero, inspect `nearestSignificanceDistance`: values above 24 blocks mean the scene simply has no nearby recorded candidates.
- Confirm FULL and THROTTLED bands can become nonzero in a scene with nearby/looked-at or medium-distance block entities.
- Inspect `Optiminium benchmark normalized` to determine whether the OFF/ON interval delta is Optiminium code, other render-thread CPU, or wait/stall time.

## Next Work

1. Re-run the benchmark and inspect the GL debug lines plus `Optiminium benchmark scene OFF[...] ON[...]`.
2. Verify the game is running `build/libs/optiminium-1.0.jar` from the latest build. If the chat output does not include `Optiminium benchmark scene-v3`, the benchmark is not coming from the current source.
3. Add a no-op significance policy report:
   - candidate counts per band
   - estimated refresh interval per band
   - protected near-camera count
4. Only then prototype behavior:
   - throttled extraction/update cadence first
   - render-state reuse second
   - proxy/impostor rendering later

## Not Yet

- No proxy rendering.
- No render-state reuse.
- No persistent mapped upload ring.
- No GL state caching.
- No terrain renderer rewrite.
- No Sodium/Embeddium/Iris/Oculus integration assumptions beyond default-off experimental diagnostics.
