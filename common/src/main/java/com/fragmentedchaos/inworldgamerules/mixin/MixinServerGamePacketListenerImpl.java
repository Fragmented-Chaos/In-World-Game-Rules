package com.fragmentedchaos.inworldgamerules.mixin;

import com.fragmentedchaos.inworldgamerules.network.GameRuleSyncServer;
import com.fragmentedchaos.inworldgamerules.network.GameRulesChannel;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端侧接住自定义载荷（26.x 这一步由官方包和服务器网络层完成）：
 * 请求 → 回全量快照；设置 → 校验权限后应用。权限不足静默忽略，等价原版 {@code /gamerule} 的权限门。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void inwr$handleRuleSync(ServerboundCustomPayloadPacket packet, CallbackInfo callbackInfo) {
        if (!GameRulesChannel.ID.equals(packet.getIdentifier())) {
            return;
        }
        ServerPlayer serverPlayer = this.player;
        if (serverPlayer == null || !serverPlayer.hasPermissions(2)) {
            return;
        }
        String data = packet.getData().readUtf(32767);
        if (data.isEmpty()) {
            return;
        }
        callbackInfo.cancel();
        if (data.charAt(0) == GameRulesChannel.REQUEST) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeUtf(GameRulesChannel.VALUES + GameRuleSyncServer.snapshot(serverPlayer.server));
            serverPlayer.connection.send(new ClientboundCustomPayloadPacket(GameRulesChannel.ID, buf));
        } else if (data.charAt(0) == GameRulesChannel.SET) {
            GameRuleSyncServer.apply(serverPlayer.server, data.substring(1));
        }
    }
}
