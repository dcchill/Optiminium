# Optiminium Technical Design & Architecture

## Intelligent Rendering for Modern Minecraft

**Version:** 2.0 draft  
**Project:** Optiminium  
**Target:** Minecraft 1.21.1 / NeoForge 21.1.x  
**Role:** Sodium/Embeddium companion optimization mod  

---

## 1. Executive Summary

Optiminium is a client side mod for optimizing problems that remain after other mods like Sodium and Embeddium to make the game feel smoother and fix 1% lows and choppyness.  Modded worlds and high density vanilla worlds are the main focus.

Instead of re-inventing the wheel, Optiminium creates a high level management layer that will decide which hard to render objects deserve to be in full fidelity.  Its central idea is the **Visual Significance Engine**: a lightweight system that evaluates objects using screen coverage, camera attention, render cost, confidence, and safety rules.

This makes Optiminium a companion rather than a competitor. It avoids replacing Sodium and instead focuses on block entities, entities, particles, render-states, upload scheduling, and dense-scene behavior.

Recent benchmarks show meaningful improvements in average FPS, 1% lows, worst-frame FPS, GPU frame time, and CPU frame time.

The long term goal is to make Minecraft's rendering workload behave like modern rendering engines, with the least amount impact on visuals.

---

## 2. The Problem: What Remains After Sodium

Sodium and related renderers significantly improve Minecraft's chunk rendering pipeline, but heavy modded worlds stress the client in ways terrain optimization alone does not solve.

A dense modded base can include thousands of visually active objects:

- chests, barrels, signs, beds, and vanilla block entities
- Create-style shafts, belts, encased fans, pumps, and contraptions
- Mekanism machines and cables
- AE2 or Refined Storage terminals, buses, and dense networks
- particles, smoke, sparks, effects, and visual feedback
- animated entities, villagers, animals, item entities, and display-style objects
- shader-sensitive translucent or emissive renderers

Even when the GPU is capable of drawing the scene, the render thread can still suffer from:

- renderer lookup overhead
- block entity renderer preparation
- matrix and lighting setup
- texture binds
- shader binds
- buffer uploads
- driver synchronization
- bursty rebuilds or upload admissions
- repeated per-frame work for objects the player barely notices

Minecraft generally has frustum checks and distance checks, but it does not have a rich notion of **visual importance**. A nearby chest, a far tiny barrel, a large Create contraption, and a barely visible particle group can all pass through similar high-level rendering paths before being accepted or rejected. The result is that expensive work is often performed before the client has asked the most important question:

> Is this visual object worth full-quality work this frame?

Optiminium's answer is to perform that decision earlier and more intelligently.

```text
Loaded Scene
    │
    ▼
Raw visual candidates
    │
    ▼
Visual Significance Engine
    │
    ▼
Quality band / scheduling decision
    │
    ▼
Minecraft + Sodium/Embeddium render path
    │
    ▼
OpenGL / GPU
```

This architecture keeps the renderer compatibility boundary intact. Optiminium does not need to own terrain rendering to improve frame pacing; it can shape the visual workload before expensive or low-value work reaches the renderer.

---

## 3. Design Philosophy

Optiminium is not designed as another distance-culling mod. Distance is useful, but distance alone is too crude. A far object can still matter if it is large on screen, centered in view, moving, recently interacted with, or visually expensive. A nearby object can be visually irrelevant if it is tiny, offscreen, static, or hidden behind other detail.

Optiminium's design philosophy is based on three principles.

### 3.1 Allocate Rendering Effort by Visual Value

Every frame has a limited budget. Optiminium attempts to spend that budget where the player gets the most perceptual value. This means giving priority to:

- objects near the player
- objects the player is looking at
- living entities and important gameplay visuals
- recently visible or recently interacted objects
- objects with large screen coverage
- expensive objects whose update genuinely matters

It also means reducing effort for:

- tiny far objects
- stable objects whose visual state can be reused
- repeated far details
- particle effects that contribute little to the final image
- low-significance objects during dense scenes

### 3.2 Prefer Degradation Over Disappearance

A critical lesson from development is that visible objects should not simply vanish. Chests, machines, animals, villagers, and other recognizable objects should stay visually stable unless they are clearly offscreen, tiny, far away, or under extreme pressure.

For important visual classes, the safe degradation path is:

```text
FULL → THROTTLED → REUSED → PROXY → CULLED
```

Not:

```text
FULL → CULLED
```

This is why Optiminium's current approach emphasizes temporal reuse and quality bands rather than raw culling. Culling is the last resort, not the core identity.

### 3.3 Complement Sodium, Do Not Replace It

Sodium and Embeddium focus on making rendering faster. Optiminium focuses on making the workload smarter. That separation keeps compatibility risk lower and gives the project a distinct niche.

Optiminium should avoid:

- replacing Sodium's terrain renderer
- taking ownership of chunk rendering
- caching raw OpenGL state globally
- rewriting shader pack behavior
- changing server logic, AI, ticking, hitboxes, or networking

Optiminium should focus on:

- visual priority classification
- client-side render decision making
- block entity and entity visual stability
- particle reduction
- upload pacing
- render profiling
- benchmark-driven optimization

---

## 4. Overall Architecture

Optiminium is organized around a layered pipeline. Each layer answers a different question.

```text
Minecraft scene data
        │
        ▼
Raw candidate gathering
        │
        ▼
Visual Significance Engine
        │
        ▼
Quality band selection
        │
        ▼
Adaptive Quality / frame-pressure adjustment
        │
        ▼
Upload scheduling and render admission
        │
        ▼
Minecraft + Sodium/Embeddium rendering
        │
        ▼
OpenGL driver and GPU
```

### 4.1 Visual Significance Engine

The Visual Significance Engine is the core decision system. It assigns a continuously updated score and confidence model to visual objects, then maps them into quality bands.

It considers:

- projected screen coverage
- distance
- camera focus
- camera stability
- render cost
- temporal history
- confidence
- object type
- living entity safety
- recent visibility
- recent interaction
- dense-scene pressure

### 4.2 Adaptive Quality

Adaptive Quality is a secondary system. It should not replace Visual Significance. Instead, it adjusts budgets under sustained pressure.

Visual Significance decides:

> What deserves quality?

Adaptive Quality decides:

> How much quality is affordable right now?

This distinction prevents the adaptive system from blindly reducing quality on important objects.

### 4.3 Upload Scheduling

Upload scheduling exists to prevent redundant or poorly timed GPU upload work. The current implementation tracks upload behavior and redundant scheduling prevention. The next step is to make upload admission more significance-aware while keeping CPU overhead low.

### 4.4 Benchmark and Diagnostics

The benchmark system is not just a performance test. It is an architecture validation tool. It reports FPS, 1% lows, worst frames, GPU timers, CPU timers, render state counts, significance bands, safety counters, upload counters, and worst-case subsystem timings.

The project has repeatedly used benchmark diagnostics to discover incorrect metrics, overly aggressive culling, entity popping, overuse of `FULL`, and scheduler overhead. This feedback loop is now a major part of Optiminium's development process.

---

## 5. The Visual Significance Engine

The Visual Significance Engine is the defining system in Optiminium. Its purpose is to classify visual work before Minecraft spends full effort on it.

The current system has evolved from simple culling into a continuous scoring and stabilization model.

```text
Visual object
    │
    ▼
Estimate screen coverage
    │
    ▼
Estimate render cost
    │
    ▼
Apply object safety rules
    │
    ▼
Update temporal history
    │
    ▼
Compute weighted significance score
    │
    ▼
Apply confidence and hysteresis
    │
    ▼
Select quality band
    │
    ▼
Render, reuse, proxy, throttle, or skip
```

### 5.1 Weighted Significance Scoring

Early versions of the system used broad rules such as "important objects stay full quality" or "small objects become proxy candidates." That worked, but it caused one major problem: too many objects were promoted to `FULL`, preventing the engine from using `THROTTLED`, `REUSED`, and `PROXY` effectively.

The current design moves toward weighted scoring. Instead of one boolean deciding the result, several independent factors contribute to a normalized significance score.

A simplified model looks like this:

```text
VisualSignificance =
    screenCoverageWeight
  + cameraAttentionWeight
  + temporalConfidenceWeight
  + recentVisibilityWeight
  + objectImportanceWeight
  + motionWeight
  + renderCostWeight
  - distancePenalty
  - lowConfidencePenalty
```

The exact weights are implementation details, but the principle matters: "important" should raise a score, not automatically force `FULL` quality except for safety-critical cases.

This change led to a healthier distribution of quality bands. Instead of `fullBecauseImportant` dominating every benchmark, the system began assigning many stable objects to `REUSED`, especially when the camera is stable. That is a major architectural improvement: reuse preserves visual continuity while reducing work.

### 5.2 Quality Bands

Optiminium uses five quality bands.

| Band | Meaning | Typical use |
|---|---|---|
| `FULL` | Full quality, normal render/update behavior | Nearby, looked-at, selected, recently interacted, or safety-critical objects |
| `THROTTLED` | Reduced visual update rate | Mid-priority animated visuals under pressure |
| `REUSED` | Reuse previous visual state | Stable block entities, camera-stable scenes, non-changing visuals |
| `PROXY` | Cheaper representation or simplified future path | Far, repeated, low-screen-size objects |
| `CULLED` | Skip visual work | Offscreen, tiny, very low-value, or safe budget removals |

The important design choice is that `CULLED` is not the default optimization path. For visible block entities and living entities, Optiminium should normally prefer `THROTTLED`, `REUSED`, or `PROXY` before culling.

### 5.3 Temporal Stability and Hysteresis

Visual popping is one of the easiest ways to make an optimization mod feel broken. A system can improve FPS while still being unacceptable if animals, chests, or machines appear and disappear as the player moves.

To avoid that, Optiminium uses hysteresis and temporal stability. Objects do not switch bands immediately just because their score crosses a threshold for one frame. Instead, the engine can require sustained evidence before demotion and can protect recently visible objects from sudden removal.

This is especially important at band boundaries. Without hysteresis, an object near a threshold can bounce:

```text
FULL → PROXY → FULL → PROXY → FULL
```

With hysteresis, the object changes more slowly:

```text
FULL → FULL → THROTTLED → REUSED → REUSED
```

The second sequence is visually calmer and usually better for frame pacing because it avoids sudden upload or render-state bursts.

### 5.4 Confidence

Confidence is a companion to significance. It estimates how certain Optiminium is that a given band is safe.

High confidence means the engine can be more willing to reuse, proxy, or throttle. Low confidence means the engine should be conservative. This is useful for borderline cases, unknown renderer classes, living entities, or objects whose visual state changes unpredictably.

A useful rule is:

> When uncertain, keep the object safer for longer.

This protects visual quality while still allowing stable low-value objects to be optimized over time.

### 5.5 Render Cost Estimation

Render cost matters because not all objects are equal. A simple chest, a sign, a vanilla bed, a Create machine, and a modded translucent animated renderer can have very different costs.

The Visual Significance Engine should eventually estimate cost from:

- renderer class
- historical render cost
- animation frequency
- model complexity when available
- texture/shader sensitivity
- upload behavior
- state volatility

The current benchmark already reports average render cost and top expensive tracked objects. The next step is making those diagnostics more human-readable and tying cost estimates to actual renderer classes rather than opaque internal keys.

### 5.6 Safety Rules

Optiminium should optimize aggressively only where it is visually safe. The current safety model protects:

- nearby objects
- looked-at objects
- living entities
- important entities
- recently visible block entities
- selected or interacted objects
- middle-distance living entities

This was added because real testing showed that technically correct culling can still feel wrong. A sheep in the middle distance should not pop in and out while the player walks. A chest should not disappear simply because it is not the most significant object in the scene.

The safety rules are therefore not optional polish. They are central to making the system usable.

---

## 6. Adaptive Quality

Adaptive Quality is Optiminium's frame-pressure response system. It monitors dense scenes, raw spikes, pacing spikes, GPU/CPU pressure, and other indicators, then adjusts budgets.

The key architectural rule is that Adaptive Quality should not bypass Visual Significance. It should not blindly scale everything down. Instead, it should modify the thresholds and budgets that Visual Significance uses.

```text
Frame pressure detected
        │
        ▼
Adaptive Quality adjusts available budget
        │
        ▼
Visual Significance chooses lower-value work first
        │
        ▼
Important / visible / living / looked-at objects remain protected
```

This prevents bad behavior such as lowering the quality of important visible objects while leaving low-value work untouched.

Current benchmark logs show Adaptive Quality activating in dense scenes and reducing visual scales while Visual Significance safety rules keep block entities and living entities stable. This is the correct relationship: Adaptive Quality applies pressure, but Visual Significance decides where that pressure lands.

Future work should make Adaptive Quality more budget-oriented and less scale-oriented. Instead of only applying scale factors like `gpuScale`, `particleScale`, and `blockEntityScale`, it should expose a clearer frame-pressure state:

- normal
- medium pressure
- heavy pressure
- emergency

Then each subsystem can respond consistently.

---

## 7. Benchmark Framework

Optiminium's benchmark framework has become one of the project's most important systems. It provides evidence for whether an optimization is actually working and whether it is visually safe.

The benchmark currently tracks several categories.

### 7.1 Frame Performance

- average FPS
- 1% low FPS
- worst-frame FPS
- average GPU ms
- worst GPU ms
- average CPU ms
- render-thread CPU ms
- Optiminium CPU overhead
- external estimated CPU cost

These numbers show whether Optiminium is improving average performance or only moving work around.

### 7.2 Visual Significance Metrics

- `significanceBands`
- `fullBecauseNearby`
- `fullBecauseImportant`
- `fullBecauseLookedAt`
- `fullForcedBySafety`
- `fullByWeightedScore`
- `importantButThrottled`
- `importantButReused`
- `importantButProxy`
- `avgSignificanceScore`
- `avgConfidence`
- `avgRenderCost`
- `avgScreenCoverage`
- `avgTemporalScore`
- `highestVisualSignificance`
- `lowestVisualSignificance`

These metrics are what made it possible to detect that the engine was previously too conservative. When `fullBecauseImportant` dominated the output, the system was safe but not smart enough. When `reusedBecauseCameraStable` became the most common reason, the system started behaving more like a render budget manager.

### 7.3 Safety Metrics

- `blockEntityVisibleCullEvents`
- `blockEntityCullPreventedByRecentlyVisible`
- `blockEntityCullPreventedByLookedAt`
- `entityCullPreventedByMiddleDistance`
- `entityPromotedBecauseLiving`
- `entityCullOscillationEvents`
- `demotionsPreventedByHysteresis`

These metrics matter because FPS is not enough. An optimization that improves FPS while hiding visible chests or making animals pop is not successful.

### 7.4 Render Pipeline Metrics

- texture bind count
- shader bind count
- framebuffer bind count
- render-layer switch count
- buffer upload count
- buffer upload ms
- suspected GL stall frames
- total render profiling ms

These reveal whether improvements are happening on the CPU, the GPU, the driver path, or simply from rendering fewer objects.

### 7.5 Example Benchmark Snapshot

A representative recent run showed:

| Metric | OFF | ON | Result |
|---|---:|---:|---:|
| Average FPS | 58.5 | 64.1 | +9.6% |
| 1% low FPS | 22.2 | 31.8 | +43% |
| Worst-frame FPS | 16.9 | 29.1 | +72% |
| Average GPU ms | 10.94 | 10.39 | Better |
| Average CPU ms | 17.11 | 15.60 | Better |
| Optiminium CPU ms | 0.153 | 0.169 | Small overhead |
| Worst Optiminium CPU ms | 0.958 | 0.612 | Better |
| Visible block entity culls | 0 | 0 | Safe |

This is the type of result Optiminium should target: modest overhead, strong 1% low improvement, better worst-frame behavior, and no visible object disappearance.

---

## 8. Lessons Learned During Development

Several important lessons emerged during Optiminium's development.

### 8.1 Distance Alone Is Not Enough

Distance is easy to compute, but it does not reliably predict visual importance. Screen coverage, camera focus, motion, and recent visibility matter just as much.

### 8.2 Binary Importance Was Too Conservative

The early system treated many objects as important and forced them to `FULL`. That protected visuals, but it prevented meaningful use of `REUSED` and `PROXY` bands. Weighted scoring is a better model.

### 8.3 Reuse Is Often Better Than Culling

For block entities, reuse is frequently the safest optimization. A stable object that reuses its previous visual state can remain visible while still reducing update cost. This feels much better than making it disappear.

### 8.4 Hysteresis Is Essential

Without hysteresis, borderline objects oscillate between bands. With hysteresis, transitions become stable, predictable, and visually calmer.

### 8.5 Safety Rules Are Not Optional

Living entities, recently visible objects, looked-at objects, and recognizable block entities need protection. Performance improvements are only useful if the player does not notice the optimization.

### 8.6 Benchmarks Must Explain Why

A simple FPS number is not enough. Optiminium's benchmark became much more useful once it could explain why objects were full, throttled, reused, proxied, or culled.

### 8.7 The Project's Identity Changed

Optiminium began as a collection of optimization ideas. It is becoming a render workload manager. That is a stronger and more unique identity.

---

## 9. Future Roadmap

The next stage should build on Visual Significance rather than replace it.

### 9.1 Near Term: Polish Visual Significance

Near-term work should focus on stability and clarity:

- reduce worst-case significance CPU spikes
- make top expensive object diagnostics cheaper
- map expensive object keys to renderer names
- continue testing entity and block entity anti-pop behavior
- tune weighted scoring across different scenes
- test with actual heavy modpacks, especially Create-style factories

### 9.2 Near Term: Upload Governor

Upload scheduling is the next major performance target. The goal is not simply to reduce uploads, but to prevent redundant or low-value uploads from hurting frame pacing.

Useful metrics include:

- `uploadsSkippedBecauseLowSignificance`
- `uploadsDeduplicated`
- `uploadsDeferredBySignificance`
- `uploadsPromotedBecauseNear`
- `redundantUploadSchedulingPrevented`
- upload queue depth
- upload scheduling CPU time
- upload backlog frames

The Upload Governor should use Visual Significance to decide upload priority.

### 9.3 Medium Term: Render Work Scheduler

Once Visual Significance and upload scheduling are stable, Optiminium can expand into a general render work scheduler.

This scheduler would prioritize:

- state extraction
- proxy refreshes
- uploads
- particle work
- chunk rebuild admissions
- expensive visual updates

The guiding rule should be:

> High-significance work happens first; low-significance work is delayed, reused, or degraded.

### 9.4 Medium Term: Cluster Proxies

Cluster proxies are a logical next step, but only after the significance engine is mature. They are most useful for far machine rooms, repeated cable banks, large storage walls, or dense decorative areas.

The first implementation should be conservative:

- opaque visuals first
- low-interaction content first
- no shader-sensitive renderers at first
- no terrain renderer replacement
- only Optiminium-owned proxy batches

### 9.5 Long Term: Modern OpenGL Techniques

Long-term rendering improvements may include:

- persistent mapped buffers for Optiminium-owned uploads
- ring-buffered upload staging
- fence-based synchronization
- multi-draw or indirect rendering for proxy batches
- better batching for Optiminium-managed visuals

These should remain scoped to Optiminium-owned work. Replacing Sodium's renderer is not the goal.

---

## 10. Testing Strategy

Optiminium should be tested across hardware classes and scene types.

| Hardware | Scenario | What to measure |
|---|---|---|
| Intel iGPU laptop | Hermitcraft-style base | FPS, 1% lows, worst GPU ms, entity/block entity safety |
| Intel iGPU laptop | Create-heavy factory | visual stability, block entity pressure, particles, GPU time |
| Mid-range desktop | Modded base traversal | upload bursts, worst frames, pacing stability |
| High-end 14700K + RTX 4090 | Dense base | render-thread overhead, CPU bottlenecks, 1% lows |
| High-end 14700K + RTX 4090 | Shader-heavy scene | compatibility, bind counts, GPU stalls |
| Integrated singleplayer | Dense base | frame time and local server MSPT stability |

Every benchmark should include:

- same camera position or path
- warm-up period
- OFF/ON comparison
- repeated runs
- benchmark logs saved
- visible inspection for popping
- screenshots or video when testing visual artifacts

A successful optimization must satisfy both performance and visual correctness.

```text
Good optimization:
    FPS improves
    1% lows improve
    worst frames improve
    no obvious popping
    no visible block entities disappear
    CPU overhead remains bounded

Bad optimization:
    FPS improves
    but animals pop
    or chests disappear
    or worst frames regress
```

---

## 11. Conclusion

Optiminium's strongest direction is not becoming another renderer replacement. Its strongest direction is becoming an intelligent rendering workload layer for heavy modded Minecraft.

The Visual Significance Engine gives the project a clear identity. It continuously evaluates what the player is likely to notice, assigns visual work to quality bands, stabilizes decisions over time, protects important objects, and allows expensive scenes to degrade gracefully rather than collapse into frame spikes.

This architecture complements Sodium because it operates at a different level. Sodium improves the rendering engine. Optiminium reduces and schedules the visual work that reaches it.

The current implementation already demonstrates the value of this approach: better average FPS, improved 1% lows, better worst-frame behavior, reduced GPU stalls, and safer visual decisions. The next major step is to build stronger upload and render work scheduling around the Visual Significance Engine.

The long-term vision is simple:

> **Optiminium makes Minecraft spend its rendering budget where it matters most.**

That is the project's niche, its architecture, and its best path forward.
