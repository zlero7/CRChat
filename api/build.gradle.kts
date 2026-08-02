plugins {
    kotlin("jvm")
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

    // ChatDecorator 가 ChatMessage 를 주고받으므로 api 로 노출한다.
    // CRRPGCore 같은 외부 플러그인이 이 모듈에 의존하면 protocol 도 함께 딸려온다.
    api(project(":protocol"))
}
