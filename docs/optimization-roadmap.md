# Optiminium Optimization Roadmap

## Latest benchmark

Test result:

- OFF: 44.5 avg FPS, 23.7 1% low FPS, 20.2 worst-frame FPS, 16.70 avg GPU ms, 25.38 worst GPU ms.
- ON: 46.7 avg FPS, 27.1 1% low FPS, 23.6 worst-frame FPS, 15.35 avg GPU ms, 21.45 worst GPU ms, 0.17 avg upload ms, 0.41 worst upload ms, 4.7 uploads/frame.

Read:

- Avg FPS improved 4.9%.
- 1% low improved 14.3%.
- Worst-frame FPS improved 16.8%.
- Avg GPU time dropped 8.1%.
- Worst GPU time dropped 15.5%.
- Upload time is not the current bottleneck.
- Dense block-entity scenes are the clear pressure source: max visible block entities hit 1366, dense-scene frames hit 561, and block-entity render culling reached 712725.
- Adaptive systems did not activate in this run: spike and cause cooldowns stayed at 0 and quality scales stayed at 1.00.

Conclusion: Optiminium is helping lows and worst frames, but the old adaptive trigger was too conservative because it waited on smoothed pacing. Adaptive quality now reacts to raw CPU/GPU spikes or dense block-entity scenes, and its thresholds are configurable.

## What the mod already does

- GPU/frame pacing with CPU and timer-query feedback.
- Chunk rebuild and OpenGL upload pacing.
- Entity, particle, shadow, cloud, weather, and block-entity render culling.
- Block-entity tick sleeping, item/xp clustering, redstone/light dedupe, adaptive simulation distance.
- An in-game benchmark for FPS, GPU time, upload time, and optimization attribution.
- Configurable adaptive quality: enable/disable, spike trigger percent, and dense block-entity threshold.

## Current priority

1. Run benchmark and validate adaptive activation.
   Adaptive quality now triggers from raw frame/GPU spikes or dense block-entity scenes. The next run should show nonzero block-entity cooldown frames in the benchmark when dense scenes like 1366 visible block entities appear.

2. Expensive scene detection from visible render sections.
   Dense block-entity areas are a common frame-time spike source, and vanilla renders block entities after terrain from every visible section. Count renderable block entities before that pass and tighten only block-entity work when the scene is dense.

3. Spike-aware frame pacing.
   Keep separate slow-spike and steady-load signals. React fast to upload or block-entity spikes, recover slowly, and avoid lowering quality after loading/tabbing gaps.

4. Camera-motion aware chunk rebuild priority.
   Bias async rebuilds toward the camera velocity vector, not just current look direction, so fast turns and elytra travel produce fewer visible holes.

5. Render-area cost memory.
   Keep a tiny per-section or per-region score for repeated block-entity/render-layer cost and use it to prioritize or defer work near known expensive builds.

6. Animated texture throttling.
   If a mapped hook is stable, reduce far/hidden animated atlas work under GPU pressure. This needs more care than render culling because atlas animation is shared state.

7. Presets.
   Add low-end GPU, iGPU, balanced, and high-end presets only after the adaptive signals are stable enough to make the presets meaningful.

## First implementation

Use `LevelRenderer.visibleSections` during `compileSections` to count renderable block entities for the current view. Feed that into `OptiminiumGpuOptimizer`, then lower block-entity render distance and distant block-entity frame budget only when the visible scene is dense.

## Second implementation

Use camera motion in chunk rebuild priority. Dirty sections ahead of player movement get a small priority boost, reducing visible rebuild lag during fast travel and quick strafing without adding another scheduler.

## Third implementation

Add a short spike cooldown. If a frame or upload pass overruns hard, temporarily clamp chunk rebuilds and uploads to minimum work for a few frames, then recover automatically.

## Fourth implementation

Use section-local render cost in chunk rebuild priority. Sections with many renderable block entities are deferred behind cheaper sections unless the player just changed them, improving visible progress per rebuild during dense-base spikes.

## Fifth implementation

Add benchmark attribution. The result line now includes hidden particles, culled block entities/entities, chunk rebuilds, sync rebuilds, custom uploads, max visible block entities, dense-scene frames, generic spike-cooldown frames, cause-specific cooldown frames, and max upload backlog.

## Sixth implementation

Add adaptive quality by spike reason. Slow frames with hidden particles temporarily tighten particle distance/count. Slow frames in dense or culled block-entity scenes temporarily tighten block-entity range and distant block-entity budget. Slow frames with chunk rebuild activity temporarily clamp async rebuilds and disable sync rebuilds. Particle and block-entity response strength scales by measured frame overrun instead of a fixed guessed value.

## Seventh implementation

Fix adaptive activation from the dense-scene benchmark. The old trigger only used smoothed pacing, so short worst-frame spikes and dense block-entity scenes could fail to activate. Adaptive quality now uses raw CPU/GPU spike data or configurable dense-scene detection. Added config for adaptive quality, spike trigger percent, and dense block-entity threshold, plus debug output showing active thresholds and cooldown reasons.

## Eighth implementation

Split upload and visual-effect pressure into their own adaptive signals. Uploads now get a short cause-specific cooldown when upload time exceeds the current upload budget, clamping upload count to 1 without relying only on the generic spike cooldown. Raw CPU/GPU spikes now also trigger an effect-pressure cooldown, so expensive optional visuals such as clouds and weather can be skipped during short spikes even when the smoothed GPU scale has not fallen yet. Benchmark output now reports `uploadCooldownFrames` and `effectCooldownFrames`.

## Ninth implementation

Make chunk rebuild budgets configurable instead of hardcoded. `chunkRebuildsPerFrame` and `syncChunkRebuildsPerFrame` now persist in the Optiminium config and appear in the settings screen, so rebuild pacing can be tuned alongside upload pacing after the next benchmark. Block-entity render tightening also now uses the configured dense-scene threshold instead of the old hardcoded `96/192` cutoffs, keeping normal scenes closer to full quality.

## Tenth implementation

Add trigger-source counters to the debug and benchmark output. The next benchmark now reports `rawSpikeTriggerFrames`, `pacingSpikeTriggerFrames`, and `uploadOverBudgetFrames`, so nonzero cooldowns can be traced back to raw frame/GPU spikes, smoothed pacing spikes, upload budget pressure, or dense scenes.

## Eleventh implementation

Make graphics-effect culling configurable. Cloud, weather, and shadow effect throttling now respect a persisted `graphicsEffectCulling` setting with a settings-screen toggle, so effect-pressure adaptation can be disabled without turning off the rest of frame pacing.

## Twelfth implementation

Add a compact settings snapshot to every benchmark phase. Copied benchmark lines now include adaptive quality state, spike trigger percent, dense threshold, rebuild/upload budgets, upload mode, and effect-culling state, making before/after comparisons reproducible.

## Next implementation

Run the benchmark again and validate thresholds:

- High `denseSceneFrames` or `culledBlockEntities`: tune block-entity cooldown scale and budget.
- High `rawSpikeTriggerFrames` with low `denseSceneFrames`: tune spike trigger percent or effect pressure.
- High `pacingSpikeTriggerFrames`: tune generic spike cooldown and rebuild/upload clamping.
- High `hiddenParticles` with weak 1% lows: tune particle cooldown scale.
- High `chunkRebuilds` or `syncChunkRebuilds`: tune rebuild cooldown length.
- High `maxUploadBacklog`, `uploadOverBudgetFrames`, or `uploadCooldownFrames`: revisit upload pacing. The latest benchmark does not point here yet.
- High `effectCooldownFrames` with weak worst-frame FPS: tune cloud/weather/shadow culling thresholds.
