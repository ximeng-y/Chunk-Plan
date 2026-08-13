package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 坑 #27：写前 .bak 备份（覆盖式最多一个）+ 损坏兜底恢复测试 */
class AtomicFileTest {

    @TempDir
    Path tmp;

    private static final Logger LOG = LoggerFactory.getLogger(AtomicFileTest.class);

    /** 测试用简单 DTO */
    static final class Dto {
        int a;
        String b;
    }

    private Path file() {
        return tmp.resolve("data.json");
    }

    private Path bak() {
        return tmp.resolve("data.json.bak");
    }

    @Test
    void writeBacksUpPreviousContentWithSingleBak() throws Exception {
        AtomicFile.write(file(), "{\"a\":1}");
        // 首次写入无现有文件，不产生 .bak
        assertFalse(Files.exists(bak()));
        AtomicFile.write(file(), "{\"a\":2}");
        assertEquals("{\"a\":1}", Files.readString(bak()));
        AtomicFile.write(file(), "{\"a\":3}");
        // 覆盖式：.bak 恒为上一版，且同一文件 .bak 最多一个
        assertEquals("{\"a\":2}", Files.readString(bak()));
        try (var s = Files.list(tmp)) {
            assertEquals(1, s.filter(p -> p.getFileName().toString().endsWith(".bak")).count());
        }
    }

    @Test
    void writeNoBackupNeverCreatesOrUpdatesBak() throws Exception {
        AtomicFile.write(file(), "{\"a\":1}");
        AtomicFile.writeNoBackup(file(), "{\"a\":2}");
        assertFalse(Files.exists(bak()));
        // 下次普通写备份的是 writeNoBackup 写出的内容（writeNoBackup 不污染 .bak 语义）
        AtomicFile.write(file(), "{\"a\":3}");
        assertEquals("{\"a\":2}", Files.readString(bak()));
    }

    @Test
    void readJsonPrefersMainFile() throws Exception {
        AtomicFile.write(file(), "{\"a\":1}");
        Dto dto = AtomicFile.readJson(file(), Dto.class, "测试数据", LOG);
        assertNotNull(dto);
        assertEquals(1, dto.a);
        // 主文件完好时不读 .bak，也不产生 .bak
        assertFalse(Files.exists(bak()));
    }

    @Test
    void readJsonRestoresFromBackupAndRepairsMainFile() throws Exception {
        AtomicFile.write(file(), "{\"a\":1}");
        AtomicFile.write(file(), "{\"a\":2}"); // .bak = {"a":1}
        Files.writeString(file(), "{broken json", StandardCharsets.UTF_8); // 直接损坏主文件
        Dto dto = AtomicFile.readJson(file(), Dto.class, "测试数据", LOG);
        assertNotNull(dto);
        assertEquals(1, dto.a); // 从 .bak 恢复（上一版）
        // 主文件被写回修复（恢复 .bak 原文），.bak 保留不动
        assertEquals("{\"a\":1}", Files.readString(file()));
        assertEquals("{\"a\":1}", Files.readString(bak()));
    }

    @Test
    void readJsonReturnsNullWhenBothBroken() throws Exception {
        Files.writeString(file(), "{broken json", StandardCharsets.UTF_8);
        Files.writeString(bak(), "also broken", StandardCharsets.UTF_8);
        assertNull(AtomicFile.readJson(file(), Dto.class, "测试数据", LOG));
    }

    @Test
    void readJsonReturnsNullWhenMissing() {
        assertNull(AtomicFile.readJson(file(), Dto.class, "测试数据", LOG));
    }
}
