plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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

    // 크로스서버 채팅 연동(네트워크, 13장 Phase 8) — MySQL 로 다른 서버가 보낸 메시지를
    // 릴레이 테이블에서 폴링해온다. Redis 대신 MySQL 을 쓰는 이유는 이 프로젝트가 이미
    // docker-compose 로 MySQL 을 갖추고 있어서다.
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
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
    relocate("com.zaxxer.hikari", "kr.teamcraft.crchat.libs.hikari")
    relocate("com.mysql", "kr.teamcraft.crchat.libs.mysql")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
