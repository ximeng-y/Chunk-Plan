package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PlayerQuotaDataTest {

    /** 便捷：区块坐标打包 */
    private static long chunk(int x, int z) {
        return ChunkPosPacker.pack(x, z);
    }

    @Test
    void jsonRoundTrip() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.markExplored("minecraft:overworld", chunk(1, 2));
        p.markExplored("minecraft:overworld", chunk(3, -4));
        p.markExplored("minecraft:the_nether", chunk(-5, 6));
        // 首消锚定整分钟（1000min），同周期内两笔消费累计
        p.recordSpend(1, 1000L * 60000 + 30000, 1.0);
        p.recordSpend(1, 1000L * 60000 + 40000, 0.5);
        p.recordSpend(2, 1000L * 60000 + 45000, 0.05);

        String json = GsonHolder.GSON.toJson(p.toDto());
        PlayerQuotaData back = PlayerQuotaData.fromDto(
                GsonHolder.GSON.fromJson(json, PlayerQuotaData.Dto.class));

        assertTrue(back.isExplored("minecraft:overworld", chunk(1, 2)));
        assertTrue(back.isExplored("minecraft:overworld", chunk(3, -4)));
        assertFalse(back.isExplored("minecraft:overworld", chunk(1, 3)));
        assertTrue(back.isExplored("minecraft:the_nether", chunk(-5, 6)));
        assertFalse(back.isExplored("minecraft:the_nether", chunk(0, 0)));

        long now = 1000L * 60000 + 50000; // 周期内（窗口 3600s 未到点）
        assertEquals(1.5, back.effectiveSpent(1, now, 3600), 1e-9);
        assertEquals(0.05, back.effectiveSpent(2, now, 3600), 1e-9);
        assertEquals(1000L * 60000, back.cycleStartMillis(1));
    }

    @Test
    void recordSpendAnchorsAtMinuteFloor() {
        PlayerQuotaData p = new PlayerQuotaData();
        // 首消在 100.5 分钟 -> 锚定到 100 分钟整
        p.recordSpend(1, 100L * 60000 + 30000, 1.0);
        assertEquals(100L * 60000, p.cycleStartMillis(1));
        // 同周期后续消费不改锚点
        p.recordSpend(1, 102L * 60000 + 10000, 2.0);
        assertEquals(100L * 60000, p.cycleStartMillis(1));
        assertEquals(3.0, p.effectiveSpent(1, 102L * 60000 + 20000, 3600), 1e-9);
    }

    @Test
    void cycleBoundaryFullReset() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.recordSpend(1, 100L * 60000 + 30000, 5.0); // 锚定 100min，窗口 60s
        long end = 101L * 60000;                     // 周期终点 = 101min 整
        // 终点前 1ms 仍计入；到达终点即整窗清零（与消费多少无关）
        assertEquals(5.0, p.effectiveSpent(1, end - 1, 60), 1e-9);
        assertEquals(0.0, p.effectiveSpent(1, end, 60), 1e-9);
    }

    @Test
    void expireIfNeededResetsAndReanchors() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.recordSpend(1, 100L * 60000 + 30000, 5.0); // 锚定 100min，窗口 60s
        long end = 101L * 60000;
        assertFalse(p.expireIfNeeded(1, end - 1, 60)); // 未到期不清
        assertTrue(p.expireIfNeeded(1, end, 60));      // 到期整窗清零
        assertEquals(-1, p.cycleStartMillis(1));
        assertEquals(0.0, p.effectiveSpent(1, end, 60), 1e-9);
        // 清零后再次消费重新锚定完整周期
        p.recordSpend(1, end + 30000, 1.0);
        assertEquals(end, p.cycleStartMillis(1)); // (end+30s) 所在分钟 = 101min
        assertEquals(1.0, p.effectiveSpent(1, end + 30000, 60), 1e-9);
    }

    @Test
    void effectiveSpentIsPureReadAfterExpiry() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.recordSpend(1, 100L * 60000, 5.0);
        p.clearDirty();
        long end = 101L * 60000;
        assertEquals(0.0, p.effectiveSpent(1, end, 60), 1e-9); // 过期视为 0
        assertEquals(100L * 60000, p.cycleStartMillis(1));     // 纯读：状态未被清除
        assertFalse(p.isDirty());                              // 纯读不置脏
    }

    @Test
    void fromDtoToleratesNulls() {
        PlayerQuotaData p = PlayerQuotaData.fromDto(new PlayerQuotaData.Dto());
        assertFalse(p.isExplored("x", chunk(0, 0)));
        assertEquals(0.0, p.effectiveSpent(1, System.currentTimeMillis(), 60), 1e-9);
        assertEquals(-1, p.cycleStartMillis(1));
    }

    @Test
    void toJsonStructure() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.markExplored("minecraft:overworld", chunk(1, 2));
        p.recordSpend(1, 1000L * 60000, 3.5);
        String json = GsonHolder.GSON.toJson(p.toDto());
        Map<?, ?> parsed = GsonHolder.GSON.fromJson(json, Map.class);
        assertEquals(3, ((Number) parsed.get("version")).intValue());
        assertTrue(parsed.containsKey("explored"));
        assertTrue(parsed.containsKey("tiers"));
        // explored 为按行对象结构：{"minecraft:overworld":{"2":[[1,1]]}}
        Map<?, ?> explored = (Map<?, ?>) parsed.get("explored");
        Map<?, ?> rows = (Map<?, ?>) explored.get("minecraft:overworld");
        assertTrue(rows.containsKey("2"));
        // tiers 为档位 -> {cycleStartMillis, spent}
        Map<?, ?> tiers = (Map<?, ?>) parsed.get("tiers");
        Map<?, ?> tier1 = (Map<?, ?>) tiers.get("1");
        assertEquals(1000L * 60000, ((Number) tier1.get("cycleStartMillis")).longValue());
        assertEquals(3.5, (Double) tier1.get("spent"), 1e-9);
    }

    // ---------- 坑 #30/#40：按档位独立 / 单档清空 / v1、v2 迁移 ----------

    @Test
    void perTierCyclesIndependent() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.recordSpend(1, 100L * 60000 + 30000, 1.0);
        p.recordSpend(1, 100L * 60000 + 30000, 0.5);
        p.recordSpend(2, 100L * 60000 + 30000, 0.2);
        long now = 100L * 60000 + 40000;
        assertEquals(1.5, p.effectiveSpent(1, now, 3600), 1e-9);
        assertEquals(0.2, p.effectiveSpent(2, now, 3600), 1e-9);
        assertEquals(0.0, p.effectiveSpent(3, now, 3600), 1e-9); // 无该档位记录
        assertEquals(-1, p.cycleStartMillis(3));                 // 无该档位记录
        assertEquals(100L * 60000, p.cycleStartMillis(1));
        assertEquals(100L * 60000, p.cycleStartMillis(2));
    }

    @Test
    void clearTierSpendOnlyClearsThatTier() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.recordSpend(1, 100L * 60000, 1.0);
        p.recordSpend(2, 100L * 60000, 2.0);
        assertTrue(p.clearTierSpend(1));
        long now = 100L * 60000 + 30000;
        assertEquals(0.0, p.effectiveSpent(1, now, 3600), 1e-9);
        assertEquals(-1, p.cycleStartMillis(1)); // 锚点一并清除，下次消费重新锚定
        assertEquals(2.0, p.effectiveSpent(2, now, 3600), 1e-9);
        assertFalse(p.clearTierSpend(1)); // 已空：不置脏
    }

    @Test
    void v1LegacyBucketsDroppedOnLoad() {
        // v1 共享分钟桶：fromDto 有意不读取（无法映射为固定周期），explored 保留
        String v1Json = "{\"version\":1,\"explored\":{\"d\":{\"0\":[[1,1]]}},"
                + "\"minuteBuckets\":{\"100\":5.0}}";
        PlayerQuotaData.Dto dto = GsonHolder.GSON.fromJson(v1Json, PlayerQuotaData.Dto.class);
        assertEquals(1, dto.version);
        PlayerQuotaData p = PlayerQuotaData.fromDto(dto);
        assertTrue(p.isExplored("d", chunk(1, 0)));
        assertEquals(0.0, p.effectiveSpent(1, 100L * 60000 + 30000, 3600), 1e-9);
        assertEquals(-1, p.cycleStartMillis(1));
    }

    @Test
    void v2LegacyBucketsDroppedOnLoad() {
        // v2 滚动窗口分钟桶：fromDto 有意不读取（无法映射为固定周期），explored 保留
        String v2Json = "{\"version\":2,\"explored\":{\"d\":{\"0\":[[1,1]]}},"
                + "\"tierBuckets\":{\"1\":{\"100\":5.0}}}";
        PlayerQuotaData.Dto dto = GsonHolder.GSON.fromJson(v2Json, PlayerQuotaData.Dto.class);
        assertEquals(2, dto.version);
        PlayerQuotaData p = PlayerQuotaData.fromDto(dto);
        assertTrue(p.isExplored("d", chunk(1, 0)));
        assertEquals(0.0, p.effectiveSpent(1, 100L * 60000 + 30000, 3600), 1e-9);
        assertEquals(-1, p.cycleStartMillis(1));
    }

    @Test
    void adjacentBlocksMergeIntoOneRange() {
        // 同行连续踏入 1、2、3 -> 一个区间 [1,3]
        PlayerQuotaData p = new PlayerQuotaData();
        assertTrue(p.markExplored("d", chunk(1, 10)));
        assertTrue(p.markExplored("d", chunk(2, 10)));
        assertTrue(p.markExplored("d", chunk(3, 10)));
        assertEquals("[[1, 3]]", java.util.Arrays.deepToString(
                p.toDto().explored.get("d").get("10")));
        // 边界与内部均命中，间隔点不命中
        assertTrue(p.isExplored("d", chunk(1, 10)));
        assertTrue(p.isExplored("d", chunk(3, 10)));
        assertFalse(p.isExplored("d", chunk(0, 10)));
        assertFalse(p.isExplored("d", chunk(4, 10)));
        assertFalse(p.isExplored("d", chunk(2, 11)));
    }

    @Test
    void fillingGapMergesThreeRanges() {
        // 先走两端 [5,8] 与 [10,12]，再踏入 9 填平凹口 -> 三合一 [5,12]
        PlayerQuotaData p = new PlayerQuotaData();
        for (int x = 5; x <= 8; x++) {
            p.markExplored("d", chunk(x, 10));
        }
        for (int x = 10; x <= 12; x++) {
            p.markExplored("d", chunk(x, 10));
        }
        assertEquals("[[5, 8], [10, 12]]", java.util.Arrays.deepToString(
                p.toDto().explored.get("d").get("10")));
        assertTrue(p.markExplored("d", chunk(9, 10)));
        assertEquals("[[5, 12]]", java.util.Arrays.deepToString(
                p.toDto().explored.get("d").get("10")));
        assertTrue(p.isExplored("d", chunk(9, 10)));
    }

    @Test
    void differentRowsDoNotMerge() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.markExplored("d", chunk(5, 10));
        p.markExplored("d", chunk(5, 11)); // 同 x 不同 z：独立区间
        assertEquals(2, p.toDto().explored.get("d").size());
        assertTrue(p.isExplored("d", chunk(5, 10)));
        assertTrue(p.isExplored("d", chunk(5, 11)));
        assertFalse(p.isExplored("d", chunk(5, 12)));
    }

    @Test
    void negativeCoordinatesWork() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.markExplored("d", chunk(-3, -4));
        p.markExplored("d", chunk(-2, -4));
        p.markExplored("d", chunk(-1, -4)); // z=-4 行 [-3,-1] 连续
        assertEquals("[[-3, -1]]", java.util.Arrays.deepToString(
                p.toDto().explored.get("d").get("-4")));
        assertTrue(p.isExplored("d", chunk(-3, -4)));
        assertTrue(p.isExplored("d", chunk(-1, -4)));
        assertFalse(p.isExplored("d", chunk(-4, -4)));
    }

    @Test
    void duplicateMarkNotDirty() {
        PlayerQuotaData p = new PlayerQuotaData();
        assertTrue(p.markExplored("d", chunk(5, 10)));
        p.clearDirty();
        assertFalse(p.markExplored("d", chunk(5, 10))); // 已存在
        assertFalse(p.isDirty());                        // 重复标记不置脏
        // 区间内部重复同样不置脏
        p.markExplored("d", chunk(6, 10));
        p.markExplored("d", chunk(7, 10));
        p.clearDirty();
        assertFalse(p.markExplored("d", chunk(6, 10)));
        assertFalse(p.isDirty());
    }

    @Test
    void fromDtoSkipsInvalidRanges() {
        PlayerQuotaData.Dto dto = new PlayerQuotaData.Dto();
        dto.explored = Map.of(
                "d", Map.of(
                        "10", new int[][]{{1, 3}, {5, 4}, {7, 8}, null, {9}}, // 合法 / 起点>终点 / 合法 / null / 长度不足
                        "abc", new int[][]{{0, 1}},                          // 非法行号
                        "-2", new int[][]{{-3, -1}}));                       // 合法负行
        PlayerQuotaData p = PlayerQuotaData.fromDto(dto);
        assertTrue(p.isExplored("d", chunk(2, 10)));
        assertTrue(p.isExplored("d", chunk(8, 10)));
        assertFalse(p.isExplored("d", chunk(5, 10))); // 起点>终点被跳过
        assertFalse(p.isExplored("d", chunk(0, 1)));  // 非法行号被跳过
        assertTrue(p.isExplored("d", chunk(-2, -2)));
        // 相邻区间在加载时顺带规范化合并（[1,3] 与 [7,8] 不相邻，保持两条）
        assertEquals("[[1, 3], [7, 8]]", java.util.Arrays.deepToString(
                p.toDto().explored.get("d").get("10")));
    }

    @Test
    void fromDtoSkipsInvalidCycles() {
        PlayerQuotaData.Dto dto = new PlayerQuotaData.Dto();
        dto.tiers = Map.of(
                1, cycle(1000L, 1.5),   // 合法
                2, cycle(-1L, 9.9),     // 未锚定：跳过
                5, cycle(1000L, 1.0),   // 非法档位：跳过
                0, cycle(1000L, 1.0));  // 非法档位：跳过
        PlayerQuotaData p = PlayerQuotaData.fromDto(dto);
        assertEquals(1.5, p.effectiveSpent(1, 1000L + 1000, 3600), 1e-9);
        assertEquals(-1, p.cycleStartMillis(2));
        assertEquals(-1, p.cycleStartMillis(5));
        assertEquals(-1, p.cycleStartMillis(0));
    }

    private static PlayerQuotaData.TierCycle cycle(long start, double spent) {
        PlayerQuotaData.TierCycle c = new PlayerQuotaData.TierCycle();
        c.cycleStartMillis = start;
        c.spent = spent;
        return c;
    }
}
