package dev.chunkplan.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChunkPlan 管理名单（{@code chunkplan-managed-bans.json}）：
 * 记录由 ChunkPlan 加的原版 ban，区分服主手动 ban，解 ban 时不误删。
 * 纯文件读写，加 ban/解 ban 的原版操作由壳层执行。
 */
public final class ManagedBanStore {

    private static final Logger LOG = LoggerFactory.getLogger(ManagedBanStore.class);

    public record Entry(UUID uuid, String reason, long expiresAtMillis) {
    }

    private final Path file;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public ManagedBanStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized void add(Entry entry) {
        entries.put(entry.uuid(), entry);
        save();
    }

    public synchronized boolean remove(UUID uuid) {
        if (entries.remove(uuid) != null) {
            save();
            return true;
        }
        return false;
    }

    public synchronized boolean contains(UUID uuid) {
        return entries.containsKey(uuid);
    }

    public synchronized List<Entry> all() {
        return List.copyOf(entries.values());
    }

    /** 移除已过期的条目（壳层解 ban 扫描时同步清理） */
    public synchronized List<UUID> removeExpired(long nowMillis) {
        List<UUID> removed = new ArrayList<>();
        boolean changed = entries.values().removeIf(e -> {
            if (e.expiresAtMillis() <= nowMillis) {
                removed.add(e.uuid());
                return true;
            }
            return false;
        });
        if (changed) {
            save();
        }
        return removed;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        // 坑 #27：损坏时从 .bak 兜底恢复（避免管理名单损坏导致 scanBans 失效、残留 ban 无人解除）
        Dto dto = AtomicFile.readJson(file, Dto.class, "管理名单", LOG);
        entries.clear();
        if (dto != null && dto.bans != null) {
            for (EntryDto e : dto.bans) {
                if (e == null || e.uuid == null) {
                    continue;
                }
                entries.put(e.uuid, new Entry(e.uuid, e.reason == null ? "" : e.reason, e.expiresAt));
            }
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Dto dto = new Dto();
            dto.bans = entries.values().stream()
                    .map(en -> new EntryDto(en.uuid(), en.reason(), en.expiresAtMillis()))
                    .toList();
            AtomicFile.write(file, GsonHolder.GSON.toJson(dto));
        } catch (IOException e) {
            LOG.error("写入管理名单 {} 失败", file, e);
        }
    }

    // ---------- JSON ----------

    private static final class Dto {
        List<EntryDto> bans;
    }

    private static final class EntryDto {
        UUID uuid;
        String reason;
        long expiresAt;

        EntryDto(UUID uuid, String reason, long expiresAt) {
            this.uuid = uuid;
            this.reason = reason;
            this.expiresAt = expiresAt;
        }
    }
}
