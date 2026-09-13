package dev.chunkplan.fabric;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.chunkplan.common.GuiStatus;
import dev.chunkplan.common.PresetStore;
import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaEngine;
import dev.chunkplan.common.QuotaTiers;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * ChunkPlan 客户端 GUI 网络通道（Fabric 1.21.1）。
 *
 * <p>协议同 NeoForge 端：C2S {@link GuiRequestPayload} / {@link GuiCommandPayload}（命令透传）
 * + S2C {@link GuiStatusPayload}（{@link GuiStatus} 字节数组）。「客户端可选」：Fabric play 阶段
 * 无通道协商，vanilla 客户端收到未知 S2C 会被忽略（永不踢出）；发送前用
 * {@link ServerPlayNetworking#canSend} 判断，仅向装了 mod 的客户端回推。
 */
public final class ChunkPlanNetwork {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPlan");
    private static final String MODID = "chunkplan";

    /** 状态请求冷却（毫秒）：防高频请求放大（配置文件仅管理员请求才重读） */
    private static final long REQUEST_COOLDOWN_MILLIS = 250L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> LAST_REQUEST =
            new java.util.concurrent.ConcurrentHashMap<>();

    public record GuiRequestPayload(int protocolVersion) implements CustomPacketPayload {
        public static final Type<GuiRequestPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(MODID, "gui_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuiRequestPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, GuiRequestPayload::protocolVersion, GuiRequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GuiCommandPayload(String command) implements CustomPacketPayload {
        public static final Type<GuiCommandPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(MODID, "gui_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuiCommandPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, GuiCommandPayload::command, GuiCommandPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GuiStatusPayload(byte[] status) implements CustomPacketPayload {
        public static final Type<GuiStatusPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(MODID, "gui_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuiStatusPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, GuiStatusPayload::status, GuiStatusPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private ChunkPlanNetwork() {
    }

    /** 注册 payload 类型与编解码（双端都要，Fabric 发送方无 codec 会抛） */
    public static void registerTypes() {
        PayloadTypeRegistry.playC2S().register(GuiRequestPayload.TYPE, GuiRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(GuiCommandPayload.TYPE, GuiCommandPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(GuiStatusPayload.TYPE, GuiStatusPayload.STREAM_CODEC);
    }

    /** 注册服务端接收器（在 main 入口调用；服务端执行） */
    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(GuiRequestPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                if (!allowRequest(context.player().getUUID())) {
                    return;
                }
                if (payload.protocolVersion() != GuiStatus.PROTOCOL_VERSION) {
                    // 版本不匹配：回版本横幅（不依赖引擎，引擎未就绪也可回），客户端据此渲染兜底页并
                    // 显示两端版本号；旧客户端（无横幅解析能力）读协议头即失败，行为同从前
                    ServerPlayNetworking.send(context.player(), new GuiStatusPayload(
                            GuiStatus.versionBanner(ChunkPlanFabric.MOD_VERSION).encode()));
                    return;
                }
                sendStatus(context.player());
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(GuiCommandPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                String cmd = sanitize(payload.command());
                if (cmd == null) {
                    sendStatus(context.player());
                    return;
                }
                try {
                    context.server().getCommands().getDispatcher()
                            .execute(cmd, context.player().createCommandSourceStack());
                } catch (Exception e) {
                    LOG.debug("GUI 命令执行失败: {}", cmd, e);
                }
                sendStatus(context.player());
            });
        });
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
        QuotaEngine eng = ChunkPlanFabric.engine;
        if (eng == null) {
            return;
        }
        // 仅向装了 mod 的客户端回推（vanilla 客户端 canSend 恒 false）
        if (!ServerPlayNetworking.canSend(player, GuiStatusPayload.TYPE)) {
            return;
        }
        GuiStatus status = buildGuiStatus(eng, player);
        ServerPlayNetworking.send(player, new GuiStatusPayload(status.encode()));
    }

    public static GuiStatus buildGuiStatus(QuotaEngine eng, ServerPlayer player) {
        UUID uuid = player.getUUID();
        QuotaConfig cfg = eng.getConfig();
        boolean independent = eng.isIndependentMode();
        String currentDim = player.level().dimension().identifier().toString();
        List<String> liveDims = ChunkPlanFabric.liveDims(player.level().getServer());
        QuotaEngine.QuotaStatus qs = independent ? eng.quotaStatus(uuid, currentDim) : eng.quotaStatus(uuid);
        boolean isAdmin = DevCommands.hasPermission(player, 2);;
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
                isAdmin ? FabricConfig.readRawTiers(ChunkPlanFabric.configFile) : List.of(),
                qs.lines(), qs.allExceeded(), qs.recoveryMillis(), worst,
                presetNames, eng.getPlayerPresetName(uuid),
                independent ? 1 : 0, currentDim, liveDims, dimLines, dimConfig,
                ChunkPlanFabric.MOD_VERSION, false);
    }
}
