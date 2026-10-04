// Dormant NeoForge adapter skeleton. This directory is intentionally excluded from settings.gradle.kts
// until the Fabric integration surface is stable and a matching Iris NeoForge build is enabled.
plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.107"
}

base {
    archivesName.set("vibris-neoforge")
}

repositories {
    mavenLocal()
    maven("https://maven.neoforged.net/releases/")
    mavenCentral()
}

neoForge {
    version = "21.11.0-beta"
    mods {
        create("vibris") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    compileOnly(project(":vibris-mod-common"))
    compileOnly("net.irisshaders:common:1.10.6-vibris.2+mc1.21.11")
}

tasks.withType<JavaCompile>().configureEach {
    source(project(":vibris-mod-common").sourceSets.main.get().allSource)
}

tasks.withType<ProcessResources>().configureEach {
    from(project(":vibris-mod-common").sourceSets.main.get().resources)
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
