# How Optiminium Improves Block Entity Performance

## Reusing block entity models

The main block entity optimization in Optiminium is **model reuse**.

Normally, Minecraft may rebuild the same model every frame.

For example, imagine a storage room with 100 identical chests. Minecraft may repeatedly process the chest model for every chest, even though most of them look nearly identical.

Optiminium tries to build the chest model once, save it, and reuse it for the other matching chests.

Instead of doing this:

```text
Build chest model
Draw chest

Build chest model again
Draw chest

Build chest model again
Draw chest
```

Optiminium tries to do this:

```text
Build chest model once
Save it

Reuse saved model
Reuse saved model
Reuse saved model
```

Each chest can still appear in a different position, direction, and lighting condition.

This reduces the amount of repeated CPU work needed every frame.

---

## Keeping models ready on the graphics card

After Optiminium creates a reusable model, it can keep that model stored in graphics memory.

This means the game does not have to keep sending the same model to the graphics card every frame.

The graphics card already has the model ready and only needs information such as where the block is, its direction, and light level.  This is especially useful in large storage rooms or modded factories where many block entities use the same model.

---

## Drawing similar block entities together

Optiminium can also group similar block entities together before drawing them.  Minecraft normally processes many block entities one at a time. Constantly switching between models, textures, and rendering settings can add overhead.  Optiminium queues compatible block entities and draws them in groups when possible.  This can reduce repeated graphics settings changes, texture changes, and frametime spikes.  The more repeated block entities there are, the more useful this can become.

---

## Only optimizing when it is worthwhile

Saving and reusing models also has a small performance cost. Optiminium therefore does not automatically cache every block entity it sees.  It looks for groups of repeated block entities where model reuse is likely to save more time than it costs.

Optiminium also measures how expensive normal rendering is compared with its optimized rendering.  When the optimization is not helping, it can leave that block entity on Minecraft’s normal rendering system.

---

## Safe fallback to normal Minecraft rendering

Not every block entity can safely reuse a saved model.

Some block entities:

* Animate
* Change shape
* Change texture
* Display different items
* Use unusual modded rendering
* Update constantly

When Optiminium is not sure that a saved model will look correct, it uses the normal Minecraft renderer instead.

This helps prevent:

* Missing models
* Incorrect textures
* Broken animations
* Visual glitches
* Mod compatibility problems

The optimization is designed to fail safely rather than forcing every block entity through the new system.

---

## Preventing noticeable switching

Changing rendering quality too often could make objects flicker or visibly pop between detail levels.  Optiminium remembers the current quality level for a short time before allowing another change.  This makes transitions more stable when the player moves near a distance boundary.

---

## Where players should notice the biggest improvement

The block entity optimizations are most likely to help in areas such as large chest-monsters, large modded machine rooms, and bases with many banners, skulls, or shulker boxes.

The biggest benefit should usually be lower CPU frame time, which leads to higher fps, lower frame times, and a smoother experience.

