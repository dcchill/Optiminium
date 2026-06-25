# Memory-Aware Visual Significance Engine

## Overview

The second-generation Visual Importance Engine fundamentally changes how Optiminium evaluates rendering significance. Instead of asking *"How important is this object right now?"* it asks *"How important is this object likely to be over the next second?"*

Objects have **memory**. They track their own history, accumulate attention over time, and resist rapid quality oscillations. This is inspired by Unreal Engine's Significance Manager, Frostbite's temporal re-projection, and Factorio's UPS-conscious entity budgeting.

## Core Concepts

### 1. Temporal Object Memory (`ObjectMemory`)

Every tracked block entity gets a persistent `ObjectMemory` instance stored in a `Long2ObjectOpenHashMap` keyed by BlockPos. This memory tracks:

| Field | Purpose |
|---|---|
| `historicalImportance` | EWMA-smoothed importance (85% old / 15% new each frame) |
| `confidence` | How sure we are about the classification (0.0–1.0) |
| `attentionScore` | Accumulated attention from looking/being nearby |
| `predictedImportance` | Projected importance for the next ~10 frames |
| `predictedClassification` | Projected significance band |
| `lastChangedFrame` | When the band last changed (for cooldown) |
| `stableBandFrames` | How long the object has stayed in its current band |
| `lookedAtStreak` | Consecutive frames the player looked at it |
| `predictionCorrectFrames` | How often prediction matched actual classification |

Memory is cached incrementally — no per-frame scanning of all objects. Stale entries (unseen for 200+ frames) are cleaned up every 60 frames.

### 2. Camera Motion Tracking

The engine now tracks camera dynamics continuously:

- **Velocity** (blocks/frame): dx, dz, magnitude
- **Rotation speed** (degrees/frame): combined yaw+pitch delta
- **Acceleration**: rate of velocity change
- **Stability flag**: true when position change < 0.01 blocks AND rotation < 0.5 degrees
- **Fast-moving flag**: true when velocity > 0.5 blocks/frame (sprinting, elytra) OR rotation > 10 degrees/frame (quick turns)

These drive prediction and adaptive thresholds without per-object overhead.

### 3. Attention Accumulation and Decay

Instead of binary "looked at / not looked at," objects accumulate attention:

- **Gain**: +0.12/frame when directly looked at, +0.03/frame when in front and nearby
- **Decay**: 0.008 + 0.0001×distance per frame when not looked at
- **Cap**: [0.0, 1.0]

This prevents brief glances from triggering full quality on distant objects, while ensuring sustained attention keeps important objects visible.

### 4. Prediction (Camera Velocity Projection)

Objects project their position 10 frames ahead using camera velocity:

```
predictedDx = currentDx - cameraVelocityX × 10 × 0.05
predictedDz = currentDz - cameraVelocityZ × 10 × 0.05
```

If the projected position is behind the camera, the object is predicted to go off-screen soon and gets demoted. If it remains in front, the projected distance computes a predicted score weighted 60/40 with historical importance.

Prediction accuracy feeds back into confidence: when predictions are consistently correct, the engine trusts its classification more and resists oscillation.

### 5. Hysteresis (5 Rules)

Band changes are resisted to prevent quality popping:

| Rule | Condition | Effect |
|---|---|---|
| 1 | Transition cooldown (8 frames) | Stay in current band unless change > 1 level |
| 2 | High confidence + prediction matches current | Resist change unless score delta > 0.15 |
| 3 | Recently promoted to FULL | Hold FULL for 12 frames |
| 4 | Recently demoted to CULLED | Hold CULLED for 12 frames |
| 5 | Fast camera + distant | Resist upgrading objects beyond THROTTLED distance |

### 6. Confidence Scoring

Confidence combines multiple signals:

- **Base**: 0.5 (moderate uncertainty by default)
- **Prediction bonus**: up to +0.3 from correct prediction streak
- **Stability bonus**: up to +0.25 from staying in same band
- **Attention bonus**: attentionScore × 0.15
- **Fast-motion penalty**: -0.15 when camera is moving fast

Final confidence: [0.05, 1.0]

### 7. Visual Momentum

When the camera moves fast (elytra, sprinting, quick turns), the engine defers evaluation of distant objects:

- Objects beyond THROTTLED distance with confidence < 0.5 get CULLED during fast movement
- Objects at moderate distance resist UPGRADING during fast movement
- Fast-camera culled objects are tracked in `culledBecauseFastCamera`

This prevents the engine from wasting CPU on objects that will be out of view in 2–3 frames anyway.

## New Reason Counters

| Counter | Meaning |
|---|---|
| `fullBecauseHighConfidence` | Object held at FULL because confidence > 0.8 and stable for hysteresis period |
| `throttledBecausePredictedLow` | Object throttled because predicted importance < 0.4 |
| `reusedBecauseHysteresis` | Object held at REUSED due to high confidence + stability |
| `proxyBecauseHighConfidence` | Object at PROXY with high confidence (stable, well-understood) |
| `culledBecausePredictedOffscreen` | Culled because prediction + confidence suggests imminent off-screen |
| `culledBecauseLowConfidencePromotable` | Culled due to low confidence + not fast-moving (could be promoted with more data) |
| `culledBecauseFastCamera` | Deferred due to fast camera movement (defer processing distant objects) |

## Overhead Strategy

The engine maintains < 0.04 ms/frame through:

- **Incremental updates**: memory per-object is updated only when the object is recorded
- **No per-frame scans**: stale cleanup runs every 60 frames, not every frame
- **Constant-time data structures**: `Long2ObjectOpenHashMap` for memory, `Long2ByteOpenHashMap` for classifications
- **Simple math**: no trig functions in hot path; `Math.sqrt` only for distance checks (already existed)
- **Camera tracking**: 6 subtractions + 2 multiplies per frame, independent of object count
- **Memory cap**: `MAX_OBJECT_MEMORY = 4096` prevents unbounded growth

## Performance Targets

- **Average CPU overhead**: < 0.04 ms/frame (verified by existing profiling counters)
- **Classification stability**: < 1% of objects per frame should change bands under stable camera
- **Prediction accuracy**: > 70% of predictions should match actual classification
- **Memory footprint**: < 256 KB for `ObjectMemory` entries (4096 × 64 bytes ≈ 256 KB)