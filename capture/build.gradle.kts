plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.published-module")
}

dependencies {
    implementation(project(":vibris-api"))
    implementation(project(":vibris-common"))
    implementation(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))
    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-opengl")
    runtimeOnly("org.lwjgl", "lwjgl", classifier = "natives-windows")
    implementation(libs.bundles.glWrapper)
    implementation(libs.kotlinxSerializationCore)
    implementation(libs.kotlinxSerializationJson)
    implementation(libs.kmogus.core)
    implementation(libs.fastutil)

    testImplementation(kotlin("test"))
    testImplementation("org.lwjgl", "lwjgl-glfw")
    testRuntimeOnly("org.lwjgl", "lwjgl-opengl", classifier = "natives-windows")
    testRuntimeOnly("org.lwjgl", "lwjgl-glfw", classifier = "natives-windows")
}

tasks.test {
    systemProperty("vibris.runtimeTest", providers.gradleProperty("vibris.runtimeTest").orElse("false").get())
}
