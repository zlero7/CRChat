plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(project(":protocol"))
    implementation(project(":api"))
    implementation(project(":common")) // TextSanitizer (7.4)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // 다른 플러그인이 자기 Kotlin·kotlinx.serialization 사본을 번들해도 충돌하지
    // 않도록 우리 사본만 격리한다. (2.1 신뢰 경계와는 별개의 순수 빌드 이슈)
    relocate("kotlin", "kr.teamcraft.crchat.libs.kotlin")
    relocate("kotlinx.serialization", "kr.teamcraft.crchat.libs.kotlinx.serialization")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
