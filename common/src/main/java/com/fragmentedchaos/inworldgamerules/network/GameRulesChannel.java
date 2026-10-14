package com.fragmentedchaos.inworldgamerules.network;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * 规则同步通道。26.x 有官方的一对包（{@code ClientboundGameRuleValuesPacket} 全量下发 +
 * {@code ServerboundSetGameRulePacket} 只发改动），1.20.1 没有，这里用原版自定义载荷补一对等价的：
 *
 * <pre>
 *   C2S  "R" + 协议号                  → 请求全量规则
 *   S2C  "V" + 协议号 + json {id:值}   → 全量下发（等价 ClientboundGameRuleValuesPacket）
 *   C2S  "S" + 协议号 + json {id:值}   → 只发改动（等价 ServerboundSetGameRulePacket）
 * </pre>
 *
 * <p>带协议号是为了两端版本不一致时直接放弃，而不是按错误的格式去解析。
 * 走原版 {@code Clientbound/ServerboundCustomPayloadPacket}，两端各用 mixin 接住 ——
 * 不依赖 Fabric API，也不依赖 Forge 的 SimpleChannel，四个加载器同一套代码。
 *
 * <p><b>远端只此一条路</b>：早期版本还用 {@code /gamerule} 命令做回退，已删除 ——
 * 它未在真实服务器验证通过，且一旦基线出错会把整批规则刷掉（出过事故），命令还可能被服务端插件拦。
 */
public final class GameRulesChannel {

    public static final ResourceLocation ID = new ResourceLocation("inworldgamerules:gamerules");
    public static final int PROTOCOL = 1;

    public static final char REQUEST = 'R';
    public static final char VALUES = 'V';
    public static final char SET = 'S';

    /** 载荷上限：一条规则集也就几 KB，超过一律当异常丢弃 */
    public static final int MAX_PAYLOAD = 64 * 1024;

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private GameRulesChannel() {
    }

    public static String request() {
        return "" + REQUEST + PROTOCOL;
    }

    public static String values(Map<String, String> values) {
        return "" + VALUES + PROTOCOL + encode(values);
    }

    public static String set(Map<String, String> changed) {
        return "" + SET + PROTOCOL + encode(changed);
    }

    /** 判断载荷是不是指定类型、且协议号一致。 */
    public static boolean matches(String data, char type) {
        return data != null && data.length() >= 2 && data.charAt(0) == type
                && Character.getNumericValue(data.charAt(1)) == PROTOCOL;
    }

    /** 去掉类型与协议号后的正文。 */
    public static String body(String data) {
        return data.length() <= 2 ? "{}" : data.substring(2);
    }

    public static String encode(Map<String, String> values) {
        return GSON.toJson(values);
    }

    public static Map<String, String> decode(String json) {
        try {
            Map<String, String> parsed = GSON.fromJson(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
