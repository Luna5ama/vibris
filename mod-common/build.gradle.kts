plugins {
    id("java")
    id("fabric-loom")
}

val minecraftVersion = "1.21.11"
val irisVersion = providers.gradleProperty("vibris.irisVersion").get()

repositories {
    mavenLocal()
    maven("https://maven.luna5ama.dev")
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modCompileOnly("net.fabricmc:fabric-loader:0.18.1")
    // Consume the included checkout's named source variant, not Loom's cached remap of
    // an earlier JAR published under the same development coordinate.
    compileOnly("net.irisshaders:common:$irisVersion") { targetConfiguration = "namedElements" }
    modCompileOnly(files(rootProject.file("../Iris/custom_sodium/sodium-fabric-0.8.6-SNAPSHOT+mc1.21.11-local.jar")))

    implementation(project(":vibris-api"))
    implementation(project(":vibris-capture"))
    implementation(project(":vibris-core"))
    implementation(project(":vibris-protocol-java"))
    compileOnly(libs.bundles.glWrapper)

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("net.irisshaders:common:$irisVersion") { targetConfiguration = "namedElements" }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom {
    mixin {
        defaultRefmapName.set("vibris-common.refmap.json")
        useLegacyMixinAp = false
    }
}

tasks.test {
    useJUnitPlatform()
}
