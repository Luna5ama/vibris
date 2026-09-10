import buildsrc.convention.transformMarkedRecordConstructors

plugins {
    id("buildsrc.convention.kotlin-jvm")
}

transformMarkedRecordConstructors()

dependencies {
    api(project(":vibris-api"))
    api(project(":vibris-protocol-java"))
    implementation("io.grpc:grpc-netty-shaded:${libs.versions.grpcJava.get()}")
    implementation(libs.kotlinxSerializationJson)
    implementation(libs.commons.compress)
    implementation(libs.zstd.jni)

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
