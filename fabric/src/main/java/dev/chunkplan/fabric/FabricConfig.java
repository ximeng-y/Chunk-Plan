package dev.chunkplan.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.chunkplan.common.DurationParser;
import dev.chunkplan.common.GsonHolder;
import dev.chunkplan.common.QuotaConfig;

/**
 * Fabric JSON 配置（config/chunkplan.json）。
 * 结构与 NeoForge TOML 一致，全部数值可配；非法值由 common 校验回退默认并告警。
 */
public final class FabricConfig {

    /** 默认配置（与计划默认一致：5h≤500 + 24h≤2000；与 NeoForge TOML 平行数组格式一致） */
    private static final String DEFAULT_JSON = """
            {
              "lines": [ "5h", "24h" ],
              "lineLimits": [ 500.0, 2000.0 ],
              "firstEntryFee": 1.0,
              "familiarEntryFee": 0.05,
              "highSpeedThreshold": 1.0,
              "highSpeedMultiplier": 2.0,
              "exemptByDefault": true,
              "exemptPlayers": [],
              "saveIntervalSec": 300,
              "banScanIntervalSec": 30,
              "logFeeEvents": true
            }
            """;

    private static final class Dto {
        List<String> lines;
        List<Double> lineLimits;
        Double firstEntryFee;
        Double familiarEntryFee;
        Double highSpeedThreshold;
        Double highSpeedMultiplier;
        Boolean exemptByDefault;
        List<String> exemptPlayers;
        Long saveIntervalSec;
        Long banScanIntervalSec;
        Boolean logFeeEvents;
    }

    private FabricConfig() {
    }

    /**
     * 读取配置文件（不存在则写入默认配置），构建 common 配置。
     *
     * @param warnings 配置告警输出
     */
    public static QuotaConfig load(Path configFile, List<String> warnings) {
        Dto dto;
        try {
            if (!Files.exists(configFile)) {
                if (configFile.getParent() != null) {
                    Files.createDirectories(configFile.getParent());
                }
                Files.writeString(configFile, DEFAULT_JSON, StandardCharsets.UTF_8);
                dto = GsonHolder.GSON.fromJson(DEFAULT_JSON, Dto.class);
            } else {
                dto = GsonHolder.GSON.fromJson(Files.readString(configFile, StandardCharsets.UTF_8), Dto.class);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取配置文件失败: " + configFile, e);
        } catch (RuntimeException e) {
            // 畸形 JSON（JsonSyntaxException/NPE 等）解析失败：回退默认配置并告警，
            // 与 NeoForge reload 回退行为对齐，避免配置文件损坏导致整服启动失败
            warnings.add("配置文件解析失败（" + e.getMessage() + "），已回退默认配置: " + configFile);
            dto = GsonHolder.GSON.fromJson(DEFAULT_JSON, Dto.class);
        }
        if (dto == null) {
            warnings.add("配置文件为空，已回退默认配置: " + configFile);
            dto = GsonHolder.GSON.fromJson(DEFAULT_JSON, Dto.class);
        }

        List<QuotaConfig.Line> lines = new ArrayList<>();
        if (dto.lines != null || dto.lineLimits != null) {
            List<String> windows = dto.lines == null ? List.of() : dto.lines;
            List<Double> limits = dto.lineLimits == null ? List.of() : dto.lineLimits;
            int n = Math.min(windows.size(), limits.size());
            if (windows.size() != limits.size()) {
                warnings.add("lines 与 lineLimits 数量不一致（" + windows.size() + " vs " + limits.size()
                        + "），多余项已丢弃");
            }
            for (int i = 0; i < n; i++) {
                try {
                    lines.add(new QuotaConfig.Line(
                            DurationParser.parseSeconds(windows.get(i)), limits.get(i)));
                } catch (RuntimeException e) {
                    // 含 NPE（lineLimits 元素为 null）等解析异常，统一按非法额度线告警丢弃
                    warnings.add("额度线 " + i + " 非法（window=" + windows.get(i) + ", limit=" + limits.get(i) + "）：" + e.getMessage());
                }
            }
        }

        List<UUID> exempt = new ArrayList<>();
        if (dto.exemptPlayers != null) {
            for (String s : dto.exemptPlayers) {
                try {
                    exempt.add(UUID.fromString(s));
                } catch (IllegalArgumentException e) {
                    warnings.add("exemptPlayers 含非法 UUID: " + s);
                }
            }
        }

        return QuotaConfig.builder()
                .lines(lines)
                .firstEntryFee(dto.firstEntryFee == null ? 1.0 : dto.firstEntryFee)
                .familiarEntryFee(dto.familiarEntryFee == null ? 0.05 : dto.familiarEntryFee)
                .highSpeedThreshold(dto.highSpeedThreshold == null ? 1.0 : dto.highSpeedThreshold)
                .highSpeedMultiplier(dto.highSpeedMultiplier == null ? 2.0 : dto.highSpeedMultiplier)
                .exemptByDefault(dto.exemptByDefault == null ? true : dto.exemptByDefault)
                .exemptPlayers(exempt)
                .saveIntervalSec(dto.saveIntervalSec == null ? 300 : dto.saveIntervalSec)
                .banScanIntervalSec(dto.banScanIntervalSec == null ? 30 : dto.banScanIntervalSec)
                .logFeeEvents(dto.logFeeEvents == null ? true : dto.logFeeEvents)
                .build(warnings);
    }
}
