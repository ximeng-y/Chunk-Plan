package dev.chunkplan.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

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
 *       <li>累加进分钟桶，写独立扣费日志</li>
 *       <li>先记账后判踢：所有额度线均满 -> 返回 BAN（含恢复时间；文案由壳层按玩家语言渲染）</li>
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

    /** 单线状态：窗口/上限/已消费/下次重置时间（该线窗口内最早消费桶滑出的时刻，各线独立；无消费为 -1） */
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
        this.config = config;
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
        long minute = now / 60000;
        data.addSpend(minute, fee);

        if (cfg.logFeeEvents() && feeLogger != null) {
            feeLogger.logFee(uuid, dimKey, curChunk, speed, fee, totalSpent(data, now));
        }

        // 先记账后判踢：任一额度线满 -> BAN（坑 #25：原"全部满才拒"，用户确认为单线满即拒）
        if (isAllLinesExceeded(data, now)) {
            long until = recoveryMillis(data, now);
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
            double spent = data.spendInWindow(now, line.windowSeconds());
            // 各线独立的下次重置时间：该线窗口内最早消费桶 + 窗口长（无消费为 -1）。
            // 与满线恢复时间同一公式；未满线也展示，便于玩家看到"该线何时滑出"（坑 #26）
            long nextReset = -1;
            long firstKey = (now - line.windowSeconds() * 1000) / 60000 + 1;
            Long earliest = data.firstBucketAtOrAfter(firstKey);
            if (earliest != null) {
                nextReset = earliest * 60000L + line.windowSeconds() * 1000L;
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
        long recovery = any ? recoveryMillis(data, now) : -1;
        return new QuotaStatus(lines, recovery, any, worst);
    }

    /** /chunkplan reset：只清消费桶，已探索集合终身保留 */
    public void resetSpend(UUID uuid) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        data.clearSpend();
        savePlayer(uuid);
    }

    /** 玩家离线/被踢：落盘并释放内存 */
    public void onPlayerDisconnect(UUID uuid) {
        savePlayer(uuid);
        tracking.remove(uuid);
        dataByPlayer.remove(uuid);
        alertStates.remove(uuid);
    }

    /** 定时/关服保存（先清理过期桶再写盘） */
    public void saveAll() {
        cleanupExpiredBuckets();
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

    /** 清理早于"最长窗口线 2 倍"的过期桶 */
    public void cleanupExpiredBuckets() {
        long maxWindow = config.lines().stream().mapToLong(l -> l.windowSeconds()).max().orElse(0);
        long cutoffMinute = (clock.getAsLong() - maxWindow * 2000) / 60000;
        for (PlayerQuotaData data : dataByPlayer.values()) {
            data.cleanupBucketsBefore(cutoffMinute);
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
        double pct = data.spendInWindow(now, line.windowSeconds()) / line.limit() * 100;
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
            if (data.spendInWindow(nowMillis, line.windowSeconds()) > line.limit()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 恢复时间 = 对每条满的线取"min(有消费的分钟) + 窗口长"的最晚者，
     * 即"何时不再有任一满线"（最早消费桶滑出窗口的时刻）。
     */
    private long recoveryMillis(PlayerQuotaData data, long nowMillis) {
        long worst = -1;
        for (QuotaConfig.Line line : config.lines()) {
            if (data.spendInWindow(nowMillis, line.windowSeconds()) <= line.limit()) {
                continue;
            }
            long firstKey = (nowMillis - line.windowSeconds() * 1000) / 60000 + 1;
            Long earliest = data.firstBucketAtOrAfter(firstKey);
            if (earliest == null) {
                continue;
            }
            long rec = earliest * 60000L + line.windowSeconds() * 1000L;
            worst = Math.max(worst, rec);
        }
        return worst;
    }

    /** 累计总点数：按最长窗口线窗口求和（仅用于日志展示） */
    private double totalSpent(PlayerQuotaData data, long nowMillis) {
        long maxWindow = config.lines().stream().mapToLong(l -> l.windowSeconds()).max().orElse(0);
        return data.spendInWindow(nowMillis, maxWindow);
    }

    private PlayerQuotaData loadOrCreate(UUID uuid) {
        Path file = playerDataDir.resolve(uuid + ".json");
        if (!Files.exists(file)) {
            return new PlayerQuotaData();
        }
        // 坑 #27：损坏时从 .bak 兜底恢复（额度/探索集合不清零）；
        // 版本不符不尝试 .bak（.bak 同版本也会不符，恢复无意义，保持重建+告警）
        PlayerQuotaData.Dto dto = AtomicFile.readJson(file, PlayerQuotaData.Dto.class, "玩家 " + uuid + " 配额数据", LOG);
        if (dto != null) {
            if (dto.version == PlayerQuotaData.VERSION) {
                return PlayerQuotaData.fromDto(dto);
            }
            LOG.warn("玩家 {} 配额数据版本不兼容（{}），将重建", uuid, dto.version);
        } else {
            LOG.warn("玩家 {} 无可用配额数据，将重建", uuid);
        }
        return new PlayerQuotaData();
    }
}
