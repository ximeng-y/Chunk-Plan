package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * /chunkplan config 数值参数解析（坑 #30）：范围 + 最多 2 位小数。
 */
class NumericParserTest {

    private static final double EPS = 1e-9;

    @Test
    void multiplierAcceptsIntegersAndTwoDecimals() {
        assertTrue(NumericParser.parseMultiplier("1").isOk());
        assertTrue(NumericParser.parseMultiplier("1000").isOk());
        assertTrue(NumericParser.parseMultiplier("1.00").isOk());
        assertTrue(NumericParser.parseMultiplier("123.45").isOk());
        assertEquals(123.45, NumericParser.parseMultiplier("123.45").value(), EPS);
        assertEquals(1000.0, NumericParser.parseMultiplier("1000.00").value(), EPS);
    }

    @Test
    void multiplierRejectsOutOfRange() {
        assertEquals(NumericParser.Error.RANGE, NumericParser.parseMultiplier("0.99").error());
        assertEquals(NumericParser.Error.RANGE, NumericParser.parseMultiplier("1000.01").error());
        assertEquals(NumericParser.Error.RANGE, NumericParser.parseMultiplier("0").error());
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseMultiplier("-5").error()); // 负号不匹配数字格式
    }

    @Test
    void multiplierRejectsBadFormat() {
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseMultiplier("1.005").error()); // 3 位小数
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseMultiplier("1.").error());
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseMultiplier(".5").error());
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseMultiplier("abc").error());
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseMultiplier("1e3").error());
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseMultiplier("1.5.2").error());
        assertEquals(NumericParser.Error.EMPTY, NumericParser.parseMultiplier("").error());
        assertEquals(NumericParser.Error.EMPTY, NumericParser.parseMultiplier(null).error());
    }

    @Test
    void limitRangeAndPrecision() {
        assertTrue(NumericParser.parseLimit("1").isOk());
        assertTrue(NumericParser.parseLimit("999999999.99").isOk());
        assertEquals(999999999.99, NumericParser.parseLimit("999999999.99").value(), EPS);
        assertEquals(NumericParser.Error.RANGE, NumericParser.parseLimit("1000000000.00").error());
        assertEquals(NumericParser.Error.RANGE, NumericParser.parseLimit("0.5").error());
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseLimit("1.001").error());
    }

    @Test
    void feeRangeAndPrecision() {
        assertTrue(NumericParser.parseFee("0").isOk());
        assertTrue(NumericParser.parseFee("0.05").isOk());
        assertTrue(NumericParser.parseFee("1").isOk());
        assertTrue(NumericParser.parseFee("999999999.99").isOk());
        assertEquals(0.05, NumericParser.parseFee("0.05").value(), EPS);
        assertEquals(999999999.99, NumericParser.parseFee("999999999.99").value(), EPS);
        assertEquals(NumericParser.Error.RANGE, NumericParser.parseFee("1000000000.00").error());
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseFee("-0.01").error()); // 负号不匹配数字格式
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseFee("0.001").error()); // 3 位小数
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseFee("1e3").error());
        assertEquals(NumericParser.Error.FORMAT, NumericParser.parseFee("abc").error());
    }
}
