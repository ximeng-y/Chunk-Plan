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
 *   <li>{@code tierBuckets}：按档位（tier 1~4）独立的分钟桶消费记录（tier -> epochMinute -> 点数，
 *       坑 #30）。各窗口独立记账：关闭/重置单窗口只动对应档位的桶，互不影响</li>
 * </ul>
 * 序列化格式（v2）：
 * <pre>
 * { "version":2, "explored":{"minecraft:overworld":{"10":[[5,8],[12,15]],...},...},
 *   "tierBuckets":{"1":{"123456":3.5,...},...} }
 * </pre>
 * v1 的共享 {@code minuteBuckets} 无法无损拆分到档位：升级时保留 explored、丢弃消费桶（从 0 起）。
 */
public final class PlayerQuotaData {

    public static final int VERSION = 2;

    /** 行内区间（含端点）；不变量：同 z 行内不重叠、不相邻（相邻即合并） */
    record Range(int startX, int endX) {
    }

    /** 维度 -> z 行号 -> 按 startX 升序的区间列表 */
    private final Map<String, Map<Integer, List<Range>>> exploredByDim = new HashMap<>();
    /** 档位(1~4) -> 分钟桶（epochMinute -> 累计点数）；外层 TreeMap 保证序列化按档位排序 */
    private final TreeMap<Integer, TreeMap<Long, Double>> tierBuckets = new TreeMap<>();
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

    /** 消费记入指定档位的分钟桶（每次踏入按所有启用档位各记一份，坑 #30） */
    public void addSpend(int tier, long minute, double fee) {
        tierBuckets.computeIfAbsent(tier, k -> new TreeMap<>()).merge(minute, fee, Double::sum);
        dirty = true;
    }

    /** 某档位窗口 (now-window, now] 内的消费点数之和（无该档位桶返回 0） */
    public double spendInWindow(int tier, long nowMillis, long windowSeconds) {
        TreeMap<Long, Double> buckets = tierBuckets.get(tier);
        if (buckets == null) {
            return 0;
        }
        long nowMin = nowMillis / 60000;
        long firstKey = (nowMillis - windowSeconds * 1000) / 60000 + 1;
        double sum = 0;
        for (Entry<Long, Double> e : buckets.subMap(firstKey, true, nowMin, true).entrySet()) {
            sum += e.getValue();
        }
        return sum;
    }

    /** 某档位大于等于 minKey 的第一个有消费的分钟桶，无则 null */
    public Long firstBucketAtOrAfter(int tier, long minKey) {
        TreeMap<Long, Double> buckets = tierBuckets.get(tier);
        if (buckets == null) {
            return null;
        }
        Entry<Long, Double> e = buckets.ceilingEntry(minKey);
        return e == null ? null : e.getKey();
    }

    /** 清空某档位消费桶（关闭窗口/单档重置用，坑 #30），返回是否发生清理 */
    public boolean clearTierSpend(int tier) {
        TreeMap<Long, Double> buckets = tierBuckets.get(tier);
        if (buckets == null || buckets.isEmpty()) {
            return false;
        }
        tierBuckets.remove(tier);
        dirty = true;
        return true;
    }

    /** 删除某档位早于 cutoffMinute 的桶（清理过期数据），返回是否发生清理 */
    public boolean cleanupBucketsBefore(int tier, long cutoffMinute) {
        TreeMap<Long, Double> buckets = tierBuckets.get(tier);
        if (buckets == null || buckets.isEmpty() || buckets.firstKey() >= cutoffMinute) {
            return false;
        }
        buckets.headMap(cutoffMinute).clear();
        dirty = true;
        return true;
    }

    /** 清空全部档位消费桶（/chunkplan reset 全档位） */
    public void clearSpend() {
        if (!tierBuckets.isEmpty()) {
            tierBuckets.clear();
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
        /** v2：档位 -> 分钟桶 */
        Map<Integer, Map<Long, Double>> tierBuckets;
        /** v1 遗留共享桶：升级时有意不读取（无法无损拆分到档位，丢弃消费） */
        Map<Long, Double> minuteBuckets;
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
        d.tierBuckets = new TreeMap<>();
        tierBuckets.forEach((tier, buckets) -> d.tierBuckets.put(tier, new LinkedHashMap<>(buckets)));
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
        if (dto.tierBuckets != null) {
            for (Entry<Integer, Map<Long, Double>> tierEntry : dto.tierBuckets.entrySet()) {
                Integer tier = tierEntry.getKey();
                Map<Long, Double> buckets = tierEntry.getValue();
                if (tier == null || tier < 1 || tier > 4 || buckets == null) {
                    continue; // 非法档位/空桶跳过
                }
                for (Entry<Long, Double> e : buckets.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        p.tierBuckets.computeIfAbsent(tier, k -> new TreeMap<>()).put(e.getKey(), e.getValue());
                    }
                }
            }
        }
        // v1 legacy minuteBuckets 有意不读取：见类注释（迁移语义：explored 保留、消费丢弃）
        return p;
    }
}
