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

        // 客户端 GUI 网络通道：注册 payload 类型 + 服务端接收器（main 入口双端都执行）
        ChunkPlanNetwork.registerTypes();
        ChunkPlanNetwork.registerServerReceivers();

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
                    player.getX(), player.getY(), player.getZ(), liveDims(player.server));
            if (result.type() == QuotaEngine.ResultType.REDIRECT) {
                // 维度独立模式耗尽重定向（issue #3）：还有可进维度就传送过去而非封禁
                applyRedirect(player, result.redirectDim());
            } else if (result.type() == QuotaEngine.ResultType.BAN) {
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
            UUID uuid = player.getUUID();
            if (eng.isIndependentMode()) {
                // 维度独立模式登录闸门（issue #3 拍板口径）：出生维度可进 -> 正常；
                // 其它维度可进 -> 放行（tick 内 BAN 分支自然重定向）；全部不可进 -> ban
                String dimKey = player.level().dimension().location().toString();
                if (!eng.isDimEnterable(uuid, dimKey) && !eng.anyDimEnterable(uuid, liveDims(player.server))) {
                    applyBan(player, eng.earliestRecoveryAcrossDims(uuid, liveDims(player.server)));
                    return;
                }
            } else if (eng.isAllLinesExceeded(uuid)) {
                // 兜底检查：额度全满则拒绝登录（已探索集合与消费桶跨重启持久化）
                applyBan(player, eng.quotaStatus(uuid).recoveryMillis());
            }
            // 登录欢迎（坑 #24）：自动 check 状态 + 提示语；语言延迟到首个 tick 渲染
            welcomePending.add(uuid);
        } catch (Exception e) {
            LOG.error("玩家 {} 登录检查异常", player.getGameProfile().getName(), e);
        }
    }

    /** 登录欢迎：自动 check 状态 + 提示语（坑 #24），按玩家客户端语言渲染 */
    private void sendLoginWelcome(QuotaEngine eng, ServerPlayer player) {
        boolean zh = ChunkPlanMessages.isChinese(player.clientInformation().language());
        boolean inList = eng.getConfig().exemptPlayers().contains(player.getUUID());
        String dimKey = eng.isIndependentMode()
                ? player.level().dimension().location().toString() : null;
        QuotaEngine.QuotaStatus status = dimKey == null
                ? eng.quotaStatus(player.getUUID()) : eng.quotaStatus(player.getUUID(), dimKey);
        player.sendSystemMessage(Component.literal(ChunkPlanMessages.welcomeMessage(
                player.getGameProfile().getName(), status, dimKey,
                eng.isExempt(player.getUUID(), player.hasPermissions(2)), inList, zh)));
    }

    private void onPlayerDisconnect(ServerPlayer player) {
        QuotaEngine eng = engine;
        if (eng != null) {
            welcomePending.remove(player.getUUID());
            eng.onPlayerDisconnect(player.getUUID());
            ChunkPlanNetwork.onPlayerDisconnect(player.getUUID());
        }
    }

    // ---------- 内部 ----------

    /** 当前世界全部 live 维度 key（动态 getAllLevels，天然含 mod/datapack 注册维度，issue #3） */
    static List<String> liveDims(MinecraftServer server) {
        List<String> dims = new ArrayList<>();
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            dims.add(level.dimension().location().toString());
        }
        java.util.Collections.sort(dims);
        return dims;
    }

    /** 额度耗尽处理：加入原版 UserBanList（expires=恢复时间，原版自动过期兜底）+ 管理名单 + 踢出 */
    static void applyBan(ServerPlayer player, long untilMillis) {
        MinecraftServer server = player.server;
        GameProfile profile = player.getGameProfile();
        // 文案按玩家客户端语言渲染（坑 #22：引擎只返回结构化数据）
        String message = banMessageFor(player, untilMillis);
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

    /** ban 公告文案：共享模式现状口径；独立模式显示触发维度 + 最早可进恢复时间（issue #3） */
    private static String banMessageFor(ServerPlayer player, long untilMillis) {
        boolean zh = ChunkPlanMessages.isChinese(player.clientInformation().language());
        if (engine.isIndependentMode()) {
            String dimKey = player.level().dimension().location().toString();
            return ChunkPlanMessages.banMessage(engine.quotaStatus(player.getUUID(), dimKey),
                    dimKey, untilMillis, zh);
        }
        return ChunkPlanMessages.banMessage(engine.quotaStatus(player.getUUID()), zh);
    }

    /**
     * 维度独立模式耗尽重定向（issue #3）：传送到目标维度的默认落地坐标 + 聊天公告
     * （口径同踢出公告，换成"被传送到某维度"）。目标失效（维度卸载/坐标被清）则回退封禁。
     */
    static void applyRedirect(ServerPlayer player, String targetDim) {
        MinecraftServer server = player.server;
        String fromDim = player.level().dimension().location().toString();
        dev.chunkplan.common.DimensionStore.SpawnPoint spawn = engine.getDimensionStore().spawn(targetDim);
        net.minecraft.server.level.ServerLevel target = server.getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(targetDim)));
        if (spawn == null || target == null) {
            LOG.warn("重定向目标维度 {} 无效（未加载或坐标缺失），回退封禁", targetDim);
            applyBan(player, engine.earliestRecoveryAcrossDims(player.getUUID(), liveDims(server)));
            return;
        }
        boolean zh = ChunkPlanMessages.isChinese(player.clientInformation().language());
        String message = ChunkPlanMessages.redirectMessage(
                engine.quotaStatus(player.getUUID(), fromDim), fromDim, targetDim, zh);
        player.teleportTo(target, spawn.x(), spawn.y(), spawn.z(), player.getYRot(), player.getXRot());
        player.sendSystemMessage(Component.literal(message));
        LOG.info("玩家 {} 维度 {} 额度耗尽，已重定向到 {}", player.getGameProfile().getName(), fromDim, targetDim);
    }

    /** 定时扫描：管理名单中额度已恢复的玩家 -> 解 ban（独立模式按维度口径判定，issue #3） */
    static void scanBans(MinecraftServer server) {
        try {
            List<String> liveDims = liveDims(server);
            UserBanList bans = server.getPlayerList().getBans();
            for (ManagedBanStore.Entry entry : engine.getBanStore().all()) {
                if (!engine.shouldStayBanned(entry.uuid(), liveDims)) {
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
