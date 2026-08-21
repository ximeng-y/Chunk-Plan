package dev.chunkplan.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

/**
 * 单个玩家的配额数据：
 * <ul>
 *   <li>{@code exploredByDim}：按维度分区的个人已探索区块集合（终身保留），
 *       按行（chunkZ）压缩为 [startX,endX] 区间列表，增量合并（凹口被填平自动合拢）</li>
 *   <li>{@code tiers}：按档位（tier 1~4）独立的固定周期消费状态（坑 #40）。
 *       每档记录周期起点（首次消费时刻，对齐整分钟）与周期内累计消费；
 *       到达"起点 + 窗口长"时整窗清零（等价 /chunkplan reset），不做逐桶滑出</li>
 * </ul>
 * 序列化格式（v3）：
 * <pre>
 * { "version":3, "explored":{"minecraft:overworld":{"10":[[5,8],[12,15]],...},...},
 *   "tiers":{"1":{"cycleStartMillis":1720000000000,"spent":3.5},...} }
 * </pre>
 * v1 的共享 {@code minuteBuckets} 与 v2 的 {@code tierBuckets}（滚动窗口分钟桶）无法映射为
 * 固定周期：升级时保留 explored、丢弃消费记录（从 0 起，坑 #40）。
 */
public final class PlayerQuotaData {

    public static final int VERSION = 3;

    /** 行内区间（含端点）；不变量：同 z 行内不重叠、不相邻（相邻即合并） */
    record Range(int startX, int endX) {
    }

    /** 单档位固定周期状态：周期起点（epoch 毫秒，分钟对齐；-1 = 从未消费）+ 周期内累计消费 */
    public static final class TierCycle {
        long cycleStartMillis = -1;
        double spent;
    }

    /** 维度 -> z 行号 -> 按 startX 升序的区间列表 */
    private final Map<String, Map<Integer, List<Range>>> exploredByDim = new HashMap<>();
    /** 档位(1~4) -> 固定周期状态；外层 TreeMap 保证序列化按档位排序 */
    private final TreeMap<Integer, TierCycle> tiers = new TreeMap<>();
    private boolean dirty;

    /** 该维度该区块是否已探索（行内二分，无该行/维度返回 false） */
    public boolean isExplored(String dimKey, long chunkKey) {
        Map<Integer, List<Range>> rows = exploredByDim.get(dimKey);
        if (rows == null) {
            return false;
        }
        List<Range> ranges = rows.get(ChunkPosPacker.z(chunkKey));
        if (ranges == null) {
            return false;
        }
        int x = ChunkPosPacker.x(chunkKey);
        int i = Collections.binarySearch(ranges, new Range(x, x), Comparator.comparingInt(Range::startX));
        int idx = i >= 0 ? i : -i - 2; // 最后一个 startX <= x 的区间
        return idx >= 0 && ranges.get(idx).endX() >= x;
    }

    /**
     * 标记探索：行内增量合并——与左邻（endX == x-1）/右邻（startX == x+1）相邻即扩展，
     * 同时接住左右两区间则三合一（填平凹口的瞬间自动合拢，无事后重排）。
     * 已存在返回 false 且不置 dirty（语义与旧 Set 版本一致）。
     */
    public boolean markExplored(String dimKey, long chunkKey) {
        int x = ChunkPosPacker.x(chunkKey);
        int z = ChunkPosPacker.z(chunkKey);
        List<Range> ranges = exploredByDim.computeIfAbsent(dimKey, k -> new HashMap<>())
                .computeIfAbsent(z, k -> new ArrayList<>());
        int i = Collections.binarySearch(ranges, new Range(x, x), Comparator.comparingInt(Range::startX));
        int ins = i >= 0 ? i : -i - 1; // 插入点：第一个 startX > x 的位置
        if (i >= 0) {
            return false; // 存在 startX == x 的区间
        }
        // x 落在左邻区间内部（startX < x <= endX）
        if (ins > 0 && ranges.get(ins - 1).endX() >= x) {
            return false;
        }
        boolean mergeLeft = ins > 0 && ranges.get(ins - 1).endX() == x - 1;
        boolean mergeRight = ins < ranges.size() && ranges.get(ins).startX() == x + 1;
        if (mergeLeft && mergeRight) {
            Range l = ranges.get(ins - 1);
            Range r = ranges.get(ins);
            ranges.set(ins - 1, new Range(l.startX(), r.endX()));
            ranges.remove(ins);
        } else if (mergeLeft) {
            Range l = ranges.get(ins - 1);
            ranges.set(ins - 1, new Range(l.startX(), x));
        } else if (mergeRight) {
            Range r = ranges.get(ins);
            ranges.set(ins, new Range(x, r.endX()));
        } else {
            ranges.add(ins, new Range(x, x));
        }
        dirty = true;
        return true;
    }

    /**
     * 记录一笔消费到指定档位的固定周期（坑 #40）：周期未锚定（首次消费）时以当前时刻
     * 所在整分钟为起点；已到期的周期由引擎先调 {@link #expireIfNeeded} 清零重锚。
     */
    public void recordSpend(int tier, long nowMillis, double fee) {
        TierCycle cycle = tiers.computeIfAbsent(tier, k -> new TierCycle());
        if (cycle.cycleStartMillis < 0) {
            // 锚点向下对齐整分钟：恢复时间恒落在整分，与文案 HH:mm 显示精确一致
            cycle.cycleStartMillis = nowMillis / 60000 * 60000;
        }
        cycle.spent += fee;
        dirty = true;
    }

    /**
     * 惰性过期：now 到达"周期起点 + 窗口长"即整窗清零（等价 reset，坑 #40），返回是否清零。
     * 读路径无需调用（{@link #effectiveSpent} 已按时间现算），仅记账前调用以重锚新周期。
     */
    public boolean expireIfNeeded(int tier, long nowMillis, long windowSeconds) {
        TierCycle cycle = tiers.get(tier);
        if (cycle == null || cycle.cycleStartMillis < 0) {
            return false;
        }
        if (nowMillis < cycle.cycleStartMillis + windowSeconds * 1000L) {
            return false;
        }
        cycle.cycleStartMillis = -1;
        cycle.spent = 0;
        dirty = true;
        return true;
    }

    /** 某档位当前有效消费：周期未锚定或已到期返回 0（纯读，不清状态、不置脏） */
    public double effectiveSpent(int tier, long nowMillis, long windowSeconds) {
        TierCycle cycle = tiers.get(tier);
        if (cycle == null || cycle.cycleStartMillis < 0) {
            return 0;
        }
        if (nowMillis >= cycle.cycleStartMillis + windowSeconds * 1000L) {
            return 0;
        }
        return cycle.spent;
    }

    /** 某档位周期起点（epoch 毫秒，分钟对齐；无记录或从未消费返回 -1）。恢复/重置时间由引擎据此推导 */
    public long cycleStartMillis(int tier) {
        TierCycle cycle = tiers.get(tier);
        return cycle == null ? -1 : cycle.cycleStartMillis;
    }

    /** 清空某档位固定周期（关闭窗口/单档重置用，坑 #30/#40），返回是否发生清理；下次消费重新锚定完整周期 */
    public boolean clearTierSpend(int tier) {
        if (tiers.remove(tier) == null) {
            return false;
        }
        dirty = true;
        return true;
    }

    /** 清空全部档位固定周期（/chunkplan reset 全档位） */
    public void clearSpend() {
        if (!tiers.isEmpty()) {
            tiers.clear();
            dirty = true;
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    // ---------- 序列化 ----------

    static final class Dto {
        int version = VERSION;
        Map<String, Map<String, int[][]>> explored;
        /** v3：档位 -> 固定周期状态 */
        Map<Integer, TierCycle> tiers;
    }

    public Dto toDto() {
        Dto d = new Dto();
        d.explored = new LinkedHashMap<>();
        // 维度保持插入序；行号用 TreeMap 排序输出，保证序列化确定性
        exploredByDim.forEach((dim, rows) -> {
            Map<String, int[][]> out = new TreeMap<>();
            rows.forEach((z, ranges) -> out.put(String.valueOf(z),
                    ranges.stream().map(r -> new int[]{r.startX(), r.endX()}).toArray(int[][]::new)));
            d.explored.put(dim, out);
        });
        d.tiers = new TreeMap<>();
        // 防御性拷贝：避免序列化期间状态被服务器线程并发修改
        tiers.forEach((tier, c) -> {
            TierCycle copy = new TierCycle();
            copy.cycleStartMillis = c.cycleStartMillis;
            copy.spent = c.spent;
            d.tiers.put(tier, copy);
        });
        return d;
    }

    public static PlayerQuotaData fromDto(Dto dto) {
        PlayerQuotaData p = new PlayerQuotaData();
        if (dto.explored != null) {
            for (Entry<String, Map<String, int[][]>> dim : dto.explored.entrySet()) {
                if (dim.getValue() == null) {
                    continue;
                }
                for (Entry<String, int[][]> row : dim.getValue().entrySet()) {
                    int z;
                    try {
                        z = Integer.parseInt(row.getKey());
                    } catch (NumberFormatException e) {
                        continue; // 非法行号跳过
                    }
                    int[][] ranges = row.getValue();
                    if (ranges == null) {
                        continue;
                    }
                    for (int[] range : ranges) {
                        // 非法区间（null/长度不对/起点大于终点）跳过，不崩溃
                        if (range == null || range.length != 2 || range[0] > range[1]) {
                            continue;
                        }
                        // 展开整段区间逐块标记：走增量合并（加载时顺带规范化相邻区间并置 dirty，
                        // 下次保存写回规范格式）；加载开销 O(区块数)，与旧 Set 格式一致
                        for (int x = range[0]; x <= range[1]; x++) {
                            p.markExplored(dim.getKey(), ChunkPosPacker.pack(x, z));
                        }
                    }
                }
            }
        }
        if (dto.tiers != null) {
            for (Entry<Integer, TierCycle> e : dto.tiers.entrySet()) {
                Integer tier = e.getKey();
                TierCycle cycle = e.getValue();
                // 非法档位/空条目/未锚定周期跳过（未锚定即无有效消费）
                if (tier == null || tier < 1 || tier > 4 || cycle == null || cycle.cycleStartMillis < 0) {
                    continue;
                }
                TierCycle copy = new TierCycle();
                copy.cycleStartMillis = cycle.cycleStartMillis;
                copy.spent = Math.max(0, cycle.spent);
                p.tiers.put(tier, copy);
            }
        }
        // v1 minuteBuckets / v2 tierBuckets 有意不读取：见类注释（迁移语义：explored 保留、消费丢弃）
        return p;
    }
}
