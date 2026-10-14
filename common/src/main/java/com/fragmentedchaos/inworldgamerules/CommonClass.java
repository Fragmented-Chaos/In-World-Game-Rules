package com.fragmentedchaos.inworldgamerules;

/** 各加载器入口共用的初始化钩子；本模组功能全在 Mixin 里，这里只打一条加载日志。 */
public class CommonClass {

    public static void init() {
        Constants.LOG.info("In-World Game Rules loaded (client-only mod)");
    }
}
