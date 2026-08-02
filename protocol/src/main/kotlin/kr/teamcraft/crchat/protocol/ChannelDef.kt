package kr.teamcraft.crchat.protocol

import kotlinx.serialization.Serializable

/**
 * 채널 가용성·표시 정보. 핸드셰이크(`SESSION_INIT`)로 내려받는다.
 *
 * 길드 탭 숨김/파티 탭 회색 같은 분기를 클라에 하드코딩하지 않는다 — 서버가
 * 이 목록으로 알려주면 클라는 그대로 그리기만 한다. (4.6)
 */
@Serializable
data class ChannelDef(
    val id: String,
    val display: String,
    /** null 이면 발행 플러그인이 색을 직접 지정한다 (system 채널, 4.3) */
    val color: String?,
    val available: Boolean,
)

/** 활성 뮤트 상태 1건. (7.2) */
@Serializable
data class MuteInfo(
    /** null 이면 전체 채널 뮤트 */
    val channel: String?,
    /** epoch millis. null 이면 영구 */
    val until: Long?,
    val reason: String,
)
