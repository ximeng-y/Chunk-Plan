package dev.chunkplan.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 维度计费配置库（{@code dimensions.json}，issue #3）：维度独立计费的全套配置。
 *
 * <p>内容：模式（shared 共享全局额度线 / independent 每维度独立四档）、每维度计费开关
 * 与默认落地坐标（/tp 数据规范）、每维度四档额度线快照、耗尽重定向（开关 + 3 个固定槽）。
 *
 * <p>为什么独立存 JSON 而不进各端主配置文件：四端主配置格式不同（TOML/JSON），维度是
 * 动态集合（Map 结构），NightConfig TOML 不支持 Map 作列表元素（坑 #2），且双格式漂移
 * 风险高——独立 JSON 由引擎自建（仿 PresetStore，坑 #44），壳层零接线、四端天然一致，
 * 读写统一走 {@link AtomicFile}（写前备份 + .bak 兜底，坑 #27）。变更即时落盘
 * （ManagedBanStore 先例），全部 synchronized。
 */
public final class DimensionStore {

    private static final Logger LOG = LoggerFactory.getLogger(DimensionStore.class);

    public static final String MODE_SHARED = "shared";
    public static final String MODE_INDEPENDENT = "independent";

    private static final int VERSION = 1;

    /** 默认落地坐标合法域（/tp 数据规范）：x/z 受世界边界约束 ±30000000；y 覆盖原版 -64~320 并为模组维度留裕量 */
    private static final double HORIZONTAL_BOUND = 30000000.0;
    private static final double Y_MIN = -2048.0;
    private static final double Y_MAX = 4096.0;

    /** 落地坐标（传送落点；传送朝向由壳层保持玩家原朝向） */
    public record SpawnPoint(double x, double y, double z) {
    }

    /** 单维度配置：billing=false = 该维度不计费（可自由进入，issue 评论"不限制探索的世界"）；spawn=null 未设置；tiers=null 未初始化快照（恒 4 项） */
    public record DimConfig(boolean billing, SpawnPoint spawn, List<QuotaTiers.Tier> tiers) {
    }

    private final Path file;
    private String mode = MODE_SHARED;
    private boolean redirectOnExhaust;
    /** 3 个固定槽（0=首选 1=次选 2=备选），元素可为 null（空槽跳过） */
    private final List<String> redirectOrder = new ArrayList<>(java.util.Arrays.asList(null, null, null));
    private final Map<String, DimConfig> dims = new LinkedHashMap<>();

    public DimensionStore(Path file) {
        this.file = file;
        load();
    }

    /** 落地坐标是否合法（/tp 数据规范，壳层 GUI 与命令共用同一判定） */
    public static boolean isValidSpawn(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) <= HORIZONTAL_BOUND && Math.abs(z) <= HORIZONTAL_BOUND
                && y >= Y_MIN && y <= Y_MAX;
    }

    public synchronized String mode() {
        return mode;
    }

    public synchronized boolean isIndependent() {
        return MODE_INDEPENDENT.equals(mode);
    }

    /** 切换模式（仅写模式字段；快照初始化由引擎在切换到 independent 前完成） */
    public synchronized void setMode(String mode) {
        if (!MODE_SHARED.equals(mode) && !MODE_INDEPENDENT.equals(mode)) {
            throw new IllegalArgumentException("非法维度模式: " + mode);
        }
        this.mode = mode;
        save();
    }

    public synchronized boolean redirectOnExhaust() {
        return redirectOnExhaust;
    }

    public synchronized void setRedirectOnExhaust(boolean v) {
        this.redirectOnExhaust = v;
        save();
    }

    /** 重定向槽位快照（3 项，元素可为 null，只读） */
    public synchronized List<String> redirectOrder() {
        return List.copyOf(redirectOrder);
    }

    public synchronized String redirectTarget(int slot) {
        if (slot < 0 || slot >= redirectOrder.size()) {
            return null;
        }
        return redirectOrder.get(slot);
    }

    /** 设置重定向槽位（slot 0~2；dim 传 null 清空该槽；不校验维度是否存在于世界，壳层负责候选范围） */
    public synchronized void setRedirectTarget(int slot, String dim) {
        if (slot < 0 || slot >= redirectOrder.size()) {
            throw new IllegalArgumentException("非法重定向槽位: " + slot);
        }
        redirectOrder.set(slot, dim == null || dim.isEmpty() ? null : dim);
        save();
    }

    /** 该维度是否计费（无条目默认计费=true，两种模式同语义） */
    public synchronized boolean isBillingEnabled(String dimKey) {
        DimConfig d = dims.get(dimKey);
        return d == null || d.billing();
    }

    public synchronized SpawnPoint spawn(String dimKey) {
        DimConfig d = dims.get(dimKey);
        return d == null ? null : d.spawn();
    }

    /** 该维度的四档原始配置（未初始化快照或无条目返回 null） */
    public synchronized List<QuotaTiers.Tier> tiers(String dimKey) {
        DimConfig d = dims.get(dimKey);
        return d == null ? null : d.tiers();
    }

    /** 已有配置条目的维度 key（含仅设过计费开关/坐标的；GUI 管理配置用） */
    public synchronized List<String> configuredDimKeys() {
        return List.copyOf(dims.keySet());
    }

    /** 设置维度计费开关（条目不存在则创建：billing 默认外的首个字段） */
    public synchronized void setBilling(String dimKey, boolean billing) {
        DimConfig d = dims.get(dimKey);
        if (d == null) {
            dims.put(dimKey, new DimConfig(billing, null, null));
        } else {
            dims.put(dimKey, new DimConfig(billing, d.spawn(), d.tiers()));
        }
        save();
    }

    /** 设置落地坐标；非法（/tp 规范）返回 false 不落盘 */
    public synchronized boolean setSpawn(String dimKey, double x, double y, double z) {
        if (!isValidSpawn(x, y, z)) {
            return false;
        }
        DimConfig d = dims.get(dimKey);
        SpawnPoint spawn = new SpawnPoint(x, y, z);
        if (d == null) {
            dims.put(dimKey, new DimConfig(true, spawn, null));
        } else {
            dims.put(dimKey, new DimConfig(d.billing(), spawn, d.tiers()));
        }
        save();
        return true;
    }

    /**
     * 设置维度四档额度线（恒 4 项）。档位合法性复用 {@link QuotaTiers#toLines} 校验
     * （告警非空即拒绝——防应用时静默回退，坑 #44 同款），失败返回 false 不落盘。
     */
    public synchronized boolean setTiers(String dimKey, List<QuotaTiers.Tier> tiers) {
        if (dimKey == null || dimKey.isEmpty() || tiers == null || tiers.size() != 4) {
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
        DimConfig d = dims.get(dimKey);
        List<QuotaTiers.Tier> copy = List.copyOf(tiers);
        if (d == null) {
            dims.put(dimKey, new DimConfig(true, null, copy));
        } else {
            dims.put(dimKey, new DimConfig(d.billing(), d.spawn(), copy));
        }
        save();
        return true;
    }

    /** 仅当该维度缺失或未初始化快照时写入 tiers（模式切换/未知维度首次进入时的快照初始化） */
    public synchronized void ensureTiers(String dimKey, List<QuotaTiers.Tier> tiers) {
        DimConfig d = dims.get(dimKey);
        if (d != null && d.tiers() != null) {
            return;
        }
        List<QuotaTiers.Tier> copy = List.copyOf(tiers);
        if (d == null) {
            dims.put(dimKey, new DimConfig(true, null, copy));
        } else {
            dims.put(dimKey, new DimConfig(d.billing(), d.spawn(), copy));
        }
        save();
    }

    /**
     * 启用独立模式的门槛校验：返回缺少合法落地坐标的 live 维度列表（空 = 可切换）。
     * 已从世界消失的 store 条目不参与（陈旧条目无害）。
     */
    public synchronized List<String> validateIndependentReady(List<String> liveDims) {
        List<String> missing = new ArrayList<>();
        for (String dim : liveDims) {
            if (spawn(dim) == null) {
                missing.add(dim);
            }
        }
        return missing;
    }

    /**
     * 重定向目标解析（耗尽时调用，独立模式）：
     * 依次轮询 3 个固定槽（空槽/不在 live 维度跳过）→ 其余 live 维度按 key 字典序；
     * 命中第一个"可进且已有合法落地坐标"的维度；重定向开关关闭或全部不可进返回 null。
     * 当前维度无需显式排除——玩家被重定向正因其不可进，enterable 判定天然为 false。
     */
    public String resolveRedirectTarget(List<String> liveDims, Predicate<String> enterable) {
        if (!redirectOnExhaust()) {
            return null;
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (int slot = 0; slot < redirectOrder.size(); slot++) {
            String d = redirectTarget(slot);
            if (d != null && liveDims.contains(d)) {
                candidates.add(d);
            }
        }
        List<String> rest = new ArrayList<>(liveDims);
        rest.removeAll(candidates);
        Collections.sort(rest);
        candidates.addAll(rest);
        for (String d : candidates) {
            if (enterable.test(d) && spawn(d) != null) {
                return d;
            }
        }
        return null;
    }

    // ---------- 序列化 ----------

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        // 坑 #27：损坏时从 .bak 兜底恢复
        Dto dto = AtomicFile.readJson(file, Dto.class, "维度配置库", LOG);
        if (dto == null) {
            return;
        }
        if (dto.version != VERSION) {
            LOG.warn("维度配置库版本不兼容（{}），将忽略现有内容", dto.version);
            return;
        }
        if (dto.mode != null && (MODE_SHARED.equals(dto.mode) || MODE_INDEPENDENT.equals(dto.mode))) {
            mode = dto.mode;
        } else {
            LOG.warn("维度配置库模式非法（{}），回退 shared", dto.mode);
        }
        redirectOnExhaust = dto.redirectOnExhaust;
        if (dto.redirectOrder != null) {
            for (int i = 0; i < redirectOrder.size() && i < dto.redirectOrder.size(); i++) {
                String d = dto.redirectOrder.get(i);
                redirectOrder.set(i, d == null || d.isEmpty() ? null : d);
            }
        }
        if (dto.dimensions != null) {
            for (Map.Entry<String, DimDto> e : dto.dimensions.entrySet()) {
                String key = e.getKey();
                DimDto d = e.getValue();
                if (key == null || key.isEmpty() || d == null) {
                    continue;
                }
                SpawnPoint spawn = null;
                if (d.spawn != null && d.spawn.length == 3) {
                    double x = d.spawn[0], y = d.spawn[1], z = d.spawn[2];
                    if (isValidSpawn(x, y, z)) {
                        spawn = new SpawnPoint(x, y, z);
                    } else {
                        LOG.warn("维度 {} 的落地坐标非法，已忽略（需重新配置）", key);
                    }
                }
                List<QuotaTiers.Tier> tiers = null;
                if (d.tiers != null && d.tiers.size() == 4) {
                    List<QuotaTiers.Tier> parsed = new ArrayList<>(4);
                    boolean ok = true;
                    for (TierDto t : d.tiers) {
                        if (t == null) {
                            ok = false;
                            break;
                        }
                        parsed.add(new QuotaTiers.Tier(t.enabled, t.window == null ? "" : t.window, t.limit));
                    }
                    if (ok) {
                        tiers = List.copyOf(parsed);
                    } else {
                        LOG.warn("维度 {} 的额度线条目不完整，已忽略（需重新配置）", key);
                    }
                }
                dims.put(key, new DimConfig(d.billing, spawn, tiers));
            }
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Dto dto = new Dto();
            dto.mode = mode;
            dto.redirectOnExhaust = redirectOnExhaust;
            dto.redirectOrder = new ArrayList<>(redirectOrder);
            dto.dimensions = new LinkedHashMap<>();
            for (Map.Entry<String, DimConfig> e : dims.entrySet()) {
                DimDto d = new DimDto();
                d.billing = e.getValue().billing();
                SpawnPoint s = e.getValue().spawn();
                d.spawn = s == null ? null : new double[]{s.x(), s.y(), s.z()};
                List<QuotaTiers.Tier> t = e.getValue().tiers();
                d.tiers = t == null ? null : t.stream()
                        .map(x -> new TierDto(x.enabled(), x.window(), x.limit()))
                        .toList();
                dto.dimensions.put(e.getKey(), d);
            }
            AtomicFile.write(file, GsonHolder.GSON.toJson(dto));
        } catch (IOException e) {
            LOG.error("写入维度配置库 {} 失败", file, e);
        }
    }

    private static final class Dto {
        int version = VERSION;
        String mode;
        boolean redirectOnExhaust;
        List<String> redirectOrder;
        Map<String, DimDto> dimensions;
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
