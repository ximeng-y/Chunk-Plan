package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 引擎级集成测试：覆盖计划 6.1 的核心场景。
 * 使用注入时钟模拟时间流逝，区块按 16 格对齐。
 */
class QuotaEngineTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private final UUID player = UUID.randomUUID();

    @TempDir
    Path tmp;

    private TestClock clock;
    private QuotaEngine engine;

    /** 可手动推进的假时钟 */
    static final class TestClock {
        long now = 1_000_000_000L;

        long get() {
            return now;
        }

        void advanceMillis(long millis) {
            now += millis;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        // 两条短窗口线：1min/2.0 + 2min/3.0（便于测试滑动恢复）
        // 阈值 1000：测试中直接大位移跨区块不受 2x 倍率干扰（高速测试单独 setConfig）
        QuotaConfig config = QuotaConfig.builder()
                .lines(List.of(
                        new QuotaConfig.Line(1, 60, 2.0),
                        new QuotaConfig.Line(2, 120, 3.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>());
        engine = new QuotaEngine(tmp, config, null, new ManagedBanStore(tmp.resolve("bans.json")), clock::get);
    }

    /** 以指定步长在 X 轴上走，返回每步的 tick 结果（不入新区块不扣费） */
    private List<QuotaEngine.TickResult> walkTo(double targetX, double stepPerTick) {
        List<QuotaEngine.TickResult> results = new ArrayList<>();
        double x = 0;
        // 首 tick 记基准
        engine.onPlayerTick(player, false, OVERWORLD, x, 64, 0);
        while (x < targetX) {
            x += stepPerTick;
            results.add(engine.onPlayerTick(player, false, OVERWORLD, x, 64, 0));
        }
        return results;
    }

    @Test
    void firstTickOnlyRecordsBaseline() {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 0.1, 64, 0);
        // 无任何消费
        assertEquals(0, engine.quotaStatus(player).lines().get(0).spent());
        // 且未产生数据文件（不 dirty）
        assertFalse(Files.exists(tmp.resolve("players/" + player + ".json")));
    }

    @Test
    void newChunkChargesFirstFeeThenFamiliarFee() {
        // 本测试只验证计费（首费 1.0 + 熟悉费 0.05），放宽线避免新语义下单线满触发 BAN（坑 #25）
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 10.0), new QuotaConfig.Line(2, 120, 20.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);   // 基准（区块 0）
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 踏入区块 1：集合外 -> 1.0 入集合
        assertEquals(1.0, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);

        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);   // 回区块 0：1.0 入集合
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 再踏入区块 1：集合内 -> 0.05
        var s2 = engine.quotaStatus(player);
        assertEquals(2.05, s2.lines().get(0).spent(), 1e-9);
        assertFalse(s2.allExceeded());
    }

    @Test
    void idleInSameChunkChargesNothing() {
        walkTo(0.5, 0.5);
        // 同区块内反复移动/挂机
        for (int i = 0; i < 100; i++) {
            engine.onPlayerTick(player, false, OVERWORLD, i % 10 * 1.0, 64, 0);
        }
        assertEquals(0.0, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    @Test
    void highSpeedDoublesFee() {
        // 单独配置阈值 1.0：单 tick 位移 4 格 -> 2x
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(
                        new QuotaConfig.Line(1, 60, 2.0),
                        new QuotaConfig.Line(2, 3600, 3.0)))
                .highSpeedThreshold(1.0)
                .build(new ArrayList<>()));
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 4, 64, 0); // 区块 0 内移动：同区块不扣费
        // 快速飞入区块 1：speed=16 -> 1.0 * 2 = 2.0
        engine.onPlayerTick(player, false, OVERWORLD, 20, 64, 0);
        assertEquals(2.0, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    @Test
    void dimensionsAreIndependent() {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 主世界区块 1：1.0
        // 换维度 = 踏入落点区块（传送只计落点）：地狱区块 0：1.0
        engine.onPlayerTick(player, false, NETHER, 0, 64, 0);
        engine.onPlayerTick(player, false, NETHER, 16, 64, 0);     // 地狱区块 1：1.0
        var status = engine.quotaStatus(player);
        assertEquals(3.0, status.lines().get(0).spent(), 1e-9);
        // 回到主世界已探索区块 1：0.05
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);
        var s2 = engine.quotaStatus(player);
        assertEquals(3.05, s2.lines().get(0).spent(), 1e-9);
    }

    @Test
    void allLinesFullTriggersBanWithRecoveryTime() {
        // 线：1min/2.0 + 2min/3.0
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 1.0
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // 2.0
        engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0);  // 3.0：1min 线满，2min 线未满
        var r = engine.onPlayerTick(player, false, OVERWORLD, 64, 64, 0); // 4.0：两条都满 -> BAN
        assertEquals(QuotaEngine.ResultType.BAN, r.type());
        // 恢复时间 = 各满线恢复时间的 max = 最早消费桶(M0) + 最长满线窗口(2min)
        long m0 = clock.now / 60000;
        assertEquals(m0 * 60000L + 120_000L, r.banUntilMillis());
    }

    @Test
    void singleLineFullTriggersBanAndLoginBlock() {
        // 坑 #25：任一窗口满即限制——1min 线满、2min 线（宽松 100.0）未满 -> 拦截
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 2.0), new QuotaConfig.Line(2, 120, 100.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);   // 基准（区块 0）
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 1.0
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // 2.0：等于上限不算满
        assertFalse(engine.isAllLinesExceeded(player));
        assertFalse(engine.quotaStatus(player).allExceeded());
        var r = engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0); // 3.0：1min 线满 -> BAN
        assertEquals(QuotaEngine.ResultType.BAN, r.type());
        // 恢复时间 = 满线（1min 线）最早桶 M0 + 60s；2min 线未满不参与
        long m0 = clock.now / 60000;
        assertEquals(m0 * 60000L + 60_000L, r.banUntilMillis());
        // 登录拦截语义：单线满同样拒绝登录
        assertTrue(engine.isAllLinesExceeded(player));
        assertTrue(engine.quotaStatus(player).allExceeded());
    }

    @Test
    void banClearsWhenWindowSlides() {
        allLinesFullTriggersBanWithRecoveryTime();
        // 推进 121 秒：两条窗口均滑出，不再全满
        clock.advanceMillis(121_000);
        assertFalse(engine.isAllLinesExceeded(player));
        var r = engine.onPlayerTick(player, false, OVERWORLD, 80, 64, 0);
        assertEquals(QuotaEngine.ResultType.NONE, r.type());
    }

    @Test
    void loginBlockWhenFull() {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);
        // 1min 线已 2.0（未超）；1h 线未满 -> 登录不拦
        assertFalse(engine.isAllLinesExceeded(player));
        // 收紧 1h 线（阈值保持禁用倍率）
        QuotaConfig cfg = QuotaConfig.builder()
                .lines(List.of(
                        new QuotaConfig.Line(1, 60, 2.0),
                        new QuotaConfig.Line(2, 3600, 4.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>());
        engine.setConfig(cfg);
        engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0); // 3.0
        engine.onPlayerTick(player, false, OVERWORLD, 64, 64, 0); // 4.0
        engine.onPlayerTick(player, false, OVERWORLD, 80, 64, 0); // 5.0：两条都满 -> 登录拦截
        assertTrue(engine.isAllLinesExceeded(player));
        var status = engine.quotaStatus(player);
        assertTrue(status.allExceeded());
        // 恢复时间 = 各满线恢复时间的 max = 最早消费桶(M0) + 最长满线窗口(1h)
        long m0 = clock.now / 60000;
        assertEquals(m0 * 60000L + 3_600_000L, status.recoveryMillis());
    }

    @Test
    void resetClearsSpendKeepsExplored() {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0); // 区块 1：1.0 入集合
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);  // 区块 0：1.0 入集合
        engine.resetSpend(player);
        var status = engine.quotaStatus(player);
        assertEquals(0.0, status.lines().get(0).spent(), 1e-9);
        // 集合保留：再踏入已探索区块 1 收 familiar 费
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);
        assertEquals(0.05, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    @Test
    void persistenceSurvivesRestart() {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0); // 1.0 入集合
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0); // 0.05（同一区块内移动不扣，需先离开）
        // 先离开再回来才能再次计费：直接构造新引擎验证集合恢复
        engine.saveAll();
        assertTrue(Files.exists(tmp.resolve("players/" + player + ".json")));

        // 新引擎（同目录）：状态与集合恢复
        QuotaEngine engine2 = new QuotaEngine(tmp,
                QuotaConfig.builder()
                        .lines(List.of(new QuotaConfig.Line(1, 60, 2.0), new QuotaConfig.Line(2, 120, 3.0)))
                        .highSpeedThreshold(1000)
                        .build(new ArrayList<>()),
                null, new ManagedBanStore(tmp.resolve("bans.json")), clock::get);
        var status = engine2.quotaStatus(player);
        assertEquals(1.0, status.lines().get(0).spent(), 1e-9);
        // 已探索集合保留：新引擎下踏入区块 1 收 0.05
        engine2.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);  // 首 tick 基准
        engine2.onPlayerTick(player, false, OVERWORLD, 16, 64, 0); // 区块 1 已探索 -> 0.05
        assertEquals(1.05, engine2.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    @Test
    void corruptedPlayerFileRestoresFromBackup() throws Exception {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0); // 跨区块：1.0 入集合
        engine.saveAll();
        Path f = tmp.resolve("players/" + player + ".json");
        Path bak = tmp.resolve("players/" + player + ".json.bak");
        Files.copy(f, bak); // 模拟写前备份留下的上一版好数据
        Files.writeString(f, "{broken json", StandardCharsets.UTF_8); // 损坏主文件

        // 新引擎：懒加载应命中 .bak，消费与探索集合都恢复（坑 #27）
        QuotaEngine engine2 = new QuotaEngine(tmp,
                QuotaConfig.builder()
                        .lines(List.of(new QuotaConfig.Line(1, 60, 2.0), new QuotaConfig.Line(2, 120, 3.0)))
                        .highSpeedThreshold(1000)
                        .build(new ArrayList<>()),
                null, new ManagedBanStore(tmp.resolve("bans.json")), clock::get);
        assertEquals(1.0, engine2.quotaStatus(player).lines().get(0).spent(), 1e-9);
        // 已探索集合恢复：新引擎下踏入区块 2 收 0.05 而非首费 1.0
        engine2.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);  // 首 tick 基准
        engine2.onPlayerTick(player, false, OVERWORLD, 32, 64, 0); // 区块 2 已从 .bak 恢复 -> 0.05
        assertEquals(1.05, engine2.quotaStatus(player).lines().get(0).spent(), 1e-9);
        // 主文件已被写回修复（不再残留损坏内容）
        assertTrue(Files.readString(f, StandardCharsets.UTF_8).startsWith("{"));
    }

    @Test
    void corruptedPlayerFileAndBackupRebuildsEmpty() throws Exception {
        Files.createDirectories(tmp.resolve("players"));
        Files.writeString(tmp.resolve("players/" + player + ".json"), "{broken json", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("players/" + player + ".json.bak"), "also broken", StandardCharsets.UTF_8);

        QuotaEngine engine2 = new QuotaEngine(tmp,
                QuotaConfig.builder()
                        .lines(List.of(new QuotaConfig.Line(1, 60, 2.0), new QuotaConfig.Line(2, 120, 3.0)))
                        .highSpeedThreshold(1000)
                        .build(new ArrayList<>()),
                null, new ManagedBanStore(tmp.resolve("bans.json")), clock::get);
        // 主与 .bak 均损坏：重建清零（原降级语义）
        assertEquals(0.0, engine2.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    @Test
    void exemptPlayerNeverCharged() {
        QuotaConfig cfg = QuotaConfig.builder()
                .exemptPlayers(List.of(player))
                .build(new ArrayList<>());
        engine.setConfig(cfg);
        assertTrue(engine.isExempt(player, false));
        assertFalse(engine.isExempt(UUID.randomUUID(), false));
        assertTrue(engine.isExempt(UUID.randomUUID(), true)); // exemptByDefault + OP

        for (int i = 0; i < 5; i++) {
            engine.onPlayerTick(player, true, OVERWORLD, i * 16.0, 64, 0);
        }
        assertEquals(0.0, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    @Test
    void exemptThenUnexemptFirstTickNotCharged() {
        // 回归 QA P1：豁免期间位移不记基准；解除豁免后首个 tick 只记基准不扣费。
        // 旧实现不清除 tracking，解除豁免首 tick 会把豁免期间累积位移当区块变化计费。
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);    // 基准（区块 0）
        engine.onPlayerTick(player, true, OVERWORLD, 16, 64, 0);    // 开始豁免
        engine.onPlayerTick(player, true, OVERWORLD, 160, 64, 0);   // 豁免期间大位移（不参与记账）
        engine.onPlayerTick(player, false, OVERWORLD, 160, 64, 0);  // 解除豁免首个 tick：只记基准
        engine.onPlayerTick(player, false, OVERWORLD, 176, 64, 0);  // 正常计费：新区块 1.0
        assertEquals(1.0, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    @Test
    void checkShowsAllLines() {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);
        var status = engine.quotaStatus(player);
        assertEquals(2, status.lines().size());
        assertEquals(60, status.lines().get(0).windowSeconds());
        assertEquals(2.0, status.lines().get(0).limit());
        assertEquals(1.0, status.lines().get(0).spent(), 1e-9);
        assertEquals(120, status.lines().get(1).windowSeconds());
        // 两条窗口都覆盖当前消费桶
        assertEquals(1.0, status.lines().get(1).spent(), 1e-9);
        assertFalse(status.allExceeded());
        assertEquals(-1, status.recoveryMillis());
    }

    @Test
    void boundaryPacingChargesEveryCrossing() {
        // 边界踱步：来回跨区块边界每次都计费（计划明确：重复计费接受，不加防抖）
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);   // 基准（区块 0）
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 进区块 1：1.0
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);   // 回区块 0：1.0
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 再进区块 1：0.05（已探索）
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);   // 回区块 0：0.05
        assertEquals(2.10, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    @Test
    void highSpeedExactThresholdNotDoubled() {
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 2.0), new QuotaConfig.Line(2, 120, 3.0)))
                .highSpeedThreshold(1.0)
                .build(new ArrayList<>()));
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        // speed 恰好 1.0（严格大于才 2x）
        engine.onPlayerTick(player, false, OVERWORLD, 1.0, 64, 0);  // 区块 0 内，不扣费
        engine.onPlayerTick(player, false, OVERWORLD, 17.0, 64, 0); // 区块 1，speed=16 > 1 -> 2.0
        assertEquals(2.0, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    @Test
    void recoveryConsidersEarliestBucketAcrossMinutes() {
        // 消费分散在多个分钟桶：全满时恢复时间 = 各满线"最早消费桶+窗口"的最晚者
        // 线：1min/1.0 + 2min/2.0
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 1.0), new QuotaConfig.Line(2, 120, 2.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        long m0 = clock.now / 60000;
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);   // 基准
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // M0 桶 1.0
        clock.advanceMillis(60_000);
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // M1 桶 1.0
        clock.advanceMillis(60_000);
        engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0);  // M2 桶 1.0
        var status = engine.quotaStatus(player);
        // 1min 线 = M2 桶 1.0（<=1.0 未满）；2min 线 = M1+M2 = 2.0（<=2.0 未满）
        assertFalse(status.allExceeded());
        // 再踏入：M2 桶 2.0 -> 1min 2.0>1.0 满；2min 3.0>2.0 满 -> BAN
        var r = engine.onPlayerTick(player, false, OVERWORLD, 64, 64, 0);
        assertEquals(QuotaEngine.ResultType.BAN, r.type());
        // 1min 线恢复 = 窗口内最早桶 M2 + 60s = (m0+2)*60000+60000 = m0*60000+180000
        // 2min 线恢复 = 窗口内最早桶 M1 + 120s = (m0+1)*60000+120000 = m0*60000+180000
        assertEquals(m0 * 60000L + 180_000L, r.banUntilMillis());
    }

    @Test
    void configReloadTakesEffect() {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 1.0
        // 重载：两条线都收紧到 0.5 -> 已消费 1.0 两线全满，下次踏入即 BAN
        QuotaConfig strict = QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 0.5), new QuotaConfig.Line(2, 120, 0.5)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>());
        engine.setConfig(strict);
        var r = engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);
        assertEquals(QuotaEngine.ResultType.BAN, r.type());
    }

    // ---------- 坑 #28：额度百分比阈值提示 ----------

    /** 单窗口 limit=10 的提示测试配置（每进一个新区块 +1.0 = +10%，逐档可控） */
    private void setAlertConfig() {
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 10.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
    }

    /** 提取 tick 结果中触发的档位列表（按触发顺序） */
    private static List<Integer> percents(QuotaEngine.TickResult r) {
        return r.alerts().stream().map(QuotaEngine.WindowAlert::percent).toList();
    }

    @Test
    void alertsFirePerThresholdOnce() {
        setAlertConfig();
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);   // 基准
        var r = engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 10%：无
        assertTrue(r.alerts().isEmpty());
        r = engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // 20%：15
        assertEquals(List.of(15), percents(r));
        r = engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0);  // 30%：30（15 不重复）
        assertEquals(List.of(30), percents(r));
        r = engine.onPlayerTick(player, false, OVERWORLD, 64, 64, 0);  // 40%：无
        assertTrue(r.alerts().isEmpty());
        r = engine.onPlayerTick(player, false, OVERWORLD, 80, 64, 0);  // 50%：50
        assertEquals(List.of(50), percents(r));
        r = engine.onPlayerTick(player, false, OVERWORLD, 96, 64, 0);  // 60%：无
        assertTrue(r.alerts().isEmpty());
        r = engine.onPlayerTick(player, false, OVERWORLD, 112, 64, 0); // 70%：65
        assertEquals(List.of(65), percents(r));
        r = engine.onPlayerTick(player, false, OVERWORLD, 128, 64, 0); // 80%：75、80 一次跨两档
        assertEquals(List.of(75, 80), percents(r));
        r = engine.onPlayerTick(player, false, OVERWORLD, 144, 64, 0); // 90%：85、90
        assertEquals(List.of(85, 90), percents(r));
        r = engine.onPlayerTick(player, false, OVERWORLD, 160, 64, 0); // 100%：95、98
        assertEquals(List.of(95, 98), percents(r));
    }

    @Test
    void alertSeverityMapping() {
        setAlertConfig();
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        // 收集全部档位对应的严重度（用户规定：15~30 低、50~75 中、80~98 高）
        Map<Integer, QuotaEngine.Severity> got = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            QuotaEngine.TickResult r = engine.onPlayerTick(player, false, OVERWORLD, i * 16.0, 64, 0);
            for (QuotaEngine.WindowAlert a : r.alerts()) {
                got.put(a.percent(), a.severity());
            }
        }
        assertEquals(QuotaEngine.Severity.LOW, got.get(15));
        assertEquals(QuotaEngine.Severity.LOW, got.get(30));
        assertEquals(QuotaEngine.Severity.MEDIUM, got.get(50));
        assertEquals(QuotaEngine.Severity.MEDIUM, got.get(65));
        assertEquals(QuotaEngine.Severity.MEDIUM, got.get(75));
        assertEquals(QuotaEngine.Severity.HIGH, got.get(80));
        assertEquals(QuotaEngine.Severity.HIGH, got.get(85));
        assertEquals(QuotaEngine.Severity.HIGH, got.get(90));
        assertEquals(QuotaEngine.Severity.HIGH, got.get(95));
        assertEquals(QuotaEngine.Severity.HIGH, got.get(98));
    }

    @Test
    void alertsPerWindowIndependent() {
        // 双窗口：1min/10.0（+10%/区块）+ 2min/100.0（+1%/区块），各自独立触发
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 10.0), new QuotaConfig.Line(2, 120, 100.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        List<QuotaEngine.WindowAlert> all = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            all.addAll(engine.onPlayerTick(player, false, OVERWORLD, i * 16.0, 64, 0).alerts());
        }
        // 1min 线 50%（15/30/50）；2min 线仅 5%，无任何提示
        assertEquals(List.of(15, 30, 50), all.stream().map(QuotaEngine.WindowAlert::percent).toList());
        for (QuotaEngine.WindowAlert a : all) {
            assertEquals(60, a.windowSeconds());
        }
    }

    @Test
    void alertFirstTickInitializesWithoutSpam() {
        setAlertConfig();
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        for (int i = 1; i <= 6; i++) {
            engine.onPlayerTick(player, false, OVERWORLD, i * 16.0, 64, 0); // 消费到 60%
        }
        // 模拟重连：瞬态状态清空（数据落盘保留）
        engine.onPlayerDisconnect(player);
        // 重进首 tick 只初始化当前档位（60% -> 50），不补发历史档位
        var r = engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        assertTrue(r.alerts().isEmpty());
        // 向未探索方向走（+1.0 首费）：70.5% 只发 65，不发 15/30/50
        r = engine.onPlayerTick(player, false, OVERWORLD, -16, 64, 0);
        assertEquals(List.of(65), percents(r));
    }

    @Test
    void alertsReTriggerAfterResetSpend() {
        setAlertConfig();
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 10%
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // 20%：15
        engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0);  // 30%：30
        // 管理员重置：档位回落（0.5%），重新消费后再次触发
        engine.resetSpend(player);
        var r = engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0);  // 0.05 熟悉费，无触发
        assertTrue(r.alerts().isEmpty());
        r = engine.onPlayerTick(player, false, OVERWORLD, 64, 64, 0);  // 10.5%：无
        assertTrue(r.alerts().isEmpty());
        r = engine.onPlayerTick(player, false, OVERWORLD, 80, 64, 0);  // 20.5%：15 重新触发
        assertEquals(List.of(15), percents(r));
    }

    @Test
    void alertsCrossingMultipleThresholds() {
        // limit=3.0：单次进区块 +1.0 = +33.3%，一次跨 15、30 两档，逐条都发
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 3.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        var r = engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);
        assertEquals(List.of(15, 30), percents(r));
    }

    @Test
    void exemptPlayerNoAlertAndStateCleared() {
        setAlertConfig();
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 10%
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // 20%：15
        engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0);  // 30%：30
        // 豁免：不提示且清除状态（避免旧档位残留）
        var r = engine.onPlayerTick(player, true, OVERWORLD, 48, 64, 0);
        assertTrue(r.alerts().isEmpty());
        // 解除豁免：首 tick 重新初始化（30% -> 档位 30，不触发）
        r = engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0);
        assertTrue(r.alerts().isEmpty());
        r = engine.onPlayerTick(player, false, OVERWORLD, 64, 64, 0);  // 40%：无
        assertTrue(r.alerts().isEmpty());
        r = engine.onPlayerTick(player, false, OVERWORLD, 80, 64, 0);  // 50%：50
        assertEquals(List.of(50), percents(r));
    }

    @Test
    void banTickHasNoAlerts() {
        // limit=3.0：第 4 次踏入 4.0 > 3.0 -> BAN；BAN tick 不发提示（ban 消息已充分说明）
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 3.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 1.0
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // 2.0
        engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0);  // 3.0（恰等于上限，未满）
        var r = engine.onPlayerTick(player, false, OVERWORLD, 64, 64, 0); // 4.0 -> BAN
        assertEquals(QuotaEngine.ResultType.BAN, r.type());
        assertTrue(r.alerts().isEmpty());
    }

    // ---------- 坑 #29：check 档位词（quotaStatus.worstAlert） ----------

    @Test
    void worstAlertNullWhenUnder15Percent() {
        // 单窗口 limit=10：+1.0/区块 = +10%。10% 无档 -> worstAlert null
        setAlertConfig();
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        assertNull(engine.quotaStatus(player).worstAlert());
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0); // 10%
        assertNull(engine.quotaStatus(player).worstAlert());
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0); // 20% -> 15 档
        assertEquals(15, engine.quotaStatus(player).worstAlert().percent());
    }

    @Test
    void worstAlertPercentByLevel() {
        // limit=8.0：+1.0 = +12.5% 步进，逐档可控：25%->15、37.5%->30、50%->50、
        // 62.5%->50、75%->75（≥75 归不足区）、87.5%->85、100%->98
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 8.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        assertNull(engine.quotaStatus(player).worstAlert());
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 12.5%
        assertNull(engine.quotaStatus(player).worstAlert());
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // 25%
        assertEquals(15, engine.quotaStatus(player).worstAlert().percent());
        engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0);  // 37.5%
        assertEquals(30, engine.quotaStatus(player).worstAlert().percent());
        engine.onPlayerTick(player, false, OVERWORLD, 64, 64, 0);  // 50%
        assertEquals(50, engine.quotaStatus(player).worstAlert().percent());
        engine.onPlayerTick(player, false, OVERWORLD, 80, 64, 0);  // 62.5%：最高已过档仍 50
        assertEquals(50, engine.quotaStatus(player).worstAlert().percent());
        engine.onPlayerTick(player, false, OVERWORLD, 96, 64, 0);  // 75% -> 75 档（≥75 不足区）
        assertEquals(75, engine.quotaStatus(player).worstAlert().percent());
        engine.onPlayerTick(player, false, OVERWORLD, 112, 64, 0); // 87.5% -> 85
        assertEquals(85, engine.quotaStatus(player).worstAlert().percent());
        engine.onPlayerTick(player, false, OVERWORLD, 128, 64, 0); // 100% -> 98
        assertEquals(98, engine.quotaStatus(player).worstAlert().percent());
    }

    @Test
    void worstAlertTakesHighestAcrossWindows() {
        // 双窗口：1min/10.0（+10%/区块）+ 2min/100.0（+1%/区块）
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 10.0), new QuotaConfig.Line(2, 120, 100.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        for (int i = 1; i <= 8; i++) {
            engine.onPlayerTick(player, false, OVERWORLD, i * 16.0, 64, 0); // 1min 线 80%，2min 线 8%
        }
        QuotaEngine.WindowAlert worst = engine.quotaStatus(player).worstAlert();
        assertNotNull(worst);
        assertEquals(80, worst.percent());
        assertEquals(60, worst.windowSeconds()); // 取 80% 档所在的 1min 窗口
    }

    @Test
    void worstAlertFallsBackAfterWindowSlides() {
        // 90% -> 档位 90；时钟推进使消费桶滑出 60s 窗口 -> 无档回落 null（跟随当前状态）
        setAlertConfig();
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        for (int i = 1; i <= 9; i++) {
            engine.onPlayerTick(player, false, OVERWORLD, i * 16.0, 64, 0);
        }
        assertEquals(90, engine.quotaStatus(player).worstAlert().percent());
        clock.advanceMillis(61_000);
        assertNull(engine.quotaStatus(player).worstAlert());
    }

    // ---------- 坑 #30：按档位分桶 / 单档重置 / 全量清档 / 每 tick 判踢 / v1 迁移 ----------

    @Test
    void resetSpendSingleTierKeepsOtherTiers() {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 1.0 计入两条线
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // 2.0
        var before = engine.quotaStatus(player);
        assertEquals(2.0, before.lines().get(0).spent(), 1e-9);
        assertEquals(2.0, before.lines().get(1).spent(), 1e-9);
        // 只重置第一档（tier1）：tier1 清零、tier2 保留
        engine.resetSpend(player, Set.of(1));
        var after = engine.quotaStatus(player);
        assertEquals(0.0, after.lines().get(0).spent(), 1e-9);
        assertEquals(2.0, after.lines().get(1).spent(), 1e-9);
    }

    @Test
    void clearTierSpendForAllClearsOnlineAndOfflineFiles() throws Exception {
        UUID offline = UUID.randomUUID();
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);   // 在线：tier1+tier2 = 1.0
        engine.onPlayerTick(offline, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(offline, false, OVERWORLD, 16, 64, 0);  // 离线：1.0
        engine.saveAll();
        engine.onPlayerDisconnect(offline); // 落盘并移出内存
        assertTrue(Files.exists(tmp.resolve("players/" + offline + ".json")));

        engine.clearTierSpendForAll(1);

        // 在线玩家：tier1 清空、tier2 保留
        assertEquals(0.0, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);
        assertEquals(1.0, engine.quotaStatus(player).lines().get(1).spent(), 1e-9);
        // 离线玩家：文件已被改写，新引擎读取 tier1 为 0
        QuotaEngine engine2 = new QuotaEngine(tmp,
                QuotaConfig.builder()
                        .lines(List.of(new QuotaConfig.Line(1, 60, 2.0), new QuotaConfig.Line(2, 120, 3.0)))
                        .highSpeedThreshold(1000)
                        .build(new ArrayList<>()),
                null, new ManagedBanStore(tmp.resolve("bans.json")), clock::get);
        assertEquals(0.0, engine2.quotaStatus(offline).lines().get(0).spent(), 1e-9);
        assertEquals(1.0, engine2.quotaStatus(offline).lines().get(1).spent(), 1e-9);
    }

    @Test
    void setConfigLineChangeResetsAlertStateNoCrash() {
        // 单窗口配置初始化提示状态（1 条线）
        setAlertConfig();
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 10%
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // 20%：15 触发
        // 改为双窗口：lines 数变化，旧 lastLevels 数组按旧长度对齐，不复位会越界/错位
        // （坑 #30：setConfig 检测档位集合变化并清空提示状态）
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 10.0), new QuotaConfig.Line(2, 120, 100.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        // 下一 tick 不崩溃、不补发历史档位（首见重基线）
        var r = engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);
        assertTrue(r.alerts().isEmpty());
        // 新档位序列正常触发
        r = engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0); // 30%：30
        assertEquals(List.of(30), percents(r));
    }

    @Test
    void limitLoweredWhileStationaryKicksNextTick() {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);   // 基准（区块 0）
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 区块 1：1.0
        engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);  // 区块 2：2.0
        // 管理员调低第一档上限到 1.0（已消费 2.0 超限）
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 1.0), new QuotaConfig.Line(2, 120, 3.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        // 玩家原地不动（无区块变化）：下一 tick 仍应判满踢出（坑 #30 每 tick 判满）
        var r = engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);
        assertEquals(QuotaEngine.ResultType.BAN, r.type());
    }

    @Test
    void v1PlayerFileMigratesKeepingExploredDroppingSpend() throws Exception {
        // 手工构造 v1 玩家数据：explored 区块 1 + 共享分钟桶 5.0
        Files.createDirectories(tmp.resolve("players"));
        String v1 = "{"
                + "\"version\":1,"
                + "\"explored\":{\"minecraft:overworld\":{\"0\":[[1,1]]}},"
                + "\"minuteBuckets\":{\"" + (clock.now / 60000) + "\":5.0}"
                + "}";
        Files.writeString(tmp.resolve("players/" + player + ".json"), v1, StandardCharsets.UTF_8);

        // 新引擎懒加载：explored 保留（熟悉费）、消费桶丢弃（从 0 起）
        QuotaEngine engine2 = new QuotaEngine(tmp,
                QuotaConfig.builder()
                        .lines(List.of(new QuotaConfig.Line(1, 60, 2.0), new QuotaConfig.Line(2, 120, 3.0)))
                        .highSpeedThreshold(1000)
                        .build(new ArrayList<>()),
                null, new ManagedBanStore(tmp.resolve("bans.json")), clock::get);
        assertEquals(0.0, engine2.quotaStatus(player).lines().get(0).spent(), 1e-9);
        // 踏入已探索区块 1 只收熟悉费 0.05
        engine2.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);  // 基准（区块 0）
        engine2.onPlayerTick(player, false, OVERWORLD, 16, 64, 0); // 区块 1 已探索 -> 0.05
        assertEquals(0.05, engine2.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    // ---------- 坑 #31：零线（全部窗口关闭） ----------

    @Test
    void zeroLinesTickDoesNotChargeOrLoad() {
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of()) // 零线：无额度线
                .build(new ArrayList<>()));
        // 跨多个区块移动：不记账、不提示、不判踢
        for (int i = 0; i < 5; i++) {
            QuotaEngine.TickResult r = engine.onPlayerTick(player, false, OVERWORLD, i * 16.0, 64, 0);
            assertEquals(QuotaEngine.ResultType.NONE, r.type());
            assertTrue(r.alerts().isEmpty());
        }
        // 不加载玩家数据（不创建文件）
        assertFalse(Files.exists(tmp.resolve("players/" + player + ".json")));
    }

    @Test
    void zeroLinesNeverExceeded() {
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // 1.0（未满，但已有消费）
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of())
                .build(new ArrayList<>()));
        // 切零线后判满恒 false（登录 gate 放行；内存残留消费数据也不触发 BAN）
        assertFalse(engine.isAllLinesExceeded(player));
        QuotaEngine.TickResult r = engine.onPlayerTick(player, false, OVERWORLD, 32, 64, 0);
        assertEquals(QuotaEngine.ResultType.NONE, r.type());
    }

    @Test
    void zeroLinesQuotaStatusEmpty() {
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of())
                .build(new ArrayList<>()));
        QuotaEngine.QuotaStatus status = engine.quotaStatus(player);
        assertTrue(status.lines().isEmpty());
        assertFalse(status.allExceeded());
        assertEquals(-1, status.recoveryMillis());
        assertNull(status.worstAlert());
    }

    @Test
    void zeroLinesThenEnableFirstTickNotCharged() {
        // 零线期间 tick 不建立 tracking（坑 #31）→ 开启窗口后首个 tick 走首 tick 分支只记基准不扣费
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of())
                .build(new ArrayList<>()));
        engine.onPlayerTick(player, false, OVERWORLD, 100, 64, 0);  // 零线：大位移，无 tracking
        engine.setConfig(QuotaConfig.builder()
                .lines(List.of(new QuotaConfig.Line(1, 60, 10.0), new QuotaConfig.Line(2, 120, 20.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>()));
        QuotaEngine.TickResult r = engine.onPlayerTick(player, false, OVERWORLD, 116, 64, 0); // 首 tick：只记基准
        assertEquals(QuotaEngine.ResultType.NONE, r.type());
        assertEquals(0.0, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);
        engine.onPlayerTick(player, false, OVERWORLD, 132, 64, 0);  // 次 tick：正常计费 1.0
        assertEquals(1.0, engine.quotaStatus(player).lines().get(0).spent(), 1e-9);
    }

    @Test
    void clearTierSpendForAllPersistsOnlineImmediately() throws Exception {
        // 坑 #31（QA P1 回归）：在线玩家清档后立即落盘，不依赖 5 分钟周期保存；
        // 期间崩溃不会丢失清除（新引擎读文件即可验证）
        engine.onPlayerTick(player, false, OVERWORLD, 0, 64, 0);
        engine.onPlayerTick(player, false, OVERWORLD, 16, 64, 0);  // tier1+tier2 = 1.0
        engine.saveAll();
        engine.clearTierSpendForAll(1);
        // 不调 saveAll，直接由新引擎读文件：tier1 已清、tier2 保留
        QuotaEngine engine2 = new QuotaEngine(tmp,
                QuotaConfig.builder()
                        .lines(List.of(new QuotaConfig.Line(1, 60, 2.0), new QuotaConfig.Line(2, 120, 3.0)))
                        .highSpeedThreshold(1000)
                        .build(new ArrayList<>()),
                null, new ManagedBanStore(tmp.resolve("bans.json")), clock::get);
        assertEquals(0.0, engine2.quotaStatus(player).lines().get(0).spent(), 1e-9);
        assertEquals(1.0, engine2.quotaStatus(player).lines().get(1).spent(), 1e-9);
    }
}
