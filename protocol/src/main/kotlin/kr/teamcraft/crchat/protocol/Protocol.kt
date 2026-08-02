package kr.teamcraft.crchat.protocol

/**
 * 서버·클라가 이 모듈을 함께 의존하므로 버전 상수의 단일 출처가 된다.
 * 서버·클라가 각자 들고 있으면 반드시 어긋난다 (기획서 3.2 핸드셰이크 절차).
 */
const val PROTOCOL_VERSION: Int = 1

/** Bukkit 플러그인 메시지 / Fabric CustomPayload 채널 ID. (기획서 2.1, 3.2) */
const val CHANNEL_ID: String = "crchat:msg"
