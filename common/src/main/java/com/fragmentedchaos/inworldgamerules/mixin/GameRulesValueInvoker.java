package com.fragmentedchaos.inworldgamerules.mixin;

import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code GameRules.Value#deserialize} 是 protected，这里开个口子把服务端回传的字符串装回规则值。 */
@Mixin(GameRules.Value.class)
public interface GameRulesValueInvoker {

    @Invoker("deserialize")
    void inwr$deserialize(String value);
}
