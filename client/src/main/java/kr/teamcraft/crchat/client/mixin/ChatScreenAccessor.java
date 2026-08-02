package kr.teamcraft.crchat.client.mixin;

import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * `ChatScreen.originalChatText` 는 private 라 getter 가 없다. 리플렉션으로 이름
 * 문자열("originalChatText")을 그대로 넘기면 실제 게임(중간 매핑)에서는 그 이름이
 * 존재하지 않아 조용히 실패한다 — Mixin Accessor 는 @Inject 처럼 빌드 시점에
 * 정확히 리매핑되므로 이 방식이 맞다.
 */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {
    @Accessor("originalChatText")
    String crchat_getOriginalChatText();
}
