package dev.chunkplan.fabric;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.chunkplan.common.GuiStatus;
import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaEngine;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
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
                new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "gui_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuiRequestPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, GuiRequestPayload::protocolVersion, GuiRequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GuiCommandPayload(String command) implements CustomPacketPayload {
        public static final Type<GuiCommandPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "gui_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuiCommandPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, GuiCommandPayload::command, GuiCommandPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GuiStatusPayload(byte[] status) implements CustomPacketPayload {
        public static final Type<GuiStatusPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "gui_status"));
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
            if (payload.protocolVersion() != GuiStatus.PROTOCOL_VERSION) {
                return;
            }
            context.server().execute(() -> {
                if (!allowRequest(context.player().getUUID())) {
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
        QuotaEngine.QuotaStatus qs = eng.quotaStatus(uuid);
        boolean isAdmin = player.hasPermissions(2);
        boolean isExempt = eng.isExempt(uuid, isAdmin);
        boolean inList = cfg.exemptPlayers().contains(uuid);
        int worst = qs.worstAlert() == null ? -1 : qs.worstAlert().percent();
        return new GuiStatus(
                cfg.firstEntryFee(), cfg.familiarEntryFee(), cfg.highSpeedThreshold(), cfg.highSpeedMultiplier(),
                cfg.exemptByDefault(), isExempt, inList, isAdmin,
                isAdmin ? FabricConfig.readRawTiers(ChunkPlanFabric.configFile) : List.of(),
                qs.lines(), qs.allExceeded(), qs.recoveryMillis(), worst);
    }
}
