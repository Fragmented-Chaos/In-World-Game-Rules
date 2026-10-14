package com.fragmentedchaos.inworldgamerules.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * 本模组自己的文本翻译功能。
 */
public final class Translations {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();
    private static final String DEFAULT_CODE = "en_us";

    private static String cachedCode;
    private static Map<String, String> cached = Map.of();

    private Translations() {
        throw new UnsupportedOperationException("Translations cannot be instantiated");
    }

    /** @return 已翻译的文本组件；找不到翻译时用 {@code fallback}（英文原文） */
    public static Component get(String key, String fallback) {
        return Component.literal(resolve(key, fallback));
    }

    private static String resolve(String key, String fallback) {
        Language language = Language.getInstance();
        if (language != null && language.has(key)) {
            return language.getOrDefault(key);
        }
        return translations().getOrDefault(key, fallback);
    }

    /** @return 本模组某语言文件的键值对；文件不存在时返回空表 */
    static Map<String, String> load(String code) {
        String path = "/assets/inworldgamerules/lang/" + code + ".json";
        try (InputStream stream = Translations.class.getResourceAsStream(path)) {
            if (stream == null) {
                return Map.of();
            }
            Map<String, String> parsed = GSON.fromJson(
                    new InputStreamReader(stream, StandardCharsets.UTF_8), MAP_TYPE);
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, String> translations() {
        String code = currentCode();
        if (!code.equals(cachedCode)) {
            cachedCode = code;
            cached = load(code);
        }
        return cached;
    }

    private static String currentCode() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getLanguageManager() == null) {
            return DEFAULT_CODE;
        }
        String selected = minecraft.getLanguageManager().getSelected();
        return selected == null || selected.isBlank() ? DEFAULT_CODE : selected;
    }
}
