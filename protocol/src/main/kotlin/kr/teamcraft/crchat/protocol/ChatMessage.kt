package kr.teamcraft.crchat.protocol

import kotlinx.serialization.Serializable

/**
 * 채팅 메시지 1건. (기획서 3장)
 *
 * 귓속말 상대는 별도 필드로 두지 않는다. `channel = "whisper:<uuid>"` 에서
 * 파생시킨다. 같은 정보를 두 곳에 두면 어긋났을 때 어느 쪽을 믿을지 모호해진다. (3.1)
 */
@Serializable
data class ChatMessage(
    /** 서버 발급 스노우플레이크 (노드 ID 포함, 2.2 참조) */
    val id: Long,
    /** "global" | "guild" | "party" | "whisper:<uuid>" | "system" */
    val channel: String,
    /** 발신자 레지스트리 참조. null 이면 시스템 메시지 */
    val senderId: Int?,
    val segments: List<Segment>,
    val timestamp: Long,
)

/**
 * 발신자 정의. 메시지마다 싣지 않고 처음 등장할 때 1회만 전송한다.
 *
 * [id]는 클라 세션 한정이 아니라 **네트워크 전역**에서 발급된다 — 플레이어 접속 시
 * Redis `INCR` 로 1회 발급한다. 클라마다 로컬로 발급하면 클라별로 id 의미가 달라져서
 * 단일 직렬화(수신자 전원에게 동일 바이트 전송, 12.2(3))가 불가능해진다. (12.2(2))
 *
 * `ChatDecorator` 가 만든 등급·칭호·색은 이미 [displayName] 에 반영되어 있다.
 * 레벨·경지·길드 같은 RPG 개념을 이 클래스의 필드로 만들지 않는다 — CRChat 은
 * RPG 를 모르며, 꾸미기는 전적으로 Decorator 책임이다. (3.1, 15장)
 */
@Serializable
data class SenderDef(
    val id: Int,
    val uuid: String,
    val name: String,
    val displayName: String,
    val prefix: String? = null,
)
