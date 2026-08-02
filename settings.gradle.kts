pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "crchat"

// 순수 로직 모듈. 마인크래프트·Bukkit 의존이 없어서 Loom 없이 초 단위로 테스트가 돈다.
// 부록 B.2 의 테스트 대상 4종 중 3종(HangulComposer·TextSanitizer·기간 파서)이 여기 들어온다.
include("common")

// 채팅 패킷·데이터 모델. 서버·클라 공유. MC 의존 없음 (2.1, 3.2)
include("protocol")

// 외부 플러그인용 공개 API. CRChatApi·GroupProvider·ChatDecorator (4.4, 4.6, 15장)
include("api")

// Paper 플러그인
include("plugin")

// Fabric 클라이언트 모드
include("client")
