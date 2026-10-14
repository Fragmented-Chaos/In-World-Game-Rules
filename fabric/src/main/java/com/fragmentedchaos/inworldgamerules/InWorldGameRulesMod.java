package com.fragmentedchaos.inworldgamerules;

import net.fabricmc.api.ClientModInitializer;

/** Fabric 侧入口。纯客户端模组，因此用 {@link ClientModInitializer}。 */
public class InWorldGameRulesMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CommonClass.init();
    }
}
