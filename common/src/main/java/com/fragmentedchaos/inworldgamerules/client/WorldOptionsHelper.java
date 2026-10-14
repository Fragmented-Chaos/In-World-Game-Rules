package com.fragmentedchaos.inworldgamerules.client;

import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.fragmentedchaos.inworldgamerules.network.GameRuleSyncServer;
import net.minecraft.world.level.GameRules;

/** 「世界选项」的动作层。只依赖原版 API，因此 Fabric / Forge / NeoForge 共用。 */
public final class WorldOptionsHelper {

    private WorldOptionsHelper() {
    }

    /**
     * 是否把原版那一格换成「世界选项」：只看作弊权限（权限等级 ≥ 2），不要求本地集成服务器。
     * 该等级由服务端同步给客户端，所以单人房主（开作弊时）、局域网主机、以及多人服务器上的 OP 都是 ≥ 2，
     * 没有权限时保持原版按钮（单人=难度+锁，多人/已发布局域网=联机设置）。
     */
    public static boolean canShowWorldOptions(Minecraft minecraft) {
        return minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    /**
     * 有权限（权限等级 ≥ 2）就能编辑：单人/局域网主机走本地集成服务器，多人服务器走命令
     * （见 {@link #openGameRules} 与 {@link RemoteGameRules}）。
     */
    public static boolean canEditGameRules(Minecraft minecraft) {
        return canShowWorldOptions(minecraft);
    }

    /**
     * 打开原版「编辑游戏规则」界面；点完成才应用，取消则丢弃。
     *
     * <p>本地集成服务器：直接拿服务端那份 {@link GameRules}（权威值就在本进程里）。
     * 多人服务器：客户端没有规则数据，先向服务端要一份快照当基准，改动再逐条发 {@code /gamerule}。
     */
    public static void openGameRules(Minecraft minecraft, Screen returnTo) {
        MinecraftServer server = getIntegratedServer(minecraft);
        if (server == null) {
            RemoteGameRules.request(minecraft, baseline -> minecraft.setScreen(
                    new EditGameRulesScreen(baseline.copy(), optional -> {
                        minecraft.setScreen(returnTo);
                        optional.ifPresent(edited -> RemoteGameRules.apply(minecraft, edited));
                    })));
            return;
        }
        GameRules liveRules = server.getGameRules();
        GameRules before = liveRules.copy();
        minecraft.setScreen(new EditGameRulesScreen(liveRules.copy(), optional -> {
            minecraft.setScreen(returnTo);
            optional.ifPresent(edited -> {
                // 规则是服务端状态，排到服务端线程写回；服务端通知其他人，主机的回显在本地做
                ServerPlayer self = minecraft.player == null ? null
                        : server.getPlayerList().getPlayer(minecraft.player.getUUID());
                server.execute(() -> applyChanges(liveRules, before, edited, server, self));
            });
        }));
    }

    /** 只写回真正变化的规则。不用 assignFrom：它会对所有规则触发 onChanged 回调。 */
    private static void applyChanges(GameRules liveRules, GameRules before, GameRules edited,
                                     MinecraftServer server, @Nullable ServerPlayer self) {
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                T oldValue = before.getRule(key);
                T newValue = edited.getRule(key);
                if (!oldValue.toString().equals(newValue.toString())) {
                    liveRules.getRule(key).setFrom(newValue, server);
                    GameRuleSyncServer.broadcast(server, self, key, newValue.toString());
                }
            }
        });
    }



    /**
     * 本地集成服务器（没有则 null）。
     *
     * <p>注意不能用 {@code hasSingleplayerServer()}：世界一旦"对局域网开放"，那个方法就会变成 false
     * （原版据此把那一格从"难度"换成"联机设置…"），于是房主在自己开的局域网世界里会被误判成远程玩家，
     * 被迫走命令通道 —— 那正是之前"连房主都改不了规则"的原因。{@code getSingleplayerServer()} 在发布后依然非空。
     */
    @Nullable
    private static MinecraftServer getIntegratedServer(Minecraft minecraft) {
        return minecraft.getSingleplayerServer();
    }
}
