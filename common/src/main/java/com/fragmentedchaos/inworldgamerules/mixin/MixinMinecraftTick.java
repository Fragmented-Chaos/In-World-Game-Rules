package com.fragmentedchaos.inworldgamerules.mixin;

import com.fragmentedchaos.inworldgamerules.client.RemoteGameRules;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 客户端每帧检查远端规则快照是否收齐（收齐后才会打开编辑界面）。 */
@Mixin(Minecraft.class)
public class MixinMinecraftTick {

    @Inject(method = "tick", at = @At("TAIL"))
    private void inwr$pollRemoteGameRules(CallbackInfo callbackInfo) {
        RemoteGameRules.tick();
    }
}
