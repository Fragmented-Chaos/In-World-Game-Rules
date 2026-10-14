package com.fragmentedchaos.inworldgamerules.mixin;

import com.fragmentedchaos.inworldgamerules.client.RemoteGameRules;
import com.fragmentedchaos.inworldgamerules.network.GameRulesChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接收服务端下发的全量规则（1.20.1 没有官方同步包，等价 26.x 的
 * {@code ClientboundGameRuleValuesPacket} 处理）。
 *
 * <p>HEAD 注入跑在 netty IO 线程（原版在同一方法体内才切主线程），所以这里只做"读走缓冲区"这一件
 * 必须在当前线程完成的事，随后 {@code Minecraft.execute} 切回主线程再动
 * {@link RemoteGameRules} 的状态 —— 否则它的静态字段会与 tick 线程形成数据竞争。
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void inwr$receiveGameRuleValues(ClientboundCustomPayloadPacket packet, CallbackInfo callbackInfo) {
        if (!GameRulesChannel.ID.equals(packet.getIdentifier())) {
            return;
        }
        String data;
        try {
            if (packet.getData().readableBytes() <= 0 || packet.getData().readableBytes() > GameRulesChannel.MAX_PAYLOAD) {
                return;
            }
            data = packet.getData().readUtf(32767);
        } catch (Exception e) {
            return;   // 畸形载荷：静默忽略，绝不把异常抛到 netty 线程
        }
        if (!GameRulesChannel.matches(data, GameRulesChannel.VALUES)) {
            callbackInfo.cancel();
            return;   // 通道对、类型/协议号不对：吃掉，不交给原版
        }
        callbackInfo.cancel();
        String body = GameRulesChannel.body(data);
        Minecraft.getInstance().execute(() -> RemoteGameRules.acceptSync(body));
    }
}
