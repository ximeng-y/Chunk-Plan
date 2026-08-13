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
     * ban 消息（坑 #26）：标题 + 原因（满线中窗口最长者）+ 各线状态（含各线独立的下次重置时间）
     * + 最早可进入时间 + 结尾说明。排版参照用户模板：分割线分隔、标签列按显示宽度对齐。
     * 传入 quotaStatus（ban / 登录拦截时至少一条线满）。
     */
    public static String banMessage(QuotaEngine.QuotaStatus status, boolean zh) {
        // 原因行：满线中窗口最长者（5h 与 1d 同时满 -> 显示 1d）
        String fullName = null;
        long maxFull = -1;
        for (QuotaEngine.LineStatus line : status.lines()) {
            if (line.spent() > line.limit() && line.windowSeconds() > maxFull) {
                maxFull = line.windowSeconds();
                fullName = windowName(line.windowSeconds(), zh);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (zh) {
            sb.append("§c[ChunkPlan] 由于服务器管理员对于区块探索额度的限制，您已被限制进入服务器！\n");
            // 原因行配色：窗口名浅蓝 §b、探索额度上限黄 §e、其余红色警示
            sb.append("§c原因：您的 §b").append(fullName == null ? "探索额度" : fullName)
                    .append(" §e探索额度上限 §c已耗尽\n");
        } else {
            sb.append("§c[ChunkPlan] You have been restricted from joining the server due to the admin's chunk exploration quota limit!\n");
            sb.append("§cReason: Your §eexploration quota limit §b(").append(fullName == null ? "quota" : fullName.toLowerCase())
                    .append(") §cis exhausted\n");
        }
        sb.append("§7").append(DIVIDER).append("\n");
        sb.append(zh ? "§e您的探索额度情况：\n" : "§eYour exploration quota status:\n");
        for (QuotaEngine.LineStatus line : status.lines()) {
            // 子条目配色：时间窗口名浅蓝 §b（与原因行一致）、数值白 §f
            String label = windowName(line.windowSeconds(), zh) + (zh ? "：" : ": ");
            sb.append("§b").append(label).append("§f").append(String.format("%.1f/%.1f", line.spent(), line.limit()));
            if (line.spent() > line.limit()) {
                sb.append(zh ? " §c（已满，下次重置时间：" : " §c(exhausted, next reset: ")
                        .append(formatTime(line.nextResetMillis())).append(zh ? "）" : ")");
            } else if (line.nextResetMillis() > 0) {
                // 各线独立的重置时间（未满线也可能跨天，与满线不同步）
                sb.append(zh ? "§7（下次重置时间：" : " §7(next reset: ")
                        .append(formatTime(line.nextResetMillis())).append(zh ? "）" : ")");
            }
            sb.append("\n");
        }
        sb.append("§7").append(DIVIDER).append("\n");
        if (zh) {
            if (status.recoveryMillis() > 0) {
                sb.append("§a您最早可于：").append(formatTime(status.recoveryMillis())).append(" 再次进入服务器\n");
            }
            // 尾部说明：仅特定短语黄 §e（节约资源 / 咨询管理员），句子其余部分白 §f
            sb.append("§f额度限制是§e为了节约服务器的CPU、网络流量等资源§f，感谢您的配合！\n");
            sb.append("§f如有疑问/需要重置或提高额度，请§e咨询您的服务器管理员");
        } else {
            if (status.recoveryMillis() > 0) {
                sb.append("§aYou may rejoin at: ").append(formatTime(status.recoveryMillis())).append("\n");
            }
            sb.append("§fThe quota limit §esaves server CPU, network traffic and other resources§f. Thank you for your cooperation!\n");
            sb.append("§fFor quota reset/increase or questions, please §econtact your server administrator");
        }
        return sb.toString();
    }

    /** 公告分割线（长度取适中值，避免聊天自动换行打断） */
    private static final String DIVIDER = "-".repeat(44);

    /** 窗口显示名："5小时内"/"1天内"/"30分钟内" 或 "Within 5 hours"/"Within 1 day"（公告/每线展示用） */
    private static String windowName(long windowSeconds, boolean zh) {
        long m = windowSeconds / 60;
        if (zh) {
            if (m >= 1440) {
                return (m / 1440) + "天内";
            }
            if (m >= 60) {
                return (m / 60) + "小时内";
            }
            return m + "分钟内";
        }
        if (m >= 1440) {
            long d = m / 1440;
            return "Within " + d + " day" + (d > 1 ? "s" : "");
        }
        if (m >= 60) {
            long h = m / 60;
            return "Within " + h + " hour" + (h > 1 ? "s" : "");
        }
        return "Within " + m + " minute" + (m > 1 ? "s" : "");
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
