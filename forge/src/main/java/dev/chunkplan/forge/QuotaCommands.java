package dev.chunkplan.forge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import dev.chunkplan.common.DurationParser;
import dev.chunkplan.common.FeeLogFile;
import dev.chunkplan.common.NumericParser;
import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaEngine;
import dev.chunkplan.common.QuotaTiers;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * /chunkplan 命令族：check、rules、reset <target> [tier]、confirm、config
 * （exemptByDefault / window / windowTime / windowLimit / highSpeedMultiplier）、help、reload。
 * reset/confirm/reload 与 config 写操作需要权限等级 2（OP）。
 * 待确认动作单槽 + 60 秒超时（新动作覆盖旧动作，无队列），确认以超链接形式发起（坑 #30）。
 * 所有玩家可见文案按执行者客户端语言渲染（中/英，坑 #22）。
 */
public final class QuotaCommands {

    /** 待确认动作超时（毫秒） */
    private static final long CONFIRM_WINDOW_MILLIS = 60_000;

    /** 待确认动作（命令在服务端主线程串行执行，静态字段即可；单槽，新动作覆盖旧动作） */
    private static volatile PendingAction pending;

    /** 待确认动作类型：reset / 关闭窗口（tier 0 = 全部）/ 调低额度 */
    private sealed interface PendingAction {
        long expireMillis();

        /** reset：目标玩家 UUID + 档位集合（null = 全部档位） */
        record Reset(List<UUID> targets, Set<Integer> tiers, long expireMillis) implements PendingAction {
        }

        /** 关闭窗口（tier=0 表示全部窗口；关闭会清空该窗口所有玩家记录） */
        record DisableWindow(int tier, long expireMillis) implements PendingAction {
        }

        /** 调低额度：档位 + 新值原文（可能引发在线玩家无警告踢出） */
        record LowerLimit(int tier, String rawValue, long expireMillis) implements PendingAction {
        }
    }

    private QuotaCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chunkplan")
                .then(Commands.literal("check")
                        .executes(ctx -> checkSelf(ctx))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(s -> s.hasPermission(2))
                                .suggests(QuotaCommands::suggestPlayerNames)
                                .executes(ctx -> checkOther(ctx))))
                .then(Commands.literal("rules")
                        .executes(ctx -> rules(ctx)))
                .then(Commands.literal("reset")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .suggests(QuotaCommands::suggestResetTarget)
                                .executes(ctx -> reset(ctx))))
                .then(Commands.literal("confirm")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> confirm(ctx)))
                .then(Commands.literal("config")
                        .then(Commands.literal("exemptByDefault")
                                .executes(ctx -> configExemptQuery(ctx))
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .requires(s -> s.hasPermission(2))
                                        .executes(ctx -> configExemptSet(ctx))))
                        .then(Commands.literal("window")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("tier", StringArgumentType.word())
                                        .suggests(QuotaCommands::suggestTiers)
                                        .then(Commands.argument("state", StringArgumentType.word())
                                                .suggests(QuotaCommands::suggestOnOff)
                                                .executes(ctx -> configWindow(ctx)))))
                        .then(Commands.literal("windowTime")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("tier", StringArgumentType.word())
                                        .suggests(QuotaCommands::suggestTiersNoAll)
                                        .then(Commands.argument("window", StringArgumentType.word())
                                                .suggests(QuotaCommands::suggestWindowPresets)
                                                .executes(ctx -> configWindowTime(ctx)))))
                        .then(Commands.literal("windowLimit")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("tier", StringArgumentType.word())
                                        .suggests(QuotaCommands::suggestTiersNoAll)
                                        .then(Commands.argument("number", StringArgumentType.word())
                                                .executes(ctx -> configWindowLimit(ctx)))))
                        .then(Commands.literal("highSpeedMultiplier")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("number", StringArgumentType.word())
                                        .executes(ctx -> configSpeedMultiplier(ctx)))))
                .then(Commands.literal("help")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> help(ctx)))
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> reload(ctx))));
    }

    private static int checkSelf(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "控制台请指定玩家: /chunkplan check <player>",
                    "Specify a player from console: /chunkplan check <player>")));
            return 0;
        }
        sendStatus(ctx, player.getUUID(), player.getGameProfile().getName(), true, player.hasPermissions(2));
        return 1;
    }

    private static int checkOther(CommandContext<CommandSourceStack> ctx) {
        GameProfile profile = resolvePlayer(ctx);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "未找到该玩家", "Player not found")));
            return 0;
        }
        // 目标在线：权限状态可取；离线玩家 OP 状态不可查，仅判豁免名单（不误报 OP 豁免）
        ServerPlayer online = DevCommands.findByUuid(ctx.getSource().getServer(), profile.getId());
        sendStatus(ctx, profile.getId(), profileName(profile), false, online != null && online.hasPermissions(2));
        return 1;
    }

    private static void sendStatus(CommandContext<CommandSourceStack> ctx, UUID uuid, String name, boolean self, boolean isOp) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return;
        }
        boolean zh = isZh(ctx);
        // 豁免判定在命令侧：查他人时离线玩家权限不可查，仅判豁免名单（坑 #21 语义）
        boolean inList = eng.getConfig().exemptPlayers().contains(uuid);
        String text = ChunkPlanMessages.checkStatusText(name, eng.quotaStatus(uuid), self, zh,
                eng.isExempt(uuid, isOp), inList);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    }

    /** /chunkplan rules：详细计费规则（提示消息超链接点击触发；无权限要求，所有玩家可查看） */
    private static int rules(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                ChunkPlanMessages.rulesMessage(eng.getConfig(), isZh(ctx))), false);
        return 1;
    }

    /**
     * /chunkplan reset <目标> [tier|all]：解析目标（@a/@e 全部在线、@s 自身、@p 最近、@r 随机、
     * 名字/UUID 离线可用），记录待确认动作（单槽 60 秒），由 /chunkplan confirm 超链接确认执行。
     * 参数用 greedyString（含 @ 的合法内置类型，目标与可选层级同串解析：<目标> [层级]）。
     */
    private static int reset(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String raw = ctx.getArgument("target", String.class).trim();
        String[] parts = raw.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty() || parts.length > 2) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "参数格式: /chunkplan reset <玩家|@a> [tier1|tier2|tier3|tier4|all]",
                    "Usage: /chunkplan reset <player|@a> [tier1|tier2|tier3|tier4|all]")));
            return 0;
        }
        String targetArg = parts[0];
        String tierArg = parts.length == 2 ? parts[1] : null;
        // 窗口参数：缺省 = all（全部档位）；显式选择未启用档位无效（坑 #30）
        Set<Integer> tiers = null;
        if (tierArg != null) {
            if (!tierArg.equals("all")) {
                int tier = parseTier(tierArg);
                if (tier < 0) {
                    ctx.getSource().sendFailure(Component.literal(t(ctx,
                            "未知层级: " + tierArg + "（可选 tier1~tier4 或 all）",
                            "Unknown tier: " + tierArg + " (tier1~tier4 or all)")));
                    return 0;
                }
                if (findLine(eng, tier) == null) {
                    ctx.getSource().sendFailure(Component.literal(t(ctx,
                            "该窗口未启用（tier" + tier + "），无需重置",
                            "This window is not enabled (tier" + tier + "), nothing to reset")));
                    return 0;
                }
                tiers = Set.of(tier);
            }
        }
        List<GameProfile> targets = resolveTargets(ctx, targetArg);
        if (targets.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "未找到玩家", "Player not found")));
            return 0;
        }
        List<UUID> uuids = new ArrayList<>();
        for (GameProfile gp : targets) {
            uuids.add(gp.getId());
        }
        pending = new PendingAction.Reset(uuids, tiers, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS);
        boolean zh = isZh(ctx);
        String zhScope;
        String enScope;
        if (tiers == null) {
            zhScope = "全部";
            enScope = "all windows";
        } else {
            long winSec = findLine(eng, tiers.iterator().next()).windowSeconds();
            zhScope = ChunkPlanMessages.windowName(winSec, true);
            enScope = ChunkPlanMessages.windowName(winSec, false).toLowerCase();
        }
        String zhText;
        String enText;
        if (targets.size() == 1) {
            zhText = "§a将重置 " + profileName(targets.get(0)) + " 的 " + zhScope + " 额度限制，";
            enText = "§aThis will reset " + profileName(targets.get(0)) + "'s quota for " + enScope + ", ";
        } else {
            zhText = "§a将为 " + targets.size() + " 名玩家重置 " + zhScope + " 额度限制，";
            enText = "§aThis will reset " + targets.size() + " players' quota for " + enScope + ", ";
        }
        Component msg = Component.literal(t(ctx, zhText, enText)).append(ChunkPlanMessages.confirmLink(zh));
        ctx.getSource().sendSuccess(() -> msg, true);
        return 1;
    }

    /** /chunkplan confirm：执行单槽待确认动作（60 秒超时；消费后即清，单发） */
    private static int confirm(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        PendingAction req = pending;
        if (req == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "当前没有待确认的操作", "No pending action to confirm.")));
            return 0;
        }
        pending = null; // 先消费再执行（单发，避免重复确认）
        if (req.expireMillis() < System.currentTimeMillis()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "待确认操作已过期，请重新发起命令",
                    "The pending action has expired. Run the command again.")));
            return 0;
        }
        boolean zh = isZh(ctx);
        if (req instanceof PendingAction.Reset r) {
            // 坑 #32/坑 #36：窗口名化——玩家通知与管理员反馈统一按重置范围显示窗口名
            // （非全部重置显示 windowName 如"1天内"，全部时"全部"），与确认提示同款；findLine 空判为防御
            String zhScope;
            String enScope;
            if (r.tiers() == null) {
                zhScope = "全部";
                enScope = "all windows";
            } else {
                int t = r.tiers().iterator().next();
                QuotaConfig.Line ln = findLine(eng, t);
                zhScope = ln == null ? "tier" + t : ChunkPlanMessages.windowName(ln.windowSeconds(), true);
                enScope = ln == null ? "tier" + t : ChunkPlanMessages.windowName(ln.windowSeconds(), false).toLowerCase();
            }
            for (UUID uuid : r.targets()) {
                eng.resetSpend(uuid, r.tiers());
            }
            // 在线通知（含 mock 玩家：虚拟连接发送为 no-op，坑 #9）
            boolean anyOnline = false;
            for (UUID uuid : r.targets()) {
                ServerPlayer target = DevCommands.findByUuid(ctx.getSource().getServer(), uuid);
                if (target != null) {
                    anyOnline = true;
                    boolean tzh = ChunkPlanMessages.isChinese(target.getLanguage());
                    target.sendSystemMessage(Component.literal(tzh
                            ? "您的" + zhScope + "探索额度已被管理员重置"
                            : "Your exploration quota (" + enScope + ") has been reset by an administrator."));
                }
            }
            String who = r.targets().size() == 1
                    ? nameOf(ctx.getSource().getServer(), r.targets().get(0))
                    : (zh ? r.targets().size() + " 名玩家" : r.targets().size() + " players");
            String notified = anyOnline
                    ? (zh ? "（已通知在线玩家）" : " (notified in-game)")
                    : (zh ? "（目标玩家均离线，未通知）" : " (all targets offline, not notified)");
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    "§a已重置 " + who + " 的 " + zhScope + " 额度限制（已探索集合保留）" + notified,
                    "§aReset " + who + "'s quota for " + enScope + " (explored chunks kept)." + notified)), true);
            return 1;
        }
        if (req instanceof PendingAction.DisableWindow d) {
            try {
                if (d.tier() == 0) {
                    for (int tier = 1; tier <= 4; tier++) {
                        ForgeConfig.writeTierEnabled(resolveConfigFile(ctx), tier, false);
                    }
                } else {
                    ForgeConfig.writeTierEnabled(resolveConfigFile(ctx), d.tier(), false);
                }
                List<String> warnings = loadAndApplyConfig(ctx);
                // 关闭即清空该窗口所有玩家记录：重新开启时从 0 起（坑 #30）
                if (d.tier() == 0) {
                    for (int tier = 1; tier <= 4; tier++) {
                        eng.clearTierSpendForAll(tier);
                    }
                    // 零线（坑 #31）：立即解除所有 ChunkPlan 来源临时封禁（scanBans 只解
                    // "ChunkPlan" 来源条目，服主手动 ban 保留——坑 #14），并反馈如实明示
                    ChunkPlanForge.GameEvents.scanBans(ctx.getSource().getServer());
                } else {
                    eng.clearTierSpendForAll(d.tier());
                }
                String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
                String tierName = d.tier() == 0 ? (zh ? "全部窗口" : "all windows") : "tier" + d.tier();
                String text = d.tier() == 0
                        ? t(ctx,
                        "§a已关闭全部窗口，额度限制已停止（ChunkPlan 临时封禁已解除，所有玩家记录已清空）",
                        "§aDisabled all windows; quota limits are off (ChunkPlan temporary bans lifted, records cleared for all players)")
                        : t(ctx,
                        "§a已关闭 " + tierName + "（该窗口所有玩家记录已清空）",
                        "§aDisabled " + tierName + " (records cleared for all players)");
                ctx.getSource().sendSuccess(() -> Component.literal(text + warning), true);
                return 1;
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger("ChunkPlan").error("confirm 关闭窗口失败", e);
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "§c写入配置失败，详见服务端日志",
                        "§cFailed to write config; see server log for details")));
                return 0;
            }
        }
        if (req instanceof PendingAction.LowerLimit l) {
            try {
                ForgeConfig.writeTierLimit(resolveConfigFile(ctx), l.tier(), Double.parseDouble(l.rawValue()));
                List<String> warnings = loadAndApplyConfig(ctx);
                String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
                ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                        // 坑 #32：窗口名化（与 windowLimit 调高反馈一致）
                        "§a已调整 " + ChunkPlanMessages.windowName(findLine(eng, l.tier()).windowSeconds(), true)
                                + " 额度为 §b" + l.rawValue() + "§a",
                        "§aAdjusted " + ChunkPlanMessages.windowName(findLine(eng, l.tier()).windowSeconds(), false)
                                .toLowerCase() + " limit to §b" + l.rawValue() + "§a") + warning), true);
                return 1;
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger("ChunkPlan").error("confirm 调整额度失败", e);
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "§c写入配置失败，详见服务端日志",
                        "§cFailed to write config; see server log for details")));
                return 0;
            }
        }
        return 1;
    }

    /** /chunkplan config window <tier|all> <on|off>：开关探索窗口；关闭清空该窗口所有玩家记录（需 confirm） */
    private static int configWindow(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String tierArg = StringArgumentType.getString(ctx, "tier");
        String stateArg = StringArgumentType.getString(ctx, "state");
        if (!stateArg.equals("on") && !stateArg.equals("off")) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "开关参数需为 on 或 off", "State must be on or off")));
            return 0;
        }
        boolean enable = stateArg.equals("on");
        boolean all = tierArg.equals("all");
        int tier = all ? 0 : parseTier(tierArg);
        if (!all && tier < 0) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "未知层级: " + tierArg + "（可选 tier1~tier4 或 all）",
                    "Unknown tier: " + tierArg + " (tier1~tier4 or all)")));
            return 0;
        }
        boolean zh = isZh(ctx);
        if (enable) {
            // 开启不清空记录，直接执行；时长继承配置文件既有时长（从未开启的 tier3/4 为默认 7d/30d）
            try {
                if (all) {
                    for (int i = 1; i <= 4; i++) {
                        ForgeConfig.writeTierEnabled(resolveConfigFile(ctx), i, true);
                    }
                } else {
                    ForgeConfig.writeTierEnabled(resolveConfigFile(ctx), tier, true);
                }
                List<String> warnings = loadAndApplyConfig(ctx);
                String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
                String text;
                if (all) {
                    StringBuilder sb = new StringBuilder();
                    for (QuotaConfig.Line line : eng.getConfig().lines()) {
                        if (sb.length() > 0) {
                            sb.append("、");
                        }
                        sb.append("tier").append(line.tier()).append(" ")
                                .append(ChunkPlanMessages.windowName(line.windowSeconds(), zh));
                    }
                    text = (zh ? "§a已开启全部窗口：" : "§aEnabled all windows: ") + sb;
                } else {
                    QuotaConfig.Line line = findLine(eng, tier);
                    text = (zh ? "§a已开启 tier" : "§aEnabled tier") + tier
                            + (zh ? "，当前该窗口为 §b" : ", window is now §b")
                            + ChunkPlanMessages.windowName(line == null ? 0 : line.windowSeconds(), zh)
                            + (zh ? "§a 内刷新" : "§a");
                }
                ctx.getSource().sendSuccess(() -> Component.literal(text + warning), true);
                return 1;
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger("ChunkPlan").error("写入配置失败", e);
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "§c写入配置失败，详见服务端日志",
                        "§cFailed to write config; see server log for details")));
                return 0;
            }
        }
        // 关闭：清空该窗口所有玩家记录 -> confirm
        pending = new PendingAction.DisableWindow(tier, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS);
        String tierName = all ? (zh ? "全部窗口" : "all windows") : "tier" + tier;
        Component msg = Component.literal(t(ctx,
                "§a将关闭 " + tierName + "，并清空该窗口所有玩家的记录，",
                "§aThis will disable " + tierName + " and clear all players' records for it, "))
                .append(ChunkPlanMessages.confirmLink(zh));
        ctx.getSource().sendSuccess(() -> msg, true);
        return 1;
    }

    /** /chunkplan config windowTime <tier> <预设时长>：调整窗口时长（预置校验 + tab 补全，时间差不补偿） */
    private static int configWindowTime(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        int tier = parseTier(StringArgumentType.getString(ctx, "tier"));
        if (tier < 0) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "未知层级（可选 tier1~tier4）", "Unknown tier (tier1~tier4)")));
            return 0;
        }
        String windowArg = StringArgumentType.getString(ctx, "window");
        List<String> presets = presetsOf(tier);
        if (!presets.contains(windowArg)) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "tier" + tier + " 可选窗口: " + String.join(" / ", presets),
                    "Valid windows for tier" + tier + ": " + String.join(" / ", presets))));
            return 0;
        }
        if (findLine(eng, tier) == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "该窗口未启用，请先使用 /chunkplan config window tier" + tier + " on 开启",
                    "This window is not enabled. Enable it first with /chunkplan config window tier" + tier + " on")));
            return 0;
        }
        try {
            ForgeConfig.writeTierWindow(resolveConfigFile(ctx), tier, windowArg);
            List<String> warnings = loadAndApplyConfig(ctx);
            String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
            long secs = DurationParser.parseSeconds(windowArg); // 预置值，解析必成功
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    // 坑 #32：窗口名化（不再显示 tierX；时长已按预置校验）
                    "§a已调整计费窗口刷新时长为 §b" + ChunkPlanMessages.windowName(secs, true) + "§a",
                    "§aAdjusted billing window refresh duration to §b"
                            + ChunkPlanMessages.windowName(secs, false).toLowerCase() + "§a")
                    + warning), true);
            return 1;
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("ChunkPlan").error("写入配置失败", e);
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c写入配置失败，详见服务端日志",
                    "§cFailed to write config; see server log for details")));
            return 0;
        }
    }

    /** /chunkplan config highSpeedMultiplier <数值>：调整高速移动倍率（1.00~1000.00，≤2 位小数） */
    private static int configSpeedMultiplier(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, "number");
        NumericParser.Parsed p = NumericParser.parseMultiplier(raw);
        if (!p.isOk()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c高速移动倍率需为 1.00~1000.00 的数字，最多 2 位小数",
                    "§cMultiplier must be a number between 1.00 and 1000.00 with at most 2 decimals")));
            return 0;
        }
        try {
            ForgeConfig.writeHighSpeedMultiplier(resolveConfigFile(ctx), p.value());
            List<String> warnings = loadAndApplyConfig(ctx);
            String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    "§a高速移动额度倍率已调整为 §b" + raw + "x§a",
                    "§aHigh-speed movement multiplier set to §b" + raw + "x§a") + warning), true);
            return 1;
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("ChunkPlan").error("写入配置失败", e);
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c写入配置失败，详见服务端日志",
                    "§cFailed to write config; see server log for details")));
            return 0;
        }
    }

    /**
     * /chunkplan config windowLimit <tier> <数值>：调整额度上限（1.00~999999999.99，≤2 位小数）。
     * 调低可能引发已超限玩家被无警告踢出 -> confirm（超链接）；调整不影响已累计额度。
     */
    private static int configWindowLimit(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        int tier = parseTier(StringArgumentType.getString(ctx, "tier"));
        if (tier < 0) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "未知层级（可选 tier1~tier4）", "Unknown tier (tier1~tier4)")));
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, "number");
        NumericParser.Parsed p = NumericParser.parseLimit(raw);
        if (!p.isOk()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c额度需为 1.00~999999999.99 的数字，最多 2 位小数",
                    "§cLimit must be a number between 1.00 and 999999999.99 with at most 2 decimals")));
            return 0;
        }
        QuotaConfig.Line line = findLine(eng, tier);
        if (line == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "该窗口未启用，请先使用 /chunkplan config window tier" + tier + " on 开启",
                    "This window is not enabled. Enable it first with /chunkplan config window tier" + tier + " on")));
            return 0;
        }
        if (p.value() < line.limit()) {
            // 调低：可能引发在线玩家无警告踢出 -> confirm
            pending = new PendingAction.LowerLimit(tier, raw, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS);
            boolean zh = isZh(ctx);
            // 坑 #32：前置提示窗口名化（与 reset 确认提示一致）
            String winZh = ChunkPlanMessages.windowName(line.windowSeconds(), true);
            String winEn = ChunkPlanMessages.windowName(line.windowSeconds(), false).toLowerCase();
            Component msg = Component.literal(t(ctx,
                    "§a将把 " + winZh + " 额度从 §b" + line.limit() + "§a 调低至 §b" + raw + "§a，可能引发部分玩家被无警告踢出，",
                    "§aThis will lower " + winEn + " limit from §b" + line.limit() + "§a to §b" + raw
                            + "§a; some players may be kicked without warning, "))
                    .append(ChunkPlanMessages.confirmLink(zh));
            ctx.getSource().sendSuccess(() -> msg, true);
            return 1;
        }
        try {
            ForgeConfig.writeTierLimit(resolveConfigFile(ctx), tier, p.value());
            List<String> warnings = loadAndApplyConfig(ctx);
            String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    // 坑 #32：窗口名化
                    "§a已调整 " + ChunkPlanMessages.windowName(line.windowSeconds(), true) + " 额度为 §b" + raw + "§a",
                    "§aAdjusted " + ChunkPlanMessages.windowName(line.windowSeconds(), false).toLowerCase()
                            + " limit to §b" + raw + "§a") + warning), true);
            return 1;
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("ChunkPlan").error("写入配置失败", e);
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c写入配置失败，详见服务端日志",
                    "§cFailed to write config; see server log for details")));
            return 0;
        }
    }

    /** /chunkplan help（仅管理员）：config 与 reset 用法教学 */
    private static int help(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(ChunkPlanMessages.helpMessage(isZh(ctx))), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        List<String> warnings = loadAndApplyConfig(ctx);
        String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§aChunkPlan 配置已重载",
                "§aChunkPlan configuration reloaded") + warning), true);
        return 1;
    }

    /** 读文件并应用到引擎（reload 与 config 设置共用）；返回配置告警（路径不对外展示） */
    private static List<String> loadAndApplyConfig(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        Path configFile = resolveConfigFile(ctx);
        List<String> warnings = new ArrayList<>();
        QuotaConfig config = ForgeConfig.toQuotaConfigFromFile(configFile, warnings);
        // 坑 #39：同步 spec 内存值，防止 Forge 关服时用启动旧值覆盖文件（重启回退默认）
        ForgeConfig.syncSpecFromFile(configFile);
        for (String w : warnings) {
            org.slf4j.LoggerFactory.getLogger("ChunkPlan").warn("配置告警: {}", w);
        }
        eng.setConfig(config);
        // logFeeEvents 开关热切换：按新配置重建/清空扣费日志
        if (config.logFeeEvents()) {
            try {
                eng.setFeeLogger(new FeeLogFile(ctx.getSource().getServer().getServerDirectory().toPath()
                        .resolve("logs").resolve("chunkplan.log")));
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger("ChunkPlan").warn("重建扣费日志失败: {}", e.getMessage());
            }
        } else {
            eng.setFeeLogger(null);
        }
        return warnings;
    }

    /**
     * 实际生效的配置文件（坑 #38）：Forge 1.20.1 的 SERVER 配置是存档级的，唯一位置
     * &lt;world&gt;/serverconfig/chunkplan-server.toml——没有 NeoForge 那种 config/ 主位置 +
     * serverconfig 覆盖层的两级语义。仍保留 config/ 兜底：存档级文件在首次进入世界前
     * 尚未生成时（异常时序）不至于抛错，路径只进服务端日志、不进聊天框（坑 #24）。
     */
    private static Path resolveConfigFile(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        Path serverConfigFile = server.getWorldPath(LevelResource.ROOT).resolve("serverconfig")
                .resolve("chunkplan-server.toml");
        Path fallbackFile = server.getServerDirectory().toPath().resolve("config")
                .resolve("chunkplan-server.toml");
        return Files.exists(serverConfigFile) ? serverConfigFile : fallbackFile;
    }

    /** /chunkplan config exemptByDefault：查询当前值（gamerule 风格，无权限要求） */
    private static int configExemptQuery(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        boolean value = eng.getConfig().exemptByDefault();
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§aexemptByDefault §7当前值: §f" + value,
                "§aexemptByDefault §7is currently set to: §f" + value)), false);
        return 1;
    }

    /** /chunkplan config exemptByDefault true|false：原子改写配置文件并热生效（重启保留） */
    private static int configExemptSet(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        boolean value = BoolArgumentType.getBool(ctx, "value");
        try {
            ForgeConfig.writeExemptByDefault(resolveConfigFile(ctx), value);
            List<String> warnings = loadAndApplyConfig(ctx);
            String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    "§a已设置 exemptByDefault = " + value + "（已写入配置文件并生效）",
                    "§aSet exemptByDefault to " + value + " (written to config and applied)") + warning), true);
            return 1;
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("ChunkPlan").error("写入配置失败", e);
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c写入配置失败，详见服务端日志",
                    "§cFailed to write config; see server log for details")));
            return 0;
        }
    }

    // ---------- 参数解析与补全 ----------

    /**
     * reset 目标参数补全：按已输入词数分阶段（坑 #35）——第 1 词补玩家名+选择器；
     * 第 2 词补窗口层级（tier1~tier4|all）；第 3 词起不再建议（reset 只接受
     * &lt;目标&gt; [层级] 两个词，无脑建议层级会把用户引入套娃死路）。greedy 参数建议需含完整剩余文本。
     */
    private static CompletableFuture<Suggestions> suggestResetTarget(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        List<String> words = new ArrayList<>();
        for (String w : remaining.split("\\s+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        int n = words.size();
        // 尾随空白用 isWhitespace 判定，与 split("\\s+") 一致（坑 #35 补丁：多空格/tab 不再误入第 1 词分支）
        int lastWs = -1;
        for (int i = remaining.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(remaining.charAt(i))) {
                lastWs = i;
                break;
            }
        }
        boolean trailingSpace = !remaining.isEmpty() && lastWs == remaining.length() - 1;
        if (n == 0 || (n == 1 && !trailingSpace)) {
            // 第 1 词输入中：玩家名 + 常用选择器
            MinecraftServer server = ctx.getSource().getServer();
            String prefix = remaining.toLowerCase();
            Set<String> seen = new HashSet<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                addName(builder, seen, prefix, p.getGameProfile().getName());
            }
            for (ServerPlayer p : DevCommands.MOCK_PLAYERS) {
                if (!p.isRemoved()) {
                    addName(builder, seen, prefix, p.getGameProfile().getName());
                }
            }
            for (String sel : List.of("@a", "@e", "@p", "@s", "@r")) {
                if (prefix.isEmpty() || sel.startsWith(prefix)) {
                    builder.suggest(sel);
                }
            }
            return builder.buildFuture();
        }
        if (n <= 2 && !(n == 2 && trailingSpace)) {
            // 第 2 词输入中或未完成：补全窗口层级（greedy 参数建议需含完整剩余文本）
            String head = lastWs >= 0 ? remaining.substring(0, lastWs + 1) : words.get(0) + " ";
            String tail = n == 2 ? words.get(1).toLowerCase() : "";
            for (String v : List.of("tier1", "tier2", "tier3", "tier4", "all")) {
                if (tail.isEmpty() || v.startsWith(tail)) {
                    builder.suggest(head + v);
                }
            }
            return builder.buildFuture();
        }
        // 第 3 词起：reset 只接受 <目标> [层级]，不再建议（坑 #35）
        return builder.buildFuture();
    }

    /** 玩家参数 tab 补全：在线（PlayerList + mock 注册表），去重并按前缀过滤（离线名需手动输入） */
    private static CompletableFuture<Suggestions> suggestPlayerNames(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        MinecraftServer server = ctx.getSource().getServer();
        String prefix = builder.getRemaining().toLowerCase();
        Set<String> seen = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            addName(builder, seen, prefix, p.getGameProfile().getName());
        }
        for (ServerPlayer p : DevCommands.MOCK_PLAYERS) {
            if (!p.isRemoved()) {
                addName(builder, seen, prefix, p.getGameProfile().getName());
            }
        }
        return builder.buildFuture();
    }

    private static void addName(SuggestionsBuilder builder, Set<String> seen, String prefix, String name) {
        if (name == null || name.isEmpty() || !seen.add(name)) {
            return;
        }
        if (prefix.isEmpty() || name.toLowerCase().startsWith(prefix)) {
            builder.suggest(name);
        }
    }

    /** 层级参数补全（含 all：config window / reset） */
    private static CompletableFuture<Suggestions> suggestTiers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggestFromList(builder, List.of("tier1", "tier2", "tier3", "tier4", "all"));
    }

    /** 层级参数补全（不含 all：windowTime / windowLimit） */
    private static CompletableFuture<Suggestions> suggestTiersNoAll(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggestFromList(builder, List.of("tier1", "tier2", "tier3", "tier4"));
    }

    private static CompletableFuture<Suggestions> suggestOnOff(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggestFromList(builder, List.of("on", "off"));
    }

    /** 窗口时长预置补全：按已选的 tier 给出该档预置列表 */
    private static CompletableFuture<Suggestions> suggestWindowPresets(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        int tier = parseTier(StringArgumentType.getString(ctx, "tier"));
        return suggestFromList(builder, presetsOf(tier));
    }

    private static CompletableFuture<Suggestions> suggestFromList(SuggestionsBuilder builder, List<String> values) {
        String prefix = builder.getRemaining().toLowerCase();
        for (String v : values) {
            if (prefix.isEmpty() || v.toLowerCase().startsWith(prefix)) {
                builder.suggest(v);
            }
        }
        return builder.buildFuture();
    }

    /**
     * 解析 reset 目标：@a/@e = 全部在线（PlayerList + mock）、@s = 自身、@p = 最近、@r = 随机；
     * 名字/UUID 走 resolvePlayerArg（离线可用）。返回去重后的 GameProfile 列表。
     */
    private static List<GameProfile> resolveTargets(CommandContext<CommandSourceStack> ctx, String arg) {
        MinecraftServer server = ctx.getSource().getServer();
        List<ServerPlayer> online = new ArrayList<>(server.getPlayerList().getPlayers());
        for (ServerPlayer p : DevCommands.MOCK_PLAYERS) {
            if (!p.isRemoved()) {
                online.add(p);
            }
        }
        if (arg.startsWith("@")) {
            return switch (arg) {
                case "@a", "@e" -> online.stream().map(ServerPlayer::getGameProfile).toList();
                case "@s" -> {
                    ServerPlayer self = ctx.getSource().getPlayer();
                    yield self == null ? List.of() : List.of(self.getGameProfile());
                }
                case "@p" -> {
                    ServerPlayer self = ctx.getSource().getPlayer();
                    if (self == null) {
                        yield online.isEmpty() ? List.of() : List.of(online.get(0).getGameProfile());
                    }
                    ServerPlayer nearest = null;
                    double best = Double.MAX_VALUE;
                    for (ServerPlayer p : online) {
                        double d = p.distanceToSqr(self);
                        if (d < best) {
                            best = d;
                            nearest = p;
                        }
                    }
                    yield nearest == null ? List.of() : List.of(nearest.getGameProfile());
                }
                case "@r" -> online.isEmpty()
                        ? List.of()
                        : List.of(online.get(ThreadLocalRandom.current().nextInt(online.size())).getGameProfile());
                default -> List.of(); // 不支持的选择器（@p 带参数等）按未找到处理
            };
        }
        GameProfile profile = resolvePlayerArg(server, arg);
        return profile == null ? List.of() : List.of(profile);
    }

    /**
     * 解析玩家参数：在线实体（PlayerList 真实玩家 + 世界实体含 mock）优先，避免 usercache
     * 旧 uuid 映射（mock 玩家曾以不同 uuid 入服）误伤；其次 UUID 直解；最后 profile cache。
     * 不用原版 GameProfileArgument：1.21.1 原版把 UUID 当玩家名查缓存（Fabric 端不可用）。
     */
    private static GameProfile resolvePlayer(CommandContext<CommandSourceStack> ctx) {
        return resolvePlayerArg(ctx.getSource().getServer(), ctx.getArgument("player", String.class));
    }

    /** 名字/UUID 解析（check 与 reset 目标共用；离线名走 profile cache） */
    private static GameProfile resolvePlayerArg(MinecraftServer server, String arg) {
        ServerPlayer online = DevCommands.findByName(server, arg);
        if (online != null) {
            return online.getGameProfile();
        }
        try {
            return new GameProfile(UUID.fromString(arg), "");
        } catch (IllegalArgumentException ignored) {
            // 不是 UUID：按名字解析
        }
        Optional<GameProfile> cached = server.getProfileCache().get(arg);
        return cached.orElse(null);
    }

    /** "tier1".."tier4" -> 1..4；非法返回 -1 */
    private static int parseTier(String s) {
        if (s.length() != 5 || !s.startsWith("tier")) {
            return -1;
        }
        char c = s.charAt(4);
        return c >= '1' && c <= '4' ? c - '0' : -1;
    }

    /** 引擎当前额度线中查找档位（未启用返回 null） */
    private static QuotaConfig.Line findLine(QuotaEngine eng, int tier) {
        for (QuotaConfig.Line line : eng.getConfig().lines()) {
            if (line.tier() == tier) {
                return line;
            }
        }
        return null;
    }

    /** 档位窗口预置列表（与 QuotaTiers 单一来源一致） */
    private static List<String> presetsOf(int tier) {
        return switch (tier) {
            case 1 -> QuotaTiers.TIER1_WINDOWS;
            case 2 -> QuotaTiers.TIER2_WINDOWS;
            case 3 -> QuotaTiers.TIER3_WINDOWS;
            case 4 -> QuotaTiers.TIER4_WINDOWS;
            default -> List.of();
        };
    }

    /** 由 UUID 反查显示名：在线/mock 优先，其次 profile cache，最后 UUID 串 */
    private static String nameOf(MinecraftServer server, UUID uuid) {
        ServerPlayer p = DevCommands.findByUuid(server, uuid);
        if (p != null) {
            String n = p.getGameProfile().getName();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        }
        Optional<GameProfile> cached = server.getProfileCache().get(uuid);
        if (cached.isPresent()) {
            String n = cached.get().getName();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        }
        return uuid.toString();
    }

    /** 执行者语言判定：玩家按客户端语言，控制台/rcon 默认英文 */
    private static boolean isZh(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        return ChunkPlanMessages.isChinese(p == null ? null : p.getLanguage());
    }

    private static String t(CommandContext<CommandSourceStack> ctx, String zhText, String enText) {
        return isZh(ctx) ? zhText : enText;
    }

    private static String profileName(GameProfile profile) {
        String name = profile.getName();
        return name != null && !name.isEmpty() ? name : profile.getId().toString();
    }
}
