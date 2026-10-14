package com.fragmentedchaos.inworldgamerules.network;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * 26.x 有一对官方的规则同步包（{@code ClientboundGameRuleValuesPacket} 全量下发 +
 * {@code ServerboundSetGameRulePacket} 只发改动）；1.20.1 没有，这里用原版自定义载荷补一对等价的：
 *
 * <pre>
 *   C2S  "R"          → 请求全量规则（等价 26.x 客户端进界面时的请求）
 *   S2C  "V" + json   → 全量下发 {规则id:值}（等价 ClientboundGameRuleValuesPacket）
 *   C2S  "S" + json   → 只发改动 {规则id:值}（等价 ServerboundSetGameRulePacket）
 * </pre>
 *
 * 走原版 {@code Clientbound/ServerboundCustomPayloadPacket}，两端各自用 mixin 接住，
 * 不依赖 Fabric API，也不依赖 Forge 的 SimpleChannel —— 四个加载器同一套代码。
 */
public final class GameRulesChannel {

    public static final ResourceLocation ID = new ResourceLocation("inworldgamerules:gamerules");

    public static final char REQUEST = 'R';
    public static final char VALUES = 'V';
    public static final char SET = 'S';

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private GameRulesChannel() {
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
