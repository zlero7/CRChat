plugins {
    // fabric-language-kotlin 1.13.13 이 Kotlin 2.4.10 을 번들한다.
    // 전 모듈을 이 버전에 맞춰야 client 모듈에서 stdlib 버전이 어긋나지 않는다.
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("fabric-loom") version "1.17.17" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
}

allprojects {
    group = "kr.teamcraft.crchat"
    version = "0.1.0-SNAPSHOT"
}
