package dev.chunkplan.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;

/**
 * 文件安全读写工具（坑 #27）：
 * <ul>
 *   <li>原子写：先写同目录 .tmp 再 rename 覆盖，避免写一半损坏数据</li>
 *   <li>写前备份：写目标前先把现有文件复制为 {@code <file>.bak}（覆盖式，同一文件 .bak 最多一个），
 *       .bak 恒为上一份完好数据，供主文件损坏时兜底恢复</li>
 *   <li>损坏兜底读：主文件 parse/IO 失败时尝试 .bak，成功则恢复数据并写回主文件（修复现场）</li>
 * </ul>
 */
public final class AtomicFile {

    private AtomicFile() {
    }

    /** 原子写 + 写前备份：目标存在时先把现有文件备份为 .bak，再写新内容 */
    public static void write(Path target, String content) throws IOException {
        backupExisting(target);
        writeNoBackup(target, content);
    }

    /** 原子写但跳过写前备份：从 .bak 恢复后写回主文件专用（坑 #27），避免损坏的主文件覆盖唯一的好 .bak */
    public static void writeNoBackup(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * JSON 损坏兜底读（坑 #27）：先解析主文件，parse/IO 失败时尝试 {@code <file>.bak}；
     * 从 .bak 恢复成功后立即用 {@link #writeNoBackup} 写回主文件修复现场（.bak 保留不动）。
     *
     * @param what 文件用途描述（如"玩家 xx 配额数据"），用于日志
     * @return 解析结果；主文件与 .bak 均失败返回 null（调用方走原降级逻辑）
     */
    public static <T> T readJson(Path file, Class<T> type, String what, Logger log) {
        T parsed = tryParseFile(file, type, what, log);
        if (parsed != null) {
            return parsed;
        }
        return restoreFromBackup(file, type, what, log);
    }

    /** 尝试解析主文件；IO/parse 失败返回 null 并告警（readJson 第一步） */
    private static <T> T tryParseFile(Path file, Class<T> type, String what, Logger log) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            T parsed = parse(json, type);
            if (parsed != null) {
                return parsed;
            }
            log.warn("{} 解析结果为空，尝试从 .bak 恢复", what);
        } catch (IOException e) {
            log.warn("{} 读取失败（{}），尝试从 .bak 恢复", what, e.getMessage());
        } catch (RuntimeException e) {
            // 畸形 JSON（JsonSyntaxException 等）
            log.warn("{} 解析失败（{}），尝试从 .bak 恢复", what, e.getMessage());
        }
        return null;
    }

    /** 主文件失败后尝试 .bak；成功则写回主文件修复现场并返回数据，全失败返回 null */
    private static <T> T restoreFromBackup(Path file, Class<T> type, String what, Logger log) {
        Path bak = file.resolveSibling(file.getFileName() + ".bak");
        String json;
        try {
            json = Files.readString(bak, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("{} 损坏且 .bak 不可用，无法恢复", what);
            return null;
        }
        T parsed;
        try {
            parsed = parse(json, type);
        } catch (RuntimeException e) {
            log.error("{} 损坏且 .bak 也损坏（{}），无法恢复", what, e.getMessage());
            return null;
        }
        if (parsed == null) {
            log.error("{} 损坏且 .bak 内容为空，无法恢复", what);
            return null;
        }
        log.warn("{} 已从 .bak 恢复", what);
        try {
            // 修复现场；必须跳过写前备份，否则损坏的主文件会覆盖唯一的好 .bak
            writeNoBackup(file, json);
        } catch (IOException e) {
            log.error("{} 从 .bak 恢复后写回主文件失败", what, e);
        }
        return parsed;
    }

    /** 写前备份：目标存在时复制为 {@code <file>.bak}（覆盖式，同一文件 .bak 最多一个） */
    private static void backupExisting(Path target) throws IOException {
        if (Files.exists(target)) {
            Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static <T> T parse(String json, Class<T> type) {
        return GsonHolder.GSON.fromJson(json, type);
    }
}
