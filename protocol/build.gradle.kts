plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // api 로 노출한다 — 이 모듈을 쓰는 쪽(plugin·client·api)은 전부
    // ChatMessage 등을 직렬화/역직렬화해야 하므로 kotlinx.serialization 이 항상 필요하다.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
