package dev.chunkplan.neoforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.chunkplan.common.GuiStatus;
import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChunkPlan 客户端 GUI 网络通道（NeoForge 1.21.1）。
 *
 * <p>协议：C2S {@link GuiRequestPayload}（请求状态）/ {@link GuiCommandPayload}（GUI 操作拼成
 * 命令串透传，服务端用 {@code dispatcher.execute} 复用权限/确认/配置逻辑）+ S2C
 * {@link GuiStatusPayload}（{@link GuiStatus} 编码后的字节数组）。
 *
 * <p>「客户端可选」握手（坑 #34 语义扩展）：channel 以 {@code optional()} 注册——原版客户端未装本
 * mod 时不会因缺失通道被断开；客户端连未装本 mod 的服务器时请求无响应，GUI 显示"服务器未装"。
 */
@EventBusSubscriber(modid = ChunkPlanNeoForge.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ChunkPlanNetwork {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPlan");

    private static final String CHANNEL_VERSION = "1";

    /** 状态请求冷却（毫秒）：防高频请求放大（配置文件仅管理员请求才重读，坑见 buildGuiStatus） */
    private static final long REQUEST_COOLDOWN_MILLIS = 250L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> LAST_REQUEST =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** C2S：请求状态（携带协议版本，版本不符服务端不回包） */
    public record GuiRequestPayload(int protocolVersion) implements CustomPacketPayload {
        public static final Type<GuiRequestPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(ChunkPlanNeoForge.MODID, "gui_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuiRequestPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, GuiRequestPayload::protocolVersion, GuiRequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：GUI 操作透传命令串（不含前导斜杠） */
    public record GuiCommandPayload(String command) implements CustomPacketPayload {
        public static final Type<GuiCommandPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(ChunkPlanNeoForge.MODID, "gui_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuiCommandPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, GuiCommandPayload::command, GuiCommandPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：编码后的 {@link GuiStatus} 字节数组 */
    public record GuiStatusPayload(byte[] status) implements CustomPacketPayload {
        public static final Type<GuiStatusPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(ChunkPlanNeoForge.MODID, "gui_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuiStatusPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, GuiStatusPayload::status, GuiStatusPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // optional() 返回克隆实例，必须在返回的实例上注册（漏 optional 会导致原版客户端因缺失通道被断开）
        PayloadRegistrar registrar = event.registrar(CHANNEL_VERSION).optional();
        registrar.playToServer(GuiRequestPayload.TYPE, GuiRequestPayload.STREAM_CODEC, ChunkPlanNetwork::handleRequest);
        registrar.playToServer(GuiCommandPayload.TYPE, GuiCommandPayload.STREAM_CODEC, ChunkPlanNetwork::handleCommand);
        registrar.playToClient(GuiStatusPayload.TYPE, GuiStatusPayload.STREAM_CODEC, ChunkPlanNetwork::handleStatus);
    }

    private static void handleRequest(GuiRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.protocolVersion() != GuiStatus.PROTOCOL_VERSION) {
                return;
            }
            ServerPlayer player = asPlayer(context);
            if (player == null) {
                return;
            }
            long now = System.currentTimeMillis();
            Long last = LAST_REQUEST.get(player.getUUID());
            if (last != null && now - last < REQUEST_COOLDOWN_MILLIS) {
                return; // 冷却期内丢弃，防高频状态请求放大
            }
            LAST_REQUEST.put(player.getUUID(), now);
            sendStatus(player);
        });
    }

    private static void handleCommand(GuiCommandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = asPlayer(context);
            if (player == null) {
                return;
            }
            String cmd = sanitize(payload.command());
            if (cmd == null) {
                sendStatus(player); // 空/非法输入：仍回推状态刷新
                return;
            }
            // 命令透传：权限等级由 createCommandSourceStack() 从服务端 ops 系统读取（客户端不可伪造），
            // requires(s.hasPermission(2)) 正常生效；反馈走聊天（与手输命令一致，含确认超链接）
            try {
                player.getServer().getCommands().getDispatcher().execute(cmd, player.createCommandSourceStack());
            } catch (Exception e) {
                // 命令解析/执行失败：GUI 只拼合法命令，此处仅防御（玩家侧由状态刷新兜底）
                LOG.debug("GUI 命令执行失败: {}", cmd, e);
            }
            sendStatus(player);
        });
    }

    private static void handleStatus(GuiStatusPayload payload, IPayloadContext context) {
        // S2C 仅在客户端触发（服务端永不接收 S2C）；enqueueWork 保证在主线程操作 GUI，
        // 直接调客户端类即可（ChunkPlanClient 为 client-only，服务端永不加载）
        context.enqueueWork(() -> ChunkPlanClient.onStatus(payload.status()));
    }

    /** 剥前导斜杠并校验；空串/含控制字符（换行/回车/NUL 等）返回 null——
     *  Brigadier 不接受 '/' 开头；控制字符只进日志有注入风险，故整串拒绝（仅防御） */
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

    private static ServerPlayer asPlayer(IPayloadContext context) {
        return context.player() instanceof ServerPlayer sp ? sp : null;
    }

    /** 玩家登出时清除其状态请求冷却条目（防 LAST_REQUEST 无界增长） */
    public static void onPlayerDisconnect(UUID uuid) {
        LAST_REQUEST.remove(uuid);
    }

    private static void sendStatus(ServerPlayer player) {
        QuotaEngine eng = ChunkPlanNeoForge.engine;
        if (eng == null) {
            return;
        }
        GuiStatus status = buildGuiStatus(eng, player);
        PacketDistributor.sendToPlayer(player, new GuiStatusPayload(status.encode()));
    }

    /** 由引擎 + 配置文件构建客户端 GUI 状态（用量页/管理页数据源） */
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
                isAdmin ? NeoForgeConfig.readRawTiers(resolveConfigFile(player)) : List.of(),
                qs.lines(), qs.allExceeded(), qs.recoveryMillis(), worst);
    }

    /** 实际生效的配置文件：world/serverconfig/ 覆盖层存在时优先（与启动/命令语义一致） */
    private static Path resolveConfigFile(ServerPlayer player) {
        Path base = player.getServer().getServerDirectory().resolve("config").resolve("chunkplan-server.toml");
        Path override = player.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig").resolve("chunkplan-server.toml");
        return Files.exists(override) ? override : base;
    }

    private ChunkPlanNetwork() {
    }
}
