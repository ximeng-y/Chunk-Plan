package dev.chunkplan.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
        NONE, KICK, BAN
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
     * （跨档逐条；BAN 当 tick 不发提示，ban 消息已充分说明）。
     */
    public record TickResult(ResultType type, long banUntilMillis, List<WindowAlert> alerts) {
        public static TickResult none() {
            return new TickResult(ResultType.NONE, -1, List.of());
        }

        public static TickResult none(List<WindowAlert> alerts) {
            return new TickResult(ResultType.NONE, -1, alerts);
        }

        public static TickResult ban(long untilMillis) {
            return new TickResult(ResultType.BAN, untilMillis, List.of());
        }
    }

    /** 单线状态：窗口/上限/已消费/下次重置时间（该线当前周期终点，到点整窗清零；无消费为 -1） */
    public record LineStatus(long windowSeconds, double limit, double spent, long nextResetMillis) {
    }

    public record QuotaStatus(List<LineStatus> lines, long recoveryMillis, boolean allExceeded, WindowAlert worstAlert) {
    }

    /** 每玩家追踪状态（首 tick / 上一 tick 位置与区块） */
    private static final class Tracking {
        double prevX;
        double prevY;
        double prevZ;
        Long prevChunk;
        String prevDim;
    }

    /** 每玩家额度提示状态（坑 #28，瞬态不落盘）：每窗口已触发的最高档位 */
    private static final class AlertState {
        boolean initialized;
        int[] lastLevels; // 与 config.lines() 下标对齐
    }

    /** 触发档位表（严格按用户要求）：达到即触发；15~30 低、50~75 中、80~98 高 */
    private static final int[] ALERT_PERCENTS = {15, 30, 50, 65, 75, 80, 85, 90, 95, 98};

    private final Path dataDir;
    private final Path playerDataDir;
    private volatile FeeLogger feeLogger;
    private final ManagedBanStore banStore;
    private final LongSupplier clock;

    private volatile QuotaConfig config;
    private final ConcurrentMap<UUID, PlayerQuotaData> dataByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Tracking> tracking = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AlertState> alertStates = new ConcurrentHashMap<>();

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
        this.clock = clock;
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

    /** 豁免判定：默认 OP + 配置名单豁免；exemptByDefault=false 时全员受限 */
    public boolean isExempt(UUID uuid, boolean isOp) {
        QuotaConfig cfg = config;
        return (cfg.exemptByDefault() && isOp) || cfg.exemptPlayers().contains(uuid);
    }

    /** 每玩家每 tick 调用（须在服务端主线程串行） */
    public TickResult onPlayerTick(UUID uuid, boolean exempt, String dimKey, double x, double y, double z) {
        if (exempt) {
            // 豁免玩家不参与记账；同时清除位移基准，使移除豁免后首个 tick 按首 tick 处理（只记基准不扣费）。
            // 若不清除，豁免期间累积位移会在解除豁免后的首个 tick 被当作区块变化计费（QA 实测 P1）
            tracking.remove(uuid);
            // 豁免玩家不提示；清状态避免解除豁免后旧档位残留导致不再提示
            alertStates.remove(uuid);
            return TickResult.none();
        }
        if (config.lines().isEmpty()) {
            // 零线（坑 #31）：全部窗口已关闭——不加载玩家数据、不记账、不判踢、不提示；
            // 清除位移基准：零线期间 tracking 不更新，若不清理，零线前已追踪的玩家在
            // 重开窗口后首个 tick 会把零线期间整段位移当区块变化计费（且必然触发高速
            // 倍率）——与豁免分支（上方）同款清理；清理后重开首 tick 走首 tick 分支
            // （只记基准不扣费），从 0 起
            tracking.remove(uuid);
            return TickResult.none();
        }
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
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
            return TickResult.none(checkAlerts(uuid, data, now));
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

        if (!enteredNewChunk) {
            // 同一区块内（挂机/踱步/移动）：不重复计费
            // 坑 #30：每 tick 仍判满——配置变更（降额度/改窗口/启用新线）导致超限时
            // 下一 tick 即踢出，原地不动也生效（保证"降低额度不会瘫痪系统、当场生效"）
            if (isAllLinesExceeded(data, now)) {
                return TickResult.ban(recoveryMillis(data));
            }
            return TickResult.none(checkAlerts(uuid, data, now));
        }

        // 基础费判定：先查集合，不在则先加入集合（"踏入的要么是来过的，要么是没来过的"）
        boolean familiar = data.isExplored(dimKey, curChunk);
        double base = familiar ? config.familiarEntryFee() : config.firstEntryFee();
        if (!familiar) {
            data.markExplored(dimKey, curChunk);
        }

        QuotaConfig cfg = config;
        double fee = base * (speed > cfg.highSpeedThreshold() ? cfg.highSpeedMultiplier() : 1.0);
        // 每次消费计入所有启用档位（坑 #30 各窗口独立记账）；先惰性过期再记账：
        // 已到周期的档整窗清零并重新锚定（固定周期语义，坑 #40）
        for (QuotaConfig.Line line : cfg.lines()) {
            data.expireIfNeeded(line.tier(), now, line.windowSeconds());
            data.recordSpend(line.tier(), now, fee);
        }

        if (cfg.logFeeEvents() && feeLogger != null) {
            feeLogger.logFee(uuid, dimKey, curChunk, speed, fee, totalSpent(data, now));
        }

        // 先记账后判踢：任一额度线满 -> BAN（坑 #25：原"全部满才拒"，用户确认为单线满即拒）
        if (isAllLinesExceeded(data, now)) {
            long until = recoveryMillis(data);
            return TickResult.ban(until);
        }
        return TickResult.none(checkAlerts(uuid, data, now));
    }

    /**
     * 登录兜底检查：该玩家当前是否已有任一额度线满（自动懒加载数据）。
     * 壳层据此拒绝登录并自行渲染 ban 文案（文案渲染在壳层，坑 #22）。
     */
    public boolean isAllLinesExceeded(UUID uuid) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        return isAllLinesExceeded(data, clock.getAsLong());
    }

    /** /chunkplan check 状态：各线已消费/上限、任一满标志、恢复时间（未满为 -1）、
     *  当前跨窗口最严重档位（坑 #29：check 档位词显示用，无档为 null） */
    public QuotaStatus quotaStatus(UUID uuid) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        long now = clock.getAsLong();
        List<LineStatus> lines = new ArrayList<>();
        boolean any = false;
        WindowAlert worst = null;
        for (QuotaConfig.Line line : config.lines()) {
            double spent = data.effectiveSpent(line.tier(), now, line.windowSeconds());
            // 各线独立的下次重置时间：该线当前周期终点（固定周期到点整窗清零，坑 #40）。
            // 与满线恢复时间同一公式；未满线也展示，便于玩家看到"该线何时清零"（坑 #26）
            long nextReset = -1;
            long start = data.cycleStartMillis(line.tier());
            if (spent > 0 && start >= 0) {
                nextReset = start + line.windowSeconds() * 1000L;
            }
            lines.add(new LineStatus(line.windowSeconds(), line.limit(), spent, nextReset));
            if (spent > line.limit()) {
                any = true;
            }
            // 跨窗口取当前百分比最高档位（现算跟随当前状态：额度滑出/重置自动回落，与 alertStates 触发历史解耦）
            int level = currentLevel(data, line, now);
            if (level > 0 && (worst == null || level > worst.percent())) {
                worst = new WindowAlert(line.windowSeconds(), level, severityOf(level));
            }
        }
        long recovery = any ? recoveryMillis(data) : -1;
        return new QuotaStatus(lines, recovery, any, worst);
    }

    /** /chunkplan reset（全档位）：只清消费桶，已探索集合终身保留 */
    public void resetSpend(UUID uuid) {
        resetSpend(uuid, null);
    }

    /**
     * /chunkplan reset [窗口]：清消费桶（tiers null/空 = 全部档位；否则只清指定档位，坑 #30），
     * 已探索集合终身保留。
     */
    public void resetSpend(UUID uuid, Set<Integer> tiers) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        if (tiers == null || tiers.isEmpty()) {
            data.clearSpend();
        } else {
            for (int tier : tiers) {
                data.clearTierSpend(tier);
            }
        }
        savePlayer(uuid);
    }

    /**
     * 清空某档位所有玩家的消费桶（/chunkplan config window tierN off 时调用，坑 #30）：
     * 在线玩家清内存并**立即落盘**（坑 #31：scanBans 懒加载滞留的离线玩家也在内存中，
     * 若只清内存会等 5 分钟周期保存才写盘，期间崩溃则清除丢失——QA 实测 P1）；
     * 离线玩家逐个读文件改写落盘——保证重新开启该窗口时从 0 起。
     */
    public void clearTierSpendForAll(int tier) {
        for (PlayerQuotaData data : dataByPlayer.values()) {
            data.clearTierSpend(tier);
        }
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
                data.clearTierSpend(tier);
                if (data.isDirty()) {
                    try {
                        AtomicFile.write(file, GsonHolder.GSON.toJson(data.toDto()));
                    } catch (IOException e) {
                        LOG.error("改写玩家 {} 配额数据失败", uuid, e);
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("清空档位 {} 的玩家记录失败", tier, e);
        }
    }

    /** 玩家离线/被踢：落盘并释放内存 */
    public void onPlayerDisconnect(UUID uuid) {
        savePlayer(uuid);
        tracking.remove(uuid);
        dataByPlayer.remove(uuid);
        alertStates.remove(uuid);
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
    private List<WindowAlert> checkAlerts(UUID uuid, PlayerQuotaData data, long now) {
        List<QuotaConfig.Line> lines = config.lines();
        AlertState state = alertStates.computeIfAbsent(uuid, k -> new AlertState());
        if (!state.initialized) {
            state.lastLevels = new int[lines.size()];
            for (int i = 0; i < lines.size(); i++) {
                state.lastLevels[i] = currentLevel(data, lines.get(i), now);
            }
            state.initialized = true;
            return List.of();
        }
        List<WindowAlert> alerts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            QuotaConfig.Line line = lines.get(i);
            int level = currentLevel(data, line, now);
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

    /** 当前最高已过档位（低于 15% 为 -1） */
    private static int currentLevel(PlayerQuotaData data, QuotaConfig.Line line, long now) {
        double pct = data.effectiveSpent(line.tier(), now, line.windowSeconds()) / line.limit() * 100;
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

    private boolean isAllLinesExceeded(PlayerQuotaData data, long nowMillis) {
        // 坑 #25：任一额度线满即视为超限（原"全部满才拒"改为单线满即拒）
        for (QuotaConfig.Line line : config.lines()) {
            if (data.effectiveSpent(line.tier(), nowMillis, line.windowSeconds()) > line.limit()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 恢复时间 = 各满线"周期起点 + 窗口长"的最晚者（固定周期语义，坑 #40）。
     * 到该时刻所有满线的周期同时到点、整窗清零（等价 reset），承诺精确兑现——
     * 不再有旧滚动窗口"最早桶滑出但额度仍超限"的到点二次封禁问题。
     */
    private long recoveryMillis(PlayerQuotaData data) {
        long worst = -1;
        for (QuotaConfig.Line line : config.lines()) {
            if (data.effectiveSpent(line.tier(), clock.getAsLong(), line.windowSeconds()) <= line.limit()) {
                continue;
            }
            long start = data.cycleStartMillis(line.tier());
            if (start < 0) {
                continue;
            }
            worst = Math.max(worst, start + line.windowSeconds() * 1000L);
        }
        return worst;
    }

    /** 累计总点数：按最长窗口档位求和（仅用于日志展示；各档位周期内容相同，取最大窗口档位即可） */
    private double totalSpent(PlayerQuotaData data, long nowMillis) {
        QuotaConfig.Line maxLine = null;
        for (QuotaConfig.Line line : config.lines()) {
            if (maxLine == null || line.windowSeconds() > maxLine.windowSeconds()) {
                maxLine = line;
            }
        }
        return maxLine == null ? 0 : data.effectiveSpent(maxLine.tier(), nowMillis, maxLine.windowSeconds());
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
            if (dto.version == 1 || dto.version == 2) {
                // v1/v2 -> v3 迁移（坑 #40）：滚动窗口分钟桶无法映射为固定周期，
                // 保留 explored、丢弃消费记录（该玩家消费从 0 重新累计）
                LOG.warn("玩家 {} 配额数据为旧版 v{}，已保留探索集合，消费从 0 重新累计", uuid, dto.version);
                return PlayerQuotaData.fromDto(dto);
            }
            LOG.warn("玩家 {} 配额数据版本不兼容（{}），将重建", uuid, dto.version);
        } else {
            LOG.warn("玩家 {} 无可用配额数据，将重建", uuid);
        }
        return new PlayerQuotaData();
    }
}
