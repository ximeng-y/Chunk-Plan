package dev.chunkplan.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.chunkplan.common.GsonHolder;
import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaTiers;

/**
 * Fabric JSON 配置（config/chunkplan.json）。
 * 结构与 NeoForge TOML 一致，全部数值可配；非法值由 common 校验回退默认并告警。
 */
public final class FabricConfig {

    /** 默认配置（与 NeoForge TOML 一致：第一二档开、第三四档关；阈值 0.5 即创造飞行） */
    private static final String DEFAULT_JSON = """
            {
              "tier1Enabled": true,
              "tier1Window": "5h",
              "tier1Limit": 500.0,
              "tier2Enabled": true,
              "tier2Window": "24h",
              "tier2Limit": 2000.0,
              "tier3Enabled": false,
              "tier3Window": "7d",
              "tier3Limit": 10000.0,
              "tier4Enabled": false,
              "tier4Window": "30d",
              "tier4Limit": 40000.0,
              "firstEntryFee": 1.0,
              "familiarEntryFee": 0.05,
              "highSpeedThreshold": 0.5,
              "highSpeedMultiplier": 2.0,
              "exemptByDefault": true,
              "exemptPlayers": [],
              "saveIntervalSec": 300,
              "banScanIntervalSec": 30,
              "logFeeEvents": true
            }
            """;

    private static final class Dto {
        Boolean tier1Enabled;
        String tier1Window;
        Double tier1Limit;
        Boolean tier2Enabled;
        String tier2Window;
        Double tier2Limit;
        Boolean tier3Enabled;
        String tier3Window;
        Double tier3Limit;
        Boolean tier4Enabled;
        String tier4Window;
        Double tier4Limit;
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

        List<QuotaTiers.Tier> tiers = List.of(
                new QuotaTiers.Tier(dto.tier1Enabled == null ? true : dto.tier1Enabled,
                        dto.tier1Window == null ? "5h" : dto.tier1Window,
                        dto.tier1Limit == null ? 500.0 : dto.tier1Limit),
                new QuotaTiers.Tier(dto.tier2Enabled == null ? true : dto.tier2Enabled,
                        dto.tier2Window == null ? "24h" : dto.tier2Window,
                        dto.tier2Limit == null ? 2000.0 : dto.tier2Limit),
                new QuotaTiers.Tier(dto.tier3Enabled == null ? false : dto.tier3Enabled,
                        dto.tier3Window == null ? "7d" : dto.tier3Window,
                        dto.tier3Limit == null ? 10000.0 : dto.tier3Limit),
                new QuotaTiers.Tier(dto.tier4Enabled == null ? false : dto.tier4Enabled,
                        dto.tier4Window == null ? "30d" : dto.tier4Window,
                        dto.tier4Limit == null ? 40000.0 : dto.tier4Limit));

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
                .lines(QuotaTiers.toLines(tiers, warnings))
                .firstEntryFee(dto.firstEntryFee == null ? 1.0 : dto.firstEntryFee)
                .familiarEntryFee(dto.familiarEntryFee == null ? 0.05 : dto.familiarEntryFee)
                .highSpeedThreshold(dto.highSpeedThreshold == null ? 0.5 : dto.highSpeedThreshold)
                .highSpeedMultiplier(dto.highSpeedMultiplier == null ? 2.0 : dto.highSpeedMultiplier)
                .exemptByDefault(dto.exemptByDefault == null ? true : dto.exemptByDefault)
                .exemptPlayers(exempt)
                .saveIntervalSec(dto.saveIntervalSec == null ? 300 : dto.saveIntervalSec)
                .banScanIntervalSec(dto.banScanIntervalSec == null ? 30 : dto.banScanIntervalSec)
                .logFeeEvents(dto.logFeeEvents == null ? true : dto.logFeeEvents)
                .build(warnings);
    }

    /**
     * /chunkplan config exemptByDefault 用：改 JSON 字段并原子写回（重启后保留）。
     * 用 GsonHolder（compact 单行格式），写回后格式与原文件一致。
     */
    public static void writeExemptByDefault(Path configFile, boolean value) throws IOException {
        com.google.gson.JsonObject root = GsonHolder.GSON.fromJson(
                Files.readString(configFile, StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
        root.addProperty("exemptByDefault", value);
        dev.chunkplan.common.AtomicFile.write(configFile, GsonHolder.GSON.toJson(root));
    }
}
