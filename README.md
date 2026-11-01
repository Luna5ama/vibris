# Vibris

Vibris captures OpenGL compute workloads for portable replay on OpenGL or Vulkan, with MCP-based capture and shader debugging for Minecraft shaderpack development.

## Attribution

Portions of Vibris are derived from [Viewfinder](https://github.com/xirreal/viewfinder), Copyright 2026 xirreal,
and are used under the MIT License. The full attribution and license text are included in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Components

- `common`: capture format, I/O, replay options, and shader-source resolution
- `capture`: OpenGL capture hooks, resource inspection, and shader patching
- `replay-gl`: OpenGL 4.6 replay executable
- `replay-vk`: Vulkan 1.4 replay executable
- `mcp`: native MCP server for Iris shader testing and artifact capture

## Requirements

- Windows for the packaged replay and MCP binaries
- Java 21 for the shared JVM modules
- Java 24 for `replay-gl` and `replay-vk`
- An OpenGL 4.6-capable GPU for OpenGL replay and runtime tests
- A Vulkan 1.4-capable GPU and driver for Vulkan replay
- CMake 3.25+, Visual Studio 2022, and vcpkg for the native MCP server

Set `VCPKG_ROOT` before configuring the native MCP build. The MCP/Iris integration also requires a sibling Iris
checkout.

## Build

Build the JVM modules and run the default tests with Gradle:

```powershell
.\gradlew.bat build
.\gradlew.bat test
```

Build the matched Fabric runtime and sibling Iris distribution with the release version tuple:

```powershell
.\gradlew.bat '-Pbuild.release=true' :vibris-mod-fabric:build :Iris:fabric:remapJar
```

The required Iris version is pinned once in `gradle.properties` (`vibris.irisVersion`) and expanded into
Fabric metadata. The current tuple requires Iris `1.10.6-vibris.3+mc1.21.11`, which supplies shadow-only
terrain task submission and readiness. Both modules consume the included Iris checkout's `namedElements`
compile variant; a cached remap of an older development JAR must not substitute for current source.

Build the optimized replay executables:

```powershell
.\gradlew.bat :vibris-replay-gl:assemble
.\gradlew.bat :vibris-replay-vk:assemble
```

The optimized JARs are written to:

```text
replay-gl/build/libs/vibris-replay-gl-*-fatjar-optimized.jar
replay-vk/build/libs/vibris-replay-vk-*-fatjar-optimized.jar
```

Build and test the native MCP server:

```powershell
Set-Location mcp
cmake --preset windows-vs2022
cmake --build --preset release
ctest --preset release
```

The release binary is `mcp/out/build/Release/vibris-mcp.exe`.

## Replay a capture

Both replay JARs accept a capture directory as their first argument. An optional numeric argument limits the number of
frames. Shader source overrides can be supplied with `--shader-path` and repeated `--shader-pass` options.

```powershell
java -jar replay-gl/build/libs/<path-to-replay-gl-jar> <capture-dir> [frame-limit]
java -jar replay-vk/build/libs/<path-to-replay-vk-jar> <capture-dir> [frame-limit]
```

Enable Vulkan validation when needed:

```powershell
java -Dvibris.validation=true -jar replay-vk/build/libs/<path-to-replay-vk-jar> <capture-dir>
```

## Iris MCP integration

See [docs/capture-control.md](docs/capture-control.md) for the complete MCP build, packaging, server configuration,
shader profiling, capture recipes, artifacts, and troubleshooting guide.

For normal Codex use, track `.codex/config.toml` in each shader repository and point it at the packaged
`build\delivery\vibris-mcp.exe`. Leave `cwd` unset so each Codex task discovers its own Git worktree from the task cwd;
this supports concurrent tasks, linked worktrees, and independent shader repositories.
