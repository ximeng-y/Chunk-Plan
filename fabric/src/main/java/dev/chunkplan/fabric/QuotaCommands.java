package dev.chunkplan.fabric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import dev.chunkplan.common.FeeLogFile;
import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaEngine;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * /chunkplan 命令族：check（自检 + 恢复倒计时）、reset <player>（二次确认后清消费桶，集合保留）、
 * confirm（确认待执行的重置）、reload。reset/confirm/reload 需要权限等级 2（OP）。
 * 所有玩家可见文案按执行者客户端语言渲染（中/英，坑 #22）。
 */
public final class QuotaCommands {

    /** reset 二次确认窗口（毫秒） */
    private static final long CONFIRM_WINDOW_MILLIS = 60_000;

    /** 待确认的重置请求（命令在服务端主线程串行执行，静态字段即可） */
    private static volatile PendingReset pending;

    private record PendingReset(GameProfile profile, long expireMillis) {
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
                .then(Commands.literal("reset")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(QuotaCommands::suggestPlayerNames)
                                .executes(ctx -> reset(ctx))))
                .then(Commands.literal("confirm")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> confirm(ctx)))
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
        QuotaEngine eng = ChunkPlanFabric.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return;
        }
        boolean zh = isZh(ctx);
        QuotaEngine.QuotaStatus status = eng.quotaStatus(uuid);
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "§a[ChunkPlan] §f" + name + " §7探索额度状态："
                     : "§a[ChunkPlan] §f" + name + " §7exploration status:");
        for (QuotaEngine.LineStatus line : status.lines()) {
            sb.append("\n§7  ").append(ChunkPlanMessages.formatWindow(line.windowSeconds()))
                    .append(zh ? " 窗口: §f" : " window: §f")
                    .append(String.format("%.1f§7/%.1f", line.spent(), line.limit()));
        }
        if (status.allExceeded()) {
            String recover = ChunkPlanMessages.formatTime(status.recoveryMillis());
            sb.append(zh ? "\n§c  已耗尽，预计 " : "\n§c  Exhausted, recovers at ").append(recover).append(zh ? " 恢复" : "");
        } else {
            sb.append(zh ? "\n§a  未满，可正常探索" : "\n§a  Under limit, exploration allowed");
        }
        // 豁免状态提示（坑 #21：OP 默认豁免是设计语义，显式告知避免误判为故障）
        if (eng.isExempt(uuid, isOp)) {
            boolean inList = eng.getConfig().exemptPlayers().contains(uuid);
            if (zh) {
                sb.append(inList
                        ? "\n§7  [豁免] " + (self ? "你在豁免名单中" : "该玩家在豁免名单中") + "，不受额度限制"
                        : "\n§7  [豁免] " + (self ? "你当前是管理员" : "该玩家当前是管理员") + "，不受额度限制");
            } else {
                sb.append(inList
                        ? "\n§7  [exempt] " + (self ? "You are in the exempt list" : "This player is in the exempt list") + "; quota limits do not apply"
                        : "\n§7  [exempt] " + (self ? "You are an operator" : "This player is an operator") + "; quota limits do not apply");
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanFabric.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        GameProfile profile = resolvePlayer(ctx);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "未找到该玩家", "Player not found")));
            return 0;
        }
        // 二次确认：先记录请求，待管理员执行 /chunkplan confirm 才真正清空
        pending = new PendingReset(profile, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS);
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§a已记录 " + profileName(profile) + " 的重置请求，请在 60 秒内运行 /chunkplan confirm 确认",
                "§aReset request recorded for " + profileName(profile) + ". Run /chunkplan confirm within 60 seconds to confirm.")), true);
        return 1;
    }

    private static int confirm(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanFabric.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        PendingReset req = pending;
        if (req == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "当前没有待确认的重置请求", "No pending reset request.")));
            return 0;
        }
        pending = null;
        if (req.expireMillis() < System.currentTimeMillis()) {
            ctx.getSource().sendFailure(Component.literal(t(ctx,
                    "重置请求已过期，请重新运行 /chunkplan reset <player>",
                    "Reset request expired. Run /chunkplan reset <player> again.")));
            return 0;
        }
        eng.resetSpend(req.profile().getId());
        // 在线通知（含 mock 玩家：虚拟连接发送为 no-op，坑 #9）
        boolean zh = isZh(ctx);
        ServerPlayer target = DevCommands.findByUuid(ctx.getSource().getServer(), req.profile().getId());
        if (target != null) {
            boolean tzh = ChunkPlanMessages.isChinese(target.clientInformation().language());
            target.sendSystemMessage(Component.literal(tzh
                    ? "您的ChunkPlan探索额度已被管理员重置"
                    : "Your ChunkPlan exploration quota has been reset by an administrator."));
        }
        String notified = target != null
                ? (zh ? "（已通知在线玩家）" : " (notified in-game)")
                : (zh ? "（玩家当前离线，未通知）" : " (player offline, not notified)");
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§a已重置 " + profileName(req.profile()) + " 的探索额度消费（已探索集合保留）" + notified,
                "§aReset " + profileName(req.profile()) + "'s exploration quota spending (explored chunks kept)." + notified)), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanFabric.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal(t(ctx, "ChunkPlan 未初始化", "ChunkPlan not initialized")));
            return 0;
        }
        List<String> warnings = new ArrayList<>();
        QuotaConfig config = FabricConfig.load(ChunkPlanFabric.configFile, warnings);
        for (String w : warnings) {
            org.slf4j.LoggerFactory.getLogger("ChunkPlan").warn("配置告警: {}", w);
        }
        eng.setConfig(config);
        // logFeeEvents 开关热切换：按新配置重建/清空扣费日志
        if (config.logFeeEvents()) {
            try {
                eng.setFeeLogger(new FeeLogFile(ChunkPlanFabric.logFile));
            } catch (java.io.IOException e) {
                org.slf4j.LoggerFactory.getLogger("ChunkPlan").warn("重建扣费日志失败: {}", e.getMessage());
            }
        } else {
            eng.setFeeLogger(null);
        }
        String warning = warnings.isEmpty() ? "" : t(ctx, "§c（含告警，详见服务端日志）", "§c(warnings present, see server log)");
        ctx.getSource().sendSuccess(() -> Component.literal(t(ctx,
                "§aChunkPlan 配置已重载",
                "§aChunkPlan configuration reloaded") + warning), true);
        return 1;
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

    /**
     * 解析玩家参数：在线实体（PlayerList 真实玩家 + 世界实体含 mock）优先，避免 usercache
     * 旧 uuid 映射（mock 玩家曾以不同 uuid 入服）误伤；其次 UUID 直解；最后 profile cache。
     * 不用原版 GameProfileArgument：1.21.1 原版把 UUID 当玩家名查缓存（Fabric 端不可用）。
     */
    private static GameProfile resolvePlayer(CommandContext<CommandSourceStack> ctx) {
        String arg = StringArgumentType.getString(ctx, "player");
        MinecraftServer server = ctx.getSource().getServer();
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

    /** 执行者语言判定：玩家按客户端语言，控制台/rcon 默认英文 */
    private static boolean isZh(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        return ChunkPlanMessages.isChinese(p == null ? null : p.clientInformation().language());
    }

    private static String t(CommandContext<CommandSourceStack> ctx, String zhText, String enText) {
        return isZh(ctx) ? zhText : enText;
    }

    private static String profileName(GameProfile profile) {
        String name = profile.getName();
        return name != null && !name.isEmpty() ? name : profile.getId().toString();
    }
}
