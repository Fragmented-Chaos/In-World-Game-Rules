package com.fragmentedchaos.inworldgamerules;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Forge 侧入口。纯客户端模组，用 {@link FMLEnvironment#dist} 守卫。改动时与 {@code neoforge} 模块的同名类同步。 */
@Mod(Constants.MOD_ID)
public class InWorldGameRulesMod {

    public InWorldGameRulesMod() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            CommonClass.init();
        }
    }
}
