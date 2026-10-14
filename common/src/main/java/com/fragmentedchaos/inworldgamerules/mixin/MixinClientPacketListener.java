package com.fragmentedchaos.inworldgamerules.mixin;

import com.fragmentedchaos.inworldgamerules.client.RemoteGameRules;
import net.minecraft.client.multiplayer.ClientPacketListener;
import com.fragmentedchaos.inworldgamerules.network.GameRulesChannel;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 收集 {@code /gamerule} 的答复：服务端逐条回给命令发起者，我们在显示前截下来装进
 * {@link RemoteGameRules}，因此聊天栏里不会刷出一大堆规则列表。
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    /**
     * 接收服务端下发的全量规则（1.20.1 没有官方同步包，等价 26.x 的
     * {@code ClientboundGameRuleValuesPacket} 处理）。
     */
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void inwr$receiveGameRuleValues(ClientboundCustomPayloadPacket packet, CallbackInfo callbackInfo) {
        if (!GameRulesChannel.ID.equals(packet.getIdentifier())) {
            return;
        }
        String data = packet.getData().readUtf(32767);
        callbackInfo.cancel();
        if (!data.isEmpty() && data.charAt(0) == GameRulesChannel.VALUES) {
            RemoteGameRules.acceptSync(data.substring(1));
        }
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void inwr$captureGameRuleReply(ClientboundSystemChatPacket packet, CallbackInfo callbackInfo) {
        if (RemoteGameRules.consume(packet.content())) {
            callbackInfo.cancel();
        }
    }
}
