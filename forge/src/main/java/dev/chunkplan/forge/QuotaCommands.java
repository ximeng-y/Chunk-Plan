package dev.chunkplan.forge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import dev.chunkplan.common.DurationParser;
import dev.chunkplan.common.FeeLogFile;
import dev.chunkplan.common.DimensionStore;
import dev.chunkplan.common.NumericParser;
import dev.chunkplan.common.PresetStore;
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
 * 待确认动作按发起者分槽 + 60 秒超时（同一发起者的新动作覆盖自己的旧动作，无队列），
 * 确认以超链接形式发起（坑 #30）。
 * 所有玩家可见文案按执行者客户端语言渲染（中/英，坑 #22）。
 */
public final class QuotaCommands {

    /** 待确认动作超时（毫秒） */
    private static final long CONFIRM_WINDOW_MILLIS = 60_000;

    /** 控制台/rcon 发起的待确认动作归集槽位（owner 为 null 时用作 key） */
    private static final UUID CONSOLE_SLOT = new UUID(0, 0);

    /**
     * 待确认动作（命令在服务端主线程串行执行）按发起者分槽：单槽时代管理员 B 发起动作会
     * 静默覆盖管理员 A 的待确认项（A 确认时报"无待确认操作"），分槽后各发起者互不干扰；
     * 同一发起者的新动作仍覆盖自己的旧动作（无队列，60 秒超时，确认后即移除）。
     */
    private static final Map<UUID, PendingAction> PENDING = new ConcurrentHashMap<>();

    /**
     * 待确认动作。
     *  owner（坑 #58）：发起者 UUID，控制台/rcon 为 null（归入 {@link #CONSOLE_SLOT} 槽，
     *  任何 OP 可确认——rcon 冒烟依赖此路径）。
     */
    private sealed interface PendingAction {
        long expireMillis();

        /** 发起者 UUID；null = 控制台/rcon（不校验，rcon 冒烟依赖此路径） */
        UUID owner();

        /** reset：目标玩家 UUID + 档位集合（null = 全部档位） */
        record Reset(List<UUID> targets, Set<Integer> tiers, long expireMillis, UUID owner) implements PendingAction {
        }

        /** 关闭窗口（tier=0 表示全部窗口；关闭会清空该窗口所有玩家记录） */
        record DisableWindow(int tier, long expireMillis, UUID owner) implements PendingAction {
        }

        /** 调低额度：档位 + 新值原文（可能引发在线玩家无警告踢出） */
        record LowerLimit(int tier, String rawValue, long expireMillis, UUID owner) implements PendingAction {
        }

        /**
         * 应用预设到全体：仅写全局配置（12 值），<b>不改动玩家消费记录</b>。
         * 被关闭的档位记录保留，重新开启后若周期未过则继承原有消费——固定周期账本（坑 #40，
         * 数据 v3/v4）与档位开关完全解耦：周期存在 tiers[档位]（独立模式 dimTiers[维度][档位]），
         * 读路径按当前配置窗口长现算、过期即从 0 起，故本动作无需清档或任何补偿机制；
         * 显式"关掉并从 0 重来"是另一条路径：config window &lt;tier|all&gt; off
         * （{@link DisableWindow}）与维度版（{@link DisableDimWindow}），坑 #58。
         */
        record ApplyPreset(String name, long expireMillis, UUID owner) implements PendingAction {
        }

        /** 关闭维度窗口（issue #3，tier=0 表示该维度全部窗口；清空该维度该窗口所有玩家记录） */
        record DisableDimWindow(String dim, int tier, long expireMillis, UUID owner) implements PendingAction {
        }

        /** 调低维度额度上限（issue #3）：维度 + 档位 + 新值原文（可能引发在线玩家无警告踢出） */
        record LowerDimLimit(String dim, int tier, String rawValue, long expireMillis, UUID owner)
                implements PendingAction {
        }
    }

    /** 待确认动作发起者：控制台/rcon 无玩家（返回 null 即不校验，保证 rcon 可自测，坑 #58） */
    private static UUID ownerOf(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        return p == null ? null : p.getUUID();
    }

    /** 登记待确认动作：按发起者分槽（控制台/rcon 归 CONSOLE_SLOT），同发起者新动作覆盖旧动作 */
    private static void putPending(PendingAction action) {
        PENDING.put(action.owner() == null ? CONSOLE_SLOT : action.owner(), action);
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
                                        .executes(ctx -> configSpeedMultiplier(ctx))))
                        .then(Commands.literal("firstEntryFee")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("number", StringArgumentType.word())
                                        .executes(ctx -> configFirstEntryFee(ctx))))
                        .then(Commands.literal("familiarEntryFee")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("number", StringArgumentType.word())
                                        .executes(ctx -> configFamiliarEntryFee(ctx))))
                        .then(Commands.literal("dimensionMode")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests(QuotaCommands::suggestDimensionMode)
                                        .executes(ctx -> configDimensionMode(ctx))))
                        .then(Commands.literal("dimension")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("dim", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .suggests(QuotaCommands::suggestDimensionKeys)
                                        .then(Commands.literal("billing")
                                                .then(Commands.argument("state", StringArgumentType.word())
                                                        .suggests(QuotaCommands::suggestOnOff)
                                                        .executes(ctx -> configDimBilling(ctx))))
                                        .then(Commands.literal("spawn")
                                                .then(Commands.literal("clear")
                                                        .executes(ctx -> configDimSpawnClear(ctx)))
                                                .then(Commands.argument("x", StringArgumentType.word())
                                                        .then(Commands.argument("y", StringArgumentType.word())
                                                                .then(Commands.argument("z", StringArgumentType.word())
                                                                        .executes(ctx -> configDimSpawn(ctx))))))
                                        .then(Commands.literal("window")
                                                .then(Commands.argument("tier", StringArgumentType.word())
                                                        .suggests(QuotaCommands::suggestTiers)
                                                        .then(Commands.argument("state", StringArgumentType.word())
                                                                .suggests(QuotaCommands::suggestOnOff)
                                                                .executes(ctx -> configDimWindow(ctx)))))
                                        .then(Commands.literal("windowTime")
                                                .then(Commands.argument("tier", StringArgumentType.word())
                                                        .suggests(QuotaCommands::suggestTiersNoAll)
                                                        .then(Commands.argument("window", StringArgumentType.word())
                                                                .suggests(QuotaCommands::suggestWindowPresets)
                                                                .executes(ctx -> configDimWindowTime(ctx)))))
                                        .then(Commands.literal("windowLimit")
                                                .then(Commands.argument("tier", StringArgumentType.word())
                                                        .suggests(QuotaCommands::suggestTiersNoAll)
                                                        .then(Commands.argument("number", StringArgumentType.word())
                                                                .executes(ctx -> configDimWindowLimit(ctx)))))))
                        .then(Commands.literal("redirect")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .suggests(QuotaCommands::suggestOnOff)
                                        .executes(ctx -> configRedirect(ctx))))
                        .then(Commands.literal("redirectTarget")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("slot", StringArgumentType.word())
                                        .suggests(QuotaCommands::suggestRedirectSlots)
                                        .then(Commands.argument("dim", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                                .suggests(QuotaCommands::suggestRedirectTargetValues)
                                                .executes(ctx -> configRedirectTarget(ctx))))))
                .then(Commands.literal("preset")
                        .then(Commands.literal("list")
                                .requires(s -> s.hasPermission(2))
                                .executes(ctx -> presetList(ctx)))
                        .then(Commands.literal("save")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .suggests(QuotaCommands::suggestPresetSave)
                                        .executes(ctx -> presetSave(ctx))))
                        .then(Commands.literal("delete")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .suggests(QuotaCommands::suggestPresetNames)
                                        .executes(ctx -> presetDelete(ctx))))
                        .then(Commands.literal("apply")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .suggests(QuotaCommands::suggestPresetNames)
                                        .executes(ctx -> presetApply(ctx))))
                        .then(Commands.literal("player")
                                .requires(s -> s.hasPermission(2))
                                .then(Commands.argument("target", StringArgumentType.greedyString())
                                        .suggests(QuotaCommands::suggestPresetTarget)
                                        .executes(ctx -> presetPlayer(ctx)))))
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
        String dimKey = ChunkPlanForge.engine != null && ChunkPlanForge.engine.isIndependentMode()
                ? player.level().dimension().location().toString() : null;
        sendStatus(ctx, player.getUUID(), player.getGameProfile().getName(), true, player.hasPermissions(2), dimKey);
        return 1;
    }

    private static int checkOther(CommandContext<CommandSourceStack> ctx) {
        GameProfile profile = resolvePlayer(ctx);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "未找到该玩家", "Player not found")));
            return 0;
        }
        // 目标在线：权限状态可取；离线玩家 OP 状态不可查，仅判豁免名单（不误报 OP 豁免）
        QuotaEngine eng = ChunkPlanForge.engine;
        ServerPlayer online = DevCommands.findByUuid(ctx.getSource().getServer(), profile.getId());
        // 独立模式（issue #3）：仅展示目标玩家当前维度用量；离线玩家取最后在线维度
        String dimKey = null;
        if (eng != null && eng.isIndependentMode()) {
            dimKey = online != null
                    ? online.level().dimension().location().toString()
                    : eng.lastDimOf(profile.getId());
            if (dimKey == null) {
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "维度独立模式下暂无该玩家的维度记录",
                        "Per-dimension mode: no dimension record for this player yet")));
                if (online == null) {
                    // 离线目标的数据是本次检查懒加载的，不会再触发登出事件——用完即回收
                    eng.unloadPlayerData(profile.getId());
                }
                return 0;
            }
        }
        sendStatus(ctx, profile.getId(), profileName(profile), false, online != null && online.hasPermissions(2), dimKey);
        if (eng != null && online == null) {
            // 同上：离线目标数据用完即回收（数据已是最新，无需落盘的改动也会被跳过）
            eng.unloadPlayerData(profile.getId());
        }
        return 1;
    }

    private static void sendStatus(CommandContext<CommandSourceStack> ctx, UUID uuid, String name, boolean self,
                                   boolean isOp, String dimKey) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return;
        }
        boolean zh = isZh(ctx);
        // 豁免判定在命令侧：查他人时离线玩家权限不可查，仅判豁免名单（坑 #21 语义）
        boolean inList = eng.getConfig().exemptPlayers().contains(uuid);
        QuotaEngine.QuotaStatus status = dimKey == null ? eng.quotaStatus(uuid) : eng.quotaStatus(uuid, dimKey);
        String text = ChunkPlanMessages.checkStatusText(name, status, self, zh,
                eng.isExempt(uuid, isOp), inList, dimKey);
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
        // 窗口参数：缺省 = all（全部档位）；显式选择未启用档位无效（坑 #30）；
        // 独立模式各维度窗口互不相同，按"启用任意处即可清"放行（reset 清全部维度，issue #3）
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
                if (!eng.isIndependentMode() && findLine(eng, tier) == null) {
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
        putPending(new PendingAction.Reset(uuids, tiers, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS, ownerOf(ctx)));
        boolean zh = isZh(ctx);
        String zhScope;
        String enScope;
        if (tiers == null) {
            zhScope = "全部";
            enScope = "all windows";
        } else if (eng.isIndependentMode()) {
            // 独立模式各维度窗口互不相同：范围以档位身份表述（tierN）
            zhScope = "tier" + tiers.iterator().next();
            enScope = "tier" + tiers.iterator().next();
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

    /** /chunkplan confirm：执行发起者槽位中的待确认动作（60 秒超时；消费后即清，单发） */
    private static int confirm(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        // 按发起者分槽取出（先消费再执行，单发避免重复确认）：玩家从自己的槽取；
        // 自己槽为空时回退控制台槽——控制台/rcon 发起的动作任何 OP 均可确认（坑 #58 语义保留）。
        // 分槽后管理员之间互不可见对方的待确认项，天然不可能点掉别人的动作。
        ServerPlayer confirmer = ctx.getSource().getPlayer();
        PendingAction req = PENDING.remove(confirmer == null ? CONSOLE_SLOT : confirmer.getUUID());
        if (req == null && confirmer != null) {
            req = PENDING.remove(CONSOLE_SLOT);
        }
        if (req == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "当前没有待确认的操作", "No pending action to confirm.")));
            return 0;
        }
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
            } else if (eng.isIndependentMode()) {
                // 独立模式各维度窗口互不相同：范围以档位身份表述（issue #3）
                zhScope = "tier" + r.tiers().iterator().next();
                enScope = "tier" + r.tiers().iterator().next();
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
                } else {
                    // 离线目标的数据是本次重置懒加载的（resetSpend 已落盘），用完即回收
                    eng.unloadPlayerData(uuid);
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
        if (req instanceof PendingAction.LowerDimLimit l) {
            // 调低维度额度上限（issue #3）：改写该维度四档快照中对应档的 limit
            List<QuotaTiers.Tier> tiers = eng.getDimensionStore().tiers(l.dim());
            if (tiers == null || tiers.size() != 4) {
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "§c维度配置已失效，详见服务端日志",
                        "§cDimension config is no longer valid; see server log for details")));
                return 0;
            }
            List<QuotaTiers.Tier> updated = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                QuotaTiers.Tier t = tiers.get(i);
                updated.add(new QuotaTiers.Tier(t.enabled(), t.window(),
                        i + 1 == l.tier() ? Double.parseDouble(l.rawValue()) : t.limit()));
            }
            if (!eng.setDimensionTiers(l.dim(), updated)) {
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "§c写入维度配置失败（档位校验未通过）",
                        "§cFailed to write dimension config (tier validation failed)")));
                return 0;
            }
            String win = windowLabelOf(tiers.get(l.tier() - 1).window(), zh);
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    "§a已调整维度 " + l.dim() + " 的 " + win + " 额度为 §b" + l.rawValue() + "§a",
                    "§aAdjusted the " + win + " limit of dimension " + l.dim() + " to §b" + l.rawValue() + "§a")), true);
            return 1;
        }
        if (req instanceof PendingAction.DisableDimWindow d) {
            // 关闭维度窗口（issue #3）：清空该维度该窗口所有玩家记录，重开从 0 起（坑 #30 语义）
            if (d.tier() == 0) {
                eng.clearDimSpendForAll(d.dim(), null);
            } else {
                eng.clearDimSpendForAll(d.dim(), Set.of(d.tier()));
            }
            String tierName = d.tier() == 0
                    ? (zh ? "全部窗口" : "all windows")
                    : (zh ? "窗口 tier" + d.tier() : "window tier" + d.tier());
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    "§a已关闭维度 " + d.dim() + " 的 " + tierName + "（该维度该窗口所有玩家记录已清空）",
                    "§aDisabled " + tierName + " of dimension " + d.dim() + " (records cleared for all players)")), true);
            return 1;
        }
        if (req instanceof PendingAction.ApplyPreset a) {
            // 应用预设到全体（issue #1）：把预设 12 值写回配置文件 + loadAndApplyConfig 热生效
            PresetStore.Preset p = eng.getPresetStore().get(a.name());
            if (p == null) {
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "预设 " + a.name() + " 已不存在",
                        "Preset " + a.name() + " no longer exists")));
                return 0;
            }
            try {
                // 仅写 12 值到全局配置（坑 #58）：不再对被关闭的档位 clearTierSpendForAll——
                // 固定周期账本与档位开关解耦（周期存 tiers[档位]/dimTiers[维度][档位]，读路径按
                // 当前窗口长现算、过期自然从 0 起），关档保留记录后重新开启，周期未过即继承原有
                // 消费（用户要的"试做预设不改现状"）。"关掉并从 0 重来"是显式动作：
                // config window <tier|all> off（PendingAction.DisableWindow）及其维度版
                for (int tier = 1; tier <= 4; tier++) {
                    QuotaTiers.Tier t = p.tiers().get(tier - 1);
                    ForgeConfig.writeTierEnabled(resolveConfigFile(ctx), tier, t.enabled());
                    ForgeConfig.writeTierWindow(resolveConfigFile(ctx), tier, t.window());
                    ForgeConfig.writeTierLimit(resolveConfigFile(ctx), tier, t.limit());
                }
                List<String> warnings = loadAndApplyConfig(ctx);
                // 零线（坑 #31）：立即解除 ChunkPlan 来源临时封禁
                if (eng.getConfig().lines().isEmpty()) {
                    ChunkPlanForge.GameEvents.scanBans(ctx.getSource().getServer());
                }
                String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
                ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                        "§a已应用预设 §b" + a.name() + "§a：写入全局配置，对全体玩家生效",
                        "§aApplied preset §b" + a.name() + "§a: written to the global config, effective for all players")
                        + warning), true);
                return 1;
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger("ChunkPlan").error("confirm 应用预设失败", e);
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
        if (eng.isIndependentMode()) {
            // 维度独立模式（issue #3）：全局额度线不生效，指令层面同步阻止调整（用户拍板）
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c维度独立模式下全局额度线不生效，请使用 /chunkplan config dimension <维度> window ... 命令族",
                    "§cGlobal quota lines are inactive in per-dimension mode; use /chunkplan config dimension <dim> window ...")));
            return 0;
        }
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
        putPending(new PendingAction.DisableWindow(tier, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS, ownerOf(ctx)));
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
        if (eng.isIndependentMode()) {
            // 维度独立模式（issue #3）：全局额度线不生效，指令层面同步阻止调整
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c维度独立模式下全局额度线不生效，请使用 /chunkplan config dimension <维度> windowTime ... 命令族",
                    "§cGlobal quota lines are inactive in per-dimension mode; use /chunkplan config dimension <dim> windowTime ...")));
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

    /** /chunkplan config firstEntryFee <数值>：调整踏入未探索区块的费用（0.00~999999999.99，≤2 位小数） */
    private static int configFirstEntryFee(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, "number");
        NumericParser.Parsed p = NumericParser.parseFee(raw);
        if (!p.isOk()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c新区块费用需为 0.00~999999999.99 的数字，最多 2 位小数",
                    "§cNew chunk fee must be a number between 0.00 and 999999999.99 with at most 2 decimals")));
            return 0;
        }
        try {
            ForgeConfig.writeFirstEntryFee(resolveConfigFile(ctx), p.value());
            List<String> warnings = loadAndApplyConfig(ctx);
            String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    "§a新区块费用已调整为 §b" + raw + "§a",
                    "§aNew chunk fee set to §b" + raw + "§a") + warning), true);
            return 1;
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("ChunkPlan").error("写入配置失败", e);
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c写入配置失败，详见服务端日志",
                    "§cFailed to write config; see server log for details")));
            return 0;
        }
    }

    /** /chunkplan config familiarEntryFee <数值>：调整踏入已探索区块的费用（0.00~999999999.99，≤2 位小数） */
    private static int configFamiliarEntryFee(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, "number");
        NumericParser.Parsed p = NumericParser.parseFee(raw);
        if (!p.isOk()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c旧区块费用需为 0.00~999999999.99 的数字，最多 2 位小数",
                    "§cExplored chunk fee must be a number between 0.00 and 999999999.99 with at most 2 decimals")));
            return 0;
        }
        try {
            ForgeConfig.writeFamiliarEntryFee(resolveConfigFile(ctx), p.value());
            List<String> warnings = loadAndApplyConfig(ctx);
            String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    "§a旧区块费用已调整为 §b" + raw + "§a",
                    "§aExplored chunk fee set to §b" + raw + "§a") + warning), true);
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
        if (eng.isIndependentMode()) {
            // 维度独立模式（issue #3）：全局额度线不生效，指令层面同步阻止调整
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c维度独立模式下全局额度线不生效，请使用 /chunkplan config dimension <维度> windowLimit ... 命令族",
                    "§cGlobal quota lines are inactive in per-dimension mode; use /chunkplan config dimension <dim> windowLimit ...")));
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
            putPending(new PendingAction.LowerLimit(tier, raw, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS, ownerOf(ctx)));
            boolean zh = isZh(ctx);
            // 坑 #32：前置提示窗口名化（与 reset 确认提示一致）
            String winZh = ChunkPlanMessages.windowName(line.windowSeconds(), true);
            String winEn = ChunkPlanMessages.windowName(line.windowSeconds(), false).toLowerCase();
            Component msg = Component.literal(t(ctx,
                    "§a将把 " + winZh + " 额度从 §b" + line.limit() + "§a 调低至 §b" + raw
                            + "§a，可能引发部分玩家被无警告踢出；低于部分玩家当前用量时会当场生效，",
                    "§aThis will lower " + winEn + " limit from §b" + line.limit() + "§a to §b" + raw
                            + "§a; some players may be kicked without warning. If it is below a player's current "
                            + "usage it takes effect immediately, "))
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

    // ---------- 维度计费命令族（issue #3） ----------

    /** /chunkplan config dimensionMode <shared|independent>：切换维度计费模式（独立模式前置校验全部 live 维度落地坐标） */
    private static int configDimensionMode(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String mode = StringArgumentType.getString(ctx, "mode");
        if (!mode.equals("shared") && !mode.equals("independent")) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "模式需为 shared（全维度共享额度）或 independent（每维度独立）",
                    "Mode must be shared (quota shared across dimensions) or independent (per-dimension)")));
            return 0;
        }
        List<String> liveDims = ChunkPlanForge.GameEvents.liveDims(ctx.getSource().getServer());
        List<String> missing = eng.setDimensionMode(mode, liveDims);
        if (!missing.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c无法启用维度独立计费，以下维度缺少合法落地坐标（先用 /chunkplan config dimension <维度> spawn <x> <y> <z> 配置）：§f"
                            + String.join("、", missing),
                    "§cCannot enable per-dimension billing; these dimensions lack valid landing coordinates (set with /chunkplan config dimension <dim> spawn <x> <y> <z>): §f"
                            + String.join(", ", missing))));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                mode.equals("independent")
                        ? "§a已切换到维度独立计费（各维度额度线已用当前全局配置初始化；全局 window/preset apply 已停用）"
                        : "§a已切换到共享计费（全局额度线与预设恢复生效；维度配置保留）",
                mode.equals("independent")
                        ? "§aSwitched to per-dimension billing (each dimension initialized from the current global config; global window/preset apply are now inactive)"
                        : "§aSwitched to shared billing (global quota lines and presets are back; dimension configs kept)")), true);
        return 1;
    }

    /** 校验维度参数：必须在世界 live 维度列表中（动态 getAllLevels，防手滑写错 key） */
    private static String requireLiveDim(CommandContext<CommandSourceStack> ctx, QuotaEngine eng, String dim) {
        if (!ChunkPlanForge.GameEvents.liveDims(ctx.getSource().getServer()).contains(dim)) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "未知维度: " + dim + "（维度列表来自服务器动态读取，含 mod 注册维度）",
                    "Unknown dimension: " + dim + " (dimensions are read live from the server, including mod-registered ones)")));
            return null;
        }
        return dim;
    }

    /** /chunkplan config dimension <dim> billing <on|off>：维度计费开关（两种模式通用，关 = 不计费可自由进入） */
    private static int configDimBilling(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String dim = requireLiveDim(ctx, eng, net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "dim").toString());
        if (dim == null) {
            return 0;
        }
        String stateArg = StringArgumentType.getString(ctx, "state");
        if (!stateArg.equals("on") && !stateArg.equals("off")) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "开关参数需为 on 或 off", "State must be on or off")));
            return 0;
        }
        boolean enable = stateArg.equals("on");
        eng.setDimensionBilling(dim, enable);
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                enable ? "§a已开启维度 " + dim + " 的计费" : "§a已关闭维度 " + dim + " 的计费（该维度不计费、可自由进入）",
                enable ? "§aEnabled billing for dimension " + dim
                       : "§aDisabled billing for dimension " + dim + " (not billed, free to explore)")), true);
        return 1;
    }

    /** /chunkplan config dimension <dim> spawn <x> <y> <z>：设置默认落地坐标（/tp 数据规范校验） */
    private static int configDimSpawn(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String dim = requireLiveDim(ctx, eng, net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "dim").toString());
        if (dim == null) {
            return 0;
        }
        double x;
        double y;
        double z;
        try {
            x = Double.parseDouble(StringArgumentType.getString(ctx, "x"));
            y = Double.parseDouble(StringArgumentType.getString(ctx, "y"));
            z = Double.parseDouble(StringArgumentType.getString(ctx, "z"));
        } catch (NumberFormatException e) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c坐标需为数字（同 /tp 数据规范，x/z ∈ ±30000000，y ∈ [-2048, 4096]）",
                    "§cCoordinates must be numbers (same spec as /tp; x/z within ±30000000, y within [-2048, 4096])")));
            return 0;
        }
        if (!eng.setDimensionSpawn(dim, x, y, z)) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c坐标非法：x/z ∈ ±30000000，y ∈ [-2048, 4096]",
                    "§cInvalid coordinates: x/z within ±30000000, y within [-2048, 4096]")));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§a已设置维度 " + dim + " 的默认落地坐标：§b" + x + ", " + y + ", " + z,
                "§aSet the landing coordinates of dimension " + dim + " to §b" + x + ", " + y + ", " + z)), true);
        return 1;
    }

    /** /chunkplan config dimension <dim> spawn clear：清空默认落地坐标（计费开关与额度线保留） */
    private static int configDimSpawnClear(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String dim = requireLiveDim(ctx, eng, net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "dim").toString());
        if (dim == null) {
            return 0;
        }
        eng.clearDimensionSpawn(dim);
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§a已清空维度 " + dim + " 的默认落地坐标（独立模式需重新配置才能作为重定向落点）",
                "§aCleared the landing coordinates of dimension " + dim
                        + " (per-dimension mode requires them again for redirect)")), true);
        return 1;
    }

    /** /chunkplan config dimension <dim> window <tier|all> <on|off>：维度窗口开关（仅独立模式；off 清该维度记录需 confirm） */
    private static int configDimWindow(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String dim = requireLiveDim(ctx, eng, net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "dim").toString());
        if (dim == null) {
            return 0;
        }
        if (!eng.isIndependentMode()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c维度 window 命令仅在维度独立模式下可用；共享模式请使用 /chunkplan config window ...",
                    "§cThe dimension window command is only available in per-dimension mode; use /chunkplan config window ... in shared mode")));
            return 0;
        }
        List<QuotaTiers.Tier> tiers = eng.getDimensionStore().tiers(dim);
        if (tiers == null || tiers.size() != 4) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c该维度额度线尚未初始化（切换到独立模式时会自动初始化）",
                    "§cQuota lines of this dimension are not initialized yet (they are initialized when switching to per-dimension mode)")));
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
            // 开启不清空记录：沿用该维度既有的窗口/上限原值
            List<QuotaTiers.Tier> updated = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                QuotaTiers.Tier t = tiers.get(i);
                updated.add(new QuotaTiers.Tier(all || i + 1 == tier || t.enabled(), t.window(), t.limit()));
            }
            if (!eng.setDimensionTiers(dim, updated)) {
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "§c写入维度配置失败（档位校验未通过）",
                        "§cFailed to write dimension config (tier validation failed)")));
                return 0;
            }
            String tierName = all ? (zh ? "全部窗口" : "all windows") : "tier" + tier;
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    "§a已开启维度 " + dim + " 的 " + tierName,
                    "§aEnabled " + tierName + " of dimension " + dim)), true);
            return 1;
        }
        // 关闭：清空该维度该窗口所有玩家记录 -> confirm
        putPending(new PendingAction.DisableDimWindow(dim, tier, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS, ownerOf(ctx)));
        String tierName = all ? (zh ? "全部窗口" : "all windows") : "tier" + tier;
        Component msg = Component.literal(t(ctx,
                "§a将关闭维度 " + dim + " 的 " + tierName + "，并清空该维度该窗口所有玩家的记录，",
                "§aThis will disable " + tierName + " of dimension " + dim + " and clear all players' records for it, "))
                .append(ChunkPlanMessages.confirmLink(zh));
        ctx.getSource().sendSuccess(() -> msg, true);
        return 1;
    }

    /** /chunkplan config dimension <dim> windowTime <tier> <预设时长>：调整维度窗口时长（仅独立模式） */
    private static int configDimWindowTime(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String dim = requireLiveDim(ctx, eng, net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "dim").toString());
        if (dim == null) {
            return 0;
        }
        if (!eng.isIndependentMode()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c维度 windowTime 命令仅在维度独立模式下可用；共享模式请使用 /chunkplan config windowTime ...",
                    "§cThe dimension windowTime command is only available in per-dimension mode; use /chunkplan config windowTime ... in shared mode")));
            return 0;
        }
        List<QuotaTiers.Tier> tiers = eng.getDimensionStore().tiers(dim);
        if (tiers == null || tiers.size() != 4) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c该维度额度线尚未初始化（切换到独立模式时会自动初始化）",
                    "§cQuota lines of this dimension are not initialized yet (they are initialized when switching to per-dimension mode)")));
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
        if (!tiers.get(tier - 1).enabled()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "该窗口未启用，请先使用 /chunkplan config dimension " + dim + " window tier" + tier + " on 开启",
                    "This window is not enabled. Enable it first with /chunkplan config dimension " + dim + " window tier" + tier + " on")));
            return 0;
        }
        List<QuotaTiers.Tier> updated = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            QuotaTiers.Tier t = tiers.get(i);
            updated.add(new QuotaTiers.Tier(t.enabled(), i + 1 == tier ? windowArg : t.window(), t.limit()));
        }
        if (!eng.setDimensionTiers(dim, updated)) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c写入维度配置失败（档位校验未通过）",
                    "§cFailed to write dimension config (tier validation failed)")));
            return 0;
        }
        long secs = DurationParser.parseSeconds(windowArg); // 预置值，解析必成功
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§a已调整维度 " + dim + " 的计费窗口刷新时长为 §b" + ChunkPlanMessages.windowName(secs, true) + "§a",
                "§aAdjusted the billing window refresh duration of dimension " + dim + " to §b"
                        + ChunkPlanMessages.windowName(secs, false).toLowerCase() + "§a")), true);
        return 1;
    }

    /** /chunkplan config dimension <dim> windowLimit <tier> <数值>：调整维度额度上限（仅独立模式；调低需 confirm） */
    private static int configDimWindowLimit(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String dim = requireLiveDim(ctx, eng, net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "dim").toString());
        if (dim == null) {
            return 0;
        }
        if (!eng.isIndependentMode()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c维度 windowLimit 命令仅在维度独立模式下可用；共享模式请使用 /chunkplan config windowLimit ...",
                    "§cThe dimension windowLimit command is only available in per-dimension mode; use /chunkplan config windowLimit ... in shared mode")));
            return 0;
        }
        List<QuotaTiers.Tier> tiers = eng.getDimensionStore().tiers(dim);
        if (tiers == null || tiers.size() != 4) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c该维度额度线尚未初始化（切换到独立模式时会自动初始化）",
                    "§cQuota lines of this dimension are not initialized yet (they are initialized when switching to per-dimension mode)")));
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
        if (!tiers.get(tier - 1).enabled()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "该窗口未启用，请先使用 /chunkplan config dimension " + dim + " window tier" + tier + " on 开启",
                    "This window is not enabled. Enable it first with /chunkplan config dimension " + dim + " window tier" + tier + " on")));
            return 0;
        }
        QuotaTiers.Tier current = tiers.get(tier - 1);
        if (p.value() < current.limit()) {
            // 调低：可能引发在线玩家无警告踢出 -> confirm
            putPending(new PendingAction.LowerDimLimit(dim, tier, raw, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS, ownerOf(ctx)));
            boolean zh = isZh(ctx);
            String winZh = windowLabelOf(current.window(), true);
            String winEn = windowLabelOf(current.window(), false).toLowerCase();
            Component msg = Component.literal(t(ctx,
                    "§a将把维度 " + dim + " 的 " + winZh + " 额度从 §b" + current.limit() + "§a 调低至 §b" + raw
                            + "§a，可能引发部分玩家被无警告踢出；低于部分玩家当前用量时会当场生效，",
                    "§aThis will lower the " + winEn + " limit of dimension " + dim + " from §b" + current.limit()
                            + "§a to §b" + raw + "§a; some players may be kicked without warning. If it is below a "
                            + "player's current usage it takes effect immediately, "))
                    .append(ChunkPlanMessages.confirmLink(zh));
            ctx.getSource().sendSuccess(() -> msg, true);
            return 1;
        }
        List<QuotaTiers.Tier> updated = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            QuotaTiers.Tier t = tiers.get(i);
            updated.add(new QuotaTiers.Tier(t.enabled(), t.window(), i + 1 == tier ? p.value() : t.limit()));
        }
        if (!eng.setDimensionTiers(dim, updated)) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c写入维度配置失败（档位校验未通过）",
                    "§cFailed to write dimension config (tier validation failed)")));
            return 0;
        }
        boolean zh = isZh(ctx);
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§a已调整维度 " + dim + " 的 " + windowLabelOf(current.window(), zh) + " 额度为 §b" + raw + "§a",
                "§aAdjusted the " + windowLabelOf(current.window(), zh) + " limit of dimension " + dim + " to §b" + raw + "§a")), true);
        return 1;
    }

    /** /chunkplan config redirect <on|off>：耗尽传送其它维度开关（仅独立模式） */
    private static int configRedirect(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        if (!eng.isIndependentMode()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c耗尽传送其它维度仅在维度独立模式下可用",
                    "§cTeleport-on-exhaust is only available in per-dimension mode")));
            return 0;
        }
        String stateArg = StringArgumentType.getString(ctx, "state");
        if (!stateArg.equals("on") && !stateArg.equals("off")) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "开关参数需为 on 或 off", "State must be on or off")));
            return 0;
        }
        boolean enable = stateArg.equals("on");
        eng.setRedirectOnExhaust(enable);
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                enable ? "§a已开启耗尽传送其它维度（额度耗尽的玩家将被传送到可进维度，全部不可进才封禁）"
                       : "§a已关闭耗尽传送其它维度（额度耗尽的玩家将被封禁）",
                enable ? "§aEnabled teleport-on-exhaust (exhausted players are teleported to an enterable dimension; banned only when none is enterable)"
                       : "§aDisabled teleport-on-exhaust (exhausted players are banned)")), true);
        return 1;
    }

    /** /chunkplan config redirectTarget <primary|secondary|tertiary> <dim|none>：重定向槽位（仅独立模式） */
    private static int configRedirectTarget(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        if (!eng.isIndependentMode()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c重定向槽位仅在维度独立模式下可用",
                    "§cRedirect targets are only available in per-dimension mode")));
            return 0;
        }
        String slotArg = StringArgumentType.getString(ctx, "slot");
        int slot = switch (slotArg) {
            case "primary" -> 0;
            case "secondary" -> 1;
            case "tertiary" -> 2;
            default -> -1;
        };
        if (slot < 0) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "槽位需为 primary / secondary / tertiary",
                    "Slot must be primary / secondary / tertiary")));
            return 0;
        }
        String dimArg = net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "dim").toString();
        String dim = null;
        if (!dimArg.equals("none") && !dimArg.equals("minecraft:none")) {
            dim = requireLiveDim(ctx, eng, dimArg);
            if (dim == null) {
                return 0;
            }
        }
        DimensionStore.RedirectResult result = eng.setRedirectTarget(slot, dim);
        String[] slotNames = {t(ctx, "首选", "primary"), t(ctx, "次选", "secondary"), t(ctx, "备选", "tertiary")};
        if (result != DimensionStore.RedirectResult.OK) {
            ctx.getSource().sendFailure(Component.literal(result == DimensionStore.RedirectResult.DUPLICATE
                    ? t(ctx, "§c维度 " + dim + " 已占用其它槽位（首选/次选/备选不可重复）",
                            "§cDimension " + dim + " already occupies another slot (primary/secondary/tertiary must differ)")
                    : t(ctx, "§c请先填写前置槽位（首选为空时不能填次选/备选）",
                            "§cFill the preceding slot first (secondary needs primary, tertiary needs secondary)")));
            return 0;
        }
        final String dimFinal = dim;
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§a已设置" + slotNames[slot] + "维度：§b" + (dimFinal == null ? "无" : dimFinal),
                "§aSet the " + slotNames[slot] + " redirect dimension: §b" + (dimFinal == null ? "none" : dimFinal))), true);
        return 1;
    }

    // ---------- 维度命令补全（issue #3） ----------

    private static CompletableFuture<Suggestions> suggestDimensionMode(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggestFromList(builder, List.of("shared", "independent"));
    }

    /** 维度 key 补全：来自服务器动态 getAllLevels（含 mod 注册维度，issue #3） */
    private static CompletableFuture<Suggestions> suggestDimensionKeys(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        QuotaEngine eng = ChunkPlanForge.engine;
        List<String> dims = eng == null ? List.of()
                : ChunkPlanForge.GameEvents.liveDims(ctx.getSource().getServer());
        return suggestFromList(builder, dims);
    }

    private static CompletableFuture<Suggestions> suggestRedirectSlots(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggestFromList(builder, List.of("primary", "secondary", "tertiary"));
    }

    /** 重定向目标补全：live 维度 + none（清空槽位）；已占用其它槽位的维度不再建议（不可重复选） */
    private static CompletableFuture<Suggestions> suggestRedirectTargetValues(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        QuotaEngine eng = ChunkPlanForge.engine;
        List<String> values = new ArrayList<>();
        if (eng != null) {
            values.addAll(ChunkPlanForge.GameEvents.liveDims(ctx.getSource().getServer()));
            String slotArg = StringArgumentType.getString(ctx, "slot");
            int slot = switch (slotArg) {
                case "primary" -> 0;
                case "secondary" -> 1;
                case "tertiary" -> 2;
                default -> -1;
            };
            if (slot >= 0) {
                for (int i = 0; i < 3; i++) {
                    if (i != slot) {
                        values.remove(eng.getDimensionStore().redirectTarget(i));
                    }
                }
            }
        }
        values.add("none");
        return suggestFromList(builder, values);
    }

    /** 维度窗口标签：原始写法（"5h"）转窗口名（"5小时内"）；解析失败回退原文 */
    private static String windowLabelOf(String window, boolean zh) {
        try {
            return ChunkPlanMessages.windowName(DurationParser.parseSeconds(window), zh);
        } catch (IllegalArgumentException e) {
            return window;
        }
    }

    // ---------- 预设命令族（issue #1、#2） ----------

    /** /chunkplan preset list：default（全局配置别名）+ 各预设摘要 */
    private static int presetList(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        boolean zh = isZh(ctx);
        StringBuilder sb = new StringBuilder(zh ? "§e--- ChunkPlan 预设列表 ---" : "§e--- ChunkPlan Presets ---");
        sb.append("\n§bdefault§7（").append(zh ? "全局配置，对未被覆盖的玩家生效" : "global config, applies to players without an override")
                .append("）§f: ").append(linesSummary(eng.getConfig().lines(), zh));
        for (PresetStore.Preset p : eng.getPresetStore().all()) {
            sb.append("\n§b").append(p.name()).append("§f: ").append(tiersSummary(p.tiers(), zh));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /**
     * /chunkplan preset save &lt;名称&gt; [12 值]（坑 #58，issue #1）：
     * 一个词 = 旧语义（把当前全局配置 readRawTiers 原值，含禁用档，快照为预设）；
     * 13 个词 = 显式 12 值（enabled window limit × 4 档，如
     * {@code p1 true 5h 500 true 24h 2000 false 7d 10000 false 30d 40000}），
     * <b>只写预设文件、不写全局配置</b>——用户要的"做一个不立刻应用的预设"。
     * 参数用 greedyString 同串解析（含 @ 的合法类型，坑 #30 先例）。
     */
    private static int presetSave(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String[] parts = ctx.getArgument("args", String.class).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, USAGE_PRESET_SAVE_ZH, USAGE_PRESET_SAVE_EN)));
            return 0;
        }
        String name = parts[0];
        if (!PresetStore.isValidName(name)) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c预设名 1~32 字符，中文等字符均可用；不可含空格、引号、反斜杠或控制字符，也不能用保留名 default",
                    "§cPreset names are 1-32 characters (any script); no spaces, quotes, backslashes or control characters, and \"default\" is reserved")));
            return 0;
        }
        List<QuotaTiers.Tier> raw = parts.length == 1 ? ForgeConfig.readRawTiers(resolveConfigFile(ctx)) : null;
        if (parts.length == 13) {
            // 显式 12 值：字段级校验（错误信息精确到"第 N 档哪个字段非法"），只写预设文件
            List<QuotaTiers.Tier> explicit = parseExplicitTiers(ctx, parts);
            if (explicit == null) {
                return 0;
            }
            raw = explicit;
        } else if (parts.length != 1) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, USAGE_PRESET_SAVE_ZH, USAGE_PRESET_SAVE_EN)));
            return 0;
        }
        boolean explicitValues = parts.length == 13;
        if (raw == null || raw.size() != 4) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c读取当前配置失败，详见服务端日志",
                    "§cFailed to read the current config; see server log for details")));
            return 0;
        }
        boolean existed = eng.getPresetStore().exists(name);
        if (!eng.savePreset(name, raw)) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c预设保存失败（档位校验未通过，详见服务端日志）",
                    "§cFailed to save preset (tier validation failed; see server log)")));
            return 0;
        }
        String tail = existed ? t(ctx, "，已覆盖同名预设", ", overwriting the existing preset") : "";
        ctx.getSource().sendSuccess(() -> Component.literal(explicitValues
                ? t(ctx,
                        "§a已保存预设 §b" + name + "§a" + tail,
                        "§aSaved preset §b" + name + "§a" + tail)
                : t(ctx,
                        "§a已保存预设 §b" + name + "§a（基于当前全局配置）" + tail,
                        "§aSaved preset §b" + name + "§a (from the current global config)" + tail)), true);
        return 1;
    }

    /** preset save 用法（命令参数与解析错误共用文案） */
    private static final String USAGE_PRESET_SAVE_ZH =
            "参数格式: /chunkplan preset save <名称> [12 值: 开关 窗口 上限 × 4 档]";
    private static final String USAGE_PRESET_SAVE_EN =
            "Usage: /chunkplan preset save <name> [12 values: enabled window limit x 4 tiers]";

    /**
     * 解析显式 12 值（parts[1..12] = 4 组 enabled/window/limit）；非法时 sendFailure 并返回 null。
     * 字段级校验：开关 true/false/on/off（大小写不敏感），另接受带档位前缀的 t1on/t2off 等写法
     * （两种拼写等价，便于按档填写时自解释）；窗口须为该档预置（与 toLines 口径一致，也复用
     * DurationParser 解析）；额度走 NumericParser.parseLimit。
     */
    private static List<QuotaTiers.Tier> parseExplicitTiers(CommandContext<CommandSourceStack> ctx, String[] parts) {
        List<QuotaTiers.Tier> tiers = new ArrayList<>(4);
        for (int tier = 1; tier <= 4; tier++) {
            String rawEnabled = parts[(tier - 1) * 3 + 1];
            String window = parts[(tier - 1) * 3 + 2];
            String rawLimit = parts[(tier - 1) * 3 + 3];
            String bare = rawEnabled.toLowerCase();
            String prefixed = "t" + tier;
            boolean enabled;
            if (bare.equals("true") || bare.equals("on") || bare.equals(prefixed + "on")) {
                enabled = true;
            } else if (bare.equals("false") || bare.equals("off") || bare.equals(prefixed + "off")) {
                enabled = false;
            } else {
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "§c第 " + tier + " 档开关非法：可选 true / false / on / off（当前值 " + rawEnabled + "）",
                        "§cInvalid enable flag for tier " + tier + ": use true / false / on / off (got " + rawEnabled + ")")));
                return null;
            }
            List<String> presets = presetsOf(tier);
            long secs;
            try {
                secs = DurationParser.parseSeconds(window);
            } catch (IllegalArgumentException e) {
                secs = -1;
            }
            if (secs <= 0 || !presets.contains(window)) {
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "§c第 " + tier + " 档窗口非法：可选 " + String.join(" / ", presets),
                        "§cInvalid window for tier " + tier + ": choose from " + String.join(" / ", presets))));
                return null;
            }
            NumericParser.Parsed p = NumericParser.parseLimit(rawLimit);
            if (!p.isOk()) {
                ctx.getSource().sendFailure(Component.literal(t(ctx,
                        "§c第 " + tier + " 档额度非法：需为 1.00~999999999.99 的数字，最多 2 位小数",
                        "§cInvalid limit for tier " + tier + ": a number between 1.00 and 999999999.99 with at most 2 decimals")));
                return null;
            }
            tiers.add(new QuotaTiers.Tier(enabled, window, p.value()));
        }
        return tiers;
    }

    /**
     * preset save 参数补全（坑 #58）：<b>只在第 1 词给建议</b>（已有预设名，便于同名覆盖），
     * 出现空格后返回空建议——第 2 词起是 12 值（开关/窗口/额度），逐词建议既无意义，
     * 又会把"第 2 词=预设名"的旧输入习惯引导成套娃（坑 #35 教训）。
     */
    private static CompletableFuture<Suggestions> suggestPresetSave(CommandContext<CommandSourceStack> ctx,
                                                                    SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        if (remaining.isEmpty() || remaining.indexOf(' ') >= 0 || remaining.indexOf('\t') >= 0) {
            return builder.buildFuture();
        }
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            return builder.buildFuture();
        }
        return suggestFromList(builder, eng.getPresetStore().all().stream().map(PresetStore.Preset::name).toList());
    }

    /** /chunkplan preset delete <名称>：删除预设并解除相关分配（default 即全局配置，不可删） */
    private static int presetDelete(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").trim();
        if (name.equalsIgnoreCase("default")) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "default 预设即全局配置，不能删除",
                    "The default preset IS the global config and cannot be deleted")));
            return 0;
        }
        int unassigned = eng.deletePreset(name);
        if (unassigned < 0) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "预设 " + name + " 不存在", "Preset " + name + " does not exist")));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§a已删除预设 §b" + name + "§a" + (unassigned > 0
                        ? "，已解除 " + unassigned + " 名玩家的分配（回落全局配置）"
                        : ""),
                "§aDeleted preset §b" + name + "§a" + (unassigned > 0
                        ? ", unassigned " + unassigned + " player(s) (back to the global config)"
                        : ""))), true);
        return 1;
    }

    /** /chunkplan preset apply <名称>：应用预设到全体（写回全局配置，需 confirm） */
    private static int presetApply(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").trim();
        if (eng.isIndependentMode()) {
            // 维度独立模式（issue #3，用户拍板）：预设仅保留玩家分配，apply 到全局被阻止（先于 default 别名检查）
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "§c维度独立模式下全局额度线不生效，preset apply 不可用；请用 /chunkplan preset player 按玩家分配，或 /chunkplan config dimension 按维度配置",
                    "§cGlobal quota lines are inactive in per-dimension mode; preset apply is unavailable. Use /chunkplan preset player for per-player presets or /chunkplan config dimension for per-dimension config")));
            return 0;
        }
        if (name.equalsIgnoreCase("default")) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "default 即当前全局配置，无需应用",
                    "default IS the current global config; nothing to apply")));
            return 0;
        }
        PresetStore.Preset p = eng.getPresetStore().get(name);
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "预设 " + name + " 不存在", "Preset " + name + " does not exist")));
            return 0;
        }
        boolean zh = isZh(ctx);
        putPending(new PendingAction.ApplyPreset(name, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS, ownerOf(ctx)));
        Component msg = Component.literal(t(ctx,
                "§a将把预设 §b" + name + "§a（" + tiersSummary(p.tiers(), zh) + "）写入全局配置，对全体玩家生效；"
                        + "被关闭的档位不会清空玩家已消费记录，重新开启后若仍在窗口内将继承原有消费；"
                        + "调整后可能在下一 tick 使超限玩家被当场踢出/传送，",
                "§aThis will write preset §b" + name + "§a (" + tiersSummary(p.tiers(), zh) + ") to the global config, "
                        + "effective for all players; disabling a tier does NOT clear players' spent records, and "
                        + "re-enabling it inherits the spend while the cycle is still inside its window; players over "
                        + "the limit may be kicked/teleported on the next tick, "))
                .append(ChunkPlanMessages.confirmLink(zh));
        ctx.getSource().sendSuccess(() -> msg, true);
        return 1;
    }

    /**
     * /chunkplan preset player <目标> [名称|default]：按玩家应用预设（离线可用，按 UUID 持久化）；
     * 缺省名称 = 查询当前分配；default = 清除覆盖回落全局。
     * 参数用 greedyString（同 reset：<目标> [预设]），补全按词数分阶段（坑 #35）。
     */
    private static int presetPlayer(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        String raw = ctx.getArgument("target", String.class).trim();
        String[] parts = raw.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty() || parts.length > 2) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "参数格式: /chunkplan preset player <玩家|@a> [预设名|default]",
                    "Usage: /chunkplan preset player <player|@a> [preset|default]")));
            return 0;
        }
        List<GameProfile> targets = resolveTargets(ctx, parts[0]);
        if (targets.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "未找到玩家", "Player not found")));
            return 0;
        }
        boolean zh = isZh(ctx);
        String who = targets.size() == 1 ? profileName(targets.get(0))
                : (zh ? targets.size() + " 名玩家" : targets.size() + " players");
        if (parts.length == 1) {
            // 查询模式：显示每个目标当前预设（离线可查，按 UUID）
            StringBuilder sb = new StringBuilder(zh ? "§e--- 预设分配 ---" : "§e--- Preset assignments ---");
            for (GameProfile gp : targets) {
                String cur = eng.getPlayerPresetName(gp.getId());
                sb.append("\n§f").append(profileName(gp)).append(zh ? "：§b" : ": §b")
                        .append(cur == null ? "default（跟随全局）" : cur);
            }
            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        }
        String presetArg = parts[1];
        if (presetArg.equalsIgnoreCase("default")) {
            for (GameProfile gp : targets) {
                eng.clearPlayerPreset(gp.getId());
            }
            notifyPresetTargets(ctx, targets, null, zh);
            ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                    "§a已将 " + who + " 恢复为全局配置（default）",
                    "§aRestored " + who + " to the global config (default)")), true);
            return 1;
        }
        if (!eng.getPresetStore().exists(presetArg)) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "预设 " + presetArg + " 不存在", "Preset " + presetArg + " does not exist")));
            return 0;
        }
        for (GameProfile gp : targets) {
            eng.setPlayerPreset(gp.getId(), presetArg);
        }
        notifyPresetTargets(ctx, targets, presetArg, zh);
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§a已为 " + who + " 应用预设 §b" + presetArg + "§a",
                "§aApplied preset §b" + presetArg + "§a to " + who)), true);
        return 1;
    }

    /** 预设分配变更后通知在线目标（离线/mock 发送为 no-op 或跳过，坑 #9） */
    private static void notifyPresetTargets(CommandContext<CommandSourceStack> ctx, List<GameProfile> targets,
                                            String presetName, boolean zh) {
        for (GameProfile gp : targets) {
            ServerPlayer target = DevCommands.findByUuid(ctx.getSource().getServer(), gp.getId());
            if (target != null) {
                boolean tzh = ChunkPlanMessages.isChinese(target.getLanguage());
                target.sendSystemMessage(Component.literal(tzh
                        ? "您的探索额度规则已被管理员调整（" + (presetName == null ? "恢复全局默认" : "预设：" + presetName) + "）"
                        : "Your exploration quota rules were updated by an administrator ("
                                + (presetName == null ? "back to global default" : "preset: " + presetName) + ")."));
            }
        }
    }

    /** 全局额度线摘要（default 展示用）："tier1 5h≤500 / tier2 24h≤2000"，零线显示"无限制" */
    private static String linesSummary(List<QuotaConfig.Line> lines, boolean zh) {
        if (lines.isEmpty()) {
            return zh ? "零线（无限制）" : "no limits";
        }
        StringBuilder sb = new StringBuilder();
        for (QuotaConfig.Line line : lines) {
            if (sb.length() > 0) {
                sb.append(zh ? " / " : " / ");
            }
            sb.append("tier").append(line.tier()).append(" ").append(ChunkPlanMessages.formatWindow(line.windowSeconds()))
                    .append("≤").append(String.format("%.2f", line.limit()));
        }
        return sb.toString();
    }

    /** 预设摘要（四档含禁用档，只显示启用档）："tier1 5h≤500 / tier2 24h≤2000" */
    private static String tiersSummary(List<QuotaTiers.Tier> tiers, boolean zh) {
        if (tiers == null || tiers.size() != 4) {
            return zh ? "（档位数据非法）" : "(invalid tier data)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            QuotaTiers.Tier t = tiers.get(i);
            if (!t.enabled()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append("tier").append(i + 1).append(" ").append(t.window()).append("≤")
                    .append(String.format("%.2f", t.limit()));
        }
        if (sb.length() == 0) {
            return zh ? "零线（无限制）" : "no limits";
        }
        return sb.toString();
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
                // 第 2 词已完整输入时不再建议该词本身（否则 @a tier1 后按 Tab 仍弹同一建议，套娃）
                if (tail.isEmpty() || (v.startsWith(tail) && !v.equalsIgnoreCase(words.get(1)))) {
                    builder.suggest(head + v);
                }
            }
            return builder.buildFuture();
        }
        // 第 3 词起：reset 只接受 <目标> [层级]，不再建议（坑 #35）
        return builder.buildFuture();
    }

    /** 预设名补全（preset delete / apply）：来自预设库，前缀过滤 */
    private static CompletableFuture<Suggestions> suggestPresetNames(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        QuotaEngine eng = ChunkPlanForge.engine;
        List<String> names = eng == null ? List.of()
                : eng.getPresetStore().all().stream().map(PresetStore.Preset::name).toList();
        return suggestFromList(builder, names);
    }

    /**
     * preset player 目标补全：按已输入词数分阶段（坑 #35 同款）——第 1 词补玩家名+选择器；
     * 第 2 词补预设名+default；第 3 词起不再建议（命令只接受 &lt;目标&gt; [预设] 两个词）。
     */
    private static CompletableFuture<Suggestions> suggestPresetTarget(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        List<String> words = new ArrayList<>();
        for (String w : remaining.split("\\s+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        int n = words.size();
        int lastWs = -1;
        for (int i = remaining.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(remaining.charAt(i))) {
                lastWs = i;
                break;
            }
        }
        boolean trailingSpace = !remaining.isEmpty() && lastWs == remaining.length() - 1;
        if (n == 0 || (n == 1 && !trailingSpace)) {
            // 第 1 词输入中：玩家名 + 常用选择器（与 suggestResetTarget 第 1 词分支一致）
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
            // 第 2 词输入中或未完成：补全预设名 + default（greedy 参数建议需含完整剩余文本）
            QuotaEngine eng = ChunkPlanForge.engine;
            if (eng == null) {
                return builder.buildFuture();
            }
            String head = lastWs >= 0 ? remaining.substring(0, lastWs + 1) : words.get(0) + " ";
            String tail = n == 2 ? words.get(1).toLowerCase() : "";
            List<String> values = new ArrayList<>();
            for (PresetStore.Preset p : eng.getPresetStore().all()) {
                values.add(p.name());
            }
            values.add("default");
            for (String v : values) {
                // 第 2 词已完整输入时不再建议该词本身（防套娃，坑 #35 补丁 2）
                if (tail.isEmpty() || (v.toLowerCase().startsWith(tail) && !v.equalsIgnoreCase(words.get(1)))) {
                    builder.suggest(head + v);
                }
            }
            return builder.buildFuture();
        }
        // 第 3 词起：preset player 只接受 <目标> [预设]，不再建议（坑 #35）
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
