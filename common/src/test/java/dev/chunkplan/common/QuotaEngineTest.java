package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
                        new QuotaConfig.Line(60, 2.0),
                        new QuotaConfig.Line(120, 3.0)))
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
                        new QuotaConfig.Line(60, 2.0),
                        new QuotaConfig.Line(3600, 3.0)))
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
        assertNotNull(r.message());
        assertTrue(r.message().contains("探索额度已耗尽"));
        assertTrue(r.message().contains("1m 窗口"));
        // 恢复时间 = 各满线恢复时间的 max = 最早消费桶(M0) + 最长满线窗口(2min)
        long m0 = clock.now / 60000;
        assertEquals(m0 * 60000L + 120_000L, r.banUntilMillis());
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
        assertNull(engine.loginBlockMessage(player));
        // 收紧 1h 线（阈值保持禁用倍率）
        QuotaConfig cfg = QuotaConfig.builder()
                .lines(List.of(
                        new QuotaConfig.Line(60, 2.0),
                        new QuotaConfig.Line(3600, 4.0)))
                .highSpeedThreshold(1000)
                .build(new ArrayList<>());
        engine.setConfig(cfg);
        engine.onPlayerTick(player, false, OVERWORLD, 48, 64, 0); // 3.0
        engine.onPlayerTick(player, false, OVERWORLD, 64, 64, 0); // 4.0
        engine.onPlayerTick(player, false, OVERWORLD, 80, 64, 0); // 5.0：两条都满
        var msg = engine.loginBlockMessage(player);
        assertNotNull(msg);
        assertTrue(msg.contains("预计"));
        assertTrue(msg.contains("恢复"));
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
                        .lines(List.of(new QuotaConfig.Line(60, 2.0), new QuotaConfig.Line(120, 3.0)))
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
}
