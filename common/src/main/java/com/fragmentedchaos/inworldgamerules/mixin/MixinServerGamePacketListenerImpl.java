package com.fragmentedchaos.inworldgamerules.mixin;

import java.util.Map;
import com.fragmentedchaos.inworldgamerules.network.GameRuleSyncServer;
import com.fragmentedchaos.inworldgamerules.network.GameRulesChannel;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端侧接住自定义载荷（26.x 这一步由官方包和服务器网络层完成）：
 * 请求 → 回全量快照；设置 → 校验权限后应用。
 *
 * <p>原版这个方法体内<strong>没有</strong>线程切换（它是个空方法），所以读走载荷后必须自己
 * {@code server.execute} 排到服务端线程再动权威 {@code GameRules}（{@code setFrom} 会触发 onChanged，
 * 部分规则会广播包或读服务端状态）。权限不足静默忽略，等价原版 {@code /gamerule} 的权限门。
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
        String data;
        try {
            int readable = packet.getData().readableBytes();
            if (readable <= 0 || readable > GameRulesChannel.MAX_PAYLOAD) {
                return;
            }
            data = packet.getData().readUtf(32767);
        } catch (Exception e) {
            return;
        }
        callbackInfo.cancel();
        MinecraftServer server = serverPlayer.server;

        if (GameRulesChannel.matches(data, GameRulesChannel.REQUEST)) {
            server.execute(() -> {
                Map<String, String> snapshot = GameRulesChannel.decode(GameRuleSyncServer.snapshot(server));
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeUtf(GameRulesChannel.values(snapshot));
                serverPlayer.connection.send(new ClientboundCustomPayloadPacket(GameRulesChannel.ID, buf));
            });
        } else if (GameRulesChannel.matches(data, GameRulesChannel.SET)) {
            String json = GameRulesChannel.body(data);
            server.execute(() -> GameRuleSyncServer.apply(server, serverPlayer, json));
        }
    }
}
