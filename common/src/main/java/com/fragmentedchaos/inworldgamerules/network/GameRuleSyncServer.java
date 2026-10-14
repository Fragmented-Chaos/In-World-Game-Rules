package com.fragmentedchaos.inworldgamerules.network;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fragmentedchaos.inworldgamerules.Constants;
import com.fragmentedchaos.inworldgamerules.mixin.GameRulesValueInvoker;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

/**
 * 服务端侧：全量快照 / 应用改动。只依赖原版 API，且不引用任何客户端类，
 * 所以集成服务器（单人、局域网）与将来的专用服务端都能安全加载。
 */
public final class GameRuleSyncServer {

    private GameRuleSyncServer() {
    }

    /** @return 服务端全部规则的 JSON（规则 id → 值），即 26.x {@code ClientboundGameRuleValuesPacket} 的内容 */
    public static String snapshot(MinecraftServer server) {
        GameRules live = server.getGameRules();
        Map<String, String> out = new LinkedHashMap<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                out.put(key.getId(), live.getRule(key).toString());
            }
        });
        return GameRulesChannel.encode(out);
    }

    /**
     * 播报一条规则改动 —— <b>唯一的一条播报路径</b>，由服务端发出，面向所有在线玩家：
     *
     * <ul>
     *   <li>发起者本人：{@code commands.gamerule.set} 文案，加粗，不带名字和括号</li>
     *   <li>其他人：{@code [名字: 文案]}，灰色 + 斜体</li>
     * </ul>
     *
     * <p>刻意**不跳过**发起者、也不在客户端做任何回显：主机改和客机改走的是同一段代码，
     * 行为完全一致，哪一侧都不会因为版本/路径不同而少一句话。
     */
    public static void broadcast(MinecraftServer server, @Nullable ServerPlayer actor,
                                 GameRules.Key<?> key, String value) {
        Component message = Component.translatable("commands.gamerule.set", key.getId(), value);
        Style admin = Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(true);
        Component named = actor == null ? message
                : Component.empty()
                        .append(Component.literal("[").withStyle(admin))
                        .append(actor.getName().copy().withStyle(admin))
                        .append(Component.literal(": ").withStyle(admin))
                        .append(message.copy().withStyle(admin))
                        .append(Component.literal("]").withStyle(admin));
        Component own = message.copy().withStyle(ChatFormatting.BOLD);

        int players = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(player == actor ? own : named);
            players++;
        }
    }

    /** 应用客户端发来的改动项（服务端权威执行，等价 26.x 的 ServerboundSetGameRulePacket 处理）。 */
    public static int apply(MinecraftServer server, @Nullable ServerPlayer sender, String json) {
        Map<String, String> values = GameRulesChannel.decode(json);
        if (values.isEmpty()) {
            return 0;
        }
        GameRules live = server.getGameRules();
        GameRules parsed = new GameRules();
        int[] changed = {0};
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                String raw = values.get(key.getId());
                if (raw == null) {
                    return;
                }
                try {
                    T incoming = parsed.getRule(key);
                    ((GameRulesValueInvoker) (Object) incoming).inwr$deserialize(raw);
                    if (!live.getRule(key).toString().equals(raw)) {
                        live.getRule(key).setFrom(incoming, server);
                        // ★ 播报（之前这一行被我的编辑误删，导致客机改规则只写值不播报）
                        broadcast(server, sender, key, raw);
                        changed[0]++;
                    }
                } catch (Exception e) {
                    // 单个规则的值异常不能让整批失败，更不能把玩家踢掉
                    Constants.LOG.warn("[inwr] could not apply rule {} = {}: {}", key.getId(), raw, e.toString());
                }
            }
        });
        return changed[0];
    }
}
