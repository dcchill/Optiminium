# Deep Research Progress

## Current benchmark read

- OFF: 41.0 avg FPS, 24.5 1% low, 24.3 worst-frame FPS, 18.54 avg GPU ms, 28.78 worst GPU ms.
- ON: 42.5 avg FPS, 26.0 1% low, 25.2 worst-frame FPS, 18.74 avg GPU ms, 25.55 worst GPU ms.
- Net: +1.5 avg FPS, +1.5 1% low FPS, +0.9 worst-frame FPS, -3.23 ms worst GPU time.
- Active gains came from culling: 46,161 hidden particles, 175,929 culled block entities, 4,636 culled entities.
- Adaptive quality did not activate: spike triggers, cooldowns, and minimum quality scales stayed inactive.
- Uploads were not the main bottleneck in this run: 7.0 uploads/frame at 0.38 ms average/worst upload time.

## Done

- Added the deep research report: `docs/deep-research-report.md`.
- Added the modern rendering roadmap: `docs/modern-rendering-roadmap.md`.
- Added render-pipeline counters for layer switches, binds, uploads, translucent/particle/terrain frames, and suspected stalls.
- Added default-off experimental renderer gate.
- Added default-off experimental upload stall limiter.
- Fixed dense-scene metric direction so raw scene complexity is intended to come from visible render sections before Optiminium culling.
- Moved raw scene sampling onto `LevelRenderer.renderLevel` as well as compile-section pacing, so OFF/ON benchmarks are not dependent on chunk compilation activity.
- Added a `scene-v2` marker to the current benchmark format. `build/libs/optiminium-1.0.jar` contains this marker and the `Optiminium benchmark scene OFF[...] ON[...]` line; `build/libs/modid-1.0.jar` is stale and should not be used for validation.
- Started Temporal Significance Virtualization with default-off measurement only:
  - `experimentalTemporalSignificance`
  - raw-visible block-entity significance band counters
  - `significanceBands=full/throttled/reused/proxy/culled` diagnostic output
- Added a dedicated benchmark scene line that prints raw/significance metrics for OFF and ON without digging through diagnostics.

## Needs Immediate Verification

- Re-run the same benchmark after the latest metric changes.
- Confirm OFF and ON both report nonzero raw scene metrics when block entities exist:
  - `rawVisibleBlockEntities`
  - `renderedBlockEntitiesAfterCulling`
  - `culledBlockEntitiesThisRun`
  - `significanceBands`
- Confirm `denseSceneFrames` is based on raw pre-cull complexity, not post-cull rendered counts.
- Confirm old benchmark fields such as `maxVisibleBlockEntities` are either renamed or interpreted as raw only.

## Next Work

1. Re-run the benchmark and inspect `Optiminium benchmark scene OFF[...] ON[...]`.
2. Verify the game is running `build/libs/optiminium-1.0.jar` from the latest build. If the chat output does not include `Optiminium benchmark scene-v2`, the benchmark is not coming from the current source.
3. Once raw metrics are trustworthy, add a no-op significance policy report:
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
