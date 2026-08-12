package dev.chunkplan.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * 全局共享 Gson 实例（数据文件、管理名单、Fabric 配置共用）。
 */
public final class GsonHolder {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private GsonHolder() {
    }
}
