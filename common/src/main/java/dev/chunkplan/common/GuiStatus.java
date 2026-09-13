package dev.chunkplan.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 GUI 状态数据（纯 Java DTO，零 Minecraft/加载器依赖）。
 *
 * <p>服务端壳层从 {@link QuotaEngine} 构建本对象后 {@link #encode()} 为字节数组，
 * 通过各自加载器的网络通道发给客户端；客户端 {@link #decode(byte[])} 还原后渲染用量页/管理页/维度页。
 * 序列化收敛在此处，六端共享，避免重复实现（数据损坏时 decode 返回 null；
 * 协议版本不匹配时 decode 返回 versionMismatch=true 的版本横幅，客户端渲染兜底页并显示两端版本号）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code tiers}：四档原始配置（含禁用档的窗口/上限，供管理页展示与编辑；来源配置文件而非引擎 active lines）</li>
 *   <li>{@code lines}：引擎当前激活额度线状态（复用 {@link QuotaEngine.LineStatus}，已按玩家预设覆盖解析，issue #2；
 *       独立模式下为玩家当前维度的线状态，issue #3）</li>
 *   <li>{@code worstPercent}：跨窗口当前最高档位百分比（-1 = 无档，坑 #29 档位词显示用）</li>
 *   <li>{@code presets}：预设名列表（v2，issue #1；仅管理员请求填充，与 tiers 同策略）</li>
 *   <li>{@code playerPreset}：请求玩家当前预设名（v2，issue #2；null = 跟随全局 default）</li>
 *   <li>{@code dimensionMode}（v3，issue #3）：0 = 全维度共享额度，1 = 每维度独立</li>
 *   <li>{@code currentDim}（v3）：请求玩家当前所在维度 key（null = 未知）</li>
 *   <li>{@code dimensions}（v3）：服务器全部 live 维度 key（动态 getAllLevels，兼容 mod 注册维度）</li>
 *   <li>{@code dimLines}（v3）：各维度下该玩家的额度状态（用量页维度下拉；全体下发）</li>
 *   <li>{@code dimConfig}（v3）：维度管理配置（仅管理员；null = 非管理员不下发）</li>
 *   <li>{@code serverModVersion}（v4）：服务端 mod 版本号（wire 格式第 2 字段，冻结头的一部分，
 *       供版本不匹配兜底页显示；正常状态同样携带，可为 null = 未知）</li>
 *   <li>{@code versionMismatch}：版本不匹配标志（<b>非序列化</b>，仅 decode 遇协议版本不符时为 true；
 *       encode 忽略此字段，服务端构造的正常状态恒为 false）</li>
 * </ul>
 */
public record GuiStatus(
        double firstEntryFee,
        double familiarEntryFee,
        double highSpeedThreshold,
        double highSpeedMultiplier,
        boolean exemptByDefault,
        boolean isExempt,
        boolean inExemptList,
        boolean isAdmin,
        List<QuotaTiers.Tier> tiers,
        List<QuotaEngine.LineStatus> lines,
        boolean allExceeded,
        long recoveryMillis,
        int worstPercent,
        List<String> presets,
        String playerPreset,
        int dimensionMode,
        String currentDim,
        List<String> dimensions,
        List<DimLines> dimLines,
        DimConfigStatus dimConfig,
        String serverModVersion,
        boolean versionMismatch) {

    /** 协议版本：两端不一致时 decode 返回版本横幅（versionMismatch=true，兜底页显示两端版本号）；
     *  v3 增加维度字段（issue #3）；v4 在协议头插入 serverModVersion（版本不匹配兜底） */
    public static final int PROTOCOL_VERSION = 4;

    /** 编解码防御上限（防损坏数据异常内存分配，与维度实际规模相比极宽松） */
    private static final int MAX_DIMS = 64;
    private static final int MAX_LINES_PER_DIM = 16;

    /** 单维度的玩家额度状态（issue #3，用量页维度下拉渲染用） */
    public record DimLines(String dim, List<QuotaEngine.LineStatus> lines, long recoveryMillis, int worstPercent) {
    }

    /** 单维度的管理员配置条目（issue #3，维度页渲染用） */
    public record DimEntry(String dim, boolean billing, boolean hasSpawn, double x, double y, double z,
                           List<QuotaTiers.Tier> tiers) {
    }

    /** 维度管理配置（issue #3，仅管理员下发） */
    public record DimConfigStatus(boolean redirectOnExhaust, List<String> redirectOrder, List<DimEntry> dims) {
    }

    /**
     * 版本横幅（服务端在请求协议版本与本端不符时回发）：除 serverModVersion 外全部为空值，
     * 客户端 decode 走 mismatch 分支只读协议头两字段，其余内容不会解析——供兜底页显示两端版本号。
     * 服务端引擎未就绪也可回发（不依赖 engine），保证客户端总能看到版本提示。
     */
    public static GuiStatus versionBanner(String serverModVersion) {
        return new GuiStatus(0, 0, 0, 0, false, false, false, false,
                List.of(), List.of(), false, -1, -1, List.of(), null,
                0, null, List.of(), List.of(), null, serverModVersion, true);
    }

    /** 序列化为字节数组（DataOutputStream，纯 Java）。
     *  协议头为<b>冻结契约</b>：[protocolVersion int][serverModVersion UTF]——
     *  版本不匹配的客户端只解析这两字段即停止，未来任何版本不得改变其位置与含义 */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(PROTOCOL_VERSION);
            out.writeUTF(serverModVersion == null ? "" : serverModVersion);
            out.writeDouble(firstEntryFee);
            out.writeDouble(familiarEntryFee);
            out.writeDouble(highSpeedThreshold);
            out.writeDouble(highSpeedMultiplier);
            out.writeBoolean(exemptByDefault);
            out.writeBoolean(isExempt);
            out.writeBoolean(inExemptList);
            out.writeBoolean(isAdmin);
            out.writeInt(tiers == null ? 0 : tiers.size());
            if (tiers != null) {
                for (QuotaTiers.Tier t : tiers) {
                    out.writeBoolean(t.enabled());
                    out.writeUTF(t.window() == null ? "" : t.window());
                    out.writeDouble(t.limit());
                }
            }
            out.writeInt(lines == null ? 0 : lines.size());
            if (lines != null) {
                for (QuotaEngine.LineStatus l : lines) {
                    out.writeLong(l.windowSeconds());
                    out.writeDouble(l.limit());
                    out.writeDouble(l.spent());
                    out.writeLong(l.nextResetMillis());
                }
            }
            out.writeBoolean(allExceeded);
            out.writeLong(recoveryMillis);
            out.writeInt(worstPercent);
            // v2（issue #1、#2）：预设名列表 + 当前玩家预设
            out.writeInt(presets == null ? 0 : presets.size());
            if (presets != null) {
                for (String p : presets) {
                    out.writeUTF(p == null ? "" : p);
                }
            }
            out.writeUTF(playerPreset == null ? "" : playerPreset);
            // v3（issue #3）：维度模式/当前维度/live 维度/逐维度状态 + 管理员维度配置
            out.writeInt(dimensionMode);
            out.writeUTF(currentDim == null ? "" : currentDim);
            out.writeInt(dimensions == null ? 0 : dimensions.size());
            if (dimensions != null) {
                for (String d : dimensions) {
                    out.writeUTF(d == null ? "" : d);
                }
            }
            out.writeInt(dimLines == null ? 0 : dimLines.size());
            if (dimLines != null) {
                for (DimLines dl : dimLines) {
                    out.writeUTF(dl.dim() == null ? "" : dl.dim());
                    out.writeLong(dl.recoveryMillis());
                    out.writeInt(dl.worstPercent());
                    out.writeInt(dl.lines() == null ? 0 : dl.lines().size());
                    if (dl.lines() != null) {
                        for (QuotaEngine.LineStatus l : dl.lines()) {
                            out.writeLong(l.windowSeconds());
                            out.writeDouble(l.limit());
                            out.writeDouble(l.spent());
                            out.writeLong(l.nextResetMillis());
                        }
                    }
                }
            }
            if (isAdmin) {
                out.writeBoolean(dimConfig != null && dimConfig.redirectOnExhaust());
                List<String> order = dimConfig == null ? List.of() : dimConfig.redirectOrder();
                for (int slot = 0; slot < 3; slot++) {
                    out.writeUTF(slot < order.size() && order.get(slot) != null ? order.get(slot) : "");
                }
                List<DimEntry> entries = dimConfig == null ? List.of() : dimConfig.dims();
                out.writeInt(entries.size());
                for (DimEntry e : entries) {
                    out.writeUTF(e.dim() == null ? "" : e.dim());
                    out.writeBoolean(e.billing());
                    out.writeBoolean(e.hasSpawn());
                    if (e.hasSpawn()) {
                        out.writeDouble(e.x());
                        out.writeDouble(e.y());
                        out.writeDouble(e.z());
                    }
                    out.writeInt(e.tiers() == null ? 0 : e.tiers().size());
                    if (e.tiers() != null) {
                        for (QuotaTiers.Tier t : e.tiers()) {
                            out.writeBoolean(t.enabled());
                            out.writeUTF(t.window() == null ? "" : t.window());
                            out.writeDouble(t.limit());
                        }
                    }
                }
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream 不抛 IOException，此分支仅为满足签名
            return new byte[0];
        }
    }

    /** 反序列化；数据损坏返回 null（调用方按"解析失败"处理），协议版本不符返回版本横幅
     *  （versionMismatch=true，携带服务端 mod 版本号，供客户端渲染兜底页） */
    public static GuiStatus decode(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int protocol = in.readInt();
            // 冻结头第 2 字段：服务端 mod 版本（mismatch 分支只读到这里即返回，不再解析后续内容）
            String serverModVersion = in.readUTF();
            if (protocol != PROTOCOL_VERSION) {
                return versionBanner(serverModVersion == null || serverModVersion.isEmpty() ? null : serverModVersion);
            }
            double first = in.readDouble();
            double familiar = in.readDouble();
            double threshold = in.readDouble();
            double multiplier = in.readDouble();
            boolean exemptByDefault = in.readBoolean();
            boolean isExempt = in.readBoolean();
            boolean inExemptList = in.readBoolean();
            boolean isAdmin = in.readBoolean();
            int tierCount = in.readInt();
            if (tierCount < 0 || tierCount > 16) {
                return null; // 防御损坏数据：非法长度直接拒绝，避免异常内存分配
            }
            List<QuotaTiers.Tier> tiers = new ArrayList<>(tierCount);
            for (int i = 0; i < tierCount; i++) {
                tiers.add(new QuotaTiers.Tier(in.readBoolean(), in.readUTF(), in.readDouble()));
            }
            int lineCount = in.readInt();
            if (lineCount < 0 || lineCount > 16) {
                return null;
            }
            List<QuotaEngine.LineStatus> lines = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                lines.add(new QuotaEngine.LineStatus(in.readLong(), in.readDouble(), in.readDouble(), in.readLong()));
            }
            boolean allExceeded = in.readBoolean();
            long recovery = in.readLong();
            int worst = in.readInt();
            // v2（issue #1、#2）：预设名列表 + 当前玩家预设（playerPreset 空串归一为 null = default）
            int presetCount = in.readInt();
            if (presetCount < 0 || presetCount > 64) {
                return null;
            }
            List<String> presets = new ArrayList<>(presetCount);
            for (int i = 0; i < presetCount; i++) {
                String name = in.readUTF();
                if (name != null && !name.isEmpty()) {
                    presets.add(name);
                }
            }
            String playerPreset = in.readUTF();
            // v3（issue #3）：维度字段
            int dimensionMode = in.readInt();
            String currentDim = in.readUTF();
            int dimCount = in.readInt();
            if (dimCount < 0 || dimCount > MAX_DIMS) {
                return null;
            }
            List<String> dimensions = new ArrayList<>(dimCount);
            for (int i = 0; i < dimCount; i++) {
                String d = in.readUTF();
                if (d != null && !d.isEmpty()) {
                    dimensions.add(d);
                }
            }
            int dimLineCount = in.readInt();
            if (dimLineCount < 0 || dimLineCount > MAX_DIMS) {
                return null;
            }
            List<DimLines> dimLines = new ArrayList<>(dimLineCount);
            for (int i = 0; i < dimLineCount; i++) {
                String dim = in.readUTF();
                long rec = in.readLong();
                int wp = in.readInt();
                int lc = in.readInt();
                if (lc < 0 || lc > MAX_LINES_PER_DIM) {
                    return null;
                }
                List<QuotaEngine.LineStatus> dl = new ArrayList<>(lc);
                for (int j = 0; j < lc; j++) {
                    dl.add(new QuotaEngine.LineStatus(in.readLong(), in.readDouble(), in.readDouble(), in.readLong()));
                }
                dimLines.add(new DimLines(dim, dl, rec, wp));
            }
            DimConfigStatus dimConfig = null;
            if (isAdmin) {
                boolean redirectOnExhaust = in.readBoolean();
                List<String> order = new ArrayList<>(3);
                for (int slot = 0; slot < 3; slot++) {
                    String s = in.readUTF();
                    order.add(s == null || s.isEmpty() ? null : s);
                }
                int entryCount = in.readInt();
                if (entryCount < 0 || entryCount > MAX_DIMS) {
                    return null;
                }
                List<DimEntry> entries = new ArrayList<>(entryCount);
                for (int i = 0; i < entryCount; i++) {
                    String dim = in.readUTF();
                    boolean billing = in.readBoolean();
                    boolean hasSpawn = in.readBoolean();
                    double x = 0, y = 0, z = 0;
                    if (hasSpawn) {
                        x = in.readDouble();
                        y = in.readDouble();
                        z = in.readDouble();
                    }
                    int tc = in.readInt();
                    if (tc < 0 || tc > 16) {
                        return null;
                    }
                    List<QuotaTiers.Tier> dt = new ArrayList<>(tc);
                    for (int j = 0; j < tc; j++) {
                        dt.add(new QuotaTiers.Tier(in.readBoolean(), in.readUTF(), in.readDouble()));
                    }
                    entries.add(new DimEntry(dim, billing, hasSpawn, x, y, z, dt));
                }
                dimConfig = new DimConfigStatus(redirectOnExhaust, order, entries);
            }
            return new GuiStatus(first, familiar, threshold, multiplier,
                    exemptByDefault, isExempt, inExemptList, isAdmin,
                    List.copyOf(tiers), List.copyOf(lines), allExceeded, recovery, worst,
                    List.copyOf(presets), playerPreset.isEmpty() ? null : playerPreset,
                    dimensionMode, currentDim.isEmpty() ? null : currentDim,
                    List.copyOf(dimensions), List.copyOf(dimLines), dimConfig,
                    serverModVersion.isEmpty() ? null : serverModVersion, false);
        } catch (IOException | RuntimeException e) {
            return null; // 截断/损坏/版本不符：安全回退
        }
    }
}
