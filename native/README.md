# Optiminium Native Frame Generation Bridge

The Java mod now looks for a native library named `optiminium_fg_bridge`.

This bridge is the correct place for real frame generation work. The Java side can only provide:

- the Minecraft window handle,
- the current OpenGL context handle,
- the current main color texture ID,
- the framebuffer size,
- a frame timestamp.

The native side must own any lower-level presentation, OpenGL interop, swap-chain interception, or Vulkan/DX-style frame-generation backend.

Expected Java entry points are declared in:

`src/main/java/net/optiminium/client/OptiminiumNativeFrameGenerationBridge.java`

Current JNI methods:

- `nativeGetName()`
- `nativeIsAvailable()`
- `nativeAttach(long windowHandle, long glContext, int width, int height)`
- `nativeResize(int width, int height)`
- `nativeSubmitOpenGlFrame(int colorTexture, int width, int height, long timestampNanos)`
- `nativeDetach()`

On Windows, the library file should be available as `optiminium_fg_bridge.dll` on `java.library.path`.
