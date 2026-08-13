package dev.chunkplan.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *       <li>先记账后判踢：所有额度线均满 -> 返回 BAN（消息含各满线状态与恢复时间）</li>
 *     </ul>
 *   </li>
 *   <li>更新 prevPos/prevChunk/prevDim</li>
 * </ol>
 */
public final class QuotaEngine {

    private static final Logger LOG = LoggerFactory.getLogger(QuotaEngine.class);
    private static final DateTimeFormatter RECOVER_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public enum ResultType {
        NONE, KICK, BAN
    }

    public record TickResult(ResultType type, String message, long banUntilMillis) {
        public static TickResult none() {
            return new TickResult(ResultType.NONE, null, -1);
        }

        public static TickResult ban(String message, long untilMillis) {
            return new TickResult(ResultType.BAN, message, untilMillis);
        }
    }

    public record LineStatus(long windowSeconds, double limit, double spent) {
    }

    public record QuotaStatus(List<LineStatus> lines, long recoveryMillis, boolean allExceeded) {
    }

    /** 每玩家追踪状态（首 tick / 上一 tick 位置与区块） */
    private static final class Tracking {
        double prevX;
        double prevY;
        double prevZ;
        Long prevChunk;
        String prevDim;
    }

    private final Path dataDir;
    private final Path playerDataDir;
    private volatile FeeLogger feeLogger;
    private final ManagedBanStore banStore;
    private final LongSupplier clock;

    private volatile QuotaConfig config;
    private final ConcurrentMap<UUID, PlayerQuotaData> dataByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Tracking> tracking = new ConcurrentHashMap<>();

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

    /** 配置热更新（/quota reload 时调用，先由壳层校验并构建 QuotaConfig） */
    public void setConfig(QuotaConfig config) {
        this.config = config;
    }

    /** 运行期更换扣费日志实现（/quota reload 时 logFeeEvents 开关热切换；false 传 null） */
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
            return TickResult.none();
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
            return TickResult.none();
        }

        // 基础费判定：先查集合，不在则先加入集合（"踏入的要么是来过的，要么是没来过的"）
        Set<Long> explored = data.explored(dimKey);
        boolean familiar = explored.contains(curChunk);
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

        // 先记账后判踢：所有额度线均满 -> BAN
        if (isAllLinesExceeded(data, now)) {
            long until = recoveryMillis(data, now);
            return TickResult.ban(buildBanMessage(data, now, until), until);
        }
        return TickResult.none();
    }

    /** 登录兜底检查：全满 -> 返回含恢复时间的 ban 消息；否则 null */
    public String loginBlockMessage(UUID uuid) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        long now = clock.getAsLong();
        if (!isAllLinesExceeded(data, now)) {
            return null;
        }
        long until = recoveryMillis(data, now);
        return buildBanMessage(data, now, until);
    }

    /** 解 ban 扫描 / 状态判断用：该玩家当前是否所有额度线均满（自动懒加载数据） */
    public boolean isAllLinesExceeded(UUID uuid) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        return isAllLinesExceeded(data, clock.getAsLong());
    }

    /** /quota check 状态：各线已消费/上限、全满标志、恢复时间（未满为 -1） */
    public QuotaStatus quotaStatus(UUID uuid) {
        PlayerQuotaData data = dataByPlayer.computeIfAbsent(uuid, this::loadOrCreate);
        long now = clock.getAsLong();
        List<LineStatus> lines = new ArrayList<>();
        boolean all = true;
        for (QuotaConfig.Line line : config.lines()) {
            double spent = data.spendInWindow(now, line.windowSeconds());
            lines.add(new LineStatus(line.windowSeconds(), line.limit(), spent));
            if (spent <= line.limit()) {
                all = false;
            }
        }
        long recovery = all ? recoveryMillis(data, now) : -1;
        return new QuotaStatus(lines, recovery, all);
    }

    /** /quota reset：只清消费桶，已探索集合终身保留 */
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

    private boolean isAllLinesExceeded(PlayerQuotaData data, long nowMillis) {
        for (QuotaConfig.Line line : config.lines()) {
            if (data.spendInWindow(nowMillis, line.windowSeconds()) <= line.limit()) {
                return false;
            }
        }
        return !config.lines().isEmpty();
    }

    /**
     * 恢复时间 = 对每条满的线取"min(有消费的分钟) + 窗口长"的最晚者，
     * 即"何时不再全满"（最早消费桶滑出窗口的时刻）。
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

    private String buildBanMessage(PlayerQuotaData data, long nowMillis, long untilMillis) {
        StringBuilder sb = new StringBuilder("探索额度已耗尽：");
        List<QuotaConfig.Line> lines = config.lines();
        for (int i = 0; i < lines.size(); i++) {
            QuotaConfig.Line line = lines.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(String.format("%s 窗口 %.1f/%.1f", formatWindow(line.windowSeconds()),
                    data.spendInWindow(nowMillis, line.windowSeconds()), line.limit()));
        }
        String recover = RECOVER_FMT.format(Instant.ofEpochMilli(untilMillis).atZone(ZoneId.systemDefault()));
        sb.append("。预计 ").append(recover).append(" 恢复");
        return sb.toString();
    }

    private static String formatWindow(long windowSeconds) {
        long m = windowSeconds / 60;
        if (m >= 1440) {
            return (m / 1440) + "d";
        }
        if (m >= 60) {
            return (m / 60) + "h";
        }
        return m + "m";
    }

    private PlayerQuotaData loadOrCreate(UUID uuid) {
        Path file = playerDataDir.resolve(uuid + ".json");
        if (Files.exists(file)) {
            try {
                PlayerQuotaData.Dto dto = GsonHolder.GSON.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), PlayerQuotaData.Dto.class);
                if (dto != null && dto.version == PlayerQuotaData.VERSION) {
                    return PlayerQuotaData.fromDto(dto);
                }
                LOG.warn("玩家 {} 配额数据版本不兼容（{}），将重建", uuid, dto == null ? "空文件" : dto.version);
            } catch (Exception e) {
                LOG.error("读取玩家 {} 配额数据失败，将重建", uuid, e);
            }
        }
        return new PlayerQuotaData();
    }
}
