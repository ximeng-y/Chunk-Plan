package dev.chunkplan.common;

/**
 * 区块坐标打包工具：与 Minecraft 内部 {@code ChunkPos.asLong} 位序一致
 * （x 低 32 位、z 高 32 位，z 按无符号位模式存储）。
 * 持久化数据（explored 集合）绑定此位序，不得再改动，否则旧数据将解码成错误区块。
 */
public final class ChunkPosPacker {

    private ChunkPosPacker() {
    }

    /** 等价于 {@code ChunkPos.asLong(x, z)}（common 零 MC 依赖，自行实现） */
    public static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    public static int x(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    public static int z(long key) {
        return (int) (key >>> 32);
    }
}
