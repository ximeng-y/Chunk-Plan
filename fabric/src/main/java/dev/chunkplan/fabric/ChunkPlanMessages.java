package dev.chunkplan.fabric;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaEngine;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * 玩家可见消息渲染（按玩家客户端语言逐玩家选择中/英文文案，坑 #22）。
 * 引擎只返回结构化数据，文案统一在这里渲染；双端逐字重复（架构决定）。
 */
public final class ChunkPlanMessages {

    private static final DateTimeFormatter RECOVER_FMT = DateTimeFormatter.ofPattern("yyyy-M-d HH:mm");

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

    /** 窗口显示名："5小时内"/"1天内"/"30分钟内" 或 "Within 5 hours"/"Within 1 day"（公告/每线展示/命令反馈用） */
    public static String windowName(long windowSeconds, boolean zh) {
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

    /** 恢复时间显示 yyyy-M-d HH:mm（系统时区） */
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
            // 坑 #29：档位词按连续百分比区间（<50 充足 / <75 中等 / ≥75 不足），无档归充足；
            // 跨窗口取最严重档位，跟随当前状态（额度滑出/重置自动回落）
            int pct = status.worstAlert() == null ? -1 : status.worstAlert().percent();
            if (zh) {
                if (pct < 50) {
                    sb.append("\n§a  额度充足，可正常探索");
                } else if (pct < 75) {
                    sb.append("\n§e  额度中等，请注意控制探索消耗");
                } else {
                    sb.append("\n§c  额度不足，请谨慎探索，防止额度耗尽");
                }
            } else {
                if (pct < 50) {
                    sb.append("\n§a  Plenty of quota, exploration allowed");
                } else if (pct < 75) {
                    sb.append("\n§e  Moderate quota, watch your exploration spending");
                } else {
                    sb.append("\n§c  Quota is low, explore carefully to avoid exhausting it");
                }
            }
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

    /**
     * 额度百分比阈值提示（坑 #28）：差异消息内容整体按严重度着色
     * （低 §a 浅绿 / 中 §e 黄 / 高 §c 红），窗口名与百分比字段浅蓝 §b 覆盖；
     * 固定尾部白色 §f，含 /chunkplan check 提示与规则超链接（点击执行 /chunkplan rules）。
     * 返回 Component：超链接带 ClickEvent，是本项目第一个富文本交互用法。
     */
    public static Component quotaAlertMessage(QuotaEngine.WindowAlert alert, boolean zh) {
        String color = switch (alert.severity()) {
            case LOW -> "§a";
            case MEDIUM -> "§e";
            case HIGH -> "§c";
        };
        String window = windowName(alert.windowSeconds(), zh);
        StringBuilder sb = new StringBuilder(color).append("[ChunkPlan] ");
        if (zh) {
            sb.append("您当前已达到 §b").append(window).append(color).append(" 探索额度上限的 §b")
                    .append(alert.percent()).append("%").append(color);
            switch (alert.severity()) {
                case LOW -> sb.append("，请合理规划您的探索。");
                case MEDIUM -> sb.append("，请合理规划您的探索，若达到任意一个额度上限，您将被暂时移出服务器，直到额度重置！");
                case HIGH -> sb.append("，您的探索额度严重不足！为防止因强制踢出而带来的损失，请及时寻找安全的下线地点，过程中确保尽可能在已踏足过的区块行走，避免鞘翅等形式的快速移动！");
            }
        } else {
            sb.append("You have reached §b").append(alert.percent()).append("%").append(color)
                    .append(" of the exploration quota limit for §b").append(window).append(color);
            switch (alert.severity()) {
                case LOW -> sb.append(". Please plan your exploration wisely.");
                case MEDIUM -> sb.append(". Please plan your exploration wisely. If any quota limit is reached, you will be temporarily removed from the server until the quota resets!");
                case HIGH -> sb.append("! Your exploration quota is critically low! To avoid losses from a forced kick, please find a safe place to log off, stay on explored chunks, and avoid fast travel such as elytra!");
            }
        }
        // 固定尾部（双语）：白色，check 命令浅绿；链接文本按语言
        sb.append(zh ? "\n§f您可以使用 §a/chunkplan check §f命令来查阅您的详细额度，"
                     : "\n§fYou can use §a/chunkplan check §f to view your detailed quota, ");
        String link = zh ? "点击此处查看详细计费规则" : "Click here to view detailed billing rules";
        return Component.literal(sb.toString()).append(Component.literal(link)
                .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chunkplan rules"))));
    }

    /**
     * 确认超链接（坑 #30）：点击执行 /chunkplan confirm。
     * 用于 reset / 关闭窗口 / 调低额度的待确认提示尾部，复用坑 #28 的 ClickEvent 模式。
     */
    public static Component confirmLink(boolean zh) {
        String link = zh ? "点击此处进行确认" : "Click here to confirm";
        return Component.literal(link)
                .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chunkplan confirm")));
    }

    /**
     * /chunkplan help（仅管理员）：config 与 reset 的用法教学（双语，纯文本）。
     * 展示完整命令族，数值规则一句话带过。
     */
    public static String helpMessage(boolean zh) {
        if (zh) {
            return "§e--- ChunkPlan 管理员帮助 ---\n"
                    + "§f查询：§a/chunkplan check [玩家]\n"
                    + "§f重置：§a/chunkplan reset <玩家|@a> [tier1|tier2|tier3|tier4|all]§f，执行后点击消息中的确认链接（60 秒内有效）\n"
                    + "§f窗口开关：§a/chunkplan config window <tier1|tier2|tier3|tier4|all> <on|off>§f，关闭会清空该窗口所有玩家记录，需确认\n"
                    + "§f窗口时长：§a/chunkplan config windowTime <tier1..tier4> <时长>§f，时长从补全列表选择（如 5h/24h）\n"
                    + "§f额度上限：§a/chunkplan config windowLimit <tier1..tier4> <数值>§f，调低可能踢出已超限玩家，需确认\n"
                    + "§f高速倍率：§a/chunkplan config highSpeedMultiplier <数值>§f（1.00~1000.00）\n"
                    + "§f默认豁免：§a/chunkplan config exemptByDefault <true|false>\n"
                    + "§f重载配置：§a/chunkplan reload\n"
                    + "§7数值允许整数或最多 2 位小数；所有修改写入配置文件并立即生效";
        }
        return "§e--- ChunkPlan Admin Help ---\n"
                + "§fQuery: §a/chunkplan check [player]\n"
                + "§fReset: §a/chunkplan reset <player|@a> [tier1|tier2|tier3|tier4|all]§f - click the confirm link in the message (valid for 60s)\n"
                + "§fWindows: §a/chunkplan config window <tier1|tier2|tier3|tier4|all> <on|off>§f - disabling clears that window's records for all players (needs confirmation)\n"
                + "§fWindow time: §a/chunkplan config windowTime <tier1..tier4> <duration>§f - pick from the tab-completed presets (e.g. 5h/24h)\n"
                + "§fWindow limit: §a/chunkplan config windowLimit <tier1..tier4> <number>§f - lowering may kick players who now exceed (needs confirmation)\n"
                + "§fHigh-speed multiplier: §a/chunkplan config highSpeedMultiplier <number>§f (1.00~1000.00)\n"
                + "§fDefault exempt: §a/chunkplan config exemptByDefault <true|false>\n"
                + "§fReload: §a/chunkplan reload\n"
                + "§7Numbers may be integers or up to 2 decimals; all changes are written to the config file and take effect immediately";
    }

    /**
     * 详细计费规则文本（/chunkplan rules 命令与提示超链接共用）：5 条规则 + 结尾注意。
     * 数值取管理员配置（String.valueOf 保留 1.0 / 0.05 / 2.0 原样显示），第 4 条内 /chunkplan check 浅绿。
     */
    public static String rulesMessage(QuotaConfig config, boolean zh) {
        String first = String.valueOf(config.firstEntryFee());
        String familiar = String.valueOf(config.familiarEntryFee());
        String mult = String.valueOf(config.highSpeedMultiplier()) + "x";
        if (zh) {
            return "§fChunkPlan计费规则：\n"
                    + "1.踏入从未踏入过的区块，消耗" + first + "探索额度\n"
                    + "2.踏入曾经踏入过的区块，消耗" + familiar + "探索额度\n"
                    + "3.使用鞘翅、创造模式飞行等方式（原版无药水效果疾跑不属于此类）快速移动时，消耗的探索额度倍率提升至" + mult + "\n"
                    + "4.计费额度分为多个计时窗口，任意一个计时窗口达到上限都会导致您被服务器暂时封禁。您可以使用§a/chunkplan check§f命令来查阅您的详细额度\n"
                    + "5.服务器管理员有权重置某个玩家的探索额度\n"
                    + "请注意：您的所有探索额度限制都来自服务器管理员的配置，有任何问题请咨询您的服务器管理员";
        }
        return "§fChunkPlan billing rules:\n"
                + "1. Entering a chunk never explored before costs " + first + " exploration quota\n"
                + "2. Entering a previously explored chunk costs " + familiar + " exploration quota\n"
                + "3. Fast movement (elytra, creative flight, etc.; vanilla sprint without potion effects is NOT included) multiplies the cost to " + mult + "\n"
                + "4. Quota is tracked in multiple timed windows; exceeding the limit of ANY window results in a temporary ban. Use §a/chunkplan check§f to view your detailed quota\n"
                + "5. Server admins can reset a player's exploration quota\n"
                + "Note: all exploration quota limits come from the server admin's configuration; contact your server admin with any questions.";
    }
}
