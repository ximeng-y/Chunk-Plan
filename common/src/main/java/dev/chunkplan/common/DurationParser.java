package dev.chunkplan.common;

/**
 * 时长字符串解析：支持 "5h"、"24h"、"30m"、"7d" 及纯数字（单位：秒）。
 */
public final class DurationParser {

    private DurationParser() {
    }

    /**
     * @param text 时长文本；null/空白/格式非法时抛 {@link IllegalArgumentException}
     * @return 秒数
     */
    public static long parseSeconds(String text) {
        if (text == null) {
            throw new IllegalArgumentException("时长为空");
        }
        String s = text.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("时长为空");
        }
        char unit = s.charAt(s.length() - 1);
        long multiplier;
        String numPart;
        if (Character.isDigit(unit)) {
            multiplier = 1;
            numPart = s;
        } else {
            multiplier = switch (Character.toLowerCase(unit)) {
                case 's' -> 1;
                case 'm' -> 60;
                case 'h' -> 3600;
                case 'd' -> 86400;
                default -> throw new IllegalArgumentException("未知时长单位: " + unit);
            };
            numPart = s.substring(0, s.length() - 1);
        }
        try {
            long value = Long.parseLong(numPart.trim());
            if (value <= 0) {
                throw new IllegalArgumentException("时长必须为正数: " + text);
            }
            return Math.multiplyExact(value, multiplier);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析时长: " + text, e);
        }
    }
}
