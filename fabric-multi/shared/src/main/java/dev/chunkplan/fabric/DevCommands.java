package dev.chunkplan.fabric;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * 开发环境专用调试命令（权限 4，生产环境不注册）：
 * 创建无网络连接的模拟玩家实体，用于客户端不可用时的端到端行为验证。
 * 实体加入世界后由服务器自动 tick，PlayerTickEvent.Post / 壳层遍历正常触发，
 * 完整走壳层计费 / 踢出 / ban 链路。
 */
public final class DevCommands {

    private DevCommands() {
    }

    /**
     * 模拟玩家注册表：getEntities(AABB) 依赖实体 section 可见性，
     * mock 玩家 tp 后 section 状态不可靠会查不到，故直接维护注册表遍历。
     * 仅服务端主线程访问。
     */
    static final java.util.List<ServerPlayer> MOCK_PLAYERS = new java.util.ArrayList<>();

    /** 1.21.11+ 权限系统（PermissionSet + PermissionLevel，替代旧 hasPermission(int)）：
     *  命令源与玩家各一个重载，均以"命令权限 ≥ level 级"为准 */
    static boolean hasPermission(CommandSourceStack src, int level) {
        return src.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
    }

    static boolean hasPermission(ServerPlayer player, int level) {
        return player.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chunkplan_debug")
                .requires(s -> hasPermission(s, 4))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> spawn(ctx))
                                .then(Commands.argument("uuid", net.minecraft.commands.arguments.UuidArgument.uuid())
                                        .executes(ctx -> spawn(ctx, net.minecraft.commands.arguments.UuidArgument.getUuid(ctx, "uuid"))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> remove(ctx))))
                .then(Commands.literal("op")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> op(ctx))))
                .then(Commands.literal("deop")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> deop(ctx)))));
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) {
        return spawn(ctx, java.util.UUID.randomUUID());
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, java.util.UUID uuid) {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        if (findByName(server, name) != null) {
            ctx.getSource().sendFailure(Component.literal("模拟玩家 " + name + " 已存在"));
            return 0;
        }
        // 清理注册表中已被移除的死实体（如 ban 时 disconnect 移除但未从注册表清除）
        MOCK_PLAYERS.removeIf(p -> p.isRemoved());
        // 同 uuid 已在线（如上次断开时实体未清理）：拒绝，避免 addFreshEntity 的 UUID 冲突
        ServerPlayer sameUuid = findByUuid(server, uuid);
        if (sameUuid != null) {
            ctx.getSource().sendFailure(Component.literal("模拟玩家 uuid=" + uuid + " 已在线（" + sameUuid.getGameProfile().name() + "），请先 remove"));
            return 0;
        }
        // 登录拦截检查：与真实客户端登录链路等价（额度全满 -> 拒绝登录并延长封禁）
        dev.chunkplan.common.QuotaEngine eng = ChunkPlanFabric.engine;
        if (eng != null && eng.isAllLinesExceeded(uuid)) {
            dev.chunkplan.common.QuotaEngine.QuotaStatus status = eng.quotaStatus(uuid);
            ctx.getSource().sendFailure(Component.literal("§c登录被拒绝：" + ChunkPlanMessages.banMessage(status, true)));
            return 0;
        }
        ServerLevel level = server.overworld();
        GameProfile profile = new GameProfile(uuid, name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        player.setGameMode(GameType.SURVIVAL);
        // 虚拟连接（channel 为 null，send 自动空操作）：避免 ChunkMap 区块跟踪向空 connection 发包崩溃
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(server,
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),
                player, net.minecraft.server.network.CommonListenerCookie.createInitial(
                        new GameProfile(player.getUUID(), player.getGameProfile().name()), false));
        // 1.21.11：moveTo(BlockPos,float,float) 已移除，用 teleportTo 等价定位（新实体旋转默认 0）
        net.minecraft.core.BlockPos spawn = level.getRespawnData().pos();
        player.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        level.addFreshEntity(player);
        MOCK_PLAYERS.add(player);
        ctx.getSource().sendSuccess(() -> Component.literal("已生成模拟玩家 " + name + "（uuid=" + profile.id() + "）"), true);
        return 1;
    }

    private static int op(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer player = findByName(server, name);
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("未找到模拟玩家 " + name));
            return 0;
        }
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(
                new NameAndId(player.getGameProfile()), LevelBasedPermissionSet.forLevel(PermissionLevel.GAMEMASTERS), false));
        ctx.getSource().sendSuccess(() -> Component.literal("已将 " + name + " 设为 OP（uuid=" + player.getUUID() + "）"), true);
        return 1;
    }

    private static int deop(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer player = findByName(server, name);
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("未找到模拟玩家 " + name));
            return 0;
        }
        server.getPlayerList().getOps().remove(new NameAndId(player.getGameProfile()));
        ctx.getSource().sendSuccess(() -> Component.literal("已取消 " + name + " 的 OP"), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer player = findByName(server, name);
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("未找到模拟玩家 " + name));
            return 0;
        }
        player.kill((ServerLevel) player.level());
        MOCK_PLAYERS.remove(player);
        ctx.getSource().sendSuccess(() -> Component.literal("已移除模拟玩家 " + name), true);
        return 1;
    }

    /** 按名字查找：注册表优先（mock，section 查询不可靠）-> PlayerList 真实玩家 -> 世界实体兜底 */
    static ServerPlayer findByName(MinecraftServer server, String name) {
        for (ServerPlayer p : MOCK_PLAYERS) {
            if (!p.isRemoved() && p.getGameProfile().name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(ServerPlayer.class),
                    net.minecraft.world.phys.AABB.ofSize(net.minecraft.world.phys.Vec3.atCenterOf(level.getRespawnData().pos()), 6.0E7, 6.0E7, 6.0E7), p -> true)) {
                if (p.getGameProfile().name().equalsIgnoreCase(name)) {
                    return p;
                }
            }
        }
        return null;
    }

    /** 按 uuid 查找：注册表优先（mock，section 查询不可靠）-> PlayerList 真实玩家 -> 世界实体兜底 */
    static ServerPlayer findByUuid(MinecraftServer server, UUID uuid) {
        for (ServerPlayer p : MOCK_PLAYERS) {
            if (!p.isRemoved() && p.getUUID().equals(uuid)) {
                return p;
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(uuid)) {
                return p;
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(ServerPlayer.class),
                    net.minecraft.world.phys.AABB.ofSize(net.minecraft.world.phys.Vec3.atCenterOf(level.getRespawnData().pos()), 6.0E7, 6.0E7, 6.0E7), p -> true)) {
                if (p.getUUID().equals(uuid)) {
                    return p;
                }
            }
        }
        return null;
    }
}
