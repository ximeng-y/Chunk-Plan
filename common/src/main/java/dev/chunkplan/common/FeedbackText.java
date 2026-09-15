package dev.chunkplan.common;

/**
 * 命令反馈文本清洗（坑 #58）：壳层从命令执行期间捕获的反馈文本（可能含样式码、多行、
 * 控制字符）在此做展示安全化，再由 {@link GuiStatus.GuiFeedback} 回推给客户端 GUI。
 *
 * <p>纯 Java 零 Minecraft 依赖，与其它 common 类一致（可单测）。
 * 只做展示安全化，<b>不做任何语义加工</b>（不取反、不去重、不改写内容）。
 */
public final class FeedbackText {

    /** 截断长度上限（按 char 计，不做 codePoint 感知——GUI 单行展示，代理对边界截断仅为显示瑕疵） */
    public static final int MAX_LENGTH = 512;

    private FeedbackText() {
    }

    /**
     * 清洗规则（按序执行）：
     * <ol>
     *   <li>{@code null} 或空串 → {@code null}；</li>
     *   <li>按 {@code \n} / {@code \r} 切行，取<b>首个 trim 后非空</b>的行（多行反馈只展示第一行）；</li>
     *   <li>去掉所有 {@code §} 及其后一个字符（{@code §a}/{@code §7} 等样式码，GUI 自行按颜色渲染）；</li>
     *   <li>去掉 {@code < 0x20} 与 {@code 0x7F} 控制字符（防展示错位与日志注入）；</li>
     *   <li>{@code trim()} 后为空 → {@code null}；</li>
     *   <li>长度超过 {@value #MAX_LENGTH} 截断。</li>
     * </ol>
     * 全空（无有效文本）返回 {@code null}，调用方据此不展示反馈面板。
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String firstLine = firstNonBlankLine(raw);
        if (firstLine == null) {
            return null;
        }
        String stripped = stripFormattingAndControl(firstLine);
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() > MAX_LENGTH ? stripped.substring(0, MAX_LENGTH) : stripped;
    }

    /** 首个 trim 后非空的行；全为空行返回 null */
    private static String firstNonBlankLine(String raw) {
        for (String line : raw.split("[\n\r]+", -1)) {
            if (!line.trim().isEmpty()) {
                return line;
            }
        }
        return null;
    }

    /** 剥离样式码（§ + 后一字符）与控制字符，随后 trim */
    private static String stripFormattingAndControl(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\u00A7') {
                i++; // 跳过样式码字符本身（含串尾孤立 § 的情况）
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }
}
