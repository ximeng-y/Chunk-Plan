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

        LOG.info("ChunkPlan Fabric 壳已注册，配置文件: {}", configFile);
    }

    private void onServerStarted(MinecraftServer server) {
        Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("chunkplan");
        try {
            Path logFile = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("chunkplan.log");
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
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                UUID uuid = player.getUUID();
                boolean exempt = eng.isExempt(uuid, player.hasPermissions(2));
                QuotaEngine.TickResult result = eng.onPlayerTick(uuid, exempt,
                        player.level().dimension().location().toString(),
                        player.getX(), player.getY(), player.getZ());
                if (result.type() == QuotaEngine.ResultType.BAN) {
                    applyBan(player, result.message(), result.banUntilMillis());
                }
            } catch (Exception e) {
                LOG.error("玩家 {} tick 计费处理异常", player.getGameProfile().getName(), e);
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

    private void onPlayerJoin(ServerPlayer player) {
        QuotaEngine eng = engine;
        if (eng == null) {
            return;
        }
        try {
            // 兜底检查：额度全满则拒绝登录（已探索集合与消费桶跨重启持久化）
            String message = eng.loginBlockMessage(player.getUUID());
            if (message != null) {
                QuotaEngine.QuotaStatus status = eng.quotaStatus(player.getUUID());
                applyBan(player, message, status.recoveryMillis());
            }
        } catch (Exception e) {
            LOG.error("玩家 {} 登录检查异常", player.getGameProfile().getName(), e);
        }
    }

    private void onPlayerDisconnect(ServerPlayer player) {
        QuotaEngine eng = engine;
        if (eng != null) {
            eng.onPlayerDisconnect(player.getUUID());
        }
    }

    /** 额度耗尽处理：加入原版 UserBanList（expires=恢复时间，原版自动过期兜底）+ 管理名单 + 踢出 */
    static void applyBan(ServerPlayer player, String message, long untilMillis) {
        MinecraftServer server = player.server;
        GameProfile profile = player.getGameProfile();
        UserBanList bans = server.getPlayerList().getBans();
        bans.add(new UserBanListEntry(profile, new Date(), "ChunkPlan",
                new Date(untilMillis), message));
        engine.getBanStore().add(new ManagedBanStore.Entry(profile.getId(), message, untilMillis));
        player.connection.disconnect(Component.literal(message));
        LOG.info("玩家 {} 探索额度耗尽，临时封禁至 {}（{}）",
                profile.getName(), new Date(untilMillis), message);
    }

    /** 定时扫描：管理名单中额度已恢复的玩家 -> 解 ban */
    static void scanBans(MinecraftServer server) {
        try {
            UserBanList bans = server.getPlayerList().getBans();
            for (ManagedBanStore.Entry entry : engine.getBanStore().all()) {
                if (!engine.isAllLinesExceeded(entry.uuid())) {
                    GameProfile profile = new GameProfile(entry.uuid(), null);
                    if (bans.isBanned(profile)) {
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
