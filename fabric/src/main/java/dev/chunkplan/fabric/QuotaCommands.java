package dev.chunkplan.fabric;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

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
 * /quota 命令族：check（自检 + 恢复倒计时）、reset <player>（清消费桶，集合保留）、reload。
 * reset/reload 需要权限等级 2（OP）。
 */
public final class QuotaCommands {

    private static final DateTimeFormatter RECOVER_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private QuotaCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("quota")
                .then(Commands.literal("check")
                        .executes(ctx -> checkSelf(ctx))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(s -> s.hasPermission(2))
                                .executes(ctx -> checkOther(ctx))))
                .then(Commands.literal("reset")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> reset(ctx))))
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> reload(ctx))));
    }

    private static int checkSelf(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("控制台请指定玩家: /quota check <player>"));
            return 0;
        }
        sendStatus(ctx, player.getUUID(), player.getGameProfile().getName());
        return 1;
    }

    private static int checkOther(CommandContext<CommandSourceStack> ctx) {
        GameProfile profile = resolvePlayer(ctx);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal("未找到该玩家"));
            return 0;
        }
        sendStatus(ctx, profile.getId(), profileName(profile));
        return 1;
    }

    private static void sendStatus(CommandContext<CommandSourceStack> ctx, UUID uuid, String name) {
        QuotaEngine eng = ChunkPlanFabric.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal("ChunkPlan 未初始化"));
            return;
        }
        QuotaEngine.QuotaStatus status = eng.quotaStatus(uuid);
        StringBuilder sb = new StringBuilder();
        sb.append("§a[ChunkPlan] §f").append(name).append(" §7探索额度状态：");
        for (QuotaEngine.LineStatus line : status.lines()) {
            sb.append("\n§7  ").append(formatWindow(line.windowSeconds()))
                    .append(String.format(" 窗口: §f%.1f§7/%.1f", line.spent(), line.limit()));
        }
        if (status.allExceeded()) {
            String recover = RECOVER_FMT.format(Instant.ofEpochMilli(status.recoveryMillis()).atZone(ZoneId.systemDefault()));
            sb.append("\n§c  已耗尽，预计 ").append(recover).append(" 恢复");
        } else {
            sb.append("\n§a  未满，可正常探索");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanFabric.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal("ChunkPlan 未初始化"));
            return 0;
        }
        GameProfile profile = resolvePlayer(ctx);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal("未找到该玩家"));
            return 0;
        }
        eng.resetSpend(profile.getId());
        ctx.getSource().sendSuccess(() -> Component.literal("§a已重置 " + profileName(profile) + " 的探索额度消费（已探索集合保留）"), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanFabric.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal("ChunkPlan 未初始化"));
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
        ctx.getSource().sendSuccess(() -> Component.literal("§aChunkPlan 配置已重载"
                + (warnings.isEmpty() ? "" : "§c（含告警，详见服务端日志）")), true);
        return 1;
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

    private static String profileName(GameProfile profile) {
        String name = profile.getName();
        return name != null && !name.isEmpty() ? name : profile.getId().toString();
    }

    private static String formatWindow(long windowSeconds) {
        long m = windowSeconds / 60;
        if (m >= 1440) {
            return (m / 1440) + "d";
        }
        if (m >= 60) {
            return (m / 60) + "h";
        }
        return m + "m";
    }
}
