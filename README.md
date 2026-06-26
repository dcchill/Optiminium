## Optiminium

Optiminium is a client side mod for optimizing problems that remain after other mods like Sodium and Embeddium to make the game feel smoother and fix 1% lows and choppiness. Modded worlds and high density vanilla worlds are the main focus.

Instead of re-inventing the wheel, Optiminium creates a high level management layer that will decide which hard to render objects deserve to be in full fidelity. Its central idea is the **Visual Significance Engine**: a lightweight system that evaluates objects using screen coverage, camera attention, render cost, confidence, and safety rules.

This makes Optiminium a companion rather than a competitor. It avoids replacing Sodium and instead focuses on block entities, entities, particles, render-states, upload scheduling, and dense-scene behavior.

Recent benchmarks show meaningful improvements in average FPS, 1% lows, worst-frame FPS, GPU frame time, and CPU frame time.

The long term goal is to make Minecraft's rendering workload behave like modern rendering engines, with the least amount impact on visuals.

## The Problem
A dense modded base can include thousands of visually active objects:

-   chests, barrels, signs, beds, and vanilla block entities
    
-   Create-style shafts, belts, encased fans, pumps, and contraptions
    
-   Mekanism machines and cables
    
-   AE2 or Refined Storage terminals, buses, and dense networks
    
-   particles, smoke, sparks, effects, and visual feedback
    
-   animated entities, villagers, animals, item entities, and display-style objects
    
-   shader-sensitive translucent or emissive renderers
    

Even when the GPU is capable of drawing the scene, the render thread can still suffer from:

-   renderer lookup overhead
    
-   block entity renderer preparation
    
-   matrix and lighting setup
    
-   texture binds
    
-   shader binds
    
-   buffer uploads
    
-   driver synchronization
    
-   bursty rebuilds or upload admissions
    
-   repeated per-frame work for objects the player barely notices

## Visual Significance Engine
The Visual Significance Engine is Optiminiums way of deciding if entities are worth rendering.

Instead of treating every visible object like it deserves the same amount of effort, Optiminium tries to figure out what the player is actually going to notice. A chest right in front of you matters.  A sheep walking nearby probably matters. But a tiny barrel across the base, a barely visible particle effect, or some random far-away detail probably does not need full-quality work every single frame.

So instead of asking only if the object is within sim distance, it tries to figure out how important that object is to the "scene".

The engine looks at things like distance, screen size, whether the player is looking at it, whether it was recently visible, whether it is alive or important, how expensive it is to render, and how confident Optiminium is that it is safe to optimize.

It then rates the object into these categories.

-   FULL → render normally 
-   THROTTLED → update less often 
-   REUSED → reuse the last visual state 
-   PROXY → use a cheaper version later 
-   CULLED → skip it when it is safe

The important part is that Optiminium is not trying to make everything disappear. That would technically improve FPS, but it would feel awful. The goal is to make the scene cheaper while keeping it visually stable.

For example, if a machine is far away and not changing much, Optiminium might reuse its last visual state instead of fully updating it every frame. The player still sees the machine, but Minecraft does less work. If the player walks closer or looks directly at it, Optiminium can promote it back to full quality.

## Benchmarks
The most recent stable build benchmark gave these results on the Hermitcraft 9 world download as a test.  This was tested on a i7 - 13700H with the Iris XE iGPU.

| Metric | OFF | ON |Percent Change|
|---|---:|---:|---|
| Average FPS | 58.5 | 64.1 | 9.8 % higher
| 1% low FPS | 22.2 | 31.8 |43.0 % higher
| Worst-frame FPS | 16.9 | 29.1 |72.20 % higher
| Average GPU ms | 10.94 | 10.39 | 5.02 % faster
| Average CPU ms | 17.11 | 15.60 | 8.82 % faster
| Optiminium CPU ms | 0.153 | 0.169 | 10.45 % faster
| Worst Optiminium CPU ms | 0.958 | 0.612 | 36.11 % faster



