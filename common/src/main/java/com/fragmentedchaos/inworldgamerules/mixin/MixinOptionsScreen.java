package com.fragmentedchaos.inworldgamerules.mixin;

import com.fragmentedchaos.inworldgamerules.client.Translations;
import com.fragmentedchaos.inworldgamerules.client.WorldOptionsHelper;
import com.fragmentedchaos.inworldgamerules.client.WorldOptionsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把「选项」界面右上角那一格换成「世界选项」入口；没有作弊权限（或无本地集成服务器）时保持原版
 * —— 单人存档为「难度 + 锁」，多人游戏为「联机设置…」。权限判定见 {@link WorldOptionsHelper#canEditGameRules}。
 */
@Mixin(OptionsScreen.class)
public abstract class MixinOptionsScreen extends Screen {

    /**
     * 按钮文案每次开界面时现取：Fabric/Quilt 上模组语言文件不进资源系统（那需要 Fabric API），
     * 由 {@link Translations} 自己按当前语言解析；静态常量会把语言冻结在首次加载时。
     */
    @Unique
    private static Component inwr$worldOptionsLabel() {
        return Translations.get("inworldgamerules.world_options.button", "World Options...");
    }

    private MixinOptionsScreen(Component title) {
        super(title);
    }

    @Inject(method = "createOnlineButton", at = @At("HEAD"), cancellable = true)
    private void inwr$worldOptionsInsteadOfDifficulty(CallbackInfoReturnable<LayoutElement> callbackInfo) {
        // 这一格原版在"多人/已发布局域网"时是「联机设置…」；有作弊权限就换成「世界选项」，
        // 没权限则原样返回，交给原版建联机设置按钮。
        if (this.minecraft == null || !WorldOptionsHelper.canShowWorldOptions(this.minecraft)) {
            return;
        }
        callbackInfo.setReturnValue(Button.builder(inwr$worldOptionsLabel(),
                button -> this.minecraft.setScreen(new WorldOptionsScreen(this))).width(150).build());
    }
}
