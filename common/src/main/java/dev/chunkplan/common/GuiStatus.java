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
 * 通过各自加载器的网络通道发给客户端；客户端 {@link #decode(byte[])} 还原后渲染用量页/管理页。
 * 序列化收敛在此处，六端共享，避免重复实现（协议版本不匹配/数据损坏时 decode 返回 null，
 * 客户端显示"服务器未装 ChunkPlan 或版本不匹配"）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code tiers}：四档原始配置（含禁用档的窗口/上限，供管理页展示与编辑；来源配置文件而非引擎 active lines）</li>
 *   <li>{@code lines}：引擎当前激活额度线状态（复用 {@link QuotaEngine.LineStatus}，已按玩家预设覆盖解析，issue #2）</li>
 *   <li>{@code worstPercent}：跨窗口当前最高档位百分比（-1 = 无档，坑 #29 档位词显示用）</li>
 *   <li>{@code presets}：预设名列表（v2，issue #1；仅管理员请求填充，与 tiers 同策略）</li>
 *   <li>{@code playerPreset}：请求玩家当前预设名（v2，issue #2；null = 跟随全局 default）</li>
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
        String playerPreset) {

    /** 协议版本：两端不一致时 decode 返回 null（客户端提示升级）；v2 增加预设字段（issue #1、#2） */
    public static final int PROTOCOL_VERSION = 2;

    /** 序列化为字节数组（DataOutputStream，纯 Java） */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(PROTOCOL_VERSION);
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
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream 不抛 IOException，此分支仅为满足签名
            return new byte[0];
        }
    }

    /** 反序列化；协议版本不符或数据损坏返回 null（调用方按"服务器未装/版本不匹配"处理） */
    public static GuiStatus decode(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != PROTOCOL_VERSION) {
                return null;
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
            return new GuiStatus(first, familiar, threshold, multiplier,
                    exemptByDefault, isExempt, inExemptList, isAdmin,
                    List.copyOf(tiers), List.copyOf(lines), allExceeded, recovery, worst,
                    List.copyOf(presets), playerPreset.isEmpty() ? null : playerPreset);
        } catch (IOException | RuntimeException e) {
            return null; // 截断/损坏/版本不符：安全回退
        }
    }
}
