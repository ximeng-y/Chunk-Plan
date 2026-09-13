package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 维度配置库（issue #3）：持久化、.bak 兜底、独立模式门槛、重定向解析、坐标校验 */
class DimensionStoreTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";

    @TempDir
    Path tmp;

    private static List<QuotaTiers.Tier> fourTiers() {
        return List.of(
                new QuotaTiers.Tier(true, "5h", 500.0),
                new QuotaTiers.Tier(true, "24h", 2000.0),
                new QuotaTiers.Tier(false, "7d", 10000.0),
                new QuotaTiers.Tier(false, "30d", 40000.0));
    }

    @Test
    void defaultsAreSharedModeWithoutRedirect() {
        DimensionStore store = new DimensionStore(tmp.resolve("dimensions.json"));
        assertEquals(DimensionStore.MODE_SHARED, store.mode());
        assertFalse(store.isIndependent());
        assertFalse(store.redirectOnExhaust());
        assertNull(store.redirectTarget(0));
        // 无条目默认计费
        assertTrue(store.isBillingEnabled(OVERWORLD));
        assertNull(store.spawn(OVERWORLD));
        assertNull(store.tiers(OVERWORLD));
    }

    @Test
    void roundTripPersistsAllFields() {
        Path file = tmp.resolve("dimensions.json");
        DimensionStore store = new DimensionStore(file);
        store.setMode(DimensionStore.MODE_INDEPENDENT);
        store.setRedirectOnExhaust(true);
        store.setRedirectTarget(0, NETHER);
        store.setRedirectTarget(2, END);
        store.setBilling(OVERWORLD, false);
        assertTrue(store.setSpawn(OVERWORLD, 1.5, 64.0, -3.0));
        assertTrue(store.setTiers(NETHER, fourTiers()));

        DimensionStore reloaded = new DimensionStore(file);
        assertTrue(reloaded.isIndependent());
        assertTrue(reloaded.redirectOnExhaust());
        assertEquals(NETHER, reloaded.redirectTarget(0));
        assertNull(reloaded.redirectTarget(1));
        assertEquals(END, reloaded.redirectTarget(2));
        assertFalse(reloaded.isBillingEnabled(OVERWORLD));
        assertEquals(new DimensionStore.SpawnPoint(1.5, 64.0, -3.0), reloaded.spawn(OVERWORLD));
        assertEquals(fourTiers(), reloaded.tiers(NETHER));
    }

    @Test
    void corruptedFileRestoresFromBackup() throws Exception {
        Path file = tmp.resolve("dimensions.json");
        DimensionStore store = new DimensionStore(file);
        store.setBilling(OVERWORLD, true);   // save #1
        store.setBilling(OVERWORLD, false);  // save #2：写前自动备份，.bak = save #1 的状态（计费开）
        Files.writeString(file, "{broken", StandardCharsets.UTF_8); // 损坏主文件

        DimensionStore reloaded = new DimensionStore(file);
        // 从 .bak（坑 #27 兜底）恢复：overworld 条目回到"计费开启"的上一版状态
        assertTrue(reloaded.configuredDimKeys().contains(OVERWORLD));
        assertTrue(reloaded.isBillingEnabled(OVERWORLD));
    }

    @Test
    void spawnValidationTpSpec() {
        DimensionStore store = new DimensionStore(tmp.resolve("dimensions.json"));
        assertTrue(store.setSpawn(OVERWORLD, 29999999, 320, -29999999));
        assertFalse(store.setSpawn(OVERWORLD, 30000001, 64, 0));      // x 越界
        assertFalse(store.setSpawn(OVERWORLD, 0, 64, 30000000.5));    // z 越界
        assertFalse(store.setSpawn(OVERWORLD, 0, -2049, 0));          // y 越下界
        assertFalse(store.setSpawn(OVERWORLD, 0, 4097, 0));           // y 越上界
        assertFalse(store.setSpawn(OVERWORLD, Double.NaN, 64, 0));
        assertFalse(store.setSpawn(OVERWORLD, Double.POSITIVE_INFINITY, 64, 0));
        // 静态方法与实例写入同口径（壳层 GUI 预校验共用）
        assertTrue(DimensionStore.isValidSpawn(-30000000, -2048, 30000000));
        assertFalse(DimensionStore.isValidSpawn(0, 5000, 0));
    }

    @Test
    void setTiersValidatesViaToLines() {
        DimensionStore store = new DimensionStore(tmp.resolve("dimensions.json"));
        // 启用档窗口不在预设内 -> toLines 告警非空 -> 拒绝（防应用时静默回退，坑 #44 同款）
        assertFalse(store.setTiers(OVERWORLD, List.of(
                new QuotaTiers.Tier(true, "13m", 100.0),
                new QuotaTiers.Tier(false, "24h", 2000.0),
                new QuotaTiers.Tier(false, "7d", 10000.0),
                new QuotaTiers.Tier(false, "30d", 40000.0))));
        // 数量不足 4 拒绝
        assertFalse(store.setTiers(OVERWORLD, fourTiers().subList(0, 2)));
        // 合法保存
        assertTrue(store.setTiers(OVERWORLD, fourTiers()));
        assertEquals(fourTiers(), store.tiers(OVERWORLD));
    }

    @Test
    void ensureTiersOnlyFillsMissingSnapshot() {
        DimensionStore store = new DimensionStore(tmp.resolve("dimensions.json"));
        List<QuotaTiers.Tier> snapshot = fourTiers();
        store.ensureTiers(OVERWORLD, snapshot);
        assertEquals(snapshot, store.tiers(OVERWORLD));
        // 已有快照不被覆盖
        List<QuotaTiers.Tier> other = List.of(
                new QuotaTiers.Tier(true, "1h", 10.0),
                new QuotaTiers.Tier(false, "24h", 2000.0),
                new QuotaTiers.Tier(false, "7d", 10000.0),
                new QuotaTiers.Tier(false, "30d", 40000.0));
        store.ensureTiers(OVERWORLD, other);
        assertEquals(snapshot, store.tiers(OVERWORLD));
    }

    @Test
    void validateIndependentReadyListsDimsWithoutSpawn() {
        DimensionStore store = new DimensionStore(tmp.resolve("dimensions.json"));
        store.setSpawn(OVERWORLD, 0, 64, 0);
        List<String> missing = store.validateIndependentReady(List.of(OVERWORLD, NETHER, END));
        assertEquals(List.of(NETHER, END), missing);
        store.setSpawn(NETHER, 0, 64, 0);
        store.setSpawn(END, 0, 64, 0);
        assertTrue(store.validateIndependentReady(List.of(OVERWORLD, NETHER, END)).isEmpty());
    }

    @Test
    void resolveRedirectTargetFollowsSlotsThenLexicographic() {
        DimensionStore store = new DimensionStore(tmp.resolve("dimensions.json"));
        store.setRedirectOnExhaust(true);
        for (String d : List.of(OVERWORLD, NETHER, END, "modid:custom")) {
            store.setSpawn(d, 0, 64, 0);
        }
        java.util.function.Predicate<String> enterable = NETHER::equals;

        // 3 槽全空：字典序轮询其余维度，命中唯一可进的 nether
        assertEquals(NETHER, store.resolveRedirectTarget(
                List.of(OVERWORLD, NETHER, END, "modid:custom"), enterable));

        // 槽位优先：首选 END（不可进，跳过）→ 次选 NETHER（可进，命中）
        store.setRedirectTarget(0, END);
        store.setRedirectTarget(1, NETHER);
        assertEquals(NETHER, store.resolveRedirectTarget(
                List.of(OVERWORLD, NETHER, END), enterable));

        // 全部不可进：null
        java.util.function.Predicate<String> none = d -> false;
        assertNull(store.resolveRedirectTarget(List.of(OVERWORLD, NETHER, END), none));

        // 开关关闭：恒 null
        store.setRedirectOnExhaust(false);
        assertNull(store.resolveRedirectTarget(List.of(OVERWORLD, NETHER, END), enterable));
        store.setRedirectOnExhaust(true);

        // 槽位指向不在 live 列表的维度：跳过（陈旧槽位无害）
        store.setRedirectTarget(0, "gone:dim");
        assertEquals(NETHER, store.resolveRedirectTarget(List.of(OVERWORLD, NETHER, END), enterable));

        // 无合法落地坐标的维度不是有效重定向目标
        DimensionStore store2 = new DimensionStore(tmp.resolve("d2.json"));
        store2.setRedirectOnExhaust(true);
        assertNull(store2.resolveRedirectTarget(List.of(NETHER), d -> true));
    }

    @Test
    void invalidModeRejected() {
        DimensionStore store = new DimensionStore(tmp.resolve("dimensions.json"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.setMode("weird"));
    }
}
