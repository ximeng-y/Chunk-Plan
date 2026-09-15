package dev.chunkplan.neoforge;

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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge 薄壳：事件 -> QuotaEngine（core 零 MC 依赖）。
 * 接入点：PlayerTickEvent.Post / PlayerLoggedInEvent（兜底）/ PlayerLoggedOutEvent /
 * ServerTickEvent.Post（定时保存 + 解 ban 扫描）/ RegisterCommandsEvent。
 */
@Mod(ChunkPlanNeoForge.MODID)
public final class ChunkPlanNeoForge {

    public static final String MODID = "chunkplan";

    /** mod 版本号（构造器从 ModContainer 读取）：GUI 版本横幅与版本不匹配兜底页显示用 */
    public static volatile String MOD_VERSION = "";

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPlan");

    /** 服务端生命周期内的引擎实例（单服务器），包内供命令访问 */
    static volatile QuotaEngine engine;

    public ChunkPlanNeoForge(ModContainer modContainer) {
        MOD_VERSION = modContainer.getModInfo().getVersion().toString();
        // SERVER 类型 TOML 配置：主位置 <serverDir>/config/，world/serverconfig/ 为可选存档级覆盖层
        modContainer.registerConfig(ModConfig.Type.SERVER, NeoForgeConfig.SPEC);
    }

    // ---------- 服务端生命周期 ----------

    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class GameEvents {

        /** 待发登录欢迎的玩家（client_information 包晚于登录事件到达，须等首个 tick 语言才正确，坑 #24） */
        private static final java.util.Set<UUID> WELCOME_PENDING = new java.util.HashSet<>();

        @SubscribeEvent
        public static void onServerStarting(ServerStartingEvent event) {
            MinecraftServer server = event.getServer();
            Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("chunkplan");
            try {
                Path logFile = server.getServerDirectory().resolve("logs").resolve("chunkplan.log");
                List<String> warnings = new ArrayList<>();
                QuotaConfig config = NeoForgeConfig.toQuotaConfig(warnings);
                for (String w : warnings) {
                    LOG.warn("配置告警: {}", w);
                }
                engine = new QuotaEngine(dataDir, config,
                        config.logFeeEvents() ? new FeeLogFile(logFile) : null,
                        new ManagedBanStore(dataDir.resolve("chunkplan-managed-bans.json")));
                LOG.info("ChunkPlan 引擎已初始化，数据目录: {}", dataDir);
            } catch (IOException e) {
                LOG.error("初始化 ChunkPlan 失败", e);
            }
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            QuotaEngine eng = engine;
            if (eng != null) {
                eng.saveAll();
                engine = null;
            }
        }

        // ---------- 玩家 tick（核心计费入口） ----------

        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Post event) {
            if (engine == null || !(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            handlePlayerTick(player);
        }

        /** 计费核心（事件与 mock 遍历共用） */
        static void handlePlayerTick(ServerPlayer player) {
            try {
                // 登录欢迎延迟到首个 tick：此时 client_information 已到达，语言渲染正确（坑 #24）
                if (WELCOME_PENDING.remove(player.getUUID())) {
                    sendLoginWelcome(player);
                }
                UUID uuid = player.getUUID();
                boolean exempt = engine.isExempt(uuid, player.hasPermissions(2));
                QuotaEngine.TickResult result = engine.onPlayerTick(uuid, exempt,
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

        // ---------- 登录/登出 ----------

        @SubscribeEvent
        public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            QuotaEngine eng = engine;
            if (eng == null || !(event.getEntity() instanceof ServerPlayer player)) {
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
                WELCOME_PENDING.add(uuid);
            } catch (Exception e) {
                LOG.error("玩家 {} 登录检查异常", player.getGameProfile().getName(), e);
            }
        }

        /** 登录欢迎：自动 check 状态 + 提示语（坑 #24），按玩家客户端语言渲染 */
        private static void sendLoginWelcome(ServerPlayer player) {
            QuotaEngine eng = engine;
            if (eng == null) {
                return;
            }
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

        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            QuotaEngine eng = engine;
            if (eng == null || !(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            WELCOME_PENDING.remove(player.getUUID());
            eng.onPlayerDisconnect(player.getUUID());
            ChunkPlanNetwork.onPlayerDisconnect(player.getUUID());
        }

        // ---------- 定时保存 + 解 ban 扫描 ----------

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            QuotaEngine eng = engine;
            if (eng == null) {
                return;
            }
            MinecraftServer server = event.getServer();
            int tick = server.getTickCount();
            // mock 玩家未进实体 ticking 列表（PlayerTickEvent.Post 不触发），壳层手动计费；
            // 遍历副本：applyBan 会在遍历中移除玩家，直接遍历原列表会 CME
            DevCommands.MOCK_PLAYERS.removeIf(p -> p.isRemoved());
            for (ServerPlayer player : new java.util.ArrayList<>(DevCommands.MOCK_PLAYERS)) {
                handlePlayerTick(player);
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

        // ---------- 命令 ----------

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            QuotaCommands.register(event.getDispatcher());
            // 仅开发环境：模拟玩家实体调试命令（客户端不可用时的端到端验证）
            if (net.neoforged.fml.loading.FMLLoader.isProduction() == false) {
                DevCommands.register(event.getDispatcher());
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
                List<ManagedBanStore.Entry> entries = engine.getBanStore().all();
                for (ManagedBanStore.Entry entry : entries) {
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
                // 回收懒加载的离线封禁玩家数据：被封玩家离线时不会再触发登出事件，
                // 不显式回收其数据将常驻内存直至关服（在线玩家数据由 tick/登出管理，不动）
                for (ManagedBanStore.Entry entry : entries) {
                    if (server.getPlayerList().getPlayer(entry.uuid()) == null) {
                        engine.unloadPlayerData(entry.uuid());
                    }
                }
            } catch (Exception e) {
                LOG.error("解 ban 扫描异常", e);
            }
        }
    }
}
