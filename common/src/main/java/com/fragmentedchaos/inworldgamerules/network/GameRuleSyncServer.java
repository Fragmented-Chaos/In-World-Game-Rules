package com.fragmentedchaos.inworldgamerules.network;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fragmentedchaos.inworldgamerules.Constants;
import com.fragmentedchaos.inworldgamerules.mixin.GameRulesValueInvoker;
import net.minecraft.server.MinecraftServer;
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

    /** 应用客户端发来的改动项（服务端权威执行，等价 26.x 的 ServerboundSetGameRulePacket 处理）。 */
    public static int apply(MinecraftServer server, String json) {
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
                        changed[0]++;
                    }
                } catch (Exception e) {
                    // 单个规则的值异常不能让整批失败，更不能把玩家踢掉
                    Constants.LOG.warn("[inwr] could not apply rule {} = {}: {}", key.getId(), raw, e.toString());
                }
            }
        });
        Constants.LOG.info("[inwr] server applied {} rule changes", changed[0]);
        return changed[0];
    }
}
