package dev.chunkplan.neoforge;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import dev.chunkplan.common.QuotaEngine;

/**
 * 玩家可见消息渲染（按玩家客户端语言逐玩家选择中/英文文案，坑 #22）。
 * 引擎只返回结构化数据，文案统一在这里渲染；双端逐字重复（架构决定）。
 */
public final class ChunkPlanMessages {

    private static final DateTimeFormatter RECOVER_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private ChunkPlanMessages() {
    }

    /** 中文判定：客户端语言代码 zh_ 前缀（zh_cn/zh_tw/zh_hk 等均视为中文） */
    public static boolean isChinese(String language) {
        return language != null && language.toLowerCase().startsWith("zh_");
    }

    /**
     * ban 消息：探索额度已耗尽 + 各线状态 + 恢复时间。
     * 传入 quotaStatus（ban / 登录拦截时所有线均满）。
     */
    public static String banMessage(QuotaEngine.QuotaStatus status, boolean zh) {
        StringBuilder sb = new StringBuilder(zh ? "探索额度已耗尽：" : "Exploration quota exhausted:");
        var lines = status.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(zh ? "；" : ", ");
            }
            QuotaEngine.LineStatus line = lines.get(i);
            sb.append(formatWindow(line.windowSeconds()))
                    .append(zh ? " 窗口 " : " window ")
                    .append(String.format("%.1f/%.1f", line.spent(), line.limit()));
        }
        String recover = formatTime(status.recoveryMillis());
        sb.append(zh ? "。预计 " : ". Recovers at ").append(recover).append(zh ? " 恢复" : "");
        return sb.toString();
    }

    /** 窗口时长简写：1m / 2h / 7d */
    public static String formatWindow(long windowSeconds) {
        long m = windowSeconds / 60;
        if (m >= 1440) {
            return (m / 1440) + "d";
        }
        if (m >= 60) {
            return (m / 60) + "h";
        }
        return m + "m";
    }

    /** 恢复时间显示 MM-dd HH:mm（系统时区） */
    public static String formatTime(long epochMillis) {
        return RECOVER_FMT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}
