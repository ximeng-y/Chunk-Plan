package dev.chunkplan.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChunkPlan 记账引擎（纯 Java，零 Minecraft 依赖，双加载器共用）。
 *
 * <p>每 tick 计费流程（计划 4.2，顺序严格不可调换）：
 * <ol>
 *   <li>登录首 tick（prevChunk==null）：只记录基准位置/区块，不扣费</li>
 *   <li>区块或维度变化：
 *     <ul>
 *       <li>speed = 上 tick 到本 tick 的三维位移（格/tick）</li>
 *       <li>基础费 = 集合内含 curChunk ? familiarEntryFee : firstEntryFee；不在集合则先加入集合</li>
 *       <li>总费 = 基础费 × (speed > 阈值 ? 倍率 : 1)</li>
 *       <li>累加进各启用档位的固定周期（首消锚定起点、到点整窗清零，坑 #40），写独立扣费日志</li>
 *       <li>先记账后判踢：任一额度线满 -> 返回 BAN（含恢复时间；文案由壳层按玩家语言渲染）</li>
 *     </ul>
 *   </li>
 *   <li>更新 prevPos/prevChunk/prevDim</li>
 * </ol>
 */
public final class QuotaEngine {

    private static final Logger LOG = LoggerFactory.getLogger(QuotaEngine.class);

    public enum ResultType {
        NONE, KICK, BAN, REDIRECT
    }

    /** 提示严重度：低（浅绿）/ 中（黄）/ 高（红），壳层按此着色（坑 #28） */
    public enum Severity {
        LOW, MEDIUM, HIGH
    }

    /** 额度百分比阈值提示（坑 #28）：窗口秒数 + 触发档位 + 严重度，文案由壳层渲染 */
    public record WindowAlert(long windowSeconds, int percent, Severity severity) {
    }

    /**
     * 计费结果。用户可见文案（ban 消息等）属表现层，由壳层按玩家语言渲染，
     * 引擎只返回结构化数据（坑 #22）。alerts 为本 tick 触发的额度百分比提示
     * （跨档逐条；BAN/REDIRECT 当 tick 不发提示，公告消息已充分说明）。
     * redirectDim 仅在 type=REDIRECT 时有意义（issue #3：维度独立模式耗尽重定向的目标维度，
     * 落地坐标由壳层从 {@link #getDimensionStore()} 读取并执行跨维度传送）。
     */
    public record TickResult(ResultType type, long banUntilMillis, List<WindowAlert> alerts, String redirectDim) {
        public static TickResult none() {
            return new TickResult(ResultType.NONE, -1, List.of(), null);
        }

        public static TickResult none(List<WindowAlert> alerts) {
            return new TickResult(ResultType.NONE, -1, alerts, null);
        }

        public static TickResult ban(long untilMillis) {
            return new TickResult(ResultType.BAN, untilMillis, List.of(), null);
        }

        public static TickResult redirect(String targetDim) {
            return new TickResult(ResultType.REDIRECT, -1, List.of(), targetDim);
        }
    }

    /** 单线状态：窗口/上限/已消费/下次重置时间（该线当前周期终点，到点整窗清零；无消费为 -1） */
    public record LineStatus(long windowSeconds, double limit, double spent, long nextResetMillis) {
    }

    /**
     * check 状态。presetName 为该玩家当前应用的预设名（null = 跟随全局 default，
     * 即未被按玩家预设覆盖）——壳层据此在 check 文案中显示来源（issue #2）。
     */
    public record QuotaStatus(List<LineStatus> lines, long recoveryMillis, boolean allExceeded, WindowAlert worstAlert,
                              String presetName) {
    }

    /** 提示状态键（issue #3 维度独立）：共享模式 dim 为 null（状态跟随玩家跨维度）；
     * 独立模式按（玩家, 维度）隔离——各维度额度线互不相同 */
    private record AlertKey(UUID uuid, String dim) {
    }

    /** 每玩家追踪状态（首 tick / 上一 tick 位置与区块） */
    private static final class Tracking {
        double prevX;
        double prevY;
        double prevZ;
        Long prevChunk;
        String prevDim;
    }

    /** 每玩家（每维度）额度提示状态（坑 #28，瞬态不落盘）：每窗口已触发的最高档位 */
    private static final class AlertState {
        boolean initialized;
        int[] lastLevels; // 与有效额度线列表下标对齐
    }

    /** 触发档位表（严格按用户要求）：达到即触发；15~30 低、50~75 中、80~98 高 */
    private static final int[] ALERT_PERCENTS = {15, 30, 50, 65, 75, 80, 85, 90, 95, 98};

    private final Path dataDir;
    private final Path playerDataDir;
    private volatile FeeLogger feeLogger;
    private final ManagedBanStore banStore;
    private final PresetStore presetStore;
    /** 维度计费配置库（issue #3）：模式/计费开关/落地坐标/每维度四档/重定向，引擎自建（仿 presetStore） */
    private final DimensionStore dimStore;
    private final LongSupplier clock;

    private volatile QuotaConfig config;
    private final ConcurrentMap<UUID, PlayerQuotaData> dataByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Tracking> tracking = new ConcurrentHashMap<>();
    private final ConcurrentMap<AlertKey, AlertState> alertStates = new ConcurrentHashMap<>();
    /** 按玩家预设覆盖（issue #2）：uuid -> 覆盖配置（额度线来自预设，其余字段抄全局） */
    private final ConcurrentMap<UUID, QuotaConfig> playerOverrides = new ConcurrentHashMap<>();

    /**
     * @param dataDir   存档内数据根目录（如 {@code <world>/chunkplan}），壳层传
     * @param config    初始配置
     * @param feeLogger 扣费日志（logFeeEvents=false 时传空实现即可）
     * @param banStore  管理名单
     */
    public QuotaEngine(Path dataDir, QuotaConfig config, FeeLogger feeLogger, ManagedBanStore banStore) {
        this(dataDir, config, feeLogger, banStore, System::currentTimeMillis);
    }

    /** 包内可见：注入时钟，供单元测试模拟时间流逝 */
    QuotaEngine(Path dataDir, QuotaConfig config, FeeLogger feeLogger, ManagedBanStore banStore, LongSupplier clock) {
        this.dataDir = dataDir;
        this.playerDataDir = dataDir.resolve("players");
        this.config = config;
        this.feeLogger = feeLogger;
        this.banStore = banStore;
        // 预设库随引擎自建（issue #1：预设存储在服务端 <world>/chunkplan/presets.json），
        // 壳层无需感知文件路径；构造即按已持久化的分配重建按玩家覆盖
        this.presetStore = new PresetStore(dataDir.resolve("presets.json"));
        // 维度配置库随引擎自建（issue #3：<world>/chunkplan/dimensions.json），壳层零接线
        this.dimStore = new DimensionStore(dataDir.resolve("dimensions.json"));
        this.clock = clock;
        rebuildAllOverrides();
    }

    public Path getDataDir() {
        return dataDir;
    }

    public Path getPlayerDataDir() {
        return playerDataDir;
    }

    public ManagedBanStore getBanStore() {
        return banStore;
    }

    public PresetStore getPresetStore() {
        return presetStore;
    }

    public DimensionStore getDimensionStore() {
        return dimStore;
    }

    /** 是否处于维度独立计费模式（issue #3） */
    public boolean isIndependentMode() {
        return dimStore.isIndependent();
    }

    public QuotaConfig getConfig() {
        return config;
    }

    /** 配置热更新（/chunkplan reload 时调用，先由壳层校验并构建 QuotaConfig） */
    public void setConfig(QuotaConfig config) {
        QuotaConfig old = this.config;
        this.config = config;
        // 额度线集合（档位）变化时清空提示状态：AlertState.lastLevels 按下标对齐 lines，
        // 启用/禁用档位改变线数后旧数组会越界/错位（坑 #30；清后下次 tick 首见只重基线不刷屏）
        if (old != null && !sameLineTiers(old.lines(), config.lines())) {
            alertStates.clear();
        }
        // 按玩家覆盖的非额度线字段（费率/倍率/豁免等）抄自全局，全局变更须随之重建；
        // 覆盖的额度线来自预设（与全局无关），线数不变故不影响对应玩家的提示状态
        rebuildAllOverrides();
    }

    /** 两条额度线集合的档位序列是否一致（按顺序比较；toLines 恒按档位升序产出） */
    private static boolean sameLineTiers(List<QuotaConfig.Line> a, List<QuotaConfig.Line> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).tier() != b.get(i).tier()) {
                return false;
            }
        }
        return true;
    }

    /** 运行期更换扣费日志实现（/chunkplan reload 时 logFeeEvents 开关热切换；false 传 null） */
    public void setFeeLogger(FeeLogger feeLogger) {
        this.feeLogger = feeLogger;
    }

    // ---------- 按玩家预设覆盖（issue #1、#2） ----------

    /**
     * 玩家有效配置：有预设覆盖用覆盖（额度线来自预设，费率/倍率/豁免等抄全局），否则全局配置。
     * 引擎内所有"针对某玩家"的额度线/费率读取必须经此解析，禁止直读 {@code config}。
     */
    private QuotaConfig effectiveConfig(UUID uuid) {
        QuotaConfig override = playerOverrides.get(uuid);
        return override != null ? override : config;
    }

    /** 由预设构建覆盖配置：额度线来自预设（toLines 校验回退），其余字段抄当前全局 */
    private QuotaConfig buildOverride(PresetStore.Preset preset) {
        List<String> warnings = new ArrayList<>();
        List<QuotaConfig.Line> lines = QuotaTiers.toLines(preset.tiers(), warnings);
        for (String w : warnings) {
            LOG.warn("预设 {} 产生额度线告警：{}", preset.name(), w);
        }
        QuotaConfig g = config;
        return QuotaConfig.builder()
                .lines(lines)
                .firstEntryFee(g.firstEntryFee())
                .familiarEntryFee(g.familiarEntryFee())
                .highSpeedThreshold(g.highSpeedThreshold())
                .highSpeedMultiplier(g.highSpeedMultiplier())
                .exemptByDefault(g.exemptByDefault())
                .exemptPlayers(g.exemptPlayers())
                .saveIntervalSec(g.saveIntervalSec())
                .banScanIntervalSec(g.banScanIntervalSec())
                .logFeeEvents(g.logFeeEvents())
                .build(null);
    }

    private void rebuildAllOverrides() {
        playerOverrides.clear();
        for (Map.Entry<UUID, String> e : presetStore.assignments().entrySet()) {
            PresetStore.Preset p = presetStore.get(e.getValue());
            if (p != null) {
                playerOverrides.put(e.getKey(), buildOverride(p));
            }
        }
    }

    /** 给玩家应用预设（覆盖其额度线，按 UUID 持久化、离线可用）；预设不存在返回 false */
    public boolean setPlayerPreset(UUID uuid, String presetName) {
        PresetStore.Preset p = presetStore.get(presetName);
        if (p == null) {
            return false;
        }
        presetStore.assign(uuid, presetName);
        playerOverrides.put(uuid, buildOverride(p));
        // 预设线数可能与全局/各维度不同：AlertState.lastLevels 按下标对齐有效线列表，须清（坑 #30 同因）
        removeAlertStates(uuid);
        return true;
    }

    /** 清除玩家覆盖（回落全局 default 预设） */
    public void clearPlayerPreset(UUID uuid) {
        presetStore.assign(uuid, null);
        playerOverrides.remove(uuid);
        removeAlertStates(uuid);
    }

    /** 玩家当前预设名；null = 跟随全局 default */
    public String getPlayerPresetName(UUID uuid) {
        return presetStore.assignment(uuid);
    }

    /** 玩家最后所在维度（v4 lastDim；独立模式 check 离线玩家展示用；无记录返回 null） */
    public String lastDimOf(UUID uuid) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        return data.lastDim();
    }

    /**
     * 保存（或同名覆盖）预设；成功后刷新正在使用该预设的玩家覆盖（同名覆盖即改其生效值）。
     * 校验失败（名称/档位非法）返回 false。
     */
    public boolean savePreset(String name, List<QuotaTiers.Tier> tiers) {
        if (!presetStore.save(name, tiers)) {
            return false;
        }
        PresetStore.Preset p = presetStore.get(name);
        for (Map.Entry<UUID, String> e : presetStore.assignments().entrySet()) {
            if (e.getValue().equals(name)) {
                playerOverrides.put(e.getKey(), buildOverride(p));
                removeAlertStates(e.getKey());
            }
        }
        return true;
    }

    /** 删除预设并解除相关分配；返回解除的分配数，预设不存在返回 -1 */
    public int deletePreset(String name) {
        List<UUID> affected = new ArrayList<>();
        for (Map.Entry<UUID, String> e : presetStore.assignments().entrySet()) {
            if (e.getValue().equals(name)) {
                affected.add(e.getKey());
            }
        }
        int unassigned = presetStore.delete(name);
        if (unassigned < 0) {
            return -1;
        }
        for (UUID uuid : affected) {
            playerOverrides.remove(uuid);
            removeAlertStates(uuid);
        }
        return unassigned;
    }

    // ---------- 维度独立计费（issue #3） ----------

    /** 清除该玩家所有维度的提示状态（预设覆盖变更/断线用） */
    private void removeAlertStates(UUID uuid) {
        alertStates.keySet().removeIf(k -> k.uuid().equals(uuid));
    }

    /** 清除该维度所有玩家的提示状态（维度额度线/计费开关变更用：线数可能变化防下标错位，坑 #30 同因） */
    private void removeAlertStatesForDim(String dimKey) {
        alertStates.keySet().removeIf(k -> dimKey.equals(k.dim()));
    }

    /** 本 tick 的提示状态键：独立模式按（玩家, 维度）隔离，共享模式跟随玩家（dim=null） */
    private AlertKey alertKey(UUID uuid, String dimKey, boolean independent) {
        return new AlertKey(uuid, independent ? dimKey : null);
    }

    /**
     * 由当前全局激活线推导四档原始 12 值快照（issue #3 模式切换/未知维度初始化用）：
     * 引擎只持有激活线（无原始 12 值），启用档反查窗口写法还原；禁用档取该档默认
     * （重新启用时反正要走 windowTime/windowLimit 设置）。
     */
    private List<QuotaTiers.Tier> snapshotGlobalTiers() {
        List<QuotaConfig.Line> lines = config.lines();
        List<QuotaTiers.Tier> out = new ArrayList<>(4);
        for (int tier = 1; tier <= 4; tier++) {
            QuotaConfig.Line line = null;
            for (QuotaConfig.Line l : lines) {
                if (l.tier() == tier) {
                    line = l;
                    break;
                }
            }
            if (line == null) {
                QuotaTiers.TierDefault def = QuotaTiers.defaultOf(tier);
                out.add(new QuotaTiers.Tier(false, def.window(), def.limit()));
            } else {
                String window = QuotaTiers.presetNameForWindow(tier, line.windowSeconds());
                if (window == null) {
                    window = QuotaTiers.defaultOf(tier).window();
                }
                out.add(new QuotaTiers.Tier(true, window, line.limit()));
            }
        }
        return out;
    }

    /** 独立模式下该玩家在该维度的有效额度线：玩家预设（跨维度）> 该维度四档配置 */
    private List<QuotaConfig.Line> effectiveDimLines(UUID uuid, String dimKey) {
        QuotaConfig override = playerOverrides.get(uuid);
        if (override != null) {
            return override.lines();
        }
        List<QuotaTiers.Tier> tiers = dimStore.tiers(dimKey);
        if (tiers == null) {
            // 未知维度（模式切换后才注册等）：用全局快照初始化，保证判满语义确定
            tiers = snapshotGlobalTiers();
            dimStore.ensureTiers(dimKey, tiers);
        }
        List<String> warnings = new ArrayList<>();
        return QuotaTiers.toLines(tiers, warnings);
    }

    /** 该玩家在该维度的有效额度线（独立模式按维度，共享模式即全局语义）；bucketDim 返回记账桶维度（共享为 null） */
    private List<QuotaConfig.Line> effectiveLinesFor(UUID uuid, String dimKey, boolean[] bucketDimOut) {
        if (dimStore.isIndependent()) {
            if (bucketDimOut != null) {
                bucketDimOut[0] = true;
            }
            return effectiveDimLines(uuid, dimKey);
        }
        if (bucketDimOut != null) {
            bucketDimOut[0] = false;
        }
        return effectiveConfig(uuid).lines();
    }

    /**
     * 切换维度计费模式。切到 independent 前置校验全部 live 维度已有合法落地坐标
     * （/tp 数据规范），未通过返回缺失维度列表且不生效；通过时为缺失快照的 live 维度
     * 用当前全局 12 值初始化（快照，issue #3 拍板）。切回 shared 直接生效（维度配置保留）。
     * 成功返回空列表。共享模式全局线/预设语义不变。
     */
    public List<String> setDimensionMode(String mode, List<String> liveDims) {
        if (!DimensionStore.MODE_INDEPENDENT.equals(mode)) {
            dimStore.setMode(DimensionStore.MODE_SHARED);
            return List.of();
        }
        List<String> missing = dimStore.validateIndependentReady(liveDims);
        if (!missing.isEmpty()) {
            return missing;
        }
        List<QuotaTiers.Tier> snapshot = snapshotGlobalTiers();
        for (String dim : liveDims) {
            dimStore.ensureTiers(dim, snapshot);
        }
        dimStore.setMode(DimensionStore.MODE_INDEPENDENT);
        // 线结构整体切换（全局线 -> 各维度线）：提示状态全部重置，防 lastLevels 下标错位（坑 #30 同因）
        alertStates.clear();
        return List.of();
    }

    /** 设置维度计费开关（两种模式都生效；关闭后该维度不计费、可自由进入，仍记已探索集合） */
    public void setDimensionBilling(String dimKey, boolean billing) {
        dimStore.setBilling(dimKey, billing);
        removeAlertStatesForDim(dimKey);
    }

    /** 设置维度落地坐标；非法（/tp 规范）返回 false 不落盘 */
    public boolean setDimensionSpawn(String dimKey, double x, double y, double z) {
        return dimStore.setSpawn(dimKey, x, y, z);
    }

    /** 清空维度落地坐标（该维度转为"未配置"；独立模式下须重新配置才可作为重定向落点） */
    public void clearDimensionSpawn(String dimKey) {
        dimStore.clearSpawn(dimKey);
    }

    /** 设置维度四档额度线（独立模式）；校验失败返回 false；变更后清该维度提示状态 */
    public boolean setDimensionTiers(String dimKey, List<QuotaTiers.Tier> tiers) {
        if (!dimStore.setTiers(dimKey, tiers)) {
            return false;
        }
        removeAlertStatesForDim(dimKey);
        return true;
    }

    public void setRedirectOnExhaust(boolean v) {
        dimStore.setRedirectOnExhaust(v);
    }

    /** 设置重定向槽位；返回 {@link DimensionStore.RedirectResult}，非 OK 时槽位不变（命令层据此报错） */
    public DimensionStore.RedirectResult setRedirectTarget(int slot, String dim) {
        return dimStore.setRedirectTarget(slot, dim);
    }

    /** 豁免判定：默认 OP + 配置名单豁免；exemptByDefault=false 时全员受限 */
    public boolean isExempt(UUID uuid, boolean isOp) {
        QuotaConfig cfg = config;
        return (cfg.exemptByDefault() && isOp) || cfg.exemptPlayers().contains(uuid);
    }

    /** 每玩家每 tick 调用（须在服务端主线程串行）；共享模式可用（liveDims 传空即可） */
    public TickResult onPlayerTick(UUID uuid, boolean exempt, String dimKey, double x, double y, double z) {
        return onPlayerTick(uuid, exempt, dimKey, x, y, z, List.of());
    }

    /**
     * 每玩家每 tick 调用（须在服务端主线程串行）。liveDims 为当前世界的全部 live 维度
     * （issue #3：独立模式重定向候选与恢复时间计算需要；壳层从 server.getAllLevels() 取）。
     */
    public TickResult onPlayerTick(UUID uuid, boolean exempt, String dimKey, double x, double y, double z,
                                   List<String> liveDims) {
        if (exempt) {
            // 豁免玩家不参与记账；同时清除位移基准，使移除豁免后首个 tick 按首 tick 处理（只记基准不扣费）。
            // 若不清除，豁免期间累积位移会在解除豁免后的首个 tick 被当作区块变化计费（QA 实测 P1）
            tracking.remove(uuid);
            // 豁免玩家不提示；清状态避免解除豁免后旧档位残留导致不再提示
            removeAlertStates(uuid);
            return TickResult.none();
        }
        boolean independent = dimStore.isIndependent();
        // 该玩家的有效配置（有预设覆盖用覆盖，issue #2）；本 tick 全程用同一引用。
        // 独立模式下费率/高速等仍抄全局，额度线按维度另取（issue #3）
        QuotaConfig cfg = effectiveConfig(uuid);
        List<QuotaConfig.Line> lines = independent ? effectiveDimLines(uuid, dimKey) : cfg.lines();
        // 维度计费开关（issue #3，两种模式都生效）：关 = 该维度不计费、可自由进入
        boolean billingOn = dimStore.isBillingEnabled(dimKey);
        if (!billingOn) {
            // 该维度不计费：仍维护 tracking 与已探索集合（重新计费后熟悉费语义正确），
            // 零扣费、不判满、不提示。注意与下方"全局零线"分支的区别：这里数据照常加载
            PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
            data.setLastDim(dimKey);
            advanceTracking(data, uuid, dimKey, x, y, z);
            return TickResult.none();
        }
        if (lines.isEmpty()) {
            if (!independent) {
                // 全局/玩家覆盖零线（坑 #31）：全部窗口已关闭——不加载玩家数据、不记账、不判踢、不提示；
                // 清除位移基准：零线期间 tracking 不更新，若不清理，零线前已追踪的玩家在
                // 重开窗口后首个 tick 会把零线期间整段位移当区块变化计费（且必然触发高速
                // 倍率）——与豁免分支（上方）同款清理；清理后重开首 tick 走首 tick 分支
                // （只记基准不扣费），从 0 起
                tracking.remove(uuid);
                return TickResult.none();
            }
            // 独立模式下该维度零线（该维度全部档禁用）：该维度免费，但其它维度可能计费，
            // 因此照常维护 tracking 与已探索集合（与计费开关关闭同款）
            PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
            data.setLastDim(dimKey);
            advanceTracking(data, uuid, dimKey, x, y, z);
            return TickResult.none();
        }
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        data.setLastDim(dimKey);
        Tracking tr = tracking.computeIfAbsent(uuid, k -> new Tracking());
        long now = clock.getAsLong();

        int chunkX = (int) Math.floor(x / 16);
        int chunkZ = (int) Math.floor(z / 16);
        long curChunk = ChunkPosPacker.pack(chunkX, chunkZ);

        if (tr.prevChunk == null) {
            // 登录首 tick：只记录基准，不扣费
            tr.prevX = x;
            tr.prevY = y;
            tr.prevZ = z;
            tr.prevChunk = curChunk;
            tr.prevDim = dimKey;
            return TickResult.none(checkAlerts(alertKey(uuid, dimKey, independent), data, now, lines));
        }

        double dx = x - tr.prevX;
        double dy = y - tr.prevY;
        double dz = z - tr.prevZ;
        double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean enteredNewChunk = curChunk != tr.prevChunk || !dimKey.equals(tr.prevDim);
        // 先判变化再更新基准，顺序不可调换
        tr.prevX = x;
        tr.prevY = y;
        tr.prevZ = z;
        tr.prevChunk = curChunk;
        tr.prevDim = dimKey;

        if (enteredNewChunk) {
            // 基础费判定：先查集合，不在则先加入集合（"踏入的要么是来过的，要么是没来过的"）
            boolean familiar = data.isExplored(dimKey, curChunk);
            double base = familiar ? cfg.familiarEntryFee() : cfg.firstEntryFee();
            if (!familiar) {
                data.markExplored(dimKey, curChunk);
            }

            double fee = base * (speed > cfg.highSpeedThreshold() ? cfg.highSpeedMultiplier() : 1.0);
            // 每次消费计入有效额度线的所有启用档位（坑 #30 各窗口独立记账）；先惰性过期再记账：
            // 已到周期的档整窗清零并重新锚定（固定周期语义，坑 #40）。
            // 独立模式记入该维度桶（dimTiers），共享模式记入全局桶（tiers）
            for (QuotaConfig.Line line : lines) {
                if (independent) {
                    data.expireDimIfNeeded(dimKey, line.tier(), now, line.windowSeconds());
                    data.recordDimSpend(dimKey, line.tier(), now, fee);
                } else {
                    data.expireIfNeeded(line.tier(), now, line.windowSeconds());
                    data.recordSpend(line.tier(), now, fee);
                }
            }

            if (cfg.logFeeEvents() && feeLogger != null) {
                feeLogger.logFee(uuid, dimKey, curChunk, speed, fee, totalSpent(data, dimKey, independent, now, lines));
            }
        }

        // 先记账后判踢（坑 #25：任一额度线满即拒；坑 #30：每 tick 判满，原地不动也生效）
        if (isExceeded(data, independent ? dimKey : null, now, lines)) {
            if (independent) {
                // 独立模式：重定向优先（issue #3）——还有可进维度就传送过去而非封禁
                String target = dimStore.resolveRedirectTarget(liveDims, d -> isDimEnterable(uuid, d));
                if (target != null) {
                    return TickResult.redirect(target);
                }
                if (dimStore.redirectOnExhaust()) {
                    // 重定向开启且无候选（全维度不可进）：恢复时间 = 最早有任一维度可进的时刻
                    return TickResult.ban(earliestRecoveryAcrossDims(uuid, liveDims));
                }
                // 重定向关闭：本维度耗尽即封禁（其余维度可进也不放行），恢复 = 本维度周期终点
                return TickResult.ban(recoveryMillis(data, dimKey, now, lines));
            }
            return TickResult.ban(recoveryMillis(data, null, now, lines));
        }
        return TickResult.none(checkAlerts(alertKey(uuid, dimKey, independent), data, now, lines));
    }

    /** 维护位移/区块基准与已探索集合（不计费路径专用：计费开关关闭/该维度零线） */
    private void advanceTracking(PlayerQuotaData data, UUID uuid, String dimKey, double x, double y, double z) {
        Tracking tr = tracking.computeIfAbsent(uuid, k -> new Tracking());
        int chunkX = (int) Math.floor(x / 16);
        int chunkZ = (int) Math.floor(z / 16);
        long curChunk = ChunkPosPacker.pack(chunkX, chunkZ);
        if (tr.prevChunk == null || curChunk != tr.prevChunk || !dimKey.equals(tr.prevDim)) {
            // 踏入新区块（含传送落点）：只记已探索集合，不扣费
            data.markExplored(dimKey, curChunk);
        }
        tr.prevX = x;
        tr.prevY = y;
        tr.prevZ = z;
        tr.prevChunk = curChunk;
        tr.prevDim = dimKey;
    }

    /**
     * 登录兜底检查（共享模式闸门）：该玩家当前是否已有任一额度线满（自动懒加载数据）。
     * 壳层据此拒绝登录并自行渲染 ban 文案（文案渲染在壳层，坑 #22）。
     * 独立模式闸门用 {@link #isDimEnterable}/{@link #anyDimEnterable}（issue #3）。
     */
    public boolean isAllLinesExceeded(UUID uuid) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        return isExceeded(data, null, clock.getAsLong(), effectiveConfig(uuid).lines());
    }

    /** 该玩家在该维度是否可进（issue #3）：维度计费关闭、或该维度有效额度线未满 */
    public boolean isDimEnterable(UUID uuid, String dimKey) {
        if (!dimStore.isBillingEnabled(dimKey)) {
            return true;
        }
        boolean[] bucket = new boolean[1];
        List<QuotaConfig.Line> lines = effectiveLinesFor(uuid, dimKey, bucket);
        if (lines.isEmpty()) {
            return true;
        }
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        return !isExceeded(data, bucket[0] ? dimKey : null, clock.getAsLong(), lines);
    }

    /** 任一 live 维度可进（独立模式登录闸门放行 / scanBans 解封判定） */
    public boolean anyDimEnterable(UUID uuid, List<String> liveDims) {
        for (String dim : liveDims) {
            if (isDimEnterable(uuid, dim)) {
                return true;
            }
        }
        return false;
    }

    /** 该玩家在该维度的恢复时间（该维度各满线周期终点的最晚者；未满/零线返回 -1） */
    private long dimRecoveryMillis(UUID uuid, String dimKey) {
        boolean[] bucket = new boolean[1];
        List<QuotaConfig.Line> lines = effectiveLinesFor(uuid, dimKey, bucket);
        if (lines.isEmpty()) {
            return -1;
        }
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        return recoveryMillis(data, bucket[0] ? dimKey : null, clock.getAsLong(), lines);
    }

    /**
     * 最早可进时刻（issue #3 拍板口径）：各不可进维度的恢复时间的最小者——
     * 任一维度到点可进即应解封（独立模式全维度耗尽封禁时，ban 公告的恢复时间）。
     * liveDims 为空（无维度世界等退化输入）返回当前时刻，避免 ban(-1) 的无效公告。
     *
     * <p>"可进"按重定向落点口径（{@link #isDimRedirectable}：可进且已有落地坐标）——
     * 无坐标的维度可进也无法承接重定向，若按它承诺"现在恢复"会生成创建即过期的 ban，
     * 玩家重连后下 1 tick 再次被判满封禁（重连-被踢循环，公告恢复时间还显示"现在"）。
     * 全服没有任何落地坐标时退化为全维度最早恢复（保底口径，此时重定向本就不可能）。
     */
    public long earliestRecoveryAcrossDims(UUID uuid, List<String> liveDims) {
        long minRedirectable = -1;
        long minAny = -1;
        for (String dim : liveDims) {
            boolean hasSpawn = dimStore.spawn(dim) != null;
            if (hasSpawn && isDimEnterable(uuid, dim)) {
                return clock.getAsLong();
            }
            long r = dimRecoveryMillis(uuid, dim);
            if (r > 0) {
                if (hasSpawn && (minRedirectable < 0 || r < minRedirectable)) {
                    minRedirectable = r;
                }
                if (minAny < 0 || r < minAny) {
                    minAny = r;
                }
            }
        }
        long t = minRedirectable >= 0 ? minRedirectable : minAny;
        return t < 0 ? clock.getAsLong() : t;
    }

    /**
     * 该维度当前能否作为重定向落点（{@link DimensionStore#resolveRedirectTarget} 的候选口径）：
     * 可进且已有合法落地坐标。重定向开启时的封禁恢复/解封判定必须按此口径——只"可进"但无
     * 坐标的维度无法承接传送，按它解封会让玩家重连后下 1 tick 再次被判满封禁。
     */
    private boolean isDimRedirectable(UUID uuid, String dimKey) {
        return dimStore.spawn(dimKey) != null && isDimEnterable(uuid, dimKey);
    }

    /** scanBans/登录闸门共用：玩家是否应继续保持封禁（共享模式同现状；独立模式按拍板口径） */
    public boolean shouldStayBanned(UUID uuid, List<String> liveDims) {
        if (dimStore.isIndependent()) {
            if (dimStore.redirectOnExhaust()) {
                // 重定向开启时封禁只在"无可重定向维度"下发生，解封判定同口径：任一维度恢复到
                // "可作为重定向落点"（可进且有坐标）才解封——只按"可进"解封会把玩家放进
                // "解封→重连→下 1 tick 再被封"的循环（与 earliestRecoveryAcrossDims 同源）
                for (String dim : liveDims) {
                    if (isDimRedirectable(uuid, dim)) {
                        return false;
                    }
                }
                return true;
            }
            // 重定向关闭：按玩家最后所在维度判定（该维度恢复才解封，其余维度可进也不放行）
            PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
            String last = data.lastDim();
            if (last == null) {
                return !anyDimEnterable(uuid, liveDims);
            }
            return !isDimEnterable(uuid, last);
        }
        return isAllLinesExceeded(uuid);
    }

    /**
     * /chunkplan check 状态（dimKey 传 null = 共享模式语义，读全局桶）。
     * 独立模式壳层传玩家当前维度，仅展示该维度用量（issue #3）。
     */
    public QuotaStatus quotaStatus(UUID uuid, String dimKey) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        long now = clock.getAsLong();
        boolean independent = dimStore.isIndependent() && dimKey != null;
        List<QuotaConfig.Line> lines = independent
                ? effectiveDimLines(uuid, dimKey)
                : effectiveConfig(uuid).lines();
        String bucketDim = independent ? dimKey : null;
        List<LineStatus> linesOut = new ArrayList<>();
        boolean any = false;
        WindowAlert worst = null;
        for (QuotaConfig.Line line : lines) {
            double spent = effectiveSpentOf(data, bucketDim, line, now);
            // 各线独立的下次重置时间：该线当前周期终点（固定周期到点整窗清零，坑 #40）。
            // 与满线恢复时间同一公式；未满线也展示，便于玩家看到"该线何时清零"（坑 #26）
            long nextReset = -1;
            long start = cycleStartOf(data, bucketDim, line);
            if (spent > 0 && start >= 0) {
                nextReset = start + line.windowSeconds() * 1000L;
            }
            linesOut.add(new LineStatus(line.windowSeconds(), line.limit(), spent, nextReset));
            if (spent > line.limit()) {
                any = true;
            }
            // 跨窗口取当前百分比最高档位（现算跟随当前状态：额度滑出/重置自动回落，与 alertStates 触发历史解耦）
            int level = currentLevel(data, bucketDim, line, now);
            if (level > 0 && (worst == null || level > worst.percent())) {
                worst = new WindowAlert(line.windowSeconds(), level, severityOf(level));
            }
        }
        long recovery = any ? recoveryMillis(data, bucketDim, now, lines) : -1;
        return new QuotaStatus(linesOut, recovery, any, worst, getPlayerPresetName(uuid));
    }

    /** /chunkplan check 状态（共享模式；独立模式请用 {@link #quotaStatus(UUID, String)} 传维度） */
    public QuotaStatus quotaStatus(UUID uuid) {
        return quotaStatus(uuid, null);
    }

    /** /chunkplan reset（全档位）：只清消费桶，已探索集合终身保留 */
    public void resetSpend(UUID uuid) {
        resetSpend(uuid, null);
    }

    /**
     * /chunkplan reset [窗口]：清消费桶（tiers null/空 = 全部档位；否则只清指定档位，坑 #30），
     * 全局桶与所有维度桶一并清（issue #3：reset 清全部维度），已探索集合终身保留。
     */
    public void resetSpend(UUID uuid, Set<Integer> tiers) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        if (tiers == null || tiers.isEmpty()) {
            data.clearSpend();
        } else {
            for (int tier : tiers) {
                data.clearTierSpendEverywhere(tier);
            }
        }
        savePlayer(uuid);
    }

    /**
     * 清空某档位所有玩家的消费桶（/chunkplan config window tierN off 时调用，坑 #30）：
     * 全局桶与各维度桶一并清；在线玩家清内存并**立即落盘**（坑 #31：scanBans 懒加载滞留的
     * 离线玩家也在内存中，若只清内存会等 5 分钟周期保存才写盘，期间崩溃则清除丢失——QA 实测 P1）；
     * 离线玩家逐个读文件改写落盘——保证重新开启该窗口时从 0 起。
     */
    public void clearTierSpendForAll(int tier) {
        for (PlayerQuotaData data : dataByPlayer.values()) {
            data.clearTierSpendEverywhere(tier);
        }
        persistAllAndRewriteOfflineFiles(data -> data.clearTierSpendEverywhere(tier));
    }

    /**
     * 清空某维度（全部档位或指定档位）所有玩家的维度消费桶（issue #3：独立模式
     * config dimension &lt;dim&gt; window off 时调用），落盘语义同 {@link #clearTierSpendForAll(int)}。
     *
     * @param tiers null/空 = 该维度全部档位；否则只清指定档位
     */
    public void clearDimSpendForAll(String dimKey, Set<Integer> tiers) {
        for (PlayerQuotaData data : dataByPlayer.values()) {
            if (tiers == null || tiers.isEmpty()) {
                data.clearDimSpend(dimKey);
            } else {
                for (int tier : tiers) {
                    data.clearDimTierSpend(dimKey, tier);
                }
            }
        }
        persistAllAndRewriteOfflineFiles(data -> {
            if (tiers == null || tiers.isEmpty()) {
                data.clearDimSpend(dimKey);
            } else {
                for (int tier : tiers) {
                    data.clearDimTierSpend(dimKey, tier);
                }
            }
        });
    }

    /** 清档落盘统一实现：在线内存立即 savePlayer；离线玩家逐个读文件改写（仅 v4 文件，坑 #31/#40 门禁同款） */
    private void persistAllAndRewriteOfflineFiles(java.util.function.Consumer<PlayerQuotaData> clear) {
        // 立即落盘：savePlayer 仅 dirty（确实清掉了桶）才写，无桶玩家零开销
        for (UUID uuid : dataByPlayer.keySet()) {
            savePlayer(uuid);
        }
        if (!Files.exists(playerDataDir)) {
            return; // 新世界尚无玩家目录（非错误状态，坑 #31）
        }
        try (Stream<Path> files = Files.list(playerDataDir)) {
            Iterator<Path> it = files.iterator();
            while (it.hasNext()) {
                Path file = it.next();
                String name = file.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(name.substring(0, name.length() - 5));
                } catch (IllegalArgumentException e) {
                    continue; // 非玩家数据文件
                }
                if (dataByPlayer.containsKey(uuid)) {
                    continue; // 在线玩家已在上方处理（含立即落盘）
                }
                PlayerQuotaData.Dto dto = AtomicFile.readJson(file, PlayerQuotaData.Dto.class,
                        "玩家 " + uuid + " 配额数据", LOG);
                if (dto == null) {
                    continue; // 主与 .bak 均损坏：保留原样
                }
                if (dto.version != PlayerQuotaData.VERSION) {
                    continue; // 未知/旧版本：保留原样（旧版在玩家下次加载时迁移，坑 #40）
                }
                PlayerQuotaData data = PlayerQuotaData.fromDto(dto);
                clear.accept(data);
                if (data.isDirty()) {
                    try {
                        AtomicFile.write(file, GsonHolder.GSON.toJson(data.toDto()));
                    } catch (IOException e) {
                        LOG.error("改写玩家 {} 配额数据失败", uuid, e);
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("批量清空玩家记录失败", e);
        }
    }

    /** 玩家离线/被踢：落盘并释放内存 */
    public void onPlayerDisconnect(UUID uuid) {
        unloadPlayerData(uuid);
    }

    /**
     * 回收玩家内存数据（落盘后释放）。scanBans/离线 check/离线 reset 等路径会懒加载离线玩家
     * 数据，而这些玩家不会再触发登出事件，若不显式回收将常驻内存直至关服。
     * 调用方必须确保玩家当前不在线（在线玩家数据由 tick 持续读写，回收会导致反复加载）。
     */
    public void unloadPlayerData(UUID uuid) {
        savePlayer(uuid);
        tracking.remove(uuid);
        dataByPlayer.remove(uuid);
        removeAlertStates(uuid);
    }

    /** 定时/关服保存（固定周期状态每档 O(1)，无需清理过期数据） */
    public void saveAll() {
        for (UUID uuid : dataByPlayer.keySet()) {
            savePlayer(uuid);
        }
    }

    public void savePlayer(UUID uuid) {
        PlayerQuotaData data = dataByPlayer.get(uuid);
        if (data == null || !data.isDirty()) {
            return;
        }
        try {
            Files.createDirectories(playerDataDir);
            AtomicFile.write(playerDataDir.resolve(uuid + ".json"),
                    GsonHolder.GSON.toJson(data.toDto()));
            data.clearDirty();
        } catch (IOException e) {
            LOG.error("保存玩家 {} 配额数据失败", uuid, e);
        }
    }

    // ---------- 内部 ----------

    /**
     * 额度百分比阈值提示（坑 #28）：每窗口独立计算，达到档位表（15/30/50/65/75/80/85/90/95/98）即触发。
     * 首见（登录/重连/服务器重启后首个 tick）只初始化当前档位不触发，避免补发历史档位刷屏；
     * 档位上升跨过新档时逐档生成提示；额度重置/滑出后档位回落，重新涨回时再次触发。
     */
    private List<WindowAlert> checkAlerts(AlertKey key, PlayerQuotaData data, long now, List<QuotaConfig.Line> lines) {
        AlertState state = alertStates.computeIfAbsent(key, k -> new AlertState());
        if (!state.initialized) {
            state.lastLevels = new int[lines.size()];
            for (int i = 0; i < lines.size(); i++) {
                state.lastLevels[i] = currentLevel(data, key.dim(), lines.get(i), now);
            }
            state.initialized = true;
            return List.of();
        }
        List<WindowAlert> alerts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            QuotaConfig.Line line = lines.get(i);
            int level = currentLevel(data, key.dim(), line, now);
            if (level > state.lastLevels[i]) {
                // 跨档：从旧档下一档到新档逐档触发（严格按档位表每条都提示）
                for (int t : ALERT_PERCENTS) {
                    if (t > state.lastLevels[i] && t <= level) {
                        alerts.add(new WindowAlert(line.windowSeconds(), t, severityOf(t)));
                    }
                }
            }
            // 回落（额度重置/滑出）仅同步档位，重新涨回时再次触发
            state.lastLevels[i] = level;
        }
        return alerts;
    }

    /** 当前最高已过档位（低于 15% 为 -1）；bucketDim null = 共享模式全局桶 */
    private int currentLevel(PlayerQuotaData data, String bucketDim, QuotaConfig.Line line, long now) {
        double pct = effectiveSpentOf(data, bucketDim, line, now) / line.limit() * 100;
        int level = -1;
        for (int t : ALERT_PERCENTS) {
            if (pct >= t) {
                level = t;
            }
        }
        return level;
    }

    /** 严重度映射：15~30 低、50~75 中、80~98 高（用户规定） */
    private static Severity severityOf(int percent) {
        if (percent <= 30) {
            return Severity.LOW;
        }
        if (percent <= 75) {
            return Severity.MEDIUM;
        }
        return Severity.HIGH;
    }

    /** 某线当前有效消费（bucketDim null = 全局桶；独立模式 = 该维度桶） */
    private static double effectiveSpentOf(PlayerQuotaData data, String bucketDim, QuotaConfig.Line line, long now) {
        if (bucketDim == null) {
            return data.effectiveSpent(line.tier(), now, line.windowSeconds());
        }
        return data.effectiveDimSpent(bucketDim, line.tier(), now, line.windowSeconds());
    }

    /** 某线当前周期起点（bucketDim null = 全局桶） */
    private static long cycleStartOf(PlayerQuotaData data, String bucketDim, QuotaConfig.Line line) {
        if (bucketDim == null) {
            return data.cycleStartMillis(line.tier());
        }
        return data.dimCycleStartMillis(bucketDim, line.tier());
    }

    /** 是否有额度线满（坑 #25：任一满即拒）；bucketDim null = 全局桶，独立模式 = 该维度桶 */
    private boolean isExceeded(PlayerQuotaData data, String bucketDim, long nowMillis, List<QuotaConfig.Line> lines) {
        for (QuotaConfig.Line line : lines) {
            if (effectiveSpentOf(data, bucketDim, line, nowMillis) > line.limit()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 恢复时间 = 各满线"周期起点 + 窗口长"的最晚者（固定周期语义，坑 #40）。
     * 到该时刻所有满线的周期同时到点、整窗清零（等价 reset），承诺精确兑现——
     * 不再有旧滚动窗口"最早桶滑出但额度仍超限"的到点二次封禁问题。
     * bucketDim null = 全局桶（共享模式），独立模式 = 该维度桶。
     */
    private long recoveryMillis(PlayerQuotaData data, String bucketDim, long nowMillis, List<QuotaConfig.Line> lines) {
        long worst = -1;
        for (QuotaConfig.Line line : lines) {
            if (effectiveSpentOf(data, bucketDim, line, clock.getAsLong()) <= line.limit()) {
                continue;
            }
            long start = cycleStartOf(data, bucketDim, line);
            if (start < 0) {
                continue;
            }
            worst = Math.max(worst, start + line.windowSeconds() * 1000L);
        }
        return worst;
    }

    /** 累计总点数：按最长窗口档位求和（仅用于日志展示；独立模式按维度跨维求和） */
    private double totalSpent(PlayerQuotaData data, String dimKey, boolean independent, long nowMillis,
                              List<QuotaConfig.Line> lines) {
        QuotaConfig.Line maxLine = null;
        for (QuotaConfig.Line line : lines) {
            if (maxLine == null || line.windowSeconds() > maxLine.windowSeconds()) {
                maxLine = line;
            }
        }
        if (maxLine == null) {
            return 0;
        }
        if (!independent) {
            return data.effectiveSpent(maxLine.tier(), nowMillis, maxLine.windowSeconds());
        }
        // 独立模式：该档位在所有已记录维度桶的和（跨维度总消耗，日志口径）
        double total = 0;
        for (String dim : data.dimKeys()) {
            total += data.effectiveDimSpent(dim, maxLine.tier(), nowMillis, maxLine.windowSeconds());
        }
        return total;
    }

    private PlayerQuotaData loadOrCreate(UUID uuid) {
        Path file = playerDataDir.resolve(uuid + ".json");
        if (!Files.exists(file)) {
            return new PlayerQuotaData();
        }
        // 坑 #27：损坏时从 .bak 兜底恢复（额度/探索集合不清零）；
        // 版本不符不尝试 .bak（.bak 同版本也会不符，恢复无意义）
        PlayerQuotaData.Dto dto = AtomicFile.readJson(file, PlayerQuotaData.Dto.class, "玩家 " + uuid + " 配额数据", LOG);
        if (dto != null) {
            if (dto.version == PlayerQuotaData.VERSION) {
                return PlayerQuotaData.fromDto(dto);
            }
            if (dto.version >= 1 && dto.version < PlayerQuotaData.VERSION) {
                // v1/v2 -> 迁移（坑 #40）：滚动窗口分钟桶无法映射为固定周期，保留 explored、丢弃消费记录；
                // v3 -> v4（issue #3）：无损升级——全局 tiers 保留（切回共享模式仍用），dimTiers/lastDim 从空起
                LOG.warn("玩家 {} 配额数据为旧版 v{}，已按 v4 语义迁移（探索集合与共享模式消费保留）", uuid, dto.version);
                return PlayerQuotaData.fromDto(dto);
            }
            LOG.warn("玩家 {} 配额数据版本不兼容（{}），将重建", uuid, dto.version);
        } else {
            LOG.warn("玩家 {} 无可用配额数据，将重建", uuid);
        }
        return new PlayerQuotaData();
    }
}
