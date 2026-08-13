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

    /**
     * check 状态消息：各额度线状态 + 耗尽/未满 + 豁免提示（坑 #21 金色行）。
     * /chunkplan check 与登录欢迎共用此渲染（坑 #22：文案归属壳层）。
     *
     * @param name          查询对象显示名（自检为执行者名，查他人为目标名）
     * @param self          是否自检（豁免行人称）
     * @param isExempt      是否豁免（OP 默认豁免或豁免名单，由调用方算好）
     * @param inExemptList  是否命中豁免名单（区分"管理员豁免"与"名单豁免"文案）
     */
    public static String checkStatusText(String name, QuotaEngine.QuotaStatus status, boolean self, boolean zh,
                                         boolean isExempt, boolean inExemptList) {
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "§a[ChunkPlan] §f" + name + " §7探索额度状态："
                     : "§a[ChunkPlan] §f" + name + " §7exploration status:");
        for (QuotaEngine.LineStatus line : status.lines()) {
            sb.append("\n§7  ").append(formatWindow(line.windowSeconds()))
                    .append(zh ? " 窗口: §f" : " window: §f")
                    .append(String.format("%.1f§7/%.1f", line.spent(), line.limit()));
        }
        if (status.allExceeded()) {
            String recover = formatTime(status.recoveryMillis());
            sb.append(zh ? "\n§c  已耗尽，预计 " : "\n§c  Exhausted, recovers at ").append(recover).append(zh ? " 恢复" : "");
        } else {
            sb.append(zh ? "\n§a  未满，可正常探索" : "\n§a  Under limit, exploration allowed");
        }
        // 豁免状态提示（坑 #21：OP 默认豁免是设计语义，显式告知避免误判为故障；金色强调）
        if (isExempt) {
            if (zh) {
                sb.append(inExemptList
                        ? "\n§6  [豁免] " + (self ? "你在豁免名单中" : "该玩家在豁免名单中") + "，不受额度限制"
                        : "\n§6  [豁免] " + (self ? "你当前是管理员" : "该玩家当前是管理员") + "，不受额度限制");
            } else {
                sb.append(inExemptList
                        ? "\n§6  [exempt] " + (self ? "You are in the exempt list" : "This player is in the exempt list") + "; quota limits do not apply"
                        : "\n§6  [exempt] " + (self ? "You are an operator" : "This player is an operator") + "; quota limits do not apply");
            }
        }
        return sb.toString();
    }

    /**
     * 登录欢迎消息（坑 #24）：自动触发一次 check 状态展示 + 提示语，登录时发送一次。
     * 后续计划：客户端安装 mod 时再追加可视化查询/配置页面入口行（本期不做）。
     */
    public static String welcomeMessage(String name, QuotaEngine.QuotaStatus status, boolean isExempt,
                                        boolean inExemptList, boolean zh) {
        return checkStatusText(name, status, true, zh, isExempt, inExemptList)
                + "\n" + (zh ? "§7查询额度请使用 /chunkplan check 命令"
                              : "§7Check your quota with /chunkplan check");
    }
}
