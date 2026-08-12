package dev.chunkplan.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 扣费事件独立日志文件实现（如 {@code logs/chunkplan.log}）。
 * 扣费频率低（玩家踏入区块时），每次追加一行并落盘，无缓冲丢失风险。
 */
public final class FeeLogFile implements FeeLogger {

    private static final Logger LOG = LoggerFactory.getLogger(FeeLogFile.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;

    public FeeLogFile(Path file) throws IOException {
        this.file = file;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
    }

    @Override
    public void logFee(UUID uuid, String dimKey, long chunkKey, double speed, double fee, double total) {
        String line = FMT.format(Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()))
                + " | uuid=" + uuid
                + " | dim=" + dimKey
                + " | chunk=(" + ChunkPosPacker.x(chunkKey) + "," + ChunkPosPacker.z(chunkKey) + ")"
                + String.format(" | speed=%.2f | fee=%.2f | total=%.2f", speed, fee, total);
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("写入扣费日志 {} 失败", file, e);
        }
    }
}
