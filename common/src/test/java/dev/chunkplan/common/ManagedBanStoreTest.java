package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedBanStoreTest {

    @TempDir
    Path tmp;

    @Test
    void addContainsRemove() {
        ManagedBanStore store = new ManagedBanStore(tmp.resolve("bans.json"));
        UUID u = UUID.randomUUID();
        assertFalse(store.contains(u));
        store.add(new ManagedBanStore.Entry(u, "reason", 1_000_000L));
        assertTrue(store.contains(u));
        assertEquals(1, store.all().size());
        assertEquals("reason", store.all().get(0).reason());
        assertTrue(store.remove(u));
        assertFalse(store.contains(u));
    }

    @Test
    void removeExpired() {
        ManagedBanStore store = new ManagedBanStore(tmp.resolve("bans.json"));
        UUID expired = UUID.randomUUID();
        UUID alive = UUID.randomUUID();
        store.add(new ManagedBanStore.Entry(expired, "r", 500L));
        store.add(new ManagedBanStore.Entry(alive, "r", 2_000L));
        List<UUID> removed = store.removeExpired(1_000L);
        assertEquals(List.of(expired), removed);
        assertTrue(store.contains(alive));
        assertFalse(store.contains(expired));
    }

    @Test
    void persistsAcrossInstances() {
        Path file = tmp.resolve("bans.json");
        UUID u = UUID.randomUUID();
        new ManagedBanStore(file).add(new ManagedBanStore.Entry(u, "额度耗尽", 9_999L));
        ManagedBanStore reloaded = new ManagedBanStore(file);
        assertTrue(reloaded.contains(u));
        assertEquals("额度耗尽", reloaded.all().get(0).reason());
        assertEquals(9_999L, reloaded.all().get(0).expiresAtMillis());
    }

    @Test
    void corruptedMainFileRestoresFromBackup() throws Exception {
        Path file = tmp.resolve("bans.json");
        UUID u = UUID.randomUUID();
        new ManagedBanStore(file).add(new ManagedBanStore.Entry(u, "额度耗尽", 9_999L));
        // 模拟写前备份留下的上一版好数据，然后损坏主文件
        Files.copy(file, tmp.resolve("bans.json.bak"));
        Files.writeString(file, "{broken json", StandardCharsets.UTF_8);

        // 重新加载：从 .bak 恢复条目（坑 #27，避免管理名单损坏导致 scanBans 失效）
        ManagedBanStore reloaded = new ManagedBanStore(file);
        assertTrue(reloaded.contains(u));
        assertEquals("额度耗尽", reloaded.all().get(0).reason());
        assertEquals(9_999L, reloaded.all().get(0).expiresAtMillis());
    }
}
