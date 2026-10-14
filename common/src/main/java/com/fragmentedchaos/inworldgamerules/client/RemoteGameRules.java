package com.fragmentedchaos.inworldgamerules.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import com.fragmentedchaos.inworldgamerules.Constants;
import com.fragmentedchaos.inworldgamerules.network.GameRulesChannel;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import com.fragmentedchaos.inworldgamerules.mixin.GameRulesValueInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.GameRules;

/**
 * 多人服务器上的游戏规则读写。
 *
 * <p>1.20.1 没有任何把游戏规则同步给客户端的包（game 协议里相关包类数为 0），客户端那份 {@link GameRules}
 * 全是默认值 —— 直接拿它当基准做差分再回写会写错值。所以走命令：
 *
 * <p>读：逐条发 {@code /gamerule <规则id>}（该版本 {@code /gamerule} 不带参数不给列表），服务端每条回一行
 * {@code Gamerule <id> is currently set to: <值>}（键 {@code commands.gamerule.query}，id 在前、值在最后）。
 * 这些答复在显示前被 {@code MixinClientPacketListener} 截下，所以不会刷聊天栏。为避免一次几十条命令触发
 * 服务端的命令频率限制，按 {@link #PER_TICK} 条/帧分摊发送。
 *
 * <p>写：点完成后与镜像做差分，逐条发 {@code /gamerule <规则id> <值>}，由服务端执行。
 * 权威值始终在服务端，客户端这份只当编辑界面。
 */
public final class RemoteGameRules {

    /** 每帧最多发几条查询命令 */
    private static final int PER_TICK = 1;
    /** 收到最后一条答复后再等这么久就算收齐 */
    private static final long IDLE_MS = 250L;
    /** 兜底上限 */
    private static final long LIMIT_MS = 20000L;
    /** 探针答复超时：这么久没反应就直接放弃 */
    private static final long PROBE_TIMEOUT_MS = 3000L;
    /** 等服务端同步包的超时：服务端没装本模组时，超时后退回命令通道 */
    private static final long SYNC_TIMEOUT_MS = 1500L;

    private static boolean syncPending;
    private static long syncDeadline;

    private static final String PROBE_RULE = "doFireTick";

    private static boolean probing;
    private static boolean collecting;
    private static long startedAt;
    private static long lastMessageAt;
    private static int asked;
    private static final Deque<String> queue = new ArrayDeque<>();
    private static final Map<String, String> collected = new LinkedHashMap<>();
    @Nullable
    private static Consumer<GameRules> onReady;

    private RemoteGameRules() {
    }

    /** 全部规则 id（顺序同原版 {@code /gamerule} 的遍历顺序）。 */
    private static List<String> ruleIds() {
        List<String> ids = new ArrayList<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                ids.add(key.getId());
            }
        });
        return ids;
    }

    /** 向服务端要一份规则快照；收齐后在客户端线程带着镜像回调。 */
    public static void request(Minecraft minecraft, Consumer<GameRules> callback) {
        if (minecraft.player == null || collecting) {
            return;
        }
        collected.clear();
        queue.clear();
        onReady = callback;
        asked = 0;
        collecting = true;
        startedAt = System.currentTimeMillis();
        lastMessageAt = startedAt;

        // 26.x 式：先请服务端把全量规则同步过来（服务端装了本模组才会回应）。
        // 超时没回应，再退回命令通道（§5 的探针 + 逐条查询）。
        syncPending = true;
        syncDeadline = startedAt + SYNC_TIMEOUT_MS;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(String.valueOf(GameRulesChannel.REQUEST));
        minecraft.getConnection().send(new ServerboundCustomPayloadPacket(GameRulesChannel.ID, buf));
        Constants.LOG.info("[inwr] requested rule sync from the server (payload channel)");
    }

    private static void flush(Minecraft minecraft) {
        if (minecraft.player == null) {
            queue.clear();
            return;
        }
        for (int i = 0; i < PER_TICK && !queue.isEmpty(); i++) {
            boolean sent = minecraft.player.connection.sendUnsignedCommand("gamerule " + queue.poll());
            if (!sent) {
                Constants.LOG.warn("[inwr] client refused to send a command (unsupported?)");
            }
        }
    }

    /** @return 这条聊天反馈是不是 /gamerule 的答复（是则我们吃掉，不显示到聊天栏） */
    public static boolean consume(Component message) {
        if (!collecting) {
            return false;
        }
        if (!(message.getContents() instanceof TranslatableContents contents)) {
            Constants.LOG.info("[inwr] chat not translatable ({}) while collecting: {}",
                    message.getContents().getClass().getSimpleName(), message.getString());
            return false;
        }
        // 服务端反馈是 Component.translatable("commands.gamerule.query", <规则id>, <值>)，
        // 直接读 key 与参数即可 —— 不解析文本，因此任何语言（中文没有空格）都不会出错。
        if (!"commands.gamerule.query".equals(contents.getKey())) {
            Constants.LOG.info("[inwr] chat key {} while collecting (args={})",
                    contents.getKey(), java.util.Arrays.toString(contents.getArgs()));
            return false;
        }
        Object[] args = contents.getArgs();
        if (args.length < 2) {
            return false;
        }
        String id = String.valueOf(args[0]);
        String value = String.valueOf(args[1]);
        if (id.isEmpty() || value.isEmpty()) {
            return false;
        }
        collected.put(id, value);
        lastMessageAt = System.currentTimeMillis();
        if (probing) {
            probing = false;
            Constants.LOG.info("[inwr] probe answered, requesting {} game rules", asked);
            flush(Minecraft.getInstance());
        } else if (collected.size() <= 3) {
            Constants.LOG.info("[inwr] got rule {} = {}", id, value);
        }
        return true;
    }

    /** 客户端每帧调用：继续发查询、并在收齐或超时后收尾。 */
    public static void tick() {
        if (!collecting) {
            return;
        }
        long now = System.currentTimeMillis();
        if (syncPending) {
            if (now < syncDeadline) {
                return;
            }
            // 服务端没装本模组（或没回应）→ 退回命令通道
            syncPending = false;
            queue.addAll(ruleIds());
            asked = queue.size();
            probing = true;
            boolean sent = Minecraft.getInstance().player == null
                    || Minecraft.getInstance().player.connection.sendUnsignedCommand("gamerule " + PROBE_RULE);
            Constants.LOG.info("[inwr] no sync packet, falling back to commands (probe sent={})", sent);
            return;
        }
        if (probing) {
            if (now - startedAt < PROBE_TIMEOUT_MS) {
                return;
            }
            // 探针没有答复：直接放弃，避免刷屏，也避免误判
            probing = false;
            collecting = false;
            onReady = null;
            queue.clear();
            Constants.LOG.warn("Server did not answer /gamerule; remote rule editing unavailable");
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Translations.get(
                        "inworldgamerules.world_options.gamerules.failed",
                        "Couldn't read the game rules from the server, nothing was changed."), false);
            }
            return;
        }
        flush(Minecraft.getInstance());
        boolean enough = queue.isEmpty() && collected.size() >= asked;
        boolean idle = queue.isEmpty() && now - lastMessageAt >= IDLE_MS;
        boolean limit = now - startedAt >= LIMIT_MS;
        if (!(enough || idle || limit)) {
            return;
        }
        collecting = false;
        Consumer<GameRules> callback = onReady;
        onReady = null;
        if (collected.isEmpty()) {
            Constants.LOG.warn("Server did not answer /gamerule queries; remote rule editing unavailable");
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Translations.get(
                        "inworldgamerules.world_options.gamerules.failed",
                        "Couldn't read the game rules from the server, nothing was changed."), false);
            }
            return;
        }
        Constants.LOG.info("[inwr] collected {}/{} game rules", collected.size(), asked);
        if (callback != null) {
            callback.accept(build());
        }
    }

    /** 收到服务端下发的全量规则（26.x 的 ClientboundGameRuleValuesPacket 等价物）。 */
    public static void acceptSync(String json) {
        if (!collecting) {
            return;
        }
        java.util.Map<String, String> values = GameRulesChannel.decode(json);
        if (values.isEmpty()) {
            return;
        }
        syncPending = false;
        collected.clear();
        collected.putAll(values);
        asked = collected.size();
        lastMessageAt = System.currentTimeMillis();
        Constants.LOG.info("[inwr] received {} game rules over the sync channel", collected.size());
        finish();
    }

    private static void finish() {
        collecting = false;
        Consumer<GameRules> callback = onReady;
        onReady = null;
        queue.clear();
        if (collected.isEmpty()) {
            return;
        }
        if (callback != null) {
            callback.accept(build());
        }
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
                T value = mirror.getRule(key);
                ((GameRulesValueInvoker) (Object) value).inwr$deserialize(raw);
            }
        });
        return mirror;
    }

    /** 与服务端快照做差分，逐条发命令。 */
    public static void apply(Minecraft minecraft, GameRules edited) {
        if (minecraft.player == null) {
            return;
        }
        if (collected.size() < asked) {
            // 快照不全：宁可不写，也不要把读不到的规则按默认值覆盖过去
            Constants.LOG.warn("Refusing to apply: only {}/{} game rules were read back", collected.size(), asked);
            minecraft.player.displayClientMessage(Translations.get(
                    "inworldgamerules.world_options.gamerules.failed",
                    "Couldn't read the game rules from the server, nothing was changed."), false);
            return;
        }
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                String id = key.getId();
                String before = collected.get(id);
                String after = edited.getRule(key).toString();
                if (before == null || before.equals(after)) {
                    return;
                }
                collected.put(id, after);
                minecraft.player.connection.sendUnsignedCommand("gamerule " + id + " " + after);
            }
        });
    }
}
