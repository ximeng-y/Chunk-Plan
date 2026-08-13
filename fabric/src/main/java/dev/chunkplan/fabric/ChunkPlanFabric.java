package dev.chunkplan.fabric;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.authlib.GameProfile;

import dev.chunkplan.common.FeeLogFile;
import dev.chunkplan.common.ManagedBanStore;
import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaEngine;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Fabric 薄壳：事件 -> QuotaEngine（core 零 MC 依赖）。
 * 接入点：ServerTickEvents.END_SERVER_TICK（遍历玩家 + 定时保存/解 ban 扫描）/
 * ServerPlayConnectionEvents.JOIN（兜底）/ DISCONNECT（离线保存）/ CommandRegistrationCallback。
 */
public final class ChunkPlanFabric implements ModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPlan");

    /** 服务端生命周期内的引擎实例（单服务器），供命令访问 */
    static volatile QuotaEngine engine;

    /** 配置文件路径（初始化时确定，命令 reload 复用） */
    static Path configFile;

    /** 扣费日志文件路径（初始化时确定，reload 时 logFeeEvents 热切换复用） */
    static Path logFile;

    /** 待发登录欢迎的玩家（client_information 包晚于登录事件到达，须等首个 tick 语言才正确，坑 #24） */
    private final java.util.Set<UUID> welcomePending = new java.util.HashSet<>();

    @Override
    public void onInitialize() {
        this.configFile = FabricLoader.getInstance().getConfigDir().resolve("chunkplan.json");

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            QuotaEngine eng = engine;
            if (eng != null) {
                eng.saveAll();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onPlayerDisconnect(handler.getPlayer()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> QuotaCommands.register(dispatcher));
        // 仅开发环境：模拟玩家实体调试命令（客户端不可用时的端到端验证）
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                    DevCommands.register(dispatcher));
        }

        LOG.info("ChunkPlan Fabric 壳已注册，配置文件: {}", configFile);
    }

    private void onServerStarted(MinecraftServer server) {
        Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("chunkplan");
        try {
            logFile = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("chunkplan.log");
            List<String> warnings = new ArrayList<>();
            QuotaConfig config = FabricConfig.load(configFile, warnings);
            for (String w : warnings) {
                LOG.warn("配置告警: {}", w);
            }
            engine = new QuotaEngine(dataDir, config,
                    config.logFeeEvents() ? new FeeLogFile(logFile) : null,
                    new ManagedBanStore(dataDir.resolve("chunkplan-managed-bans.json")));
            LOG.info("ChunkPlan 引擎已初始化，数据目录: {}", dataDir);
        } catch (IOException e) {
            LOG.error("初始化 ChunkPlan 失败", e);
        } catch (IllegalStateException e) {
            LOG.error("初始化 ChunkPlan 失败", e);
        }
    }

    /** 每 tick 遍历在线玩家计费 + 定时保存/解 ban 扫描 */
    private void onServerTick(MinecraftServer server) {
        QuotaEngine eng = engine;
        if (eng == null) {
            return;
        }
        int tick = server.getTickCount();
        // 真实玩家（PlayerList）+ 模拟玩家（dev 调试注册表，tp 后实体 section 查询不可靠）
        java.util.Set<ServerPlayer> seen = new java.util.HashSet<>(server.getPlayerList().getPlayers());
        for (ServerPlayer player : seen) {
            handlePlayerTick(eng, player);
        }
        // 先清理死实体（ban 时 disconnect 移除的实体不会自动出注册表）
        DevCommands.MOCK_PLAYERS.removeIf(p -> p.isRemoved());
        // 遍历副本：applyBan 会在遍历中移除玩家，直接遍历原列表会 CME
        for (ServerPlayer player : new java.util.ArrayList<>(DevCommands.MOCK_PLAYERS)) {
            if (seen.add(player)) {
                handlePlayerTick(eng, player);
            }
        }
        // dev 环境兜底：注册表漏网的模拟玩家实体（生产环境无 mock，PlayerList 已全覆盖，不做全图扫描）
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                for (ServerPlayer player : level.getEntities(
                        net.minecraft.world.level.entity.EntityTypeTest.forClass(ServerPlayer.class),
                        net.minecraft.world.phys.AABB.ofSize(level.getSharedSpawnPos().getCenter(), 6.0E7, 6.0E7, 6.0E7), e -> seen.add(e))) {
                    handlePlayerTick(eng, player);
                }
            }
        }
        long banScanInterval = eng.getConfig().banScanIntervalSec() * 20;
        long saveInterval = eng.getConfig().saveIntervalSec() * 20;
        if (tick % banScanInterval == 0) {
            scanBans(server);
        }
        if (tick % saveInterval == 0) {
            eng.saveAll();
        }
    }

    private void handlePlayerTick(QuotaEngine eng, ServerPlayer player) {
        try {
            // 登录欢迎延迟到首个 tick：此时 client_information 已到达，语言渲染正确（坑 #24）
            if (welcomePending.remove(player.getUUID())) {
                sendLoginWelcome(eng, player);
            }
            UUID uuid = player.getUUID();
            boolean exempt = eng.isExempt(uuid, player.hasPermissions(2));
            QuotaEngine.TickResult result = eng.onPlayerTick(uuid, exempt,
                    player.level().dimension().location().toString(),
                    player.getX(), player.getY(), player.getZ());
            if (result.type() == QuotaEngine.ResultType.BAN) {
                applyBan(player, result.banUntilMillis());
            } else {
                // 额度百分比阈值提示（坑 #28）：逐条发送；tick 时 client_information 已到达，语言正确
                for (QuotaEngine.WindowAlert alert : result.alerts()) {
                    player.sendSystemMessage(ChunkPlanMessages.quotaAlertMessage(alert,
                            ChunkPlanMessages.isChinese(player.clientInformation().language())));
                }
            }
        } catch (Exception e) {
            LOG.error("玩家 {} tick 计费处理异常", player.getGameProfile().getName(), e);
        }
    }

    private void onPlayerJoin(ServerPlayer player) {
        QuotaEngine eng = engine;
        if (eng == null) {
            return;
        }
        try {
            // 兜底检查：额度全满则拒绝登录（已探索集合与消费桶跨重启持久化）
            if (eng.isAllLinesExceeded(player.getUUID())) {
                QuotaEngine.QuotaStatus status = eng.quotaStatus(player.getUUID());
                applyBan(player, status.recoveryMillis());
            } else {
                // 登录欢迎（坑 #24）：自动 check 状态 + 提示语；语言延迟到首个 tick 渲染
                welcomePending.add(player.getUUID());
            }
        } catch (Exception e) {
            LOG.error("玩家 {} 登录检查异常", player.getGameProfile().getName(), e);
        }
    }

    /** 登录欢迎：自动 check 状态 + 提示语（坑 #24），按玩家客户端语言渲染 */
    private void sendLoginWelcome(QuotaEngine eng, ServerPlayer player) {
        boolean zh = ChunkPlanMessages.isChinese(player.clientInformation().language());
        boolean inList = eng.getConfig().exemptPlayers().contains(player.getUUID());
        player.sendSystemMessage(Component.literal(ChunkPlanMessages.welcomeMessage(
                player.getGameProfile().getName(), eng.quotaStatus(player.getUUID()),
                eng.isExempt(player.getUUID(), player.hasPermissions(2)), inList, zh)));
    }

    private void onPlayerDisconnect(ServerPlayer player) {
        QuotaEngine eng = engine;
        if (eng != null) {
            welcomePending.remove(player.getUUID());
            eng.onPlayerDisconnect(player.getUUID());
        }
    }

    /** 额度耗尽处理：加入原版 UserBanList（expires=恢复时间，原版自动过期兜底）+ 管理名单 + 踢出 */
    static void applyBan(ServerPlayer player, long untilMillis) {
        MinecraftServer server = player.server;
        GameProfile profile = player.getGameProfile();
        // 文案按玩家客户端语言渲染（坑 #22：引擎只返回结构化数据）
        String message = ChunkPlanMessages.banMessage(
                engine.quotaStatus(profile.getId()), ChunkPlanMessages.isChinese(player.clientInformation().language()));
        UserBanList bans = server.getPlayerList().getBans();
        // 服主已手动封禁的玩家：不覆盖原 ban（避免手动永久 ban 被临时 ban 替换后随额度恢复被误解除）
        UserBanListEntry existing = bans.get(profile);
        if (existing == null || "ChunkPlan".equals(existing.getSource())) {
            bans.add(new UserBanListEntry(profile, new Date(), "ChunkPlan",
                    new Date(untilMillis), message));
        }
        engine.getBanStore().add(new ManagedBanStore.Entry(profile.getId(), message, untilMillis));
        if (DevCommands.MOCK_PLAYERS.contains(player)) {
            // 模拟玩家（dev 调试，虚拟连接 disconnect 是 no-op）：移除实体模拟被踢出，
            // 并清理引擎内存状态（mock 无登出事件，不清理会导致 tracking 残留、重 spawn 首 tick 误计费）
            engine.onPlayerDisconnect(profile.getId());
            player.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            DevCommands.MOCK_PLAYERS.remove(player);
        } else if (player.connection != null) {
            // 真实连接：正常踢出（客户端断开后由 PlayerList 移除实体）
            player.connection.disconnect(Component.literal(message));
        } else {
            player.kill();
        }
        LOG.info("玩家 {} 探索额度耗尽，临时封禁至 {}（{}）",
                profile.getName(), new Date(untilMillis), message);
    }

    /** 定时扫描：管理名单中额度已恢复的玩家 -> 解 ban */
    static void scanBans(MinecraftServer server) {
        try {
            UserBanList bans = server.getPlayerList().getBans();
            for (ManagedBanStore.Entry entry : engine.getBanStore().all()) {
                if (!engine.isAllLinesExceeded(entry.uuid())) {
                    GameProfile profile = new GameProfile(entry.uuid(), "");
                    // 仅解除 ChunkPlan 自己加的 ban；服主手动 ban 的条目（来源非 ChunkPlan）保留
                    UserBanListEntry ban = bans.get(profile);
                    if (ban != null && "ChunkPlan".equals(ban.getSource())) {
                        bans.remove(profile);
                        LOG.info("已解除玩家 {} 的 ChunkPlan 临时封禁", entry.uuid());
                    }
                    engine.getBanStore().remove(entry.uuid());
                }
            }
            engine.getBanStore().removeExpired(System.currentTimeMillis());
        } catch (Exception e) {
            LOG.error("解 ban 扫描异常", e);
        }
    }
}
