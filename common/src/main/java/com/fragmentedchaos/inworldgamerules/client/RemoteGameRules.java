package com.fragmentedchaos.inworldgamerules.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import com.fragmentedchaos.inworldgamerules.Constants;
import com.fragmentedchaos.inworldgamerules.mixin.GameRulesValueInvoker;
import com.fragmentedchaos.inworldgamerules.network.GameRulesChannel;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.world.level.GameRules;

/**
 * 多人服务器上的规则读写 —— 完全照 26.x 的形状：**服务端下发全量、客户端只发改动**，
 * 没有任何 {@code /gamerule} 命令。
 *
 * <pre>
 *   request()      发 "R"+协议号 → 等服务端下发
 *   acceptSync()   收到 "V"+协议号+全量 → 装回镜像 → 开原版编辑界面
 *   apply()        改完只把变化的项用 "S"+协议号 发回
 * </pre>
 *
 * <p>服务端没装本模组时不会有任何回应：3 秒超时 → 放弃并给玩家一句可见提示（绝不写任何东西）。
 * 早期版本在这里挂了一条 {@code /gamerule} 命令回退，已删除：未在真实服务器验证通过，
 * 且基线一旦出错会把整批规则刷掉（出过事故），命令本身也可能被服务端插件拦。
 *
 * <p>状态只在客户端主线程读写（netty 侧先读走字节、再切回主线程调用 {@link #acceptSync}）。
 */
public final class RemoteGameRules {

    private static final long SYNC_TIMEOUT_MS = 3000L;
    private static final String FAILED_KEY = "inworldgamerules.world_options.gamerules.failed";
    private static final String FAILED_EN = "Couldn't read the game rules from the server, nothing was changed.";

    private static boolean pending;
    private static long deadline;
    @Nullable
    private static ClientPacketListener connection;
    private static final Map<String, String> collected = new LinkedHashMap<>();
    @Nullable
    private static Consumer<GameRules> onReady;

    private RemoteGameRules() {
    }

    /** 请服务端把全量规则同步过来；收齐后在主线程带着镜像回调。 */
    public static void request(Minecraft minecraft, Consumer<GameRules> callback) {
        ClientPacketListener current = minecraft.getConnection();
        if (minecraft.player == null || current == null || pending) {
            return;
        }
        collected.clear();
        onReady = callback;
        connection = current;
        pending = true;
        deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(GameRulesChannel.request());
        current.send(new ServerboundCustomPayloadPacket(GameRulesChannel.ID, buf));
        Constants.LOG.info("[inwr] requested rule sync (protocol {})", GameRulesChannel.PROTOCOL);
    }

    /** 收到服务端下发的全量规则（26.x {@code ClientboundGameRuleValuesPacket} 的等价物）。 */
    public static void acceptSync(String json) {
        if (!pending) {
            return;
        }
        Map<String, String> values = GameRulesChannel.decode(json);
        if (values.isEmpty()) {
            cancel("empty sync payload");
            return;
        }
        pending = false;
        collected.clear();
        collected.putAll(values);
        connection = null;
        Constants.LOG.info("[inwr] received {} game rules over the sync channel", collected.size());
        Consumer<GameRules> callback = onReady;
        onReady = null;
        if (callback != null) {
            callback.accept(build());
        }
    }

    /** 客户端每帧调用：超时或换连接（断线/换世界）就收手。 */
    public static void tick() {
        if (!pending) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != connection) {
            cancel("connection changed");
            return;
        }
        if (System.currentTimeMillis() < deadline) {
            return;
        }
        cancel("no answer after " + SYNC_TIMEOUT_MS + "ms");
    }

    private static void cancel(String why) {
        pending = false;
        onReady = null;
        collected.clear();
        connection = null;
        Constants.LOG.warn("Server did not answer the rule sync channel ({}); nothing changed", why);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Translations.get(FAILED_KEY, FAILED_EN), false);
        }
    }

    /** 把编辑结果与服务端基线做差分，只把变化的项发回去。 */
    public static void apply(Minecraft minecraft, GameRules edited) {
        ClientPacketListener current = minecraft.getConnection();
        if (minecraft.player == null || current == null || collected.isEmpty()) {
            return;
        }
        Map<String, String> changed = new LinkedHashMap<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                String id = key.getId();
                String baseline = collected.get(id);
                String now = edited.getRule(key).toString();
                // 没有基线的规则一律不写：这是"读不全就不写"的落点（不再有全局计数闸门）
                if (baseline != null && !baseline.equals(now)) {
                    changed.put(id, now);
                }
            }
        });
        if (changed.isEmpty()) {
            return;
        }
        collected.putAll(changed);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(GameRulesChannel.set(changed));
        current.send(new ServerboundCustomPayloadPacket(GameRulesChannel.ID, buf));
        Constants.LOG.info("[inwr] sent {} rule changes to the server", changed.size());
    }

    private static GameRules build() {
        GameRules mirror = new GameRules();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                String raw = collected.get(key.getId());
                if (raw == null) {
                    return;
                }
                try {
                    T value = mirror.getRule(key);
                    ((GameRulesValueInvoker) (Object) value).inwr$deserialize(raw);
                } catch (Exception e) {
                    Constants.LOG.warn("[inwr] could not read rule {} = {}: {}", key.getId(), raw, e.toString());
                }
            }
        });
        return mirror;
    }
}
