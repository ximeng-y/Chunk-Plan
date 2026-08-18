package dev.chunkplan.fabric;

import java.util.List;

import dev.chunkplan.common.GuiStatus;
import dev.chunkplan.common.QuotaEngine;
import dev.chunkplan.common.QuotaTiers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * ChunkPlan 客户端 GUI（Fabric 26.x，纯原版 Screen 手绘，零 mixin、零第三方 GUI 库）。
 *
 * <p>两页：用量页（所有玩家可见，等价 /chunkplan check，进度条可视化）+ 管理页（仅权限等级 2
 * 可见，覆盖 config 全部功能与 reset）。命令不删除：GUI 操作拼成命令串透传给服务端复用同一套
 * 权限/确认/配置逻辑（见 {@link ChunkPlanNetwork}）。
 *
 * <p>用户可见文案统一走 lang 文件（{@code gui.chunkplan.*}），按客户端语言由
 * {@link Component#translatable(String, Object...)} 渲染，与按键绑定的本地化方式一致。
 */
public final class ChunkPlanGuiScreen extends Screen {

    private static final long REQUEST_TIMEOUT_MILLIS = 2500L;
    private static final int BAR_H = 12;
    private static final int COL_BG = 0xFF2A2A2A;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_GRAY = 0xFFAAAAAA;
    private static final int COL_GREEN = 0xFF55FF55;
    private static final int COL_YELLOW = 0xFFFFFF55;
    private static final int COL_RED = 0xFFFF5555;
    private static final int COL_ACCENT = 0xFF55FFFF;
    private static final int COL_PANEL = 0xE0303030;

    private GuiStatus status;
    private boolean waiting;
    private long requestTimeMillis;
    private int page; // 0 = 用量，1 = 管理

    // 待确认对话框（reset / 关窗口 / 调低额度需二次确认，与命令 confirm 流一致）
    private boolean pendingConfirm;
    private Component confirmText;
    private int yesX, yesY, yesW, yesH;
    private int noX, noY, noW, noH;

    // 管理页控件
    private final Button[] tierToggle = new Button[4];
    private final Button[] tierWindow = new Button[4];
    private final EditBox[] tierLimit = new EditBox[4];
    private final Button[] tierLimitSet = new Button[4];
    private EditBox multEdit;
    private EditBox newFeeEdit;
    private EditBox familiarFeeEdit;
    private Button resetTierCycle;
    private EditBox resetTarget;
    private int resetTier; // 0 = all，1..4

    // 用户输入保留（跨状态刷新重建不丢字）；命令派发后清空以便回显服务端确认值
    private final String[] savedLimit = new String[4];
    private String savedMult;
    private String savedNewFee;
    private String savedFamiliarFee;
    private String savedResetTarget;

    public ChunkPlanGuiScreen() {
        super(Component.literal("ChunkPlan"));
    }

    @Override
    protected void init() {
        this.resetTier = 0;
        rebuild();
        requestStatus();
    }

    /** 重建控件（onStatus / 切页 / 初始化共用；不重新发请求，避免循环） */
    private void rebuild() {
        clearWidgets();
        buildTabs();
        buildPage();
    }

    private void buildTabs() {
        addRenderableWidget(Button.builder(
                Component.literal((page == 0 ? "▶ " : "")).append(Component.translatable("gui.chunkplan.tab.usage")),
                b -> switchPage(0)).bounds(10, 8, 80, 20).build());
        if (isAdmin()) {
            addRenderableWidget(Button.builder(
                    Component.literal((page == 1 ? "▶ " : "")).append(Component.translatable("gui.chunkplan.tab.admin")),
                    b -> switchPage(1)).bounds(94, 8, 80, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.chunkplan.refresh"),
                b -> requestStatus()).bounds(width - 132, 8, 56, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.chunkplan.close"),
                b -> onClose()).bounds(width - 68, 8, 58, 20).build());
    }

    private void buildPage() {
        if (page == 1) {
            buildAdmin();
        }
    }

    private void switchPage(int p) {
        this.page = p;
        rebuild();
    }

    private boolean isAdmin() {
        return status != null && status.isAdmin();
    }

    // ---------- 管理页 ----------

    private void buildAdmin() {
        if (!isAdmin()) {
            return;
        }
        int left = 12;
        int rowH = 26;

        for (int i = 0; i < 4; i++) {
            int tier = i + 1;
            boolean enabled = tierEnabled(tier);
            QuotaTiers.Tier rt = rawTier(tier);
            String curWindow = rt == null ? "" : rt.window();
            double curLimit = rt == null ? 0 : rt.limit();
            int ry = 36 + i * rowH;

            int tx = left + 96;
            tierToggle[i] = addButton(tx, ry, 52, 20,
                    enabled ? Component.translatable("gui.chunkplan.enabled")
                            : Component.translatable("gui.chunkplan.disabled"),
                    b -> toggleTier(tier, !enabled));

            int wx = tx + 58;
            tierWindow[i] = addButton(wx, ry, 74, 20,
                    Component.literal(curWindow.isEmpty() ? "—" : curWindow),
                    b -> cycleWindow(tier));
            tierWindow[i].active = enabled;

            int lx = wx + 80;
            final int idx = i;
            tierLimit[idx] = new EditBox(font, lx, ry, 56, 20, Component.empty());
            tierLimit[idx].setValue(savedLimit[idx] != null ? savedLimit[idx] : fmtLimit(curLimit));
            tierLimit[idx].setResponder(v -> savedLimit[idx] = v);
            addRenderableWidget(tierLimit[idx]);
            tierLimit[idx].active = enabled;

            int sx = lx + 62;
            tierLimitSet[i] = addButton(sx, ry, 42, 20,
                    Component.translatable("gui.chunkplan.set"), b -> setLimit(tier));
            tierLimitSet[i].active = enabled;
        }

        int gy = 36 + 4 * rowH + 6;
        addButton(left + 96, gy, 66, 20, Component.translatable("gui.chunkplan.all_on"),
                b -> allWindows(true));
        addButton(left + 168, gy, 66, 20, Component.translatable("gui.chunkplan.all_off"),
                b -> allWindows(false));
        gy += 28;
        newFeeEdit = new EditBox(font, left + 96, gy, 56, 20, Component.empty());
        newFeeEdit.setValue(savedNewFee != null ? savedNewFee : fmtNum(status == null ? 0 : status.firstEntryFee()));
        newFeeEdit.setResponder(v -> savedNewFee = v);
        addRenderableWidget(newFeeEdit);
        addButton(left + 158, gy, 42, 20, Component.translatable("gui.chunkplan.set"),
                b -> setNewFee());
        gy += 28;
        familiarFeeEdit = new EditBox(font, left + 96, gy, 56, 20, Component.empty());
        familiarFeeEdit.setValue(savedFamiliarFee != null ? savedFamiliarFee : fmtNum(status == null ? 0 : status.familiarEntryFee()));
        familiarFeeEdit.setResponder(v -> savedFamiliarFee = v);
        addRenderableWidget(familiarFeeEdit);
        addButton(left + 158, gy, 42, 20, Component.translatable("gui.chunkplan.set"),
                b -> setFamiliarFee());
        gy += 28;
        multEdit = new EditBox(font, left + 96, gy, 56, 20, Component.empty());
        multEdit.setValue(savedMult != null ? savedMult : fmtNum(status == null ? 0 : status.highSpeedMultiplier()));
        multEdit.setResponder(v -> savedMult = v);
        addRenderableWidget(multEdit);
        addButton(left + 158, gy, 42, 20, Component.translatable("gui.chunkplan.set"),
                b -> setMultiplier());
        gy += 28;
        boolean ebd = status != null && status.exemptByDefault();
        addButton(left + 96, gy, 104, 20,
                Component.translatable("gui.chunkplan.admin_billing")
                        .append(Component.literal(": "))
                        .append(ebd ? Component.translatable("gui.chunkplan.on")
                                : Component.translatable("gui.chunkplan.off")),
                b -> setExemptDefault(!ebd));
        addButton(left + 206, gy, 56, 20, Component.translatable("gui.chunkplan.reload"),
                b -> sendCommand("chunkplan reload"));
        gy += 28;
        resetTarget = new EditBox(font, left + 96, gy, 74, 20, Component.empty());
        resetTarget.setValue(savedResetTarget != null ? savedResetTarget : "@a");
        resetTarget.setResponder(v -> savedResetTarget = v);
        addRenderableWidget(resetTarget);
        resetTierCycle = addButton(left + 176, gy, 52, 20,
                Component.literal(resetTierName()), b -> cycleResetTier());
        addButton(left + 234, gy, 56, 20, Component.translatable("gui.chunkplan.reset"),
                b -> doReset());
    }

    private Button addButton(int x, int y, int w, int h, Component msg, Button.OnPress onPress) {
        return addRenderableWidget(Button.builder(msg, onPress).bounds(x, y, w, h).build());
    }

    private QuotaTiers.Tier rawTier(int tier) {
        if (status == null || status.tiers() == null || tier < 1 || tier > status.tiers().size()) {
            return null;
        }
        return status.tiers().get(tier - 1);
    }

    private boolean tierEnabled(int tier) {
        QuotaTiers.Tier t = rawTier(tier);
        return t != null && t.enabled();
    }

    private static String fmtLimit(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private static String fmtNum(double v) {
        return String.valueOf(v);
    }

    private void toggleTier(int tier, boolean enable) {
        if (!enable) {
            sendCommand("chunkplan config window tier" + tier + " off");
            showConfirm(Component.translatable("gui.chunkplan.confirm.disable_tier", tier));
        } else {
            sendCommand("chunkplan config window tier" + tier + " on");
        }
    }

    private void cycleWindow(int tier) {
        QuotaTiers.Tier t = rawTier(tier);
        if (t == null) {
            return;
        }
        List<String> presets = presets(tier);
        int idx = presets.indexOf(t.window());
        String next = presets.get((idx + 1) % presets.size());
        sendCommand("chunkplan config windowTime tier" + tier + " " + next);
    }

    private void setLimit(int tier) {
        String raw = tierLimit[tier - 1].getValue().trim();
        if (raw.isEmpty()) {
            return;
        }
        QuotaTiers.Tier t = rawTier(tier);
        double newLimit = parseDouble(raw);
        if (Double.isNaN(newLimit)) {
            return; // 非法数值：不发送，交由玩家修正
        }
        if (t != null && newLimit < t.limit()) {
            sendCommand("chunkplan config windowLimit tier" + tier + " " + raw);
            showConfirm(Component.translatable("gui.chunkplan.confirm.lower_tier", tier));
        } else {
            sendCommand("chunkplan config windowLimit tier" + tier + " " + raw);
        }
        savedLimit[tier - 1] = null; // 派发后回读服务端确认值，不再保留输入
    }

    private void setMultiplier() {
        String raw = multEdit.getValue().trim();
        if (raw.isEmpty() || Double.isNaN(parseDouble(raw))) {
            return; // 非法数值：不发送，交由玩家修正
        }
        sendCommand("chunkplan config highSpeedMultiplier " + raw);
        savedMult = null; // 派发后回读服务端确认值，不再保留输入
    }

    private void setNewFee() {
        String raw = newFeeEdit.getValue().trim();
        if (raw.isEmpty() || Double.isNaN(parseDouble(raw))) {
            return; // 非法数值：不发送，交由玩家修正
        }
        sendCommand("chunkplan config firstEntryFee " + raw);
        savedNewFee = null; // 派发后回读服务端确认值，不再保留输入
    }

    private void setFamiliarFee() {
        String raw = familiarFeeEdit.getValue().trim();
        if (raw.isEmpty() || Double.isNaN(parseDouble(raw))) {
            return; // 非法数值：不发送，交由玩家修正
        }
        sendCommand("chunkplan config familiarEntryFee " + raw);
        savedFamiliarFee = null; // 派发后回读服务端确认值，不再保留输入
    }

    private void setExemptDefault(boolean value) {
        sendCommand("chunkplan config exemptByDefault " + value);
    }

    private void allWindows(boolean enable) {
        if (!enable) {
            sendCommand("chunkplan config window all off");
            showConfirm(Component.translatable("gui.chunkplan.confirm.disable_all"));
        } else {
            sendCommand("chunkplan config window all on");
        }
    }

    private void cycleResetTier() {
        resetTier = (resetTier + 1) % 5;
        if (resetTierCycle != null) {
            resetTierCycle.setMessage(Component.literal(resetTierName()));
        }
    }

    private String resetTierName() {
        return resetTier == 0 ? "all" : "tier" + resetTier;
    }

    private void doReset() {
        String target = resetTarget.getValue().trim();
        if (target.isEmpty()) {
            return;
        }
        String cmd = "chunkplan reset " + target + (resetTier == 0 ? "" : " " + resetTierName());
        sendCommand(cmd);
        showConfirm(Component.translatable("gui.chunkplan.confirm.reset", target));
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static List<String> presets(int tier) {
        return switch (tier) {
            case 1 -> QuotaTiers.TIER1_WINDOWS;
            case 2 -> QuotaTiers.TIER2_WINDOWS;
            case 3 -> QuotaTiers.TIER3_WINDOWS;
            case 4 -> QuotaTiers.TIER4_WINDOWS;
            default -> List.of();
        };
    }

    // ---------- 状态接收 ----------

    public void onStatus(GuiStatus s) {
        this.status = s;
        this.waiting = false;
        if (s != null) {
            rebuild();
        }
    }

    private void requestStatus() {
        this.waiting = true;
        this.requestTimeMillis = System.currentTimeMillis();
        ChunkPlanFabricClient.sendRequest();
    }

    private void sendCommand(String cmd) {
        ChunkPlanFabricClient.sendCommand(cmd);
    }

    private void showConfirm(Component message) {
        this.pendingConfirm = true;
        this.confirmText = Component.empty().append(message)
                .append(Component.translatable("gui.chunkplan.confirm.hint"));
    }

    // ---------- 渲染 ----------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        extractBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 32, width, 33, 0xFF555555);
        if (page == 0) {
            renderUsage(g);
        } else {
            renderAdmin(g);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (pendingConfirm) {
            renderConfirm(g);
        }
    }

    private void renderUsage(GuiGraphicsExtractor g) {
        boolean zh = zh();
        int x = 12;
        int y = 40;
        g.text(font, Component.translatable("gui.chunkplan.usage.title"), x, y, COL_ACCENT);
        y += 16;
        if (waiting) {
            if (System.currentTimeMillis() - requestTimeMillis > REQUEST_TIMEOUT_MILLIS) {
                g.text(font, Component.translatable("gui.chunkplan.no_server"), x, y, COL_RED);
            } else {
                g.text(font, Component.translatable("gui.chunkplan.fetching"), x, y, COL_GRAY);
            }
            return;
        }
        GuiStatus s = status;
        if (s == null) {
            g.text(font, Component.translatable("gui.chunkplan.parse_error"), x, y, COL_RED);
            return;
        }
        if (s.isExempt()) {
            g.text(font, s.inExemptList()
                            ? Component.translatable("gui.chunkplan.exempt_list")
                            : Component.translatable("gui.chunkplan.exempt_admin"),
                    x, y, COL_YELLOW);
            y += 14;
        }
        if (s.lines().isEmpty()) {
            g.text(font, Component.translatable("gui.chunkplan.zero_line"), x, y, COL_GREEN);
        } else {
            for (QuotaEngine.LineStatus l : s.lines()) {
                double pct = l.limit() > 0 ? l.spent() / l.limit() * 100.0 : 0;
                int color = pct < 50 ? COL_GREEN : (pct < 75 ? COL_YELLOW : COL_RED);
                String label = ChunkPlanMessages.windowName(l.windowSeconds(), zh);
                g.text(font, Component.literal(label + "  " + String.format("%.1f / %.1f", l.spent(), l.limit())),
                        x, y, COL_TEXT);
                y += 12;
                int barW = Math.max(80, Math.min(260, width - 40));
                drawBar(g, x, y, barW, BAR_H, pct, color);
                g.text(font, Component.literal(String.format("%.0f%%", pct)), x + barW + 4, y, color);
                y += BAR_H + 2;
                if (l.nextResetMillis() > 0) {
                    g.text(font, Component.translatable("gui.chunkplan.next_reset",
                            ChunkPlanMessages.formatTime(l.nextResetMillis())), x + 12, y, COL_GRAY);
                }
                y += 12;
            }
            y += 2;
            if (s.allExceeded()) {
                g.text(font, Component.translatable("gui.chunkplan.exhausted",
                        ChunkPlanMessages.formatTime(s.recoveryMillis())), x, y, COL_RED);
            } else {
                int wp = s.worstPercent();
                Component word = wp < 50 ? Component.translatable("gui.chunkplan.adequate")
                        : (wp < 75 ? Component.translatable("gui.chunkplan.moderate")
                        : Component.translatable("gui.chunkplan.low"));
                int wc = wp < 50 ? COL_GREEN : (wp < 75 ? COL_YELLOW : COL_RED);
                g.text(font, word, x, y, wc);
            }
        }
        // 计费规则（所有玩家可见，等价 /chunkplan rules）
        y += 18;
        g.text(font, Component.translatable("gui.chunkplan.rules_title"), x, y, COL_ACCENT);
        y += 12;
        g.text(font, Component.translatable("gui.chunkplan.rule_new", String.valueOf(s.firstEntryFee())),
                x + 8, y, COL_GRAY);
        y += 11;
        g.text(font, Component.translatable("gui.chunkplan.rule_explored", String.valueOf(s.familiarEntryFee())),
                x + 8, y, COL_GRAY);
        y += 11;
        g.text(font, Component.translatable("gui.chunkplan.rule_speed", String.valueOf(s.highSpeedMultiplier())),
                x + 8, y, COL_GRAY);
    }

    private void renderAdmin(GuiGraphicsExtractor g) {
        int x = 12;
        if (!isAdmin()) {
            g.text(font, Component.translatable("gui.chunkplan.no_permission"), x, 44, COL_RED);
            return;
        }
        for (int i = 0; i < 4; i++) {
            int ry = 36 + i * 26;
            g.text(font, Component.literal("tier" + (i + 1)), x, ry + 6, COL_TEXT);
        }
        int gy = 36 + 4 * 26 + 6;
        g.text(font, Component.translatable("gui.chunkplan.all_windows"), x, gy + 6, COL_TEXT);
        g.text(font, Component.translatable("gui.chunkplan.fee_new"), x, gy + 34, COL_TEXT);
        g.text(font, Component.translatable("gui.chunkplan.fee_explored"), x, gy + 62, COL_TEXT);
        g.text(font, Component.translatable("gui.chunkplan.speed_mult"), x, gy + 90, COL_TEXT);
        g.text(font, Component.translatable("gui.chunkplan.reset_quota"), x, gy + 146, COL_TEXT);
    }

    private void renderConfirm(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, height, 0x80000000);
        int w = Math.min(width - 20, 320);
        int h = 88;
        int bx = (width - w) / 2;
        int by = (height - h) / 2;
        g.fill(bx, by, bx + w, by + h, COL_PANEL);
        g.fill(bx, by, bx + w, by + 1, 0xFFFFFFFF);
        g.text(font, Component.translatable("gui.chunkplan.confirm.title"), bx + 8, by + 8, COL_ACCENT);
        g.text(font, confirmText == null ? Component.empty() : confirmText, bx + 8, by + 28, COL_TEXT);
        int btnY = by + h - 24;
        noX = bx + w - 118;
        noY = btnY;
        noW = 52;
        noH = 18;
        g.fill(noX, noY, noX + noW, noY + noH, 0xFF555555);
        g.centeredText(font, Component.translatable("gui.chunkplan.confirm.cancel"), noX + noW / 2, noY + 5, COL_TEXT);
        yesX = bx + w - 60;
        yesY = btnY;
        yesW = 52;
        yesH = 18;
        g.fill(yesX, yesY, yesX + yesW, yesY + yesH, 0xFF2E7D32);
        g.centeredText(font, Component.translatable("gui.chunkplan.confirm.yes"), yesX + yesW / 2, yesY + 5, COL_TEXT);
    }

    private void drawBar(GuiGraphicsExtractor g, int x, int y, int w, int h, double pct, int color) {
        g.fill(x, y, x + w, y + h, COL_BG);
        int filled = (int) (w * Math.max(0, Math.min(100, pct)) / 100.0);
        if (filled > 0) {
            g.fill(x, y, x + filled, y + h, color);
        }
        g.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        g.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
    }

    // ---------- 输入 ----------

    @Override
    // 第二参为点击序列/双击标志，透传 super 处理
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
        if (pendingConfirm) {
            if (inRect(event.x(), event.y(), yesX, yesY, yesW, yesH)) {
                sendCommand("chunkplan confirm");
                pendingConfirm = false;
                return true;
            }
            if (inRect(event.x(), event.y(), noX, noY, noW, noH)) {
                pendingConfirm = false;
                return true;
            }
            return true; // 弹窗期间拦截底层点击
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (pendingConfirm && event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { // ESC 取消确认而非关闭界面
            pendingConfirm = false;
            return true;
        }
        return super.keyPressed(event);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------- 工具 ----------

    /** 中文判定（仅用于 ChunkPlanMessages.windowName 的窗口名本地化） */
    private boolean zh() {
        return ChunkPlanMessages.isChinese(Minecraft.getInstance().getLanguageManager().getSelected());
    }
}
