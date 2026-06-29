# Optiminium Modern Rendering Roadmap

## Scope

Optiminium should avoid replacing Minecraft's renderer. The compatible path is to optimize work around the renderer: upload pacing, rebuild priority, block/entity/particle decisions, and Optiminium-owned draw work. Anything that caches global OpenGL state or rewrites terrain/chunk submission should stay experimental or abandoned unless the mod owns the whole state boundary.

Current evidence points to culling and dense-scene block-entity control as the active FPS source. Render-pipeline profiling now measures layer switches, binds, uploads, translucent frames, particle frames, terrain frames, suspected stalls, and profiling overhead. Upload-stall-aware chunk upload pacing remains an internal prototype, not a public setting.

References checked:

- OpenGL `glBufferStorage` supports immutable buffer storage and persistent mapping on GL 4.4+, but it requires explicit storage/sync ownership: https://docs.gl/gl4/glBufferStorage
- OpenGL multi-draw indirect can reduce draw-call CPU overhead, but only when the caller owns a compatible draw-command list: https://docs.gl/gl4/glMultiDrawIndirect
- NeoForge client events provide stable surrounding hooks; renderer replacement internals remain compatibility-sensitive: https://docs.neoforged.net/docs/1.21.1/concepts/events/

## Ranked Techniques

1. Internal upload-stall-aware chunk upload pacing
   - Expected benefit: Low to medium average FPS, medium 1% low improvement in scenes where uploads cause stalls.
   - Difficulty: Low.
   - Compatibility risk: Low. It only changes Optiminium-managed upload budgets and does not cache or skip raw GL state changes.
   - Required hooks: Existing `GlStateManager._glBufferData` profiling, `RenderFrameEvent.Pre`, `LevelRenderer`/upload queue budget calls.
   - Status: Internal prototype. Not exposed until a benchmark shows nonzero activation and measurable benefit.

2. Render-profile-driven tuning recommendations
   - Expected benefit: Indirect. It identifies whether low-gain scenes are terrain, translucent, particles, binds, uploads, stalls, or overdraw dominated.
   - Difficulty: Low.
   - Compatibility risk: Low. Counters observe existing calls.
   - Required hooks: Existing GL/render mixins and benchmark report.
   - Status: Stable diagnostic path.

3. Occlusion-aware rebuild and upload prioritization
   - Expected benefit: Medium for dense bases and fast camera movement; mostly improves lows and visible progress.
   - Difficulty: Medium.
   - Compatibility risk: Low to medium if limited to scheduling, not draw submission.
   - Required hooks: `LevelRenderer.visibleSections`, section rebuild queue, camera position/motion, existing culling data.
   - Status: Stable candidate.

4. Translucent/particle pass pressure response
   - Expected benefit: Medium in scenes with many effects, weather, beacon beams, glass, fluids, or alpha particles.
   - Difficulty: Medium.
   - Compatibility risk: Low if it only adjusts Optiminium particle/effect throttles; medium if it changes render ordering.
   - Required hooks: Particle render mixin, `RenderType.translucent()`, weather/cloud/shadow decisions.
   - Status: Experimental until benchmark attribution proves the pass is dominant.

5. Optiminium-owned overlay/debug batching by existing `RenderType`
   - Expected benefit: Low today, higher if Optiminium adds visual overlays.
   - Difficulty: Low.
   - Compatibility risk: Low when it only batches Optiminium's own geometry.
   - Required hooks: Existing render events and `RenderType` usage.
   - Status: Stable when needed. Not urgent because Optiminium has little custom draw work.

6. Lower-latency frame pacing with upload/rebuild backpressure
   - Expected benefit: Low average FPS gain, medium latency and worst-frame improvement when uploads or rebuilds bunch up.
   - Difficulty: Medium.
   - Compatibility risk: Low if it only changes Optiminium budgets and uses existing GPU timer/query data; medium if it changes present timing.
   - Required hooks: `RenderFrameEvent.Pre`, existing `OptiminiumGpuTimer`, upload/rebuild budget calls, benchmark frame-time history.
   - Status: Experimental until validated against GPU timer availability and driver variance.

7. Conservative iGPU/low-end preset
   - Expected benefit: Medium stability improvement on integrated GPUs by combining upload pacing, dense-scene adaptation, and particle/effect limits.
   - Difficulty: Low.
   - Compatibility risk: Low.
   - Required hooks: Existing settings and benchmark counters.
   - Status: Stable candidate after another benchmark validates defaults.

8. Persistent mapped staging buffers for Optiminium-owned uploads
   - Expected benefit: Medium to high in upload-heavy custom rendering.
   - Difficulty: High.
   - Compatibility risk: Medium. Needs GL 4.4 support, fences, ring-buffer ownership, and fallback paths.
   - Required hooks: Only safe for buffers Optiminium creates and owns.
   - Status: Experimental later. Do not apply to vanilla/Sodium chunk buffers.

9. Multi-draw indirect for Optiminium-owned batched geometry
   - Expected benefit: Medium to high when draw-call count is the bottleneck.
   - Difficulty: High.
   - Compatibility risk: Medium to high because it needs owned command buffers and shader/VAO assumptions.
   - Required hooks: Optiminium-owned renderer path, not vanilla terrain.
   - Status: Experimental later. Not a current prototype.

10. GPU-driven terrain/object culling
   - Expected benefit: High in theory.
   - Difficulty: Very high.
   - Compatibility risk: Very high with Sodium/Embeddium/Iris/Oculus because it competes with renderer ownership.
   - Required hooks: Replacement chunk draw pipeline, compute/SSBO/indirect support, renderer integration.
   - Status: Abandoned for Optiminium's current architecture.

11. Raw OpenGL state caching
   - Expected benefit: Unknown; could reduce redundant binds in some scenes.
   - Difficulty: Medium.
   - Compatibility risk: High. Compatibility renderers and shader mods may depend on their own state tracking and restoration.
   - Required hooks: Global GL state boundary, complete invalidation model.
   - Status: Abandoned for now. Keep measuring binds, but do not cache state.

## Prototype Behavior

The upload-stall prototype watches measured buffer upload time through the render profiler. If a frame spends at least 2 ms inside profiled buffer upload work, Optiminium can hold an upload-pressure signal for eight frames and clamp Optiminium-managed chunk upload budgets to one upload per frame.

The prototype is intentionally narrow:

- It is off by default.
- It does not alter vanilla/Sodium/Embeddium/Iris/Oculus GL state caching.
- It does not reorder render layers.
- It only affects Optiminium upload budgets that already existed.

## Recommended Next Safe OpenGL-Side Optimization

Keep the upload-stall limiter as the first safe OpenGL-side optimization for low-culling-gain scenes. If benchmarks show high `bufferUploadMs`, increased `bufferUploadCount`, or nonzero `suspectedGlStallFrames`, tune this limiter before attempting state caching, persistent buffers, or indirect drawing.

If low-gain scenes instead show high translucent or particle frames without upload pressure, the next safe step is effect/pass-specific throttling, not GL state caching.
