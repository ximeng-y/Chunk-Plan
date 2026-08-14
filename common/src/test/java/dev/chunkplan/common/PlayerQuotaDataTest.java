package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        p.addSpend(1, 1000L, 1.0);
        p.addSpend(1, 1000L, 0.5);
        p.addSpend(1, 1001L, 0.05);

        String json = GsonHolder.GSON.toJson(p.toDto());
        PlayerQuotaData back = PlayerQuotaData.fromDto(
                GsonHolder.GSON.fromJson(json, PlayerQuotaData.Dto.class));

        assertTrue(back.isExplored("minecraft:overworld", chunk(1, 2)));
        assertTrue(back.isExplored("minecraft:overworld", chunk(3, -4)));
        assertFalse(back.isExplored("minecraft:overworld", chunk(1, 3)));
        assertTrue(back.isExplored("minecraft:the_nether", chunk(-5, 6)));
        assertFalse(back.isExplored("minecraft:the_nether", chunk(0, 0)));

        long now = 1001L * 60000 + 30000;
        assertEquals(1.55, back.spendInWindow(1, now, 3600), 1e-9);
    }

    @Test
    void windowBoundary() {
        PlayerQuotaData p = new PlayerQuotaData();
        // 桶 100 消费 1.0，桶 101 消费 2.0；now=101.5min 时刻，窗口 60s
        p.addSpend(1, 100, 1.0);
        p.addSpend(1, 101, 2.0);
        long nowMillis = 101L * 60000 + 30000; // 101.5 分钟
        // 窗口 (100.5min, 101.5min] -> 只含桶 101
        assertEquals(2.0, p.spendInWindow(1, nowMillis, 60), 1e-9);
        // 窗口 120s -> (100.5min, 101.5min] 同样只含桶 101？(101.5-2)min=99.5 -> 含桶 100、101
        assertEquals(3.0, p.spendInWindow(1, nowMillis, 120), 1e-9);
    }

    @Test
    void cleanupOldBuckets() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.addSpend(1, 100, 1.0);
        p.addSpend(1, 200, 2.0);
        assertTrue(p.cleanupBucketsBefore(1, 150));
        assertEquals(2.0, p.spendInWindow(1, 200L * 60000 + 1000, 3600), 1e-9);
        assertFalse(p.cleanupBucketsBefore(1, 150)); // 已清理，无需再清理
    }

    @Test
    void fromDtoToleratesNulls() {
        PlayerQuotaData p = PlayerQuotaData.fromDto(new PlayerQuotaData.Dto());
        assertFalse(p.isExplored("x", chunk(0, 0)));
        assertEquals(0.0, p.spendInWindow(1, System.currentTimeMillis(), 60), 1e-9);
    }

    @Test
    void toJsonStructure() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.markExplored("minecraft:overworld", chunk(1, 2));
        p.addSpend(1, 1000, 3.5);
        String json = GsonHolder.GSON.toJson(p.toDto());
        Map<?, ?> parsed = GsonHolder.GSON.fromJson(json, Map.class);
        assertEquals(2, ((Number) parsed.get("version")).intValue());
        assertTrue(parsed.containsKey("explored"));
        assertTrue(parsed.containsKey("tierBuckets"));
        // explored 为按行对象结构：{"minecraft:overworld":{"2":[[1,1]]}}
        Map<?, ?> explored = (Map<?, ?>) parsed.get("explored");
        Map<?, ?> rows = (Map<?, ?>) explored.get("minecraft:overworld");
        assertTrue(rows.containsKey("2"));
    }

    // ---------- 坑 #30：按档位分桶 / 单档清空 / v1 迁移 ----------

    @Test
    void perTierBucketsIndependent() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.addSpend(1, 100, 1.0);
        p.addSpend(1, 100, 0.5);
        p.addSpend(2, 100, 0.2);
        long now = 100L * 60000 + 30000;
        assertEquals(1.5, p.spendInWindow(1, now, 3600), 1e-9);
        assertEquals(0.2, p.spendInWindow(2, now, 3600), 1e-9);
        assertEquals(0.0, p.spendInWindow(3, now, 3600), 1e-9); // 无该档位桶
        assertNull(p.firstBucketAtOrAfter(3, 100));              // 无该档位桶
        assertEquals(100L, p.firstBucketAtOrAfter(1, 100));
        assertEquals(100L, p.firstBucketAtOrAfter(2, 100));
    }

    @Test
    void clearTierSpendOnlyClearsThatTier() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.addSpend(1, 100, 1.0);
        p.addSpend(2, 100, 2.0);
        assertTrue(p.clearTierSpend(1));
        long now = 100L * 60000 + 30000;
        assertEquals(0.0, p.spendInWindow(1, now, 3600), 1e-9);
        assertEquals(2.0, p.spendInWindow(2, now, 3600), 1e-9);
        assertFalse(p.clearTierSpend(1)); // 已空：不置脏
    }

    @Test
    void v1LegacyBucketsDroppedOnLoad() {
        // v1 共享分钟桶：fromDto 有意不读取（无法无损拆分到档位），explored 保留
        PlayerQuotaData.Dto v1 = new PlayerQuotaData.Dto();
        v1.version = 1;
        v1.explored = Map.of("d", Map.of("0", new int[][]{{1, 1}}));
        v1.minuteBuckets = new java.util.TreeMap<>();
        v1.minuteBuckets.put(100L, 5.0);
        PlayerQuotaData p = PlayerQuotaData.fromDto(v1);
        assertTrue(p.isExplored("d", chunk(1, 0)));
        assertEquals(0.0, p.spendInWindow(1, 100L * 60000 + 30000, 3600), 1e-9);
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
}
