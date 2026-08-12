package dev.chunkplan.common;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

/**
 * 单个玩家的配额数据：
 * <ul>
 *   <li>{@code exploredByDim}：按维度分区的个人已探索区块集合（终身保留）</li>
 *   <li>{@code minuteBuckets}：分钟桶消费记录（epochMinute -> 累计点数，滚动窗口求消费）</li>
 * </ul>
 * 序列化格式（v1）：
 * <pre>
 * { "version":1, "explored":{"minecraft:overworld":[long...],...},
 *   "minuteBuckets":{"123456":3.5,...} }
 * </pre>
 */
public final class PlayerQuotaData {

    public static final int VERSION = 1;

    private final Map<String, Set<Long>> exploredByDim = new HashMap<>();
    private final TreeMap<Long, Double> minuteBuckets = new TreeMap<>();
    private boolean dirty;

    /** 该维度已探索集合（不存在则创建空集） */
    public Set<Long> explored(String dimKey) {
        return exploredByDim.computeIfAbsent(dimKey, k -> new HashSet<>());
    }

    public boolean markExplored(String dimKey, long chunkKey) {
        if (exploredByDim.computeIfAbsent(dimKey, k -> new HashSet<>()).add(chunkKey)) {
            dirty = true;
            return true;
        }
        return false;
    }

    public void addSpend(long minute, double fee) {
        minuteBuckets.merge(minute, fee, Double::sum);
        dirty = true;
    }

    /** 窗口 (now-window, now] 内的消费点数之和 */
    public double spendInWindow(long nowMillis, long windowSeconds) {
        long nowMin = nowMillis / 60000;
        long firstKey = (nowMillis - windowSeconds * 1000) / 60000 + 1;
        double sum = 0;
        for (Entry<Long, Double> e : minuteBuckets.subMap(firstKey, true, nowMin, true).entrySet()) {
            sum += e.getValue();
        }
        return sum;
    }

    /** 大于等于 minKey 的第一个有消费的分钟桶，无则 null */
    public Long firstBucketAtOrAfter(long minKey) {
        Entry<Long, Double> e = minuteBuckets.ceilingEntry(minKey);
        return e == null ? null : e.getKey();
    }

    /** 删除早于 cutoffMinute 的桶（清理过期数据），返回是否发生清理 */
    public boolean cleanupBucketsBefore(long cutoffMinute) {
        if (minuteBuckets.isEmpty() || minuteBuckets.firstKey() >= cutoffMinute) {
            return false;
        }
        minuteBuckets.headMap(cutoffMinute).clear();
        dirty = true;
        return true;
    }

    public void clearSpend() {
        if (!minuteBuckets.isEmpty()) {
            minuteBuckets.clear();
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
        Map<String, long[]> explored;
        Map<Long, Double> minuteBuckets;
    }

    public Dto toDto() {
        Dto d = new Dto();
        d.explored = new LinkedHashMap<>();
        exploredByDim.forEach((k, v) -> d.explored.put(k,
                v.stream().mapToLong(Long::longValue).sorted().toArray()));
        d.minuteBuckets = new LinkedHashMap<>();
        minuteBuckets.forEach((k, v) -> d.minuteBuckets.put(k, v));
        return d;
    }

    public static PlayerQuotaData fromDto(Dto dto) {
        PlayerQuotaData p = new PlayerQuotaData();
        if (dto.explored != null) {
            for (Entry<String, long[]> e : dto.explored.entrySet()) {
                long[] arr = e.getValue();
                p.exploredByDim.put(e.getKey(),
                        arr == null ? new HashSet<>() : Arrays.stream(arr).boxed().collect(java.util.stream.Collectors.toCollection(HashSet::new)));
            }
        }
        if (dto.minuteBuckets != null) {
            dto.minuteBuckets.forEach((k, v) -> {
                if (k != null && v != null) {
                    p.minuteBuckets.put(k, v);
                }
            });
        }
        return p;
    }
}
