package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkPosPackerTest {

    @Test
    void roundTrip() {
        assertEquals(0L, ChunkPosPacker.pack(0, 0));
        long k = ChunkPosPacker.pack(12, -34);
        assertEquals(12, ChunkPosPacker.x(k));
        assertEquals(-34, ChunkPosPacker.z(k));
    }

    @Test
    void negativeZStoredInLowBits() {
        long k = ChunkPosPacker.pack(-5, -7);
        assertEquals(-5, ChunkPosPacker.x(k));
        assertEquals(-7, ChunkPosPacker.z(k));
    }

    @Test
    void extremeValues() {
        long k = ChunkPosPacker.pack(Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertEquals(Integer.MAX_VALUE, ChunkPosPacker.x(k));
        assertEquals(Integer.MIN_VALUE, ChunkPosPacker.z(k));
    }
}
