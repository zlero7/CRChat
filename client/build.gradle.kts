plugins {
    id("fabric-loom")
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

// 기획서 10장·13장 로드맵과 확정한 타깃 (Fabric 1.21.4 / Java 21)
val minecraftVersion = "1.21.4"
val yarnMappings = "1.21.4+build.8"
val loaderVersion = "0.19.3"
val fabricApiVersion = "0.119.4+1.21.4"
val flkVersion = "1.13.13+kotlin.2.4.10"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Kotlin stdlib·coroutines·kotlinx.serialization 런타임을 이미 번들한다.
    // 그래서 모드 쪽에서 kotlin·kotlinx.serialization 을 따로 shade 하지 않는다 —
    // 전용 런처 배포라 별도 모드 배포 부담이 없으므로 FLK 의존이 더 낫다.
    modImplementation("net.fabricmc:fabric-language-kotlin:$flkVersion")

    // protocol·common 은 순수 Kotlin(비-모드) 라이브러리다. Fabric Loader 의 Jar-in-Jar 로
    // 모드 jar 안에 그대로 넣는다 — 리매핑 대상이 아니므로 modImplementation 이 아니라
    // 일반 project 의존 + include() 로 충분하다.
    include(implementation(project(":protocol"))!!)
    // 자체 한글 조합기(HangulComposer, Dubeolsik) — 부록 A. 입력창에서 직접 쓴다.
    include(implementation(project(":common"))!!)

    // 우측 Alt 한/영 전환(부록 A.9 참고 — ImmAssociateContext 로 게임 창의 OS IME 연결을
    // 끊어야 Windows 가 그 키를 언어 전환 단축키로 가로채지 않는다). JNA 로 imm32.dll 을
    // 직접 호출한다 — 네이티브 DLL을 우리가 새로 컴파일할 필요가 없다.
    include(implementation("net.java.dev.jna:jna:5.14.0")!!)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
