package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PlayerQuotaDataTest {

    @Test
    void jsonRoundTrip() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.markExplored("minecraft:overworld", ChunkPosPacker.pack(1, 2));
        p.markExplored("minecraft:overworld", ChunkPosPacker.pack(3, -4));
        p.markExplored("minecraft:the_nether", ChunkPosPacker.pack(-5, 6));
        p.addSpend(1000L, 1.0);
        p.addSpend(1000L, 0.5);
        p.addSpend(1001L, 0.05);

        String json = GsonHolder.GSON.toJson(p.toDto());
        PlayerQuotaData back = PlayerQuotaData.fromDto(
                GsonHolder.GSON.fromJson(json, PlayerQuotaData.Dto.class));

        assertEquals(2, back.explored("minecraft:overworld").size());
        assertTrue(back.explored("minecraft:overworld").contains(ChunkPosPacker.pack(1, 2)));
        assertTrue(back.explored("minecraft:overworld").contains(ChunkPosPacker.pack(3, -4)));
        assertEquals(1, back.explored("minecraft:the_nether").size());

        long now = 1001L * 60000 + 30000;
        assertEquals(1.55, back.spendInWindow(now, 3600), 1e-9);
    }

    @Test
    void windowBoundary() {
        PlayerQuotaData p = new PlayerQuotaData();
        // 桶 100 消费 1.0，桶 101 消费 2.0；now=101.5min 时刻，窗口 60s
        p.addSpend(100, 1.0);
        p.addSpend(101, 2.0);
        long nowMillis = 101L * 60000 + 30000; // 101.5 分钟
        // 窗口 (100.5min, 101.5min] -> 只含桶 101
        assertEquals(2.0, p.spendInWindow(nowMillis, 60), 1e-9);
        // 窗口 120s -> (100.5min, 101.5min] 同样只含桶 101？(101.5-2)min=99.5 -> 含桶 100、101
        assertEquals(3.0, p.spendInWindow(nowMillis, 120), 1e-9);
    }

    @Test
    void cleanupOldBuckets() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.addSpend(100, 1.0);
        p.addSpend(200, 2.0);
        assertTrue(p.cleanupBucketsBefore(150));
        assertEquals(2.0, p.spendInWindow(200L * 60000 + 1000, 3600), 1e-9);
        assertFalse(p.cleanupBucketsBefore(150)); // 已清理，无需再清理
    }

    @Test
    void fromDtoToleratesNulls() {
        PlayerQuotaData p = PlayerQuotaData.fromDto(new PlayerQuotaData.Dto());
        assertTrue(p.explored("x").isEmpty());
        assertEquals(0.0, p.spendInWindow(System.currentTimeMillis(), 60), 1e-9);
    }

    @Test
    void toJsonStructure() {
        PlayerQuotaData p = new PlayerQuotaData();
        p.markExplored("minecraft:overworld", ChunkPosPacker.pack(1, 2));
        p.addSpend(1000, 3.5);
        String json = GsonHolder.GSON.toJson(p.toDto());
        Map<?, ?> parsed = GsonHolder.GSON.fromJson(json, Map.class);
        assertEquals(1, ((Number) parsed.get("version")).intValue());
        assertTrue(parsed.containsKey("explored"));
        assertTrue(parsed.containsKey("minuteBuckets"));
    }
}
