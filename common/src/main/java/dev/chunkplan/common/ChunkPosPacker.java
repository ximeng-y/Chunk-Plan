package dev.chunkplan.common;

/**
 * 区块坐标打包工具：与 Minecraft 内部 chunkPos 一致，
 * 高 32 位存 x、低 32 位存 z（z 按无符号位模式存储）。
 */
public final class ChunkPosPacker {

    private ChunkPosPacker() {
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }
}
