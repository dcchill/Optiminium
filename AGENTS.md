# Repository Guidelines

## Project Structure & Module Organization

Optiminium is a Java 21 NeoForge 1.21.1 client optimization mod. Production code lives in `src/main/java/net/optiminium`: renderer and GPU systems are under `client/`, mixin hooks under `mixin/`, compatibility code under `compat/`, and settings/metrics under `optimization/`. Persistent-render adapters live in `client/persistence/`. Mod metadata, mixin configuration, translations, and GLSL shaders are in `src/main/resources/`. Unit tests mirror the production package in `src/test/java/net/optiminium/client/`.

Treat `build/`, `.gradle/`, and `run/` as generated or local-only. In particular, `run/` contains development worlds, logs, configuration, screenshots, and benchmark reports. Files under the root-level `net/`, `com/`, and `native/` directories are reference/support material; do not modify them unless the task specifically targets them.

## Build, Test, and Development Commands

Use the included Gradle wrapper from the repository root:

- `./gradlew build` (`.\gradlew.bat build` on Windows): compile, test, and package the mod.
- `./gradlew test`: run the JUnit test suite.
- `./gradlew runClient`: launch the NeoForge development client using `run/`.
- `.\gradlew.bat runClient "-Poptiminium.quickPlayWorld=hermitcraft9" "-Poptiminium.autoBenchmark"`: launch directly into a world and run the normal OFF/ON benchmark.

Do not add `-Poptiminium.persistenceOnlyBenchmark` when measuring complete Optiminium behavior; it intentionally isolates a persistence family.

## Coding Style & Naming Conventions

Follow the existing Java style: tabs for indentation, opening braces on the declaration line, UTF-8 source, and explicit imports. Use `UpperCamelCase` for classes/records, `lowerCamelCase` for methods and fields, and `UPPER_SNAKE_CASE` for constants. Prefix mod-owned Minecraft integration classes with `Optiminium`; name mixins after their target, such as `ItemFrameRendererMixin`. Keep hot render paths allocation-free where practical and document non-obvious compatibility or exactness constraints. No automatic formatter or linter is configured, so match nearby code.

## Testing Guidelines

Tests use JUnit Jupiter 5.10.2. Name test classes `*Test.java` and keep deterministic policy, key, budgeting, and resource-lifecycle tests beside related packages. Run `./gradlew test` for every change; use `build` for final verification. Rendering changes should also include a fixed-camera OFF/ON benchmark and visual comparison when applicable.

## Commit & Pull Request Guidelines

Recent commits use short, imperative summaries (for example, `Implemented adaptive block-entity persistence`). Keep each commit focused and explain performance-sensitive tradeoffs in the body. Pull requests should describe behavior, compatibility fallbacks, tests run, and benchmark conditions/results. Link relevant issues and attach screenshots or reports for visible rendering changes. Never commit local worlds, logs, or machine-specific configuration.
