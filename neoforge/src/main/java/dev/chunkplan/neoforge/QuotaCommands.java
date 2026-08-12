package dev.chunkplan.neoforge;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaEngine;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
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
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .requires(s -> s.hasPermission(2))
                                .executes(ctx -> checkOther(ctx))))
                .then(Commands.literal("reset")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
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
        GameProfile profile = firstProfile(ctx);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal("未找到该玩家"));
            return 0;
        }
        sendStatus(ctx, profile.getId(), profile.getName());
        return 1;
    }

    private static void sendStatus(CommandContext<CommandSourceStack> ctx, UUID uuid, String name) {
        QuotaEngine eng = ChunkPlanNeoForge.engine;
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
        QuotaEngine eng = ChunkPlanNeoForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal("ChunkPlan 未初始化"));
            return 0;
        }
        GameProfile profile = firstProfile(ctx);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal("未找到该玩家"));
            return 0;
        }
        eng.resetSpend(profile.getId());
        ctx.getSource().sendSuccess(() -> Component.literal("§a已重置 " + profile.getName() + " 的探索额度消费（已探索集合保留）"), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        QuotaEngine eng = ChunkPlanNeoForge.engine;
        if (eng == null) {
            ctx.getSource().sendFailure(Component.literal("ChunkPlan 未初始化"));
            return 0;
        }
        List<String> warnings = new ArrayList<>();
        QuotaConfig config = NeoForgeConfig.toQuotaConfig(warnings);
        for (String w : warnings) {
            org.slf4j.LoggerFactory.getLogger("ChunkPlan").warn("配置告警: {}", w);
        }
        eng.setConfig(config);
        ctx.getSource().sendSuccess(() -> Component.literal("§aChunkPlan 配置已重载"
                + (warnings.isEmpty() ? "" : "§c（含告警，详见服务端日志）")), true);
        return 1;
    }

    private static GameProfile firstProfile(CommandContext<CommandSourceStack> ctx) {
        try {
            return GameProfileArgument.getGameProfiles(ctx, "player").iterator().next();
        } catch (Exception e) {
            return null;
        }
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
