package dev.chunkplan.neoforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.chunkplan.common.FeedbackText;
import dev.chunkplan.common.GuiStatus;
import dev.chunkplan.common.PresetStore;
import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaEngine;
import dev.chunkplan.common.QuotaTiers;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
            if (payload.protocolVersion() != GuiStatus.PROTOCOL_VERSION) {
                // 版本不匹配：回版本横幅（不依赖引擎，引擎未就绪也可回），客户端据此渲染兜底页并
                // 显示两端版本号；旧客户端（无横幅解析能力）读协议头即失败，行为同从前
                PacketDistributor.sendToPlayer(player, new GuiStatusPayload(
                        GuiStatus.versionBanner(ChunkPlanNeoForge.MOD_VERSION).encode()));
                return;
            }
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
            // requires(s.hasPermission(2)) 正常生效；反馈既走聊天（与手输命令一致，含确认超链接），
            // 又经 FeedbackSource 抄一份文本塞进本次回推的 GuiStatus（坑 #58：GUI 挡着聊天框看不到结果）
            CommandSourceStack base = player.createCommandSourceStack();
            // 委托源：旧世代（1.20.1/1.21.1）Entity 即 CommandSource，玩家实体本身可作命令源
            FeedbackSource fb = new FeedbackSource(player);
            int result;
            try {
                result = player.getServer().getCommands().getDispatcher().execute(cmd, base.withSource(fb));
            } catch (Exception e) {
                // 命令解析/执行失败：GUI 只拼合法命令，此处仅防御。异常路径不回反馈文本
                // （feedback = null，界面不显示反馈面板）：异常原因各式各样，复用"写入配置失败"
                // 口径会误导，而新增一条通用失败文案需再引入 4 端文案同步面；异常详情已进服务端日志
                LOG.debug("GUI 命令执行失败: {}", cmd, e);
                sendStatus(player, null);
                return;
            }
            // 成功/失败判据用 Brigadier 返回值：本 mod 命令体全部"失败 return 0、成功 return 1"
            sendStatus(player, fb.toFeedback(result > 0));
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

    /**
     * 捕获命令反馈的命令源（坑 #58）：转发给原命令源（聊天照旧，必须有这一步，否则反馈只在
     * GUI 出现、聊天丢失）并抄一份文本供 GUI 展示。
     *
     * <p>机制（javap 实证 1.20.1/1.21.1/1.21.11/26.x 四代一致）：{@code CommandSourceStack.withSource}
     * 为 public；{@code sendSuccess}/{@code sendFailure} 最终都经
     * {@code CommandSource.sendSystemMessage(Component)} 落到 {@code source} 字段
     * （sendFailure 会追加红色样式，本类逐字转发后由 {@code FeedbackText} 剥离）。
     * {@code withCallback(CommandResultCallback)} 只携带 (boolean,int) 无文本，不适用。
     */
    private static final class FeedbackSource implements CommandSource {
        private final CommandSource delegate;
        private final List<String> captured = new ArrayList<>();

        private FeedbackSource(CommandSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public void sendSystemMessage(Component component) {
            delegate.sendSystemMessage(component);
            captured.add(component == null ? "" : component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return delegate.acceptsSuccess();
        }

        @Override
        public boolean acceptsFailure() {
            return delegate.acceptsFailure();
        }

        @Override
        public boolean shouldInformAdmins() {
            return delegate.shouldInformAdmins();
        }

        /** 无反馈文本（未产生任何消息）返回 null = 界面不显示反馈面板；首条非空反馈经 common 清洗 */
        private GuiStatus.GuiFeedback toFeedback(boolean success) {
            for (String raw : captured) {
                String text = FeedbackText.sanitize(raw);
                if (text != null) {
                    return new GuiStatus.GuiFeedback(text, success);
                }
            }
            return null;
        }
    }

    /** 玩家登出时清除其状态请求冷却条目（防 LAST_REQUEST 无界增长） */
    public static void onPlayerDisconnect(UUID uuid) {
        LAST_REQUEST.remove(uuid);
    }

    private static void sendStatus(ServerPlayer player) {
        sendStatus(player, null);
    }

    private static void sendStatus(ServerPlayer player, GuiStatus.GuiFeedback feedback) {
        QuotaEngine eng = ChunkPlanNeoForge.engine;
        if (eng == null) {
            return;
        }
        GuiStatus status = buildGuiStatus(eng, player, feedback);
        PacketDistributor.sendToPlayer(player, new GuiStatusPayload(status.encode()));
    }

    public static GuiStatus buildGuiStatus(QuotaEngine eng, ServerPlayer player) {
        return buildGuiStatus(eng, player, null);
    }

    /** 由引擎 + 配置文件构建客户端 GUI 状态（用量页/管理页/维度页数据源；feedback 见坑 #58） */
    public static GuiStatus buildGuiStatus(QuotaEngine eng, ServerPlayer player, GuiStatus.GuiFeedback feedback) {
        UUID uuid = player.getUUID();
        QuotaConfig cfg = eng.getConfig();
        boolean isAdmin = player.hasPermissions(2);
        boolean isExempt = eng.isExempt(uuid, isAdmin);
        boolean inList = cfg.exemptPlayers().contains(uuid);
        boolean independent = eng.isIndependentMode();
        String currentDim = player.level().dimension().location().toString();
        List<String> liveDims = ChunkPlanNeoForge.GameEvents.liveDims(player.getServer());
        // 独立模式（issue #3）：主状态为玩家当前维度的额度情况；共享模式同现状
        QuotaEngine.QuotaStatus qs = independent ? eng.quotaStatus(uuid, currentDim) : eng.quotaStatus(uuid);
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
        // v5（issue #1、#2）：预设内容（名 + 四档，恒 4 项）仅管理员下发，与 presets 同策略；
        // tiers() 理论上非 null，仍防御性兜底（坑 #54 的不可变列表 NPE 教训）
        List<GuiStatus.PresetInfo> presetInfos = isAdmin
                ? eng.getPresetStore().all().stream()
                        .map(p -> new GuiStatus.PresetInfo(p.name(), p.tiers() == null ? List.of() : p.tiers()))
                        .toList()
                : List.of();
        return new GuiStatus(
                cfg.firstEntryFee(), cfg.familiarEntryFee(), cfg.highSpeedThreshold(), cfg.highSpeedMultiplier(),
                cfg.exemptByDefault(), isExempt, inList, isAdmin,
                isAdmin ? NeoForgeConfig.readRawTiers(resolveConfigFile(player)) : List.of(),
                qs.lines(), qs.allExceeded(), qs.recoveryMillis(), worst,
                presetNames, eng.getPlayerPresetName(uuid),
                independent ? 1 : 0, currentDim, liveDims, dimLines, dimConfig,
                feedback, presetInfos,
                ChunkPlanNeoForge.MOD_VERSION, false);
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
