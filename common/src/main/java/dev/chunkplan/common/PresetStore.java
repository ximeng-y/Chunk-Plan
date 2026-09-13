package dev.chunkplan.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 预设库（{@code presets.json}）：命名额度线快照 + 按玩家分配（issue #1、#2）。
 *
 * <p>一个预设 = 四档额度线快照（各档 enabled/window/limit，恒 4 项含禁用档）；
 * 费率/高速倍率/豁免等不属于预设（保持全局）。{@code default} 不是存储条目，
 * 而是全局配置文件当前值的别名——"对全体玩家生效"即全局配置本身。
 * 分配（assignment）按 UUID 持久化，离线玩家可设置。
 *
 * <p>纯文件读写，全部 synchronized；变更即时落盘（ManagedBanStore 先例），
 * 读取经 {@link AtomicFile#readJson} 的 .bak 兜底（坑 #27）。
 * 额度线合法性复用 {@link QuotaTiers#toLines} 的校验（告警非空即非法），
 * 保证预设值应用于全局或个人时不会静默回退。
 */
public final class PresetStore {

    private static final Logger LOG = LoggerFactory.getLogger(PresetStore.class);

    /** 预设名规则：字母/数字/下划线/连字符，1~32 字符（补全友好、跨平台文件安全） */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    private static final int VERSION = 1;

    /** 命名预设：tiers 恒为 4 项（含禁用档，保留原值便于重新启用时还原） */
    public record Preset(String name, List<QuotaTiers.Tier> tiers) {
    }

    private final Path file;
    private final Map<String, Preset> presets = new LinkedHashMap<>();
    private final Map<UUID, String> assignments = new LinkedHashMap<>();

    public PresetStore(Path file) {
        this.file = file;
        load();
    }

    /**
     * 保存（或同名覆盖）预设。校验：名称规则、恰 4 档、启用档窗口在预设内且 limit 为正
     * （复用 toLines 校验，告警非空即拒绝——避免应用时静默回退）。非法返回 false 不落盘。
     */
    public synchronized boolean save(String name, List<QuotaTiers.Tier> tiers) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()
                || tiers == null || tiers.size() != 4) {
            return false;
        }
        for (QuotaTiers.Tier t : tiers) {
            if (t == null) {
                return false;
            }
        }
        List<String> warnings = new ArrayList<>();
        QuotaTiers.toLines(tiers, warnings);
        if (!warnings.isEmpty()) {
            return false;
        }
        presets.put(name, new Preset(name, List.copyOf(tiers)));
        save();
        return true;
    }

    /**
     * 删除预设并解除所有指向它的分配（这些玩家回落全局配置）。
     * 返回解除的分配数；预设不存在返回 -1。
     */
    public synchronized int delete(String name) {
        if (presets.remove(name) == null) {
            return -1;
        }
        int unassigned = 0;
        Iterator<Map.Entry<UUID, String>> it = assignments.entrySet().iterator();
        while (it.hasNext()) {
            if (name.equals(it.next().getValue())) {
                it.remove();
                unassigned++;
            }
        }
        save();
        return unassigned;
    }

    public synchronized Preset get(String name) {
        return name == null ? null : presets.get(name);
    }

    public synchronized boolean exists(String name) {
        return name != null && presets.containsKey(name);
    }

    /** 全部预设（名称排序，保证命令列表与 GUI 显示稳定） */
    public synchronized List<Preset> all() {
        return presets.values().stream()
                .sorted(Comparator.comparing(Preset::name))
                .toList();
    }

    /** 玩家当前分配的预设名；无分配（跟随全局 default）为 null */
    public synchronized String assignment(UUID uuid) {
        return assignments.get(uuid);
    }

    /** 设置/清除（null）玩家分配；预设名不存在时拒绝（返回 false） */
    public synchronized boolean assign(UUID uuid, String presetName) {
        if (presetName == null) {
            assignments.remove(uuid);
            save();
            return true;
        }
        if (!presets.containsKey(presetName)) {
            return false;
        }
        assignments.put(uuid, presetName);
        save();
        return true;
    }

    /** 分配快照（只读） */
    public synchronized Map<UUID, String> assignments() {
        return Map.copyOf(assignments);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        // 坑 #27：损坏时从 .bak 兜底恢复
        Dto dto = AtomicFile.readJson(file, Dto.class, "预设库", LOG);
        presets.clear();
        assignments.clear();
        if (dto == null) {
            return;
        }
        if (dto.version != VERSION) {
            LOG.warn("预设库版本不兼容（{}），将忽略现有内容", dto.version);
            return;
        }
        if (dto.presets != null) {
            for (Map.Entry<String, PresetDto> e : dto.presets.entrySet()) {
                String name = e.getKey();
                PresetDto p = e.getValue();
                if (name == null || p == null || p.tiers == null || p.tiers.size() != 4) {
                    continue;
                }
                List<QuotaTiers.Tier> tiers = new ArrayList<>(4);
                boolean ok = true;
                for (TierDto t : p.tiers) {
                    if (t == null) {
                        ok = false;
                        break;
                    }
                    tiers.add(new QuotaTiers.Tier(t.enabled, t.window == null ? "" : t.window, t.limit));
                }
                if (!ok || !save(name, tiers)) {
                    LOG.warn("预设 {} 非法（名称/档位/窗口/上限校验未通过），已忽略", name);
                }
            }
        }
        if (dto.assignments != null) {
            for (Map.Entry<String, String> e : dto.assignments.entrySet()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(e.getKey());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                // 指向缺失/非法预设的分配丢弃（玩家回落全局 default）
                if (presets.containsKey(e.getValue())) {
                    assignments.put(uuid, e.getValue());
                } else {
                    LOG.warn("玩家 {} 的预设分配指向不存在的预设 {}，已忽略", uuid, e.getValue());
                }
            }
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Dto dto = new Dto();
            dto.presets = new LinkedHashMap<>();
            for (Preset p : presets.values()) {
                PresetDto pd = new PresetDto();
                pd.tiers = p.tiers().stream()
                        .map(t -> new TierDto(t.enabled(), t.window(), t.limit()))
                        .toList();
                dto.presets.put(p.name(), pd);
            }
            dto.assignments = new LinkedHashMap<>();
            for (Map.Entry<UUID, String> e : assignments.entrySet()) {
                dto.assignments.put(e.getKey().toString(), e.getValue());
            }
            AtomicFile.write(file, GsonHolder.GSON.toJson(dto));
        } catch (IOException e) {
            LOG.error("写入预设库 {} 失败", file, e);
        }
    }

    // ---------- JSON ----------

    private static final class Dto {
        int version = VERSION;
        Map<String, PresetDto> presets;
        Map<String, String> assignments;
    }

    private static final class PresetDto {
        List<TierDto> tiers;
    }

    private static final class TierDto {
        boolean enabled;
        String window;
        double limit;

        TierDto(boolean enabled, String window, double limit) {
            this.enabled = enabled;
            this.window = window;
            this.limit = limit;
        }
    }
}
