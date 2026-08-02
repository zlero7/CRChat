package kr.teamcraft.crchat.api

import kr.teamcraft.crchat.protocol.ChatMessage
import org.bukkit.entity.Player

/**
 * 발신자 태그 꾸미기 확장점. (기획서 15장)
 *
 * CRChat 은 CRRPGCore 를 참조하지 않는다 — 반대로 CRRPGCore 가 설치되어 있으면
 * 이 인터페이스를 구현해 레벨·경지·길드 태그를 붙인다. 없으면 CRChat 기본
 * 포맷으로 동작한다. 결과는 [SenderDef.displayName] 에 반영되므로, ChatMessage
 * 자체에는 RPG 개념을 담는 필드를 두지 않는다.
 */
interface ChatDecorator {
    fun decorate(sender: Player, message: ChatMessage): ChatMessage
}
