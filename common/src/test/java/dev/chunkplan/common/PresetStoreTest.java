package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 预设库测试（issue #1）：存储 roundtrip、校验、分配管理与 .bak 兜底。
 */
class PresetStoreTest {

    @TempDir
    Path tmp;

    private static List<QuotaTiers.Tier> tiers(boolean e1, boolean e2, boolean e3, boolean e4) {
        return List.of(
                new QuotaTiers.Tier(e1, "5h", 500.0),
                new QuotaTiers.Tier(e2, "24h", 2000.0),
                new QuotaTiers.Tier(e3, "7d", 10000.0),
                new QuotaTiers.Tier(e4, "30d", 40000.0));
    }

    @Test
    void saveGetRoundtripAndPersistence() {
        Path file = tmp.resolve("presets.json");
        PresetStore store = new PresetStore(file);
        assertTrue(store.save("pvp", tiers(true, true, false, false)));
        assertTrue(store.exists("pvp"));
        assertEquals(1, store.all().size());
        assertEquals("pvp", store.all().get(0).name());
        assertEquals(tiers(true, true, false, false), store.all().get(0).tiers());

        // 重新加载（同一文件）：内容一致
        PresetStore store2 = new PresetStore(file);
        assertTrue(store2.exists("pvp"));
        assertEquals(tiers(true, true, false, false), store2.get("pvp").tiers());
    }

    @Test
    void saveRejectsInvalidName() {
        PresetStore store = new PresetStore(tmp.resolve("presets.json"));
        assertFalse(store.save(null, tiers(true, false, false, false)));
        assertFalse(store.save("", tiers(true, false, false, false)));
        assertFalse(store.save("含中文", tiers(true, false, false, false)));
        assertFalse(store.save("has space", tiers(true, false, false, false)));
        assertFalse(store.save("a".repeat(33), tiers(true, false, false, false)));
        assertFalse(store.exists(""));
    }

    @Test
    void saveRejectsInvalidTiers() {
        PresetStore store = new PresetStore(tmp.resolve("presets.json"));
        // 数量不是 4
        assertFalse(store.save("bad1", List.of(new QuotaTiers.Tier(true, "5h", 500.0))));
        // null 元素
        assertFalse(store.save("bad2", java.util.Arrays.asList(
                new QuotaTiers.Tier(true, "5h", 500.0), null,
                new QuotaTiers.Tier(false, "7d", 10000.0),
                new QuotaTiers.Tier(false, "30d", 40000.0))));
        // 启用档窗口不在预设内
        assertFalse(store.save("bad3", List.of(
                new QuotaTiers.Tier(true, "17m", 500.0),
                new QuotaTiers.Tier(false, "24h", 2000.0),
                new QuotaTiers.Tier(false, "7d", 10000.0),
                new QuotaTiers.Tier(false, "30d", 40000.0))));
        // 启用档 limit 非正
        assertFalse(store.save("bad4", List.of(
                new QuotaTiers.Tier(true, "5h", 0),
                new QuotaTiers.Tier(false, "24h", 2000.0),
                new QuotaTiers.Tier(false, "7d", 10000.0),
                new QuotaTiers.Tier(false, "30d", 40000.0))));
        assertFalse(store.exists("bad3"));
    }

    @Test
    void saveAllowsAllDisabledPreset() {
        // 全禁预设 = 按玩家零线（合法，不告警）
        PresetStore store = new PresetStore(tmp.resolve("presets.json"));
        assertTrue(store.save("peace", tiers(false, false, false, false)));
        assertTrue(store.exists("peace"));
    }

    @Test
    void saveOverwritesSameName() {
        PresetStore store = new PresetStore(tmp.resolve("presets.json"));
        store.save("p", tiers(true, false, false, false));
        assertTrue(store.save("p", tiers(true, true, true, true)));
        assertEquals(1, store.all().size());
        assertEquals(tiers(true, true, true, true), store.get("p").tiers());
    }

    @Test
    void deleteReturnsUnassignedCountAndClears() {
        PresetStore store = new PresetStore(tmp.resolve("presets.json"));
        store.save("p", tiers(true, false, false, false));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        assertTrue(store.assign(a, "p"));
        assertTrue(store.assign(b, "p"));
        assertTrue(store.assign(c, null)); // 无分配

        assertEquals(2, store.delete("p"));
        assertNull(store.assignment(a));
        assertNull(store.assignment(b));
        assertEquals(-1, store.delete("p")); // 再删不存在

        // 分配的解除已落盘
        PresetStore store2 = new PresetStore(tmp.resolve("presets.json"));
        assertFalse(store2.exists("p"));
        assertNull(store2.assignment(a));
    }

    @Test
    void assignRejectsMissingPresetAndClears() {
        PresetStore store = new PresetStore(tmp.resolve("presets.json"));
        UUID uuid = UUID.randomUUID();
        assertFalse(store.assign(uuid, "nope"));
        store.save("p", tiers(true, false, false, false));
        assertTrue(store.assign(uuid, "p"));
        assertEquals("p", store.assignment(uuid));
        assertTrue(store.assign(uuid, null));
        assertNull(store.assignment(uuid));
    }

    @Test
    void loadDropsAssignmentToMissingPreset() throws Exception {
        Path file = tmp.resolve("presets.json");
        PresetStore store = new PresetStore(file);
        store.save("p", tiers(true, false, false, false));
        UUID orphan = UUID.randomUUID();
        // 手工注入一条指向缺失预设的分配 + 一条非法 UUID 键（此时 assignments 为空对象）
        String json = Files.readString(file, StandardCharsets.UTF_8);
        json = json.replace("\"assignments\":{}",
                "\"assignments\":{\"not-a-uuid\":\"p\",\"" + orphan + "\":\"ghost\"}");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        PresetStore store2 = new PresetStore(file);
        assertTrue(store2.exists("p"));
        assertNull(store2.assignment(orphan));
        assertEquals(Map.of(), store2.assignments());
    }

    @Test
    void loadSkipsInvalidPresetEntries() throws Exception {
        Path file = tmp.resolve("presets.json");
        PresetStore store = new PresetStore(file);
        store.save("good", tiers(true, false, false, false));
        String json = Files.readString(file, StandardCharsets.UTF_8);
        // 注入一个窗口非法的预设（应用时会静默回退，正是存储层要拦的）
        json = json.replace("\"presets\":{", "\"presets\":{\"bad\":{\"tiers\":["
                + "{\"enabled\":true,\"window\":\"banana\",\"limit\":1.0},"
                + "{\"enabled\":false,\"window\":\"24h\",\"limit\":2000.0},"
                + "{\"enabled\":false,\"window\":\"7d\",\"limit\":10000.0},"
                + "{\"enabled\":false,\"window\":\"30d\",\"limit\":40000.0}]},");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        PresetStore store2 = new PresetStore(file);
        assertTrue(store2.exists("good"));
        assertFalse(store2.exists("bad"));
    }

    @Test
    void bakRecoveryOnCorruptMainFile() throws Exception {
        Path file = tmp.resolve("presets.json");
        PresetStore store = new PresetStore(file);
        store.save("p", tiers(true, false, false, false));
        UUID uuid = UUID.randomUUID();
        store.assign(uuid, "p");

        // 模拟主文件损坏、.bak 完好（坑 #27 语义：写前备份为上一份完好数据）
        Files.write(tmp.resolve("presets.json.bak"), Files.readAllBytes(file));
        Files.write(file, "{corrupt".getBytes(StandardCharsets.UTF_8));

        PresetStore store2 = new PresetStore(file);
        assertTrue(store2.exists("p"));
        assertEquals("p", store2.assignment(uuid));
        // 恢复写回主文件（readJson 的修复现场行为）
        assertTrue(Files.exists(file));
        PresetStore store3 = new PresetStore(file);
        assertTrue(store3.exists("p"));
    }
}
