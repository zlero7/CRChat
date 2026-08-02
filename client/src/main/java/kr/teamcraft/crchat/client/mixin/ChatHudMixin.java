package kr.teamcraft.crchat.client.mixin;

import kr.teamcraft.crchat.client.state.ChatState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 바닐라 ChatHud 를 실제로 가로챈다 (기획서 4.7).
 *
 * <p>렌더는 완전히 지운다 — CRChat 의 오버레이가 대신 그린다. 내용(접속 알림·명령어
 * 피드백 등)은 잃지 않고 {@link ChatState} 로 복사해 "시스템" 채널로 보여준다.
 *
 * <p><b>Java 로 쓴 이유</b>: Kotlin 소스는 Loom 의 Mixin 어노테이션 프로세서(리프맵
 * 생성)를 안 거친다. 리프맵이 없으면 이 클래스의 @Inject 대상 문자열이 named 형태
 * 그대로 남아 실제 게임(중간 매핑 런타임)에서 대상을 못 찾고 그대로 크래시난다 —
 * 처음에 Kotlin 으로 썼다가 이 문제로 다시 옮겼다.
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void crchat_cancelRender(
            DrawContext context, int currentTick, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void crchat_captureSimple(Text message, CallbackInfo ci) {
        ChatState.INSTANCE.addVanillaMessage(message.getString());
    }

    @Inject(
            method = "addMessage(Lnet/minecraft/text/Text;"
                    + "Lnet/minecraft/network/message/MessageSignatureData;"
                    + "Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"))
    private void crchat_captureSigned(
            Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        ChatState.INSTANCE.addVanillaMessage(message.getString());
    }
}
