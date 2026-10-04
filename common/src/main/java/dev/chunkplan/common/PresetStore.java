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
 * 预设库（{@code presets.json}）：命名计费策略快照 + 按玩家分配（issue #1/#2/#13/#14）。
 *
 * <p>v2 起一个预设 = {@link BillingPolicy}（shared/independent、完整维度配置、重定向 3 槽）。
 * 费率/高速倍率/豁免仍属于全局配置，不属于预设。{@code default} 不是存储条目，而是全局
 * 配置当前值的别名。
 *
 * <p>v1 -&gt; v2 迁移一次性完成：先读取并校验全部条目，再统一落盘一次，避免旧实现逐条
 * {@code save()} 覆盖 assignments 与 .bak；迁移时旧预设按升级前服务器 mode 转换：
 * shared -&gt; shared policy；independent -&gt; 复制当时全服维度 billing/spawn/redirect/order，
 * 各维度 tiers 替换为该旧预设四档。未知未来版本拒绝写回。
 */
public final class PresetStore {

    private static final Logger LOG = LoggerFactory.getLogger(PresetStore.class);

    /**
     * 预设名规则：1~32 字符，允许中文等任意可打印字符，排除文件系统/命令/JSON 层面的危险字符
     * ——空白与 Unicode 分隔符（命令分词）、双引号与反斜杠（Brigadier 转义）、控制与格式字符（JSON/日志）。
     */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[^\\p{C}\\p{Z}\"\\\\]{1,32}$");

    /** 保留名：{@code default} 是全局配置的别名（非存储条目）。 */
    public static final String RESERVED_NAME = "default";

    /** delete：预设不存在（兼容旧返回值）。 */
    public static final int DELETE_NOT_FOUND = -1;
    /** delete：删除前校验通过，但落盘失败；内存已回滚，分配关系不变。 */
    public static final int DELETE_SAVE_FAILED = -2;

    private static final int VERSION = 2;

    /** 命名预设：policy 是其完整计费策略；tiers() 兼容只读旧调用。 */
    public record Preset(String name, BillingPolicy policy) {
        public List<QuotaTiers.Tier> tiers() {
            return policy.sharedTiers();
        }
    }

    private final Path file;
    private final DimensionStore legacyDimStore;
    private final Map<String, Preset> presets = new LinkedHashMap<>();
    private final Map<UUID, String> assignments = new LinkedHashMap<>();
    /** 未知未来版本只读：阻止任何显式/隐式写回覆盖新格式。 */
    private boolean readOnly;

    public PresetStore(Path file) {
        this(file, null);
    }

    /**
     * @param legacyDimStore v1 迁移时用于复制“升级前全服维度配置”；正常可传引擎持有的
     *                       {@link DimensionStore}，测试/独立场景可为 null（退化为 shared）
     */
    public PresetStore(Path file, DimensionStore legacyDimStore) {
        this.file = file;
        this.legacyDimStore = legacyDimStore;
        load();
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches() && !RESERVED_NAME.equalsIgnoreCase(name);
    }

    /**
     * 保存（或同名覆盖）完整计费策略。名称/policy 校验失败返回 false 不落盘。
     * 同名覆盖会立即更新已分配玩家（由 {@link QuotaEngine} 调用方负责刷新缓存）。
     */
    public synchronized boolean savePolicy(String name, BillingPolicy policy) {
        if (readOnly || !isValidName(name) || policy == null) {
            return false;
        }
        List<String> warnings = policy.validate();
        if (!warnings.isEmpty()) {
            LOG.warn("预设 {} 的 policy 非法，已拒绝：{}", name, String.join("；", warnings));
            return false;
        }
        State before = snapshotState();
        presets.put(name, new Preset(name, policy));
        if (!save()) {
            restoreState(before);
            return false;
        }
        return true;
    }

    /**
     * 兼容旧调用的 shared 预设保存：四档来自参数，维度 billing/spawn/redirect 取当前全服快照。
     * 新代码应使用 {@link #savePolicy(String, BillingPolicy)} 明确保存 shared/independent。
     */
    public synchronized boolean save(String name, List<QuotaTiers.Tier> tiers) {
        if (readOnly || !isValidName(name) || tiers == null || tiers.size() != 4) {
            return false;
        }
        for (QuotaTiers.Tier t : tiers) {
            if (t == null) {
                return false;
            }
        }
        BillingPolicy policy = legacyDimStore == null
                ? new BillingPolicy(
                        BillingPolicy.MODE_SHARED,
                        tiers,
                        Map.of(),
                        false,
                        BillingPolicy.emptyRedirectOrder())
                : new BillingPolicy(
                        BillingPolicy.MODE_SHARED,
                        tiers,
                        legacyDimStore.snapshotDimensions(),
                        legacyDimStore.redirectOnExhaust(),
                        legacyDimStore.redirectOrder());
        return savePolicy(name, policy);
    }

    /**
     * 删除预设并解除所有指向它的分配（这些玩家回落全局 default）。
     * 返回解除的分配数；预设不存在返回 {@link #DELETE_NOT_FOUND}，落盘失败返回
     * {@link #DELETE_SAVE_FAILED} 且内存状态不变。
     */
    public synchronized int delete(String name) {
        if (readOnly) {
            return DELETE_NOT_FOUND;
        }
        State before = snapshotState();
        if (presets.remove(name) == null) {
            return DELETE_NOT_FOUND;
        }
        int unassigned = 0;
        Iterator<Map.Entry<UUID, String>> it = assignments.entrySet().iterator();
        while (it.hasNext()) {
            if (name.equals(it.next().getValue())) {
                it.remove();
                unassigned++;
            }
        }
        if (!save()) {
            restoreState(before);
            return DELETE_SAVE_FAILED;
        }
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
        if (readOnly) {
            return false;
        }
        State before = snapshotState();
        if (presetName == null) {
            assignments.remove(uuid);
        } else if (!presets.containsKey(presetName)) {
            return false;
        } else {
            assignments.put(uuid, presetName);
        }
        if (!save()) {
            restoreState(before);
            return false;
        }
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
        if (dto == null) {
            return;
        }
        if (dto.version != VERSION && dto.version != 1) {
            LOG.warn("预设库版本不兼容（{}），将保持只读且不覆盖原文件", dto.version);
            readOnly = true;
            return;
        }

        Map<String, Preset> loadedPresets = new LinkedHashMap<>();
        Map<UUID, String> loadedAssignments = new LinkedHashMap<>();
        boolean migrate = dto.version == 1;

        if (dto.presets != null) {
            for (Map.Entry<String, PresetDto> e : dto.presets.entrySet()) {
                String name = e.getKey();
                PresetDto p = e.getValue();
                if (name == null || p == null || !isValidName(name)) {
                    LOG.warn("预设名称非法或条目为空，已忽略：{}", name);
                    continue;
                }
                BillingPolicy policy;
                if (migrate) {
                    List<QuotaTiers.Tier> tiers = parseTiers(p.tiers);
                    if (tiers == null) {
                        LOG.warn("迁移预设 {} 失败：v1 tiers 缺失或非法，已忽略", name);
                        continue;
                    }
                    policy = legacyDimStore == null
                            ? new BillingPolicy(
                                    BillingPolicy.MODE_SHARED,
                                    tiers,
                                    Map.of(),
                                    false,
                                    BillingPolicy.emptyRedirectOrder())
                            : legacyDimStore.legacyPolicy(tiers);
                } else {
                    policy = parsePolicy(p.policy);
                }
                if (policy == null || !policy.validate().isEmpty()) {
                    LOG.warn("预设 {} 的 policy 非法，已忽略：{}", name,
                            policy == null ? "policy 缺失" : String.join("；", policy.validate()));
                    continue;
                }
                loadedPresets.put(name, new Preset(name, policy));
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
                if (loadedPresets.containsKey(e.getValue())) {
                    loadedAssignments.put(uuid, e.getValue());
                } else {
                    LOG.warn("玩家 {} 的预设分配指向不存在的预设 {}，已忽略", uuid, e.getValue());
                }
            }
        }

        presets.clear();
        presets.putAll(loadedPresets);
        assignments.clear();
        assignments.putAll(loadedAssignments);

        if (migrate) {
            // 关键：读/校验/转换全部完成后统一落盘一次，保留 assignments 与 .bak 语义
            if (!save()) {
                LOG.error("预设库 v1 -> v2 迁移落盘失败，保留原文件，重启后将再次尝试迁移");
            }
        }
    }

    private boolean save() {
        if (readOnly) {
            return false;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Dto dto = new Dto();
            dto.version = VERSION;
            dto.presets = new LinkedHashMap<>();
            for (Preset p : presets.values()) {
                PresetDto pd = new PresetDto();
                pd.policy = toDto(p.policy());
                dto.presets.put(p.name(), pd);
            }
            dto.assignments = new LinkedHashMap<>();
            for (Map.Entry<UUID, String> e : assignments.entrySet()) {
                dto.assignments.put(e.getKey().toString(), e.getValue());
            }
            AtomicFile.write(file, GsonHolder.GSON.toJson(dto));
            return true;
        } catch (IOException e) {
            LOG.error("写入预设库 {} 失败", file, e);
            return false;
        }
    }

    private State snapshotState() {
        return new State(new LinkedHashMap<>(presets), new LinkedHashMap<>(assignments));
    }

    private void restoreState(State state) {
        presets.clear();
        presets.putAll(state.presets);
        assignments.clear();
        assignments.putAll(state.assignments);
    }

    // ---------- JSON ----------

    private static List<QuotaTiers.Tier> parseTiers(List<TierDto> list) {
        if (list == null || list.size() != 4) {
            return null;
        }
        List<QuotaTiers.Tier> out = new ArrayList<>(4);
        for (TierDto t : list) {
            if (t == null) {
                return null;
            }
            out.add(new QuotaTiers.Tier(t.enabled, t.window == null ? "" : t.window, t.limit));
        }
        return out;
    }

    private static BillingPolicy parsePolicy(BillingPolicyDto dto) {
        if (dto == null) {
            return null;
        }
        List<QuotaTiers.Tier> shared = parseTiers(dto.sharedTiers);
        if (shared == null) {
            return null;
        }
        Map<String, DimensionStore.DimConfig> dims = new LinkedHashMap<>();
        if (dto.dimensions != null) {
            for (Map.Entry<String, DimDto> e : dto.dimensions.entrySet()) {
                String key = e.getKey();
                DimDto d = e.getValue();
                if (key == null || key.isEmpty() || d == null) {
                    return null;
                }
                DimensionStore.SpawnPoint spawn = parseSpawn(d.spawn);
                if (d.spawn != null && spawn == null) {
                    return null;
                }
                List<QuotaTiers.Tier> tiers = d.tiers == null ? null : parseTiers(d.tiers);
                if (d.tiers != null && tiers == null) {
                    return null;
                }
                dims.put(key, new DimensionStore.DimConfig(d.billing, spawn, tiers));
            }
        }
        return new BillingPolicy(dto.mode, shared, dims, dto.redirectOnExhaust, dto.redirectOrder);
    }

    private static DimensionStore.SpawnPoint parseSpawn(double[] a) {
        if (a == null || a.length != 3) {
            return null;
        }
        return DimensionStore.isValidSpawn(a[0], a[1], a[2])
                ? new DimensionStore.SpawnPoint(a[0], a[1], a[2])
                : null;
    }

    private static BillingPolicyDto toDto(BillingPolicy policy) {
        BillingPolicyDto dto = new BillingPolicyDto();
        dto.mode = policy.mode();
        dto.sharedTiers = toTierDtos(policy.sharedTiers());
        dto.dimensions = new LinkedHashMap<>();
        for (Map.Entry<String, DimensionStore.DimConfig> e : policy.dimensions().entrySet()) {
            DimensionStore.DimConfig d = e.getValue();
            DimDto dd = new DimDto();
            dd.billing = d.billing();
            dd.spawn = d.spawn() == null
                    ? null
                    : new double[]{d.spawn().x(), d.spawn().y(), d.spawn().z()};
            dd.tiers = toTierDtos(d.tiers());
            dto.dimensions.put(e.getKey(), dd);
        }
        dto.redirectOnExhaust = policy.redirectOnExhaust();
        dto.redirectOrder = new ArrayList<>(policy.redirectOrder());
        return dto;
    }

    private static List<TierDto> toTierDtos(List<QuotaTiers.Tier> tiers) {
        if (tiers == null) {
            return null;
        }
        List<TierDto> out = new ArrayList<>(tiers.size());
        for (QuotaTiers.Tier t : tiers) {
            out.add(t == null ? null : new TierDto(t.enabled(), t.window(), t.limit()));
        }
        return out;
    }

    private static final class Dto {
        int version = VERSION;
        Map<String, PresetDto> presets;
        Map<String, String> assignments;
    }

    private static final class State {
        final Map<String, Preset> presets;
        final Map<UUID, String> assignments;

        State(Map<String, Preset> presets, Map<UUID, String> assignments) {
            this.presets = presets;
            this.assignments = assignments;
        }
    }

    private static final class PresetDto {
        /** v1 字段 */
        List<TierDto> tiers;
        /** v2 字段 */
        BillingPolicyDto policy;
    }

    private static final class BillingPolicyDto {
        String mode;
        List<TierDto> sharedTiers;
        Map<String, DimDto> dimensions;
        boolean redirectOnExhaust;
        List<String> redirectOrder;
    }

    private static final class DimDto {
        boolean billing = true;
        double[] spawn;
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
