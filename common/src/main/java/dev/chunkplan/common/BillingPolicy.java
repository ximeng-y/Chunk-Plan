package dev.chunkplan.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 命名预设携带的完整计费策略快照（issue #13/#14）。
 *
 * <p>数据模型与全服默认配置同构：
 * <ul>
 *   <li>{@code shared}：四档共享额度线；维度 billing/spawn 仍作为快照保留</li>
 *   <li>{@code independent}：每维度 billing/spawn/四档完整 tiers；耗尽重定向开关与固定 3 槽</li>
 * </ul>
 *
 * <p>不可变纯数据类（采用 final class 而非 record，以便缓存额度线转换与校验告警，
 * 避免引擎每 tick 重复解析窗口字符串）。{@code redirectOrder} 恒为 3 项，元素可为 null。
 */
public final class BillingPolicy {

    public static final String MODE_SHARED = DimensionStore.MODE_SHARED;
    public static final String MODE_INDEPENDENT = DimensionStore.MODE_INDEPENDENT;

    private final String mode;
    private final List<QuotaTiers.Tier> sharedTiers;
    private final Map<String, DimensionStore.DimConfig> dimensions;
    private final boolean redirectOnExhaust;
    private final List<String> redirectOrder;

    private final List<QuotaConfig.Line> sharedLines;
    private final Map<String, List<QuotaConfig.Line>> dimensionLines;
    private final List<String> validationWarnings;

    public BillingPolicy(
            String mode,
            List<QuotaTiers.Tier> sharedTiers,
            Map<String, DimensionStore.DimConfig> dimensions,
            boolean redirectOnExhaust,
            List<String> redirectOrder) {
        // 保留原始 mode 以便 validate() 拒绝非法值；运行路径判断统一走 isIndependent()。
        this.mode = mode;
        this.sharedTiers = copyTiers(sharedTiers);
        this.dimensions = copyDimensions(dimensions);
        List<String> rawOrder = redirectOrder;
        this.redirectOrder = normalizeRedirectOrder(redirectOrder);
        this.redirectOnExhaust = redirectOnExhaust;

        List<String> warnings = new ArrayList<>();
        if (!MODE_SHARED.equals(mode) && !MODE_INDEPENDENT.equals(mode)) {
            warnings.add("mode 必须为 shared 或 independent（当前 " + mode + "）");
        }
        if (this.sharedTiers == null) {
            warnings.add("sharedTiers 缺失");
        } else {
            validateTiersShape("sharedTiers", this.sharedTiers, warnings);
        }
        List<String> sharedWarnings = new ArrayList<>();
        this.sharedLines = List.copyOf(QuotaTiers.toLines(this.sharedTiers, sharedWarnings));
        warnings.addAll(sharedWarnings);

        Map<String, List<QuotaConfig.Line>> dimLines = new LinkedHashMap<>();
        for (Map.Entry<String, DimensionStore.DimConfig> e : this.dimensions.entrySet()) {
            String dim = e.getKey();
            DimensionStore.DimConfig d = e.getValue();
            if (dim == null || dim.isEmpty()) {
                warnings.add("dimensions 含空维度 key");
                continue;
            }
            if (d == null) {
                warnings.add("维度 " + dim + " 的 DimConfig 缺失");
                continue;
            }
            if (d.spawn() != null
                    && !DimensionStore.isValidSpawn(d.spawn().x(), d.spawn().y(), d.spawn().z())) {
                warnings.add("维度 " + dim + " 的落地坐标非法");
            }
            List<QuotaTiers.Tier> tiers = d.tiers();
            if (isIndependentMode(mode)) {
                if (tiers == null) {
                    warnings.add("独立 policy 的维度 " + dim + " 缺少四档 tiers");
                } else {
                    validateTiersShape("维度 " + dim + " 的 tiers", tiers, warnings);
                }
            } else if (tiers != null) {
                validateTiersShape("维度 " + dim + " 的 tiers", tiers, warnings);
            }
            if (tiers != null) {
                List<String> dimWarnings = new ArrayList<>();
                List<QuotaConfig.Line> lines = QuotaTiers.toLines(tiers, dimWarnings);
                warnings.addAll(dimWarnings);
                dimLines.put(dim, List.copyOf(lines));
            }
        }
        validateRedirectOrder(rawOrder, this.redirectOrder, warnings);
        this.dimensionLines = Collections.unmodifiableMap(dimLines);
        this.validationWarnings = List.copyOf(warnings);
    }

    /** 空 3 槽（首选/次选/备选均为未配置）。不可用 List.copyOf：其拒绝 null 元素。 */
    public static List<String> emptyRedirectOrder() {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(null, null, null)));
    }

    private static boolean isIndependentMode(String mode) {
        return MODE_INDEPENDENT.equals(mode);
    }

    public String mode() {
        return mode;
    }

    public List<QuotaTiers.Tier> sharedTiers() {
        return sharedTiers;
    }

    public Map<String, DimensionStore.DimConfig> dimensions() {
        return dimensions;
    }

    public boolean redirectOnExhaust() {
        return redirectOnExhaust;
    }

    public List<String> redirectOrder() {
        return redirectOrder;
    }

    public boolean isIndependent() {
        return isIndependentMode(mode);
    }

    /** 该维度是否计费；无条目按现有规则默认 true，不自动免费。 */
    public boolean isBillingEnabled(String dimKey) {
        DimensionStore.DimConfig d = dimensions.get(dimKey);
        return d == null || d.billing();
    }

    /** 该维度落地坐标；无条目/未配置返回 null。 */
    public DimensionStore.SpawnPoint spawn(String dimKey) {
        DimensionStore.DimConfig d = dimensions.get(dimKey);
        return d == null ? null : d.spawn();
    }

    /** 共享四档转换后的额度线（构造时缓存）。 */
    public List<QuotaConfig.Line> sharedLines() {
        return sharedLines;
    }

    /**
     * 指定维度的有效额度线：独立模式优先该维度 tiers；未知维度确定性回退 sharedTiers，
     * 不写回也不修改命名预设。
     */
    public List<QuotaConfig.Line> linesFor(String dimKey) {
        if (!isIndependent() || dimKey == null) {
            return sharedLines;
        }
        List<QuotaConfig.Line> lines = dimensionLines.get(dimKey);
        return lines != null ? lines : sharedLines;
    }

    /** 构造期计算出的校验告警；空 = 可保存/应用。 */
    public List<String> validate() {
        return validationWarnings;
    }

    /** 独立 policy 对 liveDims 缺少合法落地坐标的维度列表；shared 恒为空。 */
    public List<String> missingSpawns(List<String> liveDims) {
        if (!isIndependent() || liveDims == null) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String dim : liveDims) {
            if (dim != null && spawn(dim) == null) {
                missing.add(dim);
            }
        }
        return missing;
    }

    /**
     * 按该 policy 自身重定向槽解析目标：3 槽（去空/去重、限 live）→ 其余 live 维度字典序；
     * 命中第一个可进且已有合法落地坐标的维度。开关关闭或无候选返回 null。
     */
    public String resolveRedirectTarget(List<String> liveDims, Predicate<String> enterable) {
        if (!redirectOnExhaust || liveDims == null || enterable == null) {
            return null;
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String d : redirectOrder) {
            if (d != null && liveDims.contains(d)) {
                candidates.add(d);
            }
        }
        List<String> rest = new ArrayList<>();
        for (String d : liveDims) {
            if (d != null && !candidates.contains(d)) {
                rest.add(d);
            }
        }
        rest.sort(String::compareTo);
        candidates.addAll(rest);
        for (String d : candidates) {
            if (enterable.test(d) && spawn(d) != null) {
                return d;
            }
        }
        return null;
    }

    private static void validateTiersShape(String name, List<QuotaTiers.Tier> tiers, List<String> warnings) {
        if (tiers.size() != 4) {
            warnings.add(name + " 必须恰为 4 档（当前 " + tiers.size() + "）");
        }
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i) == null) {
                warnings.add(name + " 第 " + (i + 1) + " 档为 null");
            }
        }
    }

    private static void validateRedirectOrder(List<String> raw, List<String> normalized, List<String> warnings) {
        if (raw == null || raw.size() != 3) {
            warnings.add("redirectOrder 必须恰为 3 槽");
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        boolean sawEmpty = false;
        for (String d : normalized) {
            if (d == null || d.isEmpty()) {
                sawEmpty = true;
                continue;
            }
            if (sawEmpty) {
                warnings.add("redirectOrder 必须自首选起连续填写");
            }
            if (!seen.add(d)) {
                warnings.add("redirectOrder 槽位重复: " + d);
            }
        }
    }

    private static List<QuotaTiers.Tier> copyTiers(List<QuotaTiers.Tier> tiers) {
        return tiers == null ? null : Collections.unmodifiableList(new ArrayList<>(tiers));
    }

    private static Map<String, DimensionStore.DimConfig> copyDimensions(
            Map<String, DimensionStore.DimConfig> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, DimensionStore.DimConfig> out = new LinkedHashMap<>();
        for (Map.Entry<String, DimensionStore.DimConfig> e : raw.entrySet()) {
            DimensionStore.DimConfig d = e.getValue();
            out.put(e.getKey(), d == null ? null : new DimensionStore.DimConfig(
                    d.billing(), d.spawn(), copyTiers(d.tiers())));
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<String> normalizeRedirectOrder(List<String> raw) {
        List<String> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            out.add(raw != null && i < raw.size() ? raw.get(i) : null);
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BillingPolicy other)) {
            return false;
        }
        return redirectOnExhaust == other.redirectOnExhaust
                && Objects.equals(mode, other.mode)
                && Objects.equals(sharedTiers, other.sharedTiers)
                && Objects.equals(dimensions, other.dimensions)
                && Objects.equals(redirectOrder, other.redirectOrder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, sharedTiers, dimensions, redirectOnExhaust, redirectOrder);
    }

    @Override
    public String toString() {
        return "BillingPolicy[mode=" + mode
                + ", sharedTiers=" + sharedTiers
                + ", dimensions=" + dimensions
                + ", redirectOnExhaust=" + redirectOnExhaust
                + ", redirectOrder=" + redirectOrder + "]";
    }
}
