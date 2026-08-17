package dev.chunkplan.neoforge;

import java.util.List;

import dev.chunkplan.common.GuiStatus;
import dev.chunkplan.common.QuotaEngine;
import dev.chunkplan.common.QuotaTiers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * ChunkPlan 客户端 GUI（NeoForge 1.21.1，纯原版 Screen 手绘，零 mixin、零第三方 GUI 库）。
 *
 * <p>两页：用量页（所有玩家可见，等价 /chunkplan check，进度条可视化）+ 管理页（仅权限等级 2
 * 可见，覆盖 config 全部功能与 reset）。命令不删除：GUI 操作拼成命令串透传给服务端复用同一套
 * 权限/确认/配置逻辑（见 {@link ChunkPlanNetwork}）。
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
    private String confirmText;
    private int yesX, yesY, yesW, yesH;
    private int noX, noY, noW, noH;

    // 管理页控件
    private final Button[] tierToggle = new Button[4];
    private final Button[] tierWindow = new Button[4];
    private final EditBox[] tierLimit = new EditBox[4];
    private final Button[] tierLimitSet = new Button[4];
    private EditBox multEdit;
    private Button resetTierCycle;
    private EditBox resetTarget;
    private int resetTier; // 0 = all，1..4

    // 用户输入保留（跨状态刷新重建不丢字）
    private final String[] savedLimit = new String[4];
    private String savedMult;
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
        boolean zh = zh();
        addRenderableWidget(Button.builder(Component.literal((page == 0 ? "▶ " : "") + t(zh, "用量", "Usage")),
                b -> switchPage(0)).bounds(10, 8, 80, 20).build());
        if (isAdmin()) {
            addRenderableWidget(Button.builder(Component.literal((page == 1 ? "▶ " : "") + t(zh, "管理", "Admin")),
                    b -> switchPage(1)).bounds(94, 8, 80, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal(t(zh, "刷新", "Refresh")),
                b -> requestStatus()).bounds(width - 132, 8, 56, 20).build());
        addRenderableWidget(Button.builder(Component.literal(t(zh, "关闭", "Close")),
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
        boolean zh = zh();
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
                    Component.literal(enabled ? t(zh, "已开启", "On") : t(zh, "已关闭", "Off")),
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
                    Component.literal(t(zh, "设置", "Set")), b -> setLimit(tier));
            tierLimitSet[i].active = enabled;
        }

        int gy = 36 + 4 * rowH + 6;
        addButton(left + 96, gy, 66, 20, Component.literal(t(zh, "全部开启", "All on")),
                b -> allWindows(true));
        addButton(left + 168, gy, 66, 20, Component.literal(t(zh, "全部关闭", "All off")),
                b -> allWindows(false));
        gy += 28;
        multEdit = new EditBox(font, left + 96, gy, 56, 20, Component.empty());
        multEdit.setValue(savedMult != null ? savedMult : fmtNum(status == null ? 0 : status.highSpeedMultiplier()));
        multEdit.setResponder(v -> savedMult = v);
        addRenderableWidget(multEdit);
        addButton(left + 158, gy, 42, 20, Component.literal(t(zh, "设置", "Set")),
                b -> setMultiplier());
        gy += 28;
        boolean ebd = status != null && status.exemptByDefault();
        addButton(left + 96, gy, 104, 20,
                Component.literal(t(zh, "管理员计费", "Admin billing") + ": " + (ebd ? t(zh, "开", "On") : t(zh, "关", "Off"))),
                b -> setExemptDefault(!ebd));
        addButton(left + 206, gy, 56, 20, Component.literal(t(zh, "重载配置", "Reload")),
                b -> sendCommand("chunkplan reload"));
        gy += 28;
        resetTarget = new EditBox(font, left + 96, gy, 74, 20, Component.empty());
        resetTarget.setValue(savedResetTarget != null ? savedResetTarget : "@a");
        resetTarget.setResponder(v -> savedResetTarget = v);
        addRenderableWidget(resetTarget);
        resetTierCycle = addButton(left + 176, gy, 52, 20,
                Component.literal(resetTierName()), b -> cycleResetTier());
        addButton(left + 234, gy, 56, 20, Component.literal(t(zh, "重置", "Reset")),
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
            showConfirm("将关闭 tier" + tier + " 窗口并清空该窗口所有玩家记录，",
                    "This will disable tier" + tier + " and clear all players' records for it, ");
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
        if (t != null && !Double.isNaN(newLimit) && newLimit < t.limit()) {
            sendCommand("chunkplan config windowLimit tier" + tier + " " + raw);
            showConfirm("将调低 tier" + tier + " 额度，可能引发部分玩家被无警告踢出，",
                    "This will lower tier" + tier + " limit; some players may be kicked without warning, ");
        } else {
            sendCommand("chunkplan config windowLimit tier" + tier + " " + raw);
        }
    }

    private void setMultiplier() {
        String raw = multEdit.getValue().trim();
        if (!raw.isEmpty()) {
            sendCommand("chunkplan config highSpeedMultiplier " + raw);
        }
    }

    private void setExemptDefault(boolean value) {
        sendCommand("chunkplan config exemptByDefault " + value);
    }

    private void allWindows(boolean enable) {
        if (!enable) {
            sendCommand("chunkplan config window all off");
            showConfirm("将关闭全部窗口并清空所有玩家记录，额度限制将停止。",
                    "This will disable all windows and clear all players' records; quota limits will stop. ");
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
        showConfirm("将重置 " + target + " 的探索额度，", "This will reset " + target + "'s exploration quota, ");
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
        ChunkPlanClient.sendRequest();
    }

    private void sendCommand(String cmd) {
        ChunkPlanClient.sendCommand(cmd);
    }

    private void showConfirm(String zhText, String enText) {
        this.pendingConfirm = true;
        this.confirmText = t(zhText, enText) + t("点击确认执行（60 秒内有效）", " Click to confirm (valid for 60s)");
    }

    // ---------- 渲染 ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 32, width, 33, 0xFF555555);
        if (page == 0) {
            renderUsage(g);
        } else {
            renderAdmin(g);
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (pendingConfirm) {
            renderConfirm(g);
        }
    }

    private void renderUsage(GuiGraphics g) {
        boolean zh = zh();
        int x = 12;
        int y = 40;
        g.drawString(font, t(zh, "探索额度状态", "Exploration Quota"), x, y, COL_ACCENT);
        y += 16;
        if (waiting) {
            if (System.currentTimeMillis() - requestTimeMillis > REQUEST_TIMEOUT_MILLIS) {
                g.drawString(font, t(zh, "未检测到服务器 ChunkPlan（或版本不匹配），命令仍可用",
                        "No server ChunkPlan detected (or version mismatch); commands still work"), x, y, COL_RED);
            } else {
                g.drawString(font, t(zh, "正在获取额度…", "Fetching quota..."), x, y, COL_GRAY);
            }
            return;
        }
        GuiStatus s = status;
        if (s == null) {
            g.drawString(font, t(zh, "尚未连接服务器", "Not connected to a server"), x, y, COL_GRAY);
            return;
        }
        if (s.isExempt()) {
            g.drawString(font, s.inExemptList()
                            ? t(zh, "[豁免] 你在豁免名单中，不受额度限制", "[Exempt] You are in the exempt list; quota limits do not apply")
                            : t(zh, "[豁免] 你当前是管理员，不受额度限制", "[Exempt] You are an operator; quota limits do not apply"),
                    x, y, COL_YELLOW);
            y += 14;
        }
        if (s.lines().isEmpty()) {
            g.drawString(font, t(zh, "所有探索窗口均已关闭，当前无额度限制", "All quota windows are disabled; no quota limits in effect"),
                    x, y, COL_GREEN);
        } else {
            for (QuotaEngine.LineStatus l : s.lines()) {
                double pct = l.limit() > 0 ? l.spent() / l.limit() * 100.0 : 0;
                int color = pct < 50 ? COL_GREEN : (pct < 75 ? COL_YELLOW : COL_RED);
                String label = ChunkPlanMessages.windowName(l.windowSeconds(), zh);
                g.drawString(font, label + "  " + String.format("%.1f / %.1f", l.spent(), l.limit()), x, y, COL_TEXT);
                y += 12;
                int barW = Math.max(80, Math.min(260, width - 40));
                drawBar(g, x, y, barW, BAR_H, pct, color);
                g.drawString(font, String.format("%.0f%%", pct), x + barW + 4, y, color);
                y += BAR_H + 2;
                if (l.nextResetMillis() > 0) {
                    g.drawString(font, t(zh, "下次重置：", "Next reset: ") + ChunkPlanMessages.formatTime(l.nextResetMillis()),
                            x + 12, y, COL_GRAY);
                }
                y += 12;
            }
            y += 2;
            if (s.allExceeded()) {
                g.drawString(font, t(zh, "已耗尽，预计 ", "Exhausted, recovers at ") + ChunkPlanMessages.formatTime(s.recoveryMillis()),
                        x, y, COL_RED);
            } else {
                int wp = s.worstPercent();
                String word = wp < 50 ? t(zh, "额度充足，可正常探索", "Plenty of quota")
                        : (wp < 75 ? t(zh, "额度中等，请注意控制", "Moderate quota, watch spending")
                        : t(zh, "额度不足，请谨慎探索", "Quota is low, explore carefully"));
                int wc = wp < 50 ? COL_GREEN : (wp < 75 ? COL_YELLOW : COL_RED);
                g.drawString(font, word, x, y, wc);
            }
        }
        // 计费规则（所有玩家可见，等价 /chunkplan rules）
        y += 18;
        g.drawString(font, t(zh, "计费规则", "Billing rules"), x, y, COL_ACCENT);
        y += 12;
        g.drawString(font, t(zh, "新踏足区块消耗 ", "New chunk costs ") + String.valueOf(s.firstEntryFee()), x + 8, y, COL_GRAY);
        y += 11;
        g.drawString(font, t(zh, "已踏足区块消耗 ", "Explored chunk costs ") + String.valueOf(s.familiarEntryFee()), x + 8, y, COL_GRAY);
        y += 11;
        g.drawString(font, t(zh, "高速移动倍率 ", "High-speed multiplier ") + String.valueOf(s.highSpeedMultiplier()) + "x",
                x + 8, y, COL_GRAY);
    }

    private void renderAdmin(GuiGraphics g) {
        boolean zh = zh();
        int x = 12;
        if (!isAdmin()) {
            g.drawString(font, t(zh, "无权限访问管理页", "No permission to view admin page"), x, 44, COL_RED);
            return;
        }
        for (int i = 0; i < 4; i++) {
            int ry = 36 + i * 26;
            g.drawString(font, "tier" + (i + 1), x, ry + 6, COL_TEXT);
        }
        int gy = 36 + 4 * 26 + 6;
        g.drawString(font, t(zh, "全部窗口", "All windows"), x, gy + 6, COL_TEXT);
        g.drawString(font, t(zh, "高速倍率", "Speed ×"), x, gy + 34, COL_TEXT);
        g.drawString(font, t(zh, "重置额度", "Reset quota"), x, gy + 90, COL_TEXT);
    }

    private void renderConfirm(GuiGraphics g) {
        boolean zh = zh();
        g.fill(0, 0, width, height, 0x80000000);
        int w = Math.min(width - 20, 320);
        int h = 88;
        int bx = (width - w) / 2;
        int by = (height - h) / 2;
        g.fill(bx, by, bx + w, by + h, COL_PANEL);
        g.fill(bx, by, bx + w, by + 1, 0xFFFFFFFF);
        g.drawString(font, t(zh, "确认操作", "Confirm action"), bx + 8, by + 8, COL_ACCENT);
        g.drawString(font, confirmText == null ? "" : confirmText, bx + 8, by + 28, COL_TEXT);
        int btnY = by + h - 24;
        noX = bx + w - 118;
        noY = btnY;
        noW = 52;
        noH = 18;
        g.fill(noX, noY, noX + noW, noY + noH, 0xFF555555);
        g.drawCenteredString(font, t(zh, "取消", "Cancel"), noX + noW / 2, noY + 5, COL_TEXT);
        yesX = bx + w - 60;
        yesY = btnY;
        yesW = 52;
        yesH = 18;
        g.fill(yesX, yesY, yesX + yesW, yesY + yesH, 0xFF2E7D32);
        g.drawCenteredString(font, t(zh, "确认", "Yes"), yesX + yesW / 2, yesY + 5, COL_TEXT);
    }

    private void drawBar(GuiGraphics g, int x, int y, int w, int h, double pct, int color) {
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (pendingConfirm) {
            if (inRect(mouseX, mouseY, yesX, yesY, yesW, yesH)) {
                sendCommand("chunkplan confirm");
                pendingConfirm = false;
                return true;
            }
            if (inRect(mouseX, mouseY, noX, noY, noW, noH)) {
                pendingConfirm = false;
                return true;
            }
            return true; // 弹窗期间拦截底层点击
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (pendingConfirm && keyCode == 256) { // ESC 取消确认而非关闭界面
            pendingConfirm = false;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------- 工具 ----------

    private boolean zh() {
        return ChunkPlanMessages.isChinese(Minecraft.getInstance().getLanguageManager().getSelected());
    }

    private String t(String zhText, String enText) {
        return t(zh(), zhText, enText);
    }

    private static String t(boolean zh, String zhText, String enText) {
        return zh ? zhText : enText;
    }
}
