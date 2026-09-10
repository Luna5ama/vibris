import net.fabricmc.loom.task.prod.ClientProductionRunTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.nio.file.Files

plugins {
    id("java")
    id("fabric-loom")
}

val minecraftVersion = "1.21.11"
val irisVersion = "1.10.6-vibris.1+mc1.21.11"

repositories {
    mavenLocal()
    maven("https://maven.luna5ama.dev")
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

base {
    archivesName.set("vibris-fabric")
}

val runtimeInclude = configurations.create("runtimeInclude") {
    isCanBeConsumed = false
    isCanBeResolved = false
    isTransitive = true
}

configurations.named("includeInternal") {
    extendsFrom(runtimeInclude)
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.18.1")
    modImplementation("net.fabricmc.fabric-api:fabric-command-api-v2:2.2.20+78d798af4f")
    modImplementation("net.fabricmc.fabric-api:fabric-key-binding-api-v1:1.1.7+4fc5413f3e")
    modCompileOnly("net.irisshaders:common:$irisVersion")
    modImplementation(files(rootProject.file("../Iris/custom_sodium/sodium-fabric-0.8.6-SNAPSHOT+mc1.21.11-local.jar")))

    implementation(project(":vibris-mod-common"))

    fun includeModule(projectPath: String) {
        implementation(project(projectPath))
        include(project(projectPath))
    }
    includeModule(":vibris-api")
    includeModule(":vibris-common")
    includeModule(":vibris-capture")
    implementation(project(":vibris-core"))
    runtimeInclude(project(":vibris-core"))
    includeModule(":vibris-protocol-java")

    fun includeLibrary(coordinate: String) {
        implementation(coordinate) { isTransitive = false }
        include(coordinate) { isTransitive = false }
    }
    includeLibrary("dev.luna5ama:kmogus-core:1.1-SNAPSHOT")
    includeLibrary("dev.luna5ama:gl-wrapper-base:1.1.0")
    includeLibrary("dev.luna5ama:gl-wrapper-core:1.1.0")
    includeLibrary("dev.luna5ama:gl-wrapper-lwjgl-3:1.1.0")
    includeLibrary("org.jetbrains:annotations:13.0")
    includeLibrary("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
    includeLibrary("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.21")
    includeLibrary("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.21")
    includeLibrary("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    includeLibrary("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1")
    includeLibrary("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    includeLibrary("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1")
    includeLibrary("org.apache.commons:commons-compress:1.28.0")
    includeLibrary("com.github.luben:zstd-jni:1.5.7-4")
}

loom {
    mixin {
        defaultRefmapName.set("vibris-fabric.refmap.json")
        useLegacyMixinAp = false
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version))
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(project(":vibris-mod-common").sourceSets.main.get().output)
}

tasks.register<ClientProductionRunTask>("runVibrisAutomationClient") {
    description = "Runs separate Iris and Vibris JARs in an isolated automation game directory."
    group = "verification"
    val irisJar = providers.gradleProperty("automationIrisJar")
    val vibrisJar = providers.gradleProperty("automationVibrisJar")
    val gameDirectory = providers.gradleProperty("automationGameDir")
    val runId = providers.gradleProperty("automationRunId")
    val scenario = providers.gradleProperty("automationScenario")
    doFirst {
        val game = file(gameDirectory.get())
        val pending = game.resolve("vibris/pending")
        val artifacts = game.resolve("vibris/artifacts")
        val shaderpack = game.resolve("shaderpacks/vibris")
        listOf(pending, artifacts, shaderpack).forEach { Files.createDirectories(it.toPath()) }
        val serverConfig = game.resolve("config/vibris/server.json")
        Files.createDirectories(serverConfig.parentFile.toPath())
        fun jsonPath(value: File): String = value.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        serverConfig.writeText(
            """
            {
              "schema_version": 1,
              "listen_address": "127.0.0.1:50051",
              "pending_shaders_root": "${jsonPath(pending)}",
              "artifact_root": "${jsonPath(artifacts)}",
              "artifact_quota_bytes": 3221225472,
              "shaderpack_root": "${jsonPath(shaderpack)}",
              "max_source_bytes": 536870912,
              "max_source_files": 100000,
              "max_global_queue": 32,
              "max_actions_per_job": 64
            }
            """.trimIndent()
        )
    }
    mods.from(irisJar.map(::file), vibrisJar.map(::file))
    runDir.set(layout.dir(gameDirectory.map(::file)))
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    jvmArgs.set(runId.zip(scenario) { id, selectedScenario ->
        listOf("-Dvibris.automation.runId=$id") + if (selectedScenario == "g008-c003") {
            listOf("-Dio.grpc.netty.shaded.io.netty.allocator.type=unpooled")
        } else emptyList()
    })
    programArgs.set(gameDirectory.map { game ->
        listOf("--gameDir", file(game).absolutePath, "--quickPlaySingleplayer", "vibris-automation-world")
    })
}
