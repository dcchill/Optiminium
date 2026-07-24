## Optiminium

Optiminium is a Minecraft 1.21.1 Fabric client-side optimization mod. It targets
frame-pacing problems that remain after renderer optimizers such as Sodium,
with an emphasis on smoother 1% lows in modded and high-density vanilla worlds.

## Building

Optiminium requires Java 21. The wrapper is configured to discover or provision
an Adoptium Java 21 daemon automatically, even if `JAVA_HOME` points to an older
JDK. Build and test it with:

```powershell
.\gradlew.bat test build
```

The remapped Fabric mod JAR is written to `build/libs/optiminium-1.0.jar`.
