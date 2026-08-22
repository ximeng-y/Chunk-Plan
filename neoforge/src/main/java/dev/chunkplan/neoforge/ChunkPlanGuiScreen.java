package dev.chunkplan.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.chunkplan.common.GuiStatus;
import dev.chunkplan.common.NumericParser;
import dev.chunkplan.common.QuotaEngine;
import dev.chunkplan.common.QuotaTiers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * ChunkPlan 客户端 GUI（NeoForge 1.21.1，纯原版 Screen 手绘，零 mixin、零第三方 GUI 库）。
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
    /** 补全建议框每行高度 */
    private static final int SUGGEST_ROW_H = 12;
    /** 补全建议最多行数（显示在输入框下方，防小窗口溢出） */
    private static final int SUGGEST_MAX = 6;

    private GuiStatus status;
    private boolean waiting;
    private long requestTimeMillis;
    private int page; // 0 = 用量，1 = 管理

    // 待确认对话框（reset / 关窗口 / 调低额度需二次确认，与命令 confirm 流一致）
    private boolean pendingConfirm;
    private Component confirmText;
    private int yesX, yesY, yesW, yesH;
    private int noX, noY, noW, noH;
    /** 需确认的批量命令（「设置」点击后暂存，确认弹窗点「确认」后先派发再补 /chunkplan confirm） */
    private List<String> pendingBatch;
    /** 批量命令对应档位（0 = 无档位，如重置/全部关闭）；已保存提示按档位显示 */
    private int pendingBatchTier;

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

    // 档位行「设置」待应用状态（本地先改、点「设置」才派发命令；重建后按服务端值比对回落）
    private final boolean[] pendingEnabledSet = new boolean[4];
    private final boolean[] pendingEnabled = new boolean[4];
    private final String[] pendingWindow = new String[4];
    /** 每档各自未保存（红字）/ 已保存（灰字）提示，仅本次打开期间显示 */
    private final boolean[] tierDirty = new boolean[4];
    private final boolean[] tierSavedShown = new boolean[4];

    // 重置目标自动补全（仅在线玩家名；每次打开界面不显示，输入后才出现）
    private List<String> resetSuggestions = List.of();
    private int resetSelected = -1;
    private int resetTargetX, resetTargetY, resetTargetW, resetTargetH;

    // 用户输入保留（跨状态刷新重建不丢字）；命令生效后回显服务端确认值
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
        int rowH = 32;

        for (int i = 0; i < 4; i++) {
            int tier = i + 1;
            QuotaTiers.Tier rt = rawTier(tier);
            String curWindow = rt == null ? "" : rt.window();
            double curLimit = rt == null ? 0 : rt.limit();
            final int idx = i;

            // 重建时按服务端值比对回落：已生效的「待应用」状态在此消费，界面回到服务端真相
            if (pendingEnabledSet[idx] && pendingEnabled[idx] == tierEnabled(tier)) {
                pendingEnabledSet[idx] = false;
            }
            if (pendingWindow[idx] != null && pendingWindow[idx].equals(curWindow)) {
                pendingWindow[idx] = null;
            }
            if (savedLimit[idx] != null) {
                NumericParser.Parsed p = NumericParser.parseLimit(savedLimit[idx]);
                if (p.isOk() && Double.compare(p.value(), curLimit) == 0) {
                    savedLimit[idx] = null; // 服务端已确认该值，回显格式化结果
                }
            }

            boolean effOn = effEnabled(tier);
            int ry = 36 + i * rowH;

            int tx = left + 96;
            tierToggle[i] = addButton(tx, ry, 52, 20,
                    effOn ? Component.translatable("gui.chunkplan.enabled")
                            : Component.translatable("gui.chunkplan.disabled"),
                    b -> toggleTier(tier));

            int wx = tx + 58;
            String win = pendingWindow[idx] != null ? pendingWindow[idx] : curWindow;
            tierWindow[i] = addButton(wx, ry, 74, 20,
                    Component.literal(win.isEmpty() ? "—" : win),
                    b -> cycleWindow(tier));
            tierWindow[i].active = effOn;

            int lx = wx + 80;
            tierLimit[idx] = new EditBox(font, lx, ry, 56, 20, Component.empty());
            tierLimit[idx].setValue(savedLimit[idx] != null ? savedLimit[idx] : fmtLimit(curLimit));
            tierLimit[idx].setResponder(v -> {
                savedLimit[idx] = v.trim().isEmpty() ? null : v; // 清空视为无更改，重建回显服务端值
                markTierDirty(tier);
            });
            addRenderableWidget(tierLimit[idx]);
            tierLimit[idx].active = effOn;

            int sx = lx + 62;
            tierLimitSet[i] = addButton(sx, ry, 42, 20,
                    Component.translatable("gui.chunkplan.set"), b -> applyTier(tier));
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
        gy += 28;
        resetTarget = new EditBox(font, left + 96, gy, 74, 20, Component.empty());
        resetTarget.setValue(savedResetTarget != null ? savedResetTarget : "");
        resetTarget.setResponder(v -> savedResetTarget = v);
        addRenderableWidget(resetTarget);
        resetTargetX = resetTarget.getX();
        resetTargetY = resetTarget.getY();
        resetTargetW = resetTarget.getWidth();
        resetTargetH = resetTarget.getHeight();
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

    /** 当前展示值：优先「待应用」，否则服务端值 */
    private boolean effEnabled(int tier) {
        int i = tier - 1;
        return pendingEnabledSet[i] ? pendingEnabled[i] : tierEnabled(tier);
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

    /** 栏位发生待应用更改：该档红字提示 */
    private void markTierDirty(int tier) {
        this.tierDirty[tier - 1] = true;
    }

    /** 保存后：清该档未保存标记并显示灰字「设置已保存」 */
    private void markSaved(int tier) {
        this.tierDirty[tier - 1] = false;
        this.tierSavedShown[tier - 1] = true;
    }

    /** 档位行「设置」：收集待应用更改，统一派发；关窗口/调低额度需确认 */
    private void applyTier(int tier) {
        int i = tier - 1;
        boolean curOn = tierEnabled(tier);
        boolean effOn = effEnabled(tier);
        boolean enableChanged = pendingEnabledSet[i] && pendingEnabled[i] != curOn;
        QuotaTiers.Tier t = rawTier(tier);
        String curWindow = t == null ? "" : t.window();
        double curLimit = t == null ? 0 : t.limit();
        List<String> cmds = new ArrayList<>();
        boolean needConfirm = false;
        Component confirmMsg = Component.empty();

        if (!effOn) {
            if (enableChanged) {
                cmds.add("chunkplan config window tier" + tier + " off");
                needConfirm = true;
                confirmMsg = Component.translatable("gui.chunkplan.confirm.disable_tier", tier);
            }
        } else {
            // 开启/保持开启：先启用（服务端要求窗口开启后才能改时长/上限），再改其余项
            if (enableChanged) {
                cmds.add("chunkplan config window tier" + tier + " on");
            }
            String pw = pendingWindow[i];
            if (pw != null && !pw.equals(curWindow)) {
                cmds.add("chunkplan config windowTime tier" + tier + " " + pw);
            }
            String raw = tierLimit[tier - 1].getValue().trim();
            if (!raw.isEmpty()) {
                NumericParser.Parsed p = NumericParser.parseLimit(raw);
                if (p.isOk() && Double.compare(p.value(), curLimit) != 0) {
                    cmds.add("chunkplan config windowLimit tier" + tier + " " + raw);
                    if (p.value() < curLimit) {
                        needConfirm = true;
                        confirmMsg = Component.translatable("gui.chunkplan.confirm.lower_tier", tier);
                    }
                }
            }
        }

        if (cmds.isEmpty()) {
            this.tierDirty[tier - 1] = false; // 无实际更改：清该档未保存标记但不显示「已保存」
            return;
        }
        if (needConfirm) {
            showConfirm(confirmMsg);
            this.pendingBatch = cmds; // showConfirm 会清槽位，必须在其后挂载；确认后统一派发并补 confirm
            this.pendingBatchTier = tier;
        } else {
            cmds.forEach(this::sendCommand);
            markSaved(tier);
        }
    }

    private void toggleTier(int tier) {
        int i = tier - 1;
        boolean next = !effEnabled(tier);
        pendingEnabledSet[i] = true;
        pendingEnabled[i] = next;
        tierToggle[i].setMessage(next ? Component.translatable("gui.chunkplan.enabled")
                : Component.translatable("gui.chunkplan.disabled"));
        tierWindow[i].active = next;
        tierLimit[i].active = next;
        markTierDirty(tier);
    }

    private void cycleWindow(int tier) {
        int i = tier - 1;
        QuotaTiers.Tier t = rawTier(tier);
        List<String> presets = presets(tier);
        String cur = pendingWindow[i] != null ? pendingWindow[i] : (t == null ? null : t.window());
        if (presets.isEmpty() || cur == null) {
            return;
        }
        int idx = presets.indexOf(cur);
        String next = presets.get((idx < 0 ? 0 : (idx + 1) % presets.size()));
        pendingWindow[i] = next;
        tierWindow[i].setMessage(Component.literal(next));
        markTierDirty(tier);
    }

    private void setMultiplier() {
        String raw = multEdit.getValue().trim();
        if (!NumericParser.parseMultiplier(raw).isOk()) {
            return; // 非法数值：不发送，交由玩家修正
        }
        sendCommand("chunkplan config highSpeedMultiplier " + raw);
        savedMult = null; // 派发后回读服务端确认值，不再保留输入
    }

    private void setNewFee() {
        String raw = newFeeEdit.getValue().trim();
        if (!NumericParser.parseFee(raw).isOk()) {
            return; // 非法数值：不发送，交由玩家修正
        }
        sendCommand("chunkplan config firstEntryFee " + raw);
        savedNewFee = null; // 派发后回读服务端确认值，不再保留输入
    }

    private void setFamiliarFee() {
        String raw = familiarFeeEdit.getValue().trim();
        if (!NumericParser.parseFee(raw).isOk()) {
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
            // 与档位行一致延迟派发：取消确认时不残留服务端待确认动作
            showConfirm(Component.translatable("gui.chunkplan.confirm.disable_all"));
            this.pendingBatch = List.of("chunkplan config window all off");
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
        showConfirm(Component.translatable("gui.chunkplan.confirm.reset", target));
        this.pendingBatch = List.of(cmd);
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

    // ---------- 重置目标自动补全（仅在线玩家名） ----------

    private void refreshResetSuggestions() {
        if (page != 1 || resetTarget == null || !resetTarget.isFocused()) {
            resetSuggestions = List.of();
            return;
        }
        String val = resetTarget.getValue().trim();
        if (val.isEmpty()) {
            resetSuggestions = List.of();
            return;
        }
        String lower = val.toLowerCase(Locale.ROOT);
        // 下拉在输入框下方展开：行数按窗口剩余高度动态限制，防小窗口溢出屏外
        int maxRows = Math.max(1, (height - (resetTargetY + resetTargetH + 2) - 6) / SUGGEST_ROW_H);
        resetSuggestions = onlinePlayerNames().stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(lower) && !n.equals(val))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(Math.min(SUGGEST_MAX, maxRows))
                .toList();
        if (resetSelected >= resetSuggestions.size()) {
            resetSelected = resetSuggestions.isEmpty() ? -1 : resetSuggestions.size() - 1;
        }
    }

    private List<String> onlinePlayerNames() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) {
            return List.of();
        }
        return mc.player.connection.getOnlinePlayers().stream()
                .map(p -> p.getProfile().getName())
                .filter(n -> n != null)
                .toList();
    }

    private void acceptResetSuggestion(int idx) {
        if (idx < 0 || idx >= resetSuggestions.size()) {
            return;
        }
        String name = resetSuggestions.get(idx);
        resetTarget.setValue(name);
        resetTarget.moveCursorToEnd(true);
        resetSuggestions = List.of();
        resetSelected = -1;
    }

    private void renderResetSuggestions(GuiGraphics g, int mouseX, int mouseY) {
        if (page != 1 || resetSuggestions.isEmpty()) {
            return;
        }
        int sx = resetTargetX;
        int sW = Math.max(resetTargetW + 30, 120);
        int sH = resetSuggestions.size() * SUGGEST_ROW_H;
        int sy = resetTargetY + resetTargetH + 2;
        g.fill(sx, sy, sx + sW, sy + sH, 0xFF000000);
        g.fill(sx, sy, sx + sW, sy + 1, 0xFFFFFFFF);
        g.fill(sx, sy + sH - 1, sx + sW, sy + sH, 0xFFFFFFFF);
        g.fill(sx, sy, sx + 1, sy + sH, 0xFFFFFFFF);
        g.fill(sx + sW - 1, sy, sx + sW, sy + sH, 0xFFFFFFFF);
        for (int i = 0; i < resetSuggestions.size(); i++) {
            int rowY = sy + i * SUGGEST_ROW_H;
            boolean hover = mouseX >= sx && mouseX < sx + sW && mouseY >= rowY && mouseY < rowY + SUGGEST_ROW_H;
            if (hover || i == resetSelected) {
                g.fill(sx + 1, rowY, sx + sW - 1, rowY + SUGGEST_ROW_H, 0xFF606060);
            }
            g.drawString(font, Component.literal(resetSuggestions.get(i)), sx + 6, rowY + 2, COL_TEXT);
        }
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

    private void showConfirm(Component message) {
        this.pendingBatch = null; // 槽位只服务当前确认动作，防旧批残留被误派发
        this.pendingBatchTier = 0;
        this.pendingConfirm = true;
        this.confirmText = Component.empty().append(message)
                .append(Component.translatable("gui.chunkplan.confirm.hint"));
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
        if (page == 1) {
            refreshResetSuggestions();
            renderResetSuggestions(g, mouseX, mouseY);
        }
        if (pendingConfirm) {
            renderConfirm(g);
        }
    }

    private void renderUsage(GuiGraphics g) {
        boolean zh = zh();
        int x = 12;
        int y = 40;
        g.drawString(font, Component.translatable("gui.chunkplan.usage.title"), x, y, COL_ACCENT);
        y += 16;
        if (waiting) {
            if (System.currentTimeMillis() - requestTimeMillis > REQUEST_TIMEOUT_MILLIS) {
                g.drawString(font, Component.translatable("gui.chunkplan.no_server"), x, y, COL_RED);
            } else {
                g.drawString(font, Component.translatable("gui.chunkplan.fetching"), x, y, COL_GRAY);
            }
            return;
        }
        GuiStatus s = status;
        if (s == null) {
            g.drawString(font, Component.translatable("gui.chunkplan.parse_error"), x, y, COL_RED);
            return;
        }
        if (s.isExempt()) {
            g.drawString(font, s.inExemptList()
                            ? Component.translatable("gui.chunkplan.exempt_list")
                            : Component.translatable("gui.chunkplan.exempt_admin"),
                    x, y, COL_YELLOW);
            y += 14;
        }
        if (s.lines().isEmpty()) {
            g.drawString(font, Component.translatable("gui.chunkplan.zero_line"), x, y, COL_GREEN);
        } else {
            for (QuotaEngine.LineStatus l : s.lines()) {
                double pct = l.limit() > 0 ? l.spent() / l.limit() * 100.0 : 0;
                int color = pct < 50 ? COL_GREEN : (pct < 75 ? COL_YELLOW : COL_RED);
                String label = ChunkPlanMessages.windowName(l.windowSeconds(), zh);
                g.drawString(font, Component.literal(label + "  " + String.format("%.1f / %.1f", l.spent(), l.limit())),
                        x, y, COL_TEXT);
                y += 12;
                int barW = Math.max(80, Math.min(260, width - 40));
                drawBar(g, x, y, barW, BAR_H, pct, color);
                g.drawString(font, Component.literal(String.format("%.0f%%", pct)), x + barW + 4, y, color);
                y += BAR_H + 2;
                if (l.nextResetMillis() > 0) {
                    g.drawString(font, Component.translatable("gui.chunkplan.next_reset",
                            ChunkPlanMessages.formatTime(l.nextResetMillis())), x + 12, y, COL_GRAY);
                }
                y += 12;
            }
            y += 2;
            if (s.allExceeded()) {
                g.drawString(font, Component.translatable("gui.chunkplan.exhausted",
                        ChunkPlanMessages.formatTime(s.recoveryMillis())), x, y, COL_RED);
            } else {
                int wp = s.worstPercent();
                Component word = wp < 50 ? Component.translatable("gui.chunkplan.adequate")
                        : (wp < 75 ? Component.translatable("gui.chunkplan.moderate")
                        : Component.translatable("gui.chunkplan.low"));
                int wc = wp < 50 ? COL_GREEN : (wp < 75 ? COL_YELLOW : COL_RED);
                g.drawString(font, word, x, y, wc);
            }
        }
        // 计费规则（所有玩家可见，等价 /chunkplan rules）
        y += 18;
        g.drawString(font, Component.translatable("gui.chunkplan.rules_title"), x, y, COL_ACCENT);
        y += 12;
        g.drawString(font, Component.translatable("gui.chunkplan.rule_new", String.valueOf(s.firstEntryFee())),
                x + 8, y, COL_GRAY);
        y += 11;
        g.drawString(font, Component.translatable("gui.chunkplan.rule_explored", String.valueOf(s.familiarEntryFee())),
                x + 8, y, COL_GRAY);
        y += 11;
        g.drawString(font, Component.translatable("gui.chunkplan.rule_speed", String.valueOf(s.highSpeedMultiplier())),
                x + 8, y, COL_GRAY);
    }

    private void renderAdmin(GuiGraphics g) {
        int x = 12;
        if (!isAdmin()) {
            g.drawString(font, Component.translatable("gui.chunkplan.no_permission"), x, 44, COL_RED);
            return;
        }
        for (int i = 0; i < 4; i++) {
            int ry = 36 + i * 32;
            g.drawString(font, Component.literal("tier" + (i + 1)), x, ry + 6, COL_TEXT);
            // 每档独立提示：未保存红 / 已保存灰；仅本次打开期间显示
            if (tierDirty[i]) {
                g.drawString(font, Component.translatable("gui.chunkplan.unsaved"), x, ry + 22, COL_RED);
            } else if (tierSavedShown[i]) {
                g.drawString(font, Component.translatable("gui.chunkplan.saved"), x, ry + 22, COL_GRAY);
            }
        }
        int gy = 36 + 4 * 32 + 6;
        g.drawString(font, Component.translatable("gui.chunkplan.all_windows"), x, gy + 6, COL_TEXT);
        g.drawString(font, Component.translatable("gui.chunkplan.fee_new"), x, gy + 34, COL_TEXT);
        g.drawString(font, Component.translatable("gui.chunkplan.fee_explored"), x, gy + 62, COL_TEXT);
        g.drawString(font, Component.translatable("gui.chunkplan.speed_mult"), x, gy + 90, COL_TEXT);
        g.drawString(font, Component.translatable("gui.chunkplan.reset_quota"), x, gy + 146, COL_TEXT);
        if (resetSuggestions.isEmpty()) { // 下拉弹出期间提示行被遮挡，收起后恢复
            g.drawString(font, Component.translatable("gui.chunkplan.reset_hint"), x + 96, gy + 168, COL_GRAY);
        }
    }

    private void renderConfirm(GuiGraphics g) {
        g.fill(0, 0, width, height, 0x80000000);
        int w = Math.min(width - 20, 320);
        Component text = confirmText == null ? Component.empty() : confirmText;
        // 自动换行：按可用宽度拆行，弹窗高度随行数增长（用户反馈过单行溢出换行错误）
        List<FormattedCharSequence> lines = font.split(text, w - 16);
        int h = Math.max(88, 58 + Math.max(1, lines.size()) * font.lineHeight);
        int bx = (width - w) / 2;
        int by = (height - h) / 2;
        g.fill(bx, by, bx + w, by + h, COL_PANEL);
        g.fill(bx, by, bx + w, by + 1, 0xFFFFFFFF);
        g.drawString(font, Component.translatable("gui.chunkplan.confirm.title"), bx + 8, by + 8, COL_ACCENT);
        int ty = by + 28;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, bx + 8, ty, COL_TEXT);
            ty += font.lineHeight;
        }
        int btnY = by + h - 24;
        noX = bx + w - 118;
        noY = btnY;
        noW = 52;
        noH = 18;
        g.fill(noX, noY, noX + noW, noY + noH, 0xFF555555);
        g.drawCenteredString(font, Component.translatable("gui.chunkplan.confirm.cancel"), noX + noW / 2, noY + 5, COL_TEXT);
        yesX = bx + w - 60;
        yesY = btnY;
        yesW = 52;
        yesH = 18;
        g.fill(yesX, yesY, yesX + yesW, yesY + yesH, 0xFF2E7D32);
        g.drawCenteredString(font, Component.translatable("gui.chunkplan.confirm.yes"), yesX + yesW / 2, yesY + 5, COL_TEXT);
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
                if (pendingBatch != null) {
                    // 批量命令需确认：先派发（服务端据此注册待确认动作），再补 /chunkplan confirm 执行
                    pendingBatch.forEach(this::sendCommand);
                    sendCommand("chunkplan confirm");
                    if (pendingBatchTier > 0) {
                        markSaved(pendingBatchTier);
                    }
                    pendingBatch = null;
                    pendingBatchTier = 0;
                } else {
                    sendCommand("chunkplan confirm");
                }
                pendingConfirm = false;
                return true;
            }
            if (inRect(mouseX, mouseY, noX, noY, noW, noH)) {
                pendingConfirm = false;
                pendingBatch = null;
                return true;
            }
            return true; // 弹窗期间拦截底层点击
        }
        if (!resetSuggestions.isEmpty()) {
            int sH = resetSuggestions.size() * SUGGEST_ROW_H;
            int paneY = resetTargetY + resetTargetH + 2;
            for (int i = 0; i < resetSuggestions.size(); i++) {
                if (inRect(mouseX, mouseY, resetTargetX, paneY + i * SUGGEST_ROW_H,
                        Math.max(resetTargetW + 30, 120), SUGGEST_ROW_H)) {
                    acceptResetSuggestion(i);
                    return true;
                }
            }
            // 点击建议列表之外的区域：失焦隐藏建议（点击输入框内不处理，交还 super 聚焦）
            if (!inRect(mouseX, mouseY, resetTargetX, resetTargetY, resetTargetW, resetTargetH)) {
                resetTarget.setFocused(false);
                resetSuggestions = List.of();
                resetSelected = -1;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (pendingConfirm && keyCode == 256) { // ESC 取消确认而非关闭界面
            pendingConfirm = false;
            pendingBatch = null;
            pendingBatchTier = 0;
            return true;
        }
        if (!resetSuggestions.isEmpty()) {
            switch (keyCode) {
                case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> {
                    resetSelected = (resetSelected <= 0 ? resetSuggestions.size() : resetSelected) - 1;
                    return true;
                }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> {
                    resetSelected = (resetSelected + 1) % resetSuggestions.size();
                    return true;
                }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_TAB -> {
                    acceptResetSuggestion(Math.max(resetSelected, 0));
                    return true;
                }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> {
                    resetSuggestions = List.of();
                    resetSelected = -1;
                    return true;
                }
                default -> {
                    // 其他按键透传（继续输入时实时刷新建议）
                }
            }
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

    /** 中文判定（仅用于 ChunkPlanMessages.windowName 的窗口名本地化） */
    private boolean zh() {
        return ChunkPlanMessages.isChinese(Minecraft.getInstance().getLanguageManager().getSelected());
    }
}
