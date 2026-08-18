package dev.chunkplan.common;

/**
 * /chunkplan config 数值参数解析（坑 #30，common 纯 Java，双壳共用，可单测）：
 * 允许整数或最多 2 位小数（1.005 拒绝），范围校验；
 * 返回结构化错误（EMPTY/FORMAT/RANGE），壳层按此渲染双语文案。
 */
public final class NumericParser {

    /** 解析错误类型（壳层按此渲染双语错误文案） */
    public enum Error {
        EMPTY, FORMAT, RANGE
    }

    /** 解析结果：value（错误时无意义）+ error（null 表示成功） */
    public record Parsed(double value, Error error) {
        public boolean isOk() {
            return error == null;
        }
    }

    /** 高速移动额度倍率：1.00 ~ 1000.00 */
    public static Parsed parseMultiplier(String s) {
        return parse(s, 1.0, 1000.00);
    }

    /** 窗口额度上限：1.00 ~ 999999999.99（double 精度足够，无溢出） */
    public static Parsed parseLimit(String s) {
        return parse(s, 1.0, 999999999.99);
    }

    /** 每区块费用（新区块/旧区块）：0.00 ~ 999999999.99（familiarEntryFee 默认 0.05 允许 <1） */
    public static Parsed parseFee(String s) {
        return parse(s, 0.0, 999999999.99);
    }

    private static Parsed parse(String s, double min, double max) {
        if (s == null || s.isEmpty()) {
            return new Parsed(0, Error.EMPTY);
        }
        // 允许无小数点或最多 2 位小数；"5."、".5"、科学计数法等拒绝
        if (!s.matches("\\d+(\\.\\d{1,2})?")) {
            return new Parsed(0, Error.FORMAT);
        }
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return new Parsed(0, Error.FORMAT);
        }
        if (!Double.isFinite(v) || v < min || v > max) {
            return new Parsed(0, Error.RANGE);
        }
        return new Parsed(v, null);
    }

    private NumericParser() {
    }
}
