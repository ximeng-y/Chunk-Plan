package dev.chunkplan.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.chunkplan.common.DurationParser;
import dev.chunkplan.common.GsonHolder;
import dev.chunkplan.common.QuotaConfig;

/**
 * Fabric JSON 配置（config/chunkplan.json）。
 * 结构与 NeoForge TOML 一致，全部数值可配；非法值由 common 校验回退默认并告警。
 */
public final class FabricConfig {

    /** 默认配置（与计划默认一致：5h≤500 + 24h≤2000） */
    private static final String DEFAULT_JSON = """
            {
              "lines": [ { "window": "5h", "limit": 500.0 }, { "window": "24h", "limit": 2000.0 } ],
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
        List<Map<String, Object>> lines;
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
        }
        if (dto == null) {
            throw new IllegalStateException("配置文件为空: " + configFile);
        }

        List<QuotaConfig.Line> lines = new ArrayList<>();
        if (dto.lines != null) {
            for (Map<String, Object> m : dto.lines) {
                Object window = m.get("window");
                Object limit = m.get("limit");
                if (window instanceof String ws && limit instanceof Number ln) {
                    try {
                        lines.add(new QuotaConfig.Line(DurationParser.parseSeconds(ws), ln.doubleValue()));
                        continue;
                    } catch (IllegalArgumentException e) {
                        warnings.add("额度线窗口非法（" + ws + "）：" + e.getMessage());
                    }
                }
                warnings.add("额度线缺少 window/limit 字段，已丢弃该线");
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
