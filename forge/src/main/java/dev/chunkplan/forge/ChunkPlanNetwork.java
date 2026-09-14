package dev.chunkplan.forge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.chunkplan.common.GuiStatus;
import dev.chunkplan.common.PresetStore;
import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaEngine;
import dev.chunkplan.common.QuotaTiers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * ChunkPlan 客户端 GUI 网络通道（Forge 1.20.1，SimpleChannel）。
 *
 * <p>协议同其它端：C2S {@link GuiRequestPayload} / {@link GuiCommandPayload}（命令透传）
 * + S2C {@link GuiStatusPayload}（{@link GuiStatus} 字节数组）。「客户端可选」：两个版本谓词都包
 * {@link NetworkRegistry#acceptMissingOr}——vanilla 客户端未注册通道时不因缺失通道被踢（坑 #38 扩展）。
 */
public final class ChunkPlanNetwork {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPlan");
    private static final String PROTOCOL_VERSION = "1";

    /** 状态请求冷却（毫秒）：防高频请求放大（配置文件仅管理员请求才重读） */
    private static final long REQUEST_COOLDOWN_MILLIS = 250L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> LAST_REQUEST =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("chunkplan", "gui"),
            () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals));

    private static int nextId = 0;

    private ChunkPlanNetwork() {
    }

    /** 在 mod 构造器（双端）调用一次，注册三类消息 */
    public static void register() {
        CHANNEL.registerMessage(nextId++, GuiRequestPayload.class,
                GuiRequestPayload::encode, GuiRequestPayload::decode, GuiRequestPayload::handle);
        CHANNEL.registerMessage(nextId++, GuiCommandPayload.class,
                GuiCommandPayload::encode, GuiCommandPayload::decode, GuiCommandPayload::handle);
        CHANNEL.registerMessage(nextId++, GuiStatusPayload.class,
                GuiStatusPayload::encode, GuiStatusPayload::decode, GuiStatusPayload::handle);
    }

    /** C2S：请求状态 */
    public record GuiRequestPayload(int protocolVersion) {
        public static void encode(GuiRequestPayload m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.protocolVersion());
        }

        public static GuiRequestPayload decode(FriendlyByteBuf buf) {
            return new GuiRequestPayload(buf.readVarInt());
        }

        public static void handle(GuiRequestPayload m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                if (!allowRequest(player.getUUID())) {
                    return;
                }
                if (m.protocolVersion() != GuiStatus.PROTOCOL_VERSION) {
                    // 版本不匹配：回版本横幅（不依赖引擎，引擎未就绪也可回），客户端据此渲染兜底页并
                    // 显示两端版本号；旧客户端（无横幅解析能力）读协议头即失败，行为同从前
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new GuiStatusPayload(GuiStatus.versionBanner(ChunkPlanForge.MOD_VERSION).encode()));
                    return;
                }
                sendStatus(player);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S：GUI 操作透传命令串（不含前导斜杠） */
    public record GuiCommandPayload(String command) {
        public static void encode(GuiCommandPayload m, FriendlyByteBuf buf) {
            buf.writeUtf(m.command());
        }

        public static GuiCommandPayload decode(FriendlyByteBuf buf) {
            return new GuiCommandPayload(buf.readUtf(8192));
        }

        public static void handle(GuiCommandPayload m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                String cmd = sanitize(m.command());
                if (cmd == null) {
                    sendStatus(player);
                    return;
                }
                try {
                    player.server.getCommands().getDispatcher().execute(cmd, player.createCommandSourceStack());
                } catch (Exception e) {
                    LOG.debug("GUI 命令执行失败: {}", cmd, e);
                }
                sendStatus(player);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C：编码后的 {@link GuiStatus} 字节数组 */
    public record GuiStatusPayload(byte[] status) {
        public static void encode(GuiStatusPayload m, FriendlyByteBuf buf) {
            buf.writeByteArray(m.status());
        }

        public static GuiStatusPayload decode(FriendlyByteBuf buf) {
            return new GuiStatusPayload(buf.readByteArray());
        }

        public static void handle(GuiStatusPayload m, Supplier<NetworkEvent.Context> ctx) {
            // S2C 仅在客户端触发；ChunkPlanForgeClient 为 client-only 类，服务端永不加载
            ctx.get().enqueueWork(() -> ChunkPlanForgeClient.onStatus(m.status()));
            ctx.get().setPacketHandled(true);
        }
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String cmd = raw.trim();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1).trim();
        }
        if (cmd.isEmpty()) {
            return null;
        }
        // 拒绝换行/回车与其它控制字符（防日志注入；Brigadier 命令仅用可打印 ASCII）
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return null;
            }
        }
        return cmd;
    }

    private static boolean allowRequest(UUID uuid) {
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.get(uuid);
        if (last != null && now - last < REQUEST_COOLDOWN_MILLIS) {
            return false;
        }
        LAST_REQUEST.put(uuid, now);
        return true;
    }


    /** 玩家登出时清除其状态请求冷却条目（防 LAST_REQUEST 无界增长） */
    public static void onPlayerDisconnect(UUID uuid) {
        LAST_REQUEST.remove(uuid);
    }

    private static void sendStatus(ServerPlayer player) {
        QuotaEngine eng = ChunkPlanForge.engine;
        if (eng == null) {
            return;
        }
        GuiStatus status = buildGuiStatus(eng, player);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new GuiStatusPayload(status.encode()));
    }

    public static GuiStatus buildGuiStatus(QuotaEngine eng, ServerPlayer player) {
        UUID uuid = player.getUUID();
        QuotaConfig cfg = eng.getConfig();
        boolean independent = eng.isIndependentMode();
        String currentDim = player.level().dimension().location().toString();
        List<String> liveDims = ChunkPlanForge.GameEvents.liveDims(player.getServer());
        QuotaEngine.QuotaStatus qs = independent ? eng.quotaStatus(uuid, currentDim) : eng.quotaStatus(uuid);
        boolean isAdmin = player.hasPermissions(2);;
        boolean isExempt = eng.isExempt(uuid, isAdmin);
        boolean inList = cfg.exemptPlayers().contains(uuid);
        int worst = qs.worstAlert() == null ? -1 : qs.worstAlert().percent();
        // 预设（issue #1、#2）：预设名列表仅管理员下发；当前玩家预设对全体下发（null = default）
        List<String> presetNames = isAdmin
                ? eng.getPresetStore().all().stream().map(PresetStore.Preset::name).toList()
                : List.of();
        // v3（issue #3）：各维度该玩家的额度状态（用量页维度下拉，全体下发）
        List<GuiStatus.DimLines> dimLines = new java.util.ArrayList<>();
        for (String dim : liveDims) {
            QuotaEngine.QuotaStatus ds = eng.quotaStatus(uuid, dim);
            int dworst = ds.worstAlert() == null ? -1 : ds.worstAlert().percent();
            dimLines.add(new GuiStatus.DimLines(dim, ds.lines(), ds.recoveryMillis(), dworst));
        }
        // v3：维度管理配置仅管理员下发；维度集合 = live 维度 ∪ store 已有条目（键去重排序）
        GuiStatus.DimConfigStatus dimConfig = null;
        if (isAdmin) {
            java.util.TreeSet<String> keys = new java.util.TreeSet<>(liveDims);
            keys.addAll(eng.getDimensionStore().configuredDimKeys());
            List<GuiStatus.DimEntry> entries = new java.util.ArrayList<>();
            for (String dim : keys) {
                dev.chunkplan.common.DimensionStore.SpawnPoint sp = eng.getDimensionStore().spawn(dim);
                List<QuotaTiers.Tier> tiers = eng.getDimensionStore().tiers(dim);
                entries.add(new GuiStatus.DimEntry(dim, eng.getDimensionStore().isBillingEnabled(dim),
                        sp != null, sp == null ? 0 : sp.x(), sp == null ? 0 : sp.y(), sp == null ? 0 : sp.z(),
                        tiers == null ? List.of() : tiers));
            }
            dimConfig = new GuiStatus.DimConfigStatus(eng.getDimensionStore().redirectOnExhaust(),
                    eng.getDimensionStore().redirectOrder(), entries);
        }
        return new GuiStatus(
                cfg.firstEntryFee(), cfg.familiarEntryFee(), cfg.highSpeedThreshold(), cfg.highSpeedMultiplier(),
                cfg.exemptByDefault(), isExempt, inList, isAdmin,
                isAdmin ? ForgeConfig.readRawTiers(resolveConfigFile(player)) : List.of(),
                qs.lines(), qs.allExceeded(), qs.recoveryMillis(), worst,
                presetNames, eng.getPlayerPresetName(uuid),
                independent ? 1 : 0, currentDim, liveDims, dimLines, dimConfig,
                null, List.of(),
                ChunkPlanForge.MOD_VERSION, false);
    }

    /** 实际生效的配置文件（坑 #38）：存档级 serverconfig 唯一位置，config/ 仅作异常时序兜底 */
    private static Path resolveConfigFile(ServerPlayer player) {
        Path serverConfigFile = player.server.getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig").resolve("chunkplan-server.toml");
        Path fallbackFile = player.server.getServerDirectory().toPath().resolve("config")
                .resolve("chunkplan-server.toml");
        return Files.exists(serverConfigFile) ? serverConfigFile : fallbackFile;
    }
}
