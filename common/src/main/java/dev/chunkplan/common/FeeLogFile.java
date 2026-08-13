package dev.chunkplan.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 扣费事件独立日志文件实现（如 {@code logs/chunkplan.log}）。
 * 扣费频率低（玩家踏入区块时），每次追加一行并落盘，无缓冲丢失风险。
 *
 * <p>轮转严格仿原版 {@code latest.log}（1.21.1 log4j2 行为）：触发仅两类——
 * 构造时（启动）文件非空立即轮转（对应 OnStartupTriggeringPolicy）；
 * 写入时本地日期跨天轮转（对应 TimeBasedTriggeringPolicy）。
 * 旧文件 gzip 为 {@code chunkplan-YYYY-MM-dd-N.log.gz}（同日多次轮转序号递增），
 * 不限制份数、不删除旧文件（与原版一致）。无大小阈值。
 */
public final class FeeLogFile implements FeeLogger {

    private static final Logger LOG = LoggerFactory.getLogger(FeeLogFile.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 旧文件命名：chunkplan-<日期>-<序号>.log.gz */
    private static final Pattern GZ_NAME = Pattern.compile("chunkplan-(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.log\\.gz");

    private final Path file;
    private final LongSupplier clock;
    /** 当前写入文件的日期（yyyy-MM-dd）；与本地日期不一致时触发轮转 */
    private String lastDate;

    public FeeLogFile(Path file) throws IOException {
        this(file, System::currentTimeMillis);
    }

    /** 包内可见：注入时钟，供单元测试模拟跨天 */
    FeeLogFile(Path file, LongSupplier clock) throws IOException {
        this.file = file;
        this.clock = clock;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        lastDate = today();
        // 启动轮转（对应原版 OnStartupTriggeringPolicy）：已有非空日志先压缩归档
        if (Files.exists(file) && Files.size(file) > 0) {
            rotate();
        }
    }

    @Override
    public void logFee(UUID uuid, String dimKey, long chunkKey, double speed, double fee, double total) {
        String date = today();
        if (!date.equals(lastDate)) {
            // 跨天轮转（对应原版 TimeBasedTriggeringPolicy）
            rotate();
            lastDate = date;
        }
        String line = FMT.format(Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneId.systemDefault()))
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

    private String today() {
        return LocalDate.from(Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneId.systemDefault()))
                .format(DATE_FMT);
    }

    /** 轮转：当前文件 gzip 归档为 chunkplan-<lastDate>-<N>.log.gz 并删除原文件；失败仅告警不中断计费 */
    private void rotate() {
        try {
            int n = nextIndex();
            Path gz = file.resolveSibling("chunkplan-" + lastDate + "-" + n + ".log.gz");
            try (InputStream in = Files.newInputStream(file);
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
                in.transferTo(out);
            }
            Files.delete(file);
            LOG.info("日志已轮转: {} -> {}", file.getFileName(), gz.getFileName());
        } catch (IOException e) {
            LOG.error("日志轮转失败（继续写入原文件）: {}", e.getMessage());
        }
    }

    /** 当日（lastDate）已有归档的最大序号 + 1 */
    private int nextIndex() throws IOException {
        Path dir = file.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return 1;
        }
        int max = 0;
        try (Stream<Path> s = Files.list(dir)) {
            Iterator<Path> it = s.iterator();
            while (it.hasNext()) {
                Matcher m = GZ_NAME.matcher(it.next().getFileName().toString());
                if (m.matches() && m.group(1).equals(lastDate)) {
                    max = Math.max(max, Integer.parseInt(m.group(2)));
                }
            }
        }
        return max + 1;
    }
}
