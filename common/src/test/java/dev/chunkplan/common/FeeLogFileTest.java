package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 日志轮转（仿原版：启动 + 跨天触发、gz 归档、序号递增）测试。
 * 注入假时钟模拟跨天，全部走临时目录。
 */
class FeeLogFileTest {

    /** 可手动推进的假时钟 */
    static final class TestClock {
        long now;

        TestClock(long now) {
            this.now = now;
        }

        long get() {
            return now;
        }

        void advanceDays(int days) {
            now += days * 86_400_000L;
        }
    }

    private static final UUID PLAYER = UUID.randomUUID();

    /** 基准时间：2026-08-13 12:00（本地时区） */
    private static final long BASE = Instant.parse("2026-08-13T04:00:00Z").toEpochMilli();

    @TempDir
    Path tmp;

    private static String dateStr(long millis) {
        return LocalDate.from(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    private static String gunzip(Path gz) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String line(long millis) {
        return dateStr(millis) + " 12:00:00 | uuid=" + PLAYER + " | dim=minecraft:overworld"
                + " | chunk=(1,2) | speed=1.00 | fee=1.00 | total=1.00";
    }

    private Path logFile() {
        return tmp.resolve("logs").resolve("chunkplan.log");
    }

    @Test
    void crossDayRotationArchivesAndKeepsWriting() throws IOException {
        TestClock clock = new TestClock(BASE);
        FeeLogFile log = new FeeLogFile(logFile(), clock::get);
        log.logFee(PLAYER, "minecraft:overworld", ChunkPosPacker.pack(1, 2), 1.0, 1.0, 1.0);

        // 跨天（+2 天）：下一次写入先轮转归档
        clock.advanceDays(2);
        log.logFee(PLAYER, "minecraft:overworld", ChunkPosPacker.pack(1, 2), 1.0, 1.0, 1.0);

        Path gz = tmp.resolve("logs").resolve("chunkplan-" + dateStr(BASE) + "-1.log.gz");
        assertTrue(Files.exists(gz), "跨天轮转应生成 gz 归档");
        assertEquals(line(BASE) + System.lineSeparator(), gunzip(gz));
        // 新行写入新文件
        assertEquals(line(BASE + 2L * 86_400_000L) + System.lineSeparator(),
                Files.readString(logFile(), StandardCharsets.UTF_8));
    }

    @Test
    void multipleStartupsIncrementIndex() throws IOException {
        // 同日多次"启动"（构造时文件非空即轮转）：序号递增 -1、-2
        TestClock clock = new TestClock(BASE);
        new FeeLogFile(logFile(), clock::get).logFee(PLAYER, "d", ChunkPosPacker.pack(1, 2), 1.0, 1.0, 1.0);
        new FeeLogFile(logFile(), clock::get); // 第一次"重启"：文件非空 -> 轮转 -1
        new FeeLogFile(logFile(), clock::get).logFee(PLAYER, "d", ChunkPosPacker.pack(1, 2), 1.0, 1.0, 1.0);
        new FeeLogFile(logFile(), clock::get); // 第二次"重启"：轮转 -2

        Path gz1 = tmp.resolve("logs").resolve("chunkplan-" + dateStr(BASE) + "-1.log.gz");
        Path gz2 = tmp.resolve("logs").resolve("chunkplan-" + dateStr(BASE) + "-2.log.gz");
        assertTrue(Files.exists(gz1));
        assertTrue(Files.exists(gz2));
        // 原文件被压缩删除（最后一次重启时文件为空：空文件不轮转）
        assertFalse(Files.exists(logFile()));
    }

    @Test
    void emptyFileNotRotatedOnStartup() throws IOException {
        // 构造前放一个空文件：不产生 gz，文件保留
        Files.createDirectories(logFile().getParent());
        Files.writeString(logFile(), "", StandardCharsets.UTF_8);
        new FeeLogFile(logFile(), new TestClock(BASE)::get);
        assertTrue(Files.exists(logFile()));
        assertTrue(Files.list(tmp.resolve("logs")).noneMatch(p -> p.getFileName().toString().endsWith(".gz")));
    }
}
