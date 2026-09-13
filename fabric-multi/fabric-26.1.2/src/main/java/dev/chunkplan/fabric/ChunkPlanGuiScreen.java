package dev.chunkplan.fabric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.chunkplan.common.DimensionStore;
import dev.chunkplan.common.GuiStatus;
import dev.chunkplan.common.NumericParser;
import dev.chunkplan.common.QuotaEngine;
import dev.chunkplan.common.QuotaTiers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

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
    /** 补全建议框每行高度 */
    private static final int SUGGEST_ROW_H = 12;
    /** 补全建议最多行数（显示在输入框下方，防小窗口溢出） */
    private static final int SUGGEST_MAX = 6;

    private GuiStatus status;
    private boolean waiting;
    private long requestTimeMillis;
    private int page; // 0 = 用量，1 = 管理，2 = 维度（仅管理员，issue #3）

    // 待确认对话框（reset / 关窗口 / 调低额度需二次确认，与命令 confirm 流一致）
    private boolean pendingConfirm;
    private Component confirmText;
    private int yesX, yesY, yesW, yesH;
    private int noX, noY, noW, noH;
    /** 需确认的批量命令（「设置」点击后暂存，确认弹窗点「确认」后先派发再补 /chunkplan confirm） */
    private List<String> pendingBatch;
    /** 批量命令对应档位（0 = 无档位，如重置/全部关闭）；已保存提示按档位显示 */
    private int pendingBatchTier;
    /** 批量命令是否跳过补发 confirm（预设删除无服务端确认流，补发只会报"无待确认操作"） */
    private boolean pendingSkipConfirm;

    // 管理页控件
    private final EditBox[] tierLimit = new EditBox[4];
    private final Button[] tierLimitSet = new Button[4];
    /** 档位行手绘下拉条命中区（[tier] = {x,y,w,h}）：开关条恒可点，窗口条随该档开关启用 */
    private final int[][] tierToggleRect = new int[4][4];
    private final int[][] tierWindowRect = new int[4][4];
    private final boolean[] tierWindowClickable = new boolean[4];
    private EditBox multEdit;
    private EditBox newFeeEdit;
    private EditBox familiarFeeEdit;
    private Button resetTierCycle;
    private EditBox resetTarget;
    private int resetTier; // 0 = all，1..4
    // 预设区控件（issue #1、#2）
    private Button presetCycle;
    private EditBox presetNameEdit;
    private EditBox presetTarget;

    // 档位行「设置」待应用状态（本地先改、点「设置」才派发命令；重建后按服务端值比对回落）
    private final boolean[] pendingEnabledSet = new boolean[4];
    private final boolean[] pendingEnabled = new boolean[4];
    private final String[] pendingWindow = new String[4];
    /** 每档各自未保存（红字）/ 已保存（灰字）提示，仅本次打开期间显示 */
    private final boolean[] tierDirty = new boolean[4];
    private final boolean[] tierSavedShown = new boolean[4];

    // 重置目标自动补全（仅在线玩家名；每次打开界面不显示，输入后才出现；
    // 补全机制为"当前聚焦的补全输入框"统一服务：resetTarget 或 presetTarget）
    private List<String> resetSuggestions = List.of();
    private int resetSelected = -1;
    private int resetTargetX, resetTargetY, resetTargetW, resetTargetH;
    private EditBox suggestOwner;

    // 预设区状态（issue #1、#2）
    /** 当前选中预设名（跨重建保留；null/不在列表时显示首个） */
    private String selectedPreset;
    // 用户输入保留（跨状态刷新重建不丢字）；命令生效后回显服务端确认值
    private final String[] savedLimit = new String[4];
    private String savedMult;
    private String savedNewFee;
    private String savedFamiliarFee;
    private String savedResetTarget;
    private String savedPresetName;
    private String savedPresetTarget;

    // ---------- 维度页（issue #3） ----------
    private Button dimModeButton;
    private Button redirectButton;
    /** 重定向 3 槽位手绘下拉条命中区（[slot] = {x,y,w,h}）与可点性（须自首选起连续） */
    private final int[][] slotBarRect = new int[3][4];
    private final boolean[] slotBarClickable = new boolean[3];
    private Button dimEditCycle;
    private final EditBox[] dimTierLimit = new EditBox[4];
    private final Button[] dimTierSet = new Button[4];
    private final int[][] dimTierToggleRect = new int[4][4];
    private final int[][] dimTierWindowRect = new int[4][4];
    private final boolean[] dimTierWindowClickable = new boolean[4];
    /** 列表滚动行数（滚轮） */
    private int dimScroll;
    /** 待切换的维度模式（true=独立 false=共享；null=无）：随「保存」按钮批量派发 */
    private Boolean pendingDimMode;
    /** 保存动作是否已派发（驱动「坐标配置已保存」灰字；未保存红字由 dimCoordsDirty() 现算） */
    private boolean dimCoordsSavedShown;
    /** 每维度坐标输入保留（key=dim -> [x,y,z]；非 null 元素 = 已编辑过，可能是空串 = 玩家清空） */
    private final Map<String, String[]> savedDimCoords = new HashMap<>();
    /** 每维度计费开关的待保存值（key=dim；与坐标/模式同批派发，不再即时生效——用户反馈不一致） */
    private final Map<String, Boolean> pendingBilling = new HashMap<>();
    /** 每维度档位编辑状态（key=dim；结构与管理页档位行一致） */
    private final Map<String, DimTierEdit> dimEdits = new HashMap<>();
    /** 底部档位编辑器当前选中维度（null = 第一个） */
    private String selectedDim;

    // 维度真下拉（用量页查看维度 + 维度页编辑器选维度 + 重定向槽位 + 档位开关，共用一套展开状态）
    // dimDropdownPage：0 = 用量页维度 1 = 管理页补全（未用） 2 = 维度页编辑器 3 = 重定向槽 4 = 档位开关
    private boolean dimDropdownOpen;
    private int dimDropdownPage;
    private int dimDropdownSelected = -1;
    private int dimDropX, dimDropY, dimDropW, dimDropH;
    private int dropSrcX, dropSrcY, dropSrcW, dropSrcH;
    /** 下拉展开时高亮的行索引（-1 = 无）：page 4 复用 dimDropdownSelected 表示当前生效项 */
    private int redirectSlotEditing = -1;
    /** 档位开关下拉的宿主信息：page 4 时记录哪一行的哪个条（管理页） */
    private int toggleTierRow = -1;
    /** 档位开关下拉的宿主信息：非 null = 维度页该维度的档位行 */
    private String toggleTierDim;
    /** 用量页当前选中维度（null = 跟随当前所在维度；每次状态刷新重置，无记忆） */
    private String usageDim;
    /** 待确认批量命令所属维度（非 null = 维度页档位编辑器发起，确认后按维度状态标记已保存） */
    private String pendingBatchDim;

    /** 维度页档位编辑器状态（与管理页档位行的全局数组对应，按维度隔离） */
    private static final class DimTierEdit {
        final boolean[] pendingEnabledSet = new boolean[4];
        final boolean[] pendingEnabled = new boolean[4];
        final String[] pendingWindow = new String[4];
        final boolean[] dirty = new boolean[4];
        final boolean[] savedShown = new boolean[4];
        final String[] savedLimit = new String[4];
    }

    public ChunkPlanGuiScreen() {
        super(Component.literal("ChunkPlan"));
    }

    @Override
    protected void init() {
        this.resetTier = 0;
        if (isVersionMismatch()) {
            // 版本不匹配兜底页：不建控件也不发请求（服务端已确认协议不符），避免空数据控件残影
            return;
        }
        rebuild();
        requestStatus();
    }

    /** 当前是否处于版本不匹配兜底状态（decode 返回的版本横幅） */
    private boolean isVersionMismatch() {
        return status != null && status.versionMismatch();
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
            addRenderableWidget(Button.builder(
                    Component.literal((page == 2 ? "▶ " : "")).append(Component.translatable("gui.chunkplan.tab.dimensions")),
                    b -> switchPage(2)).bounds(178, 8, 80, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.chunkplan.refresh"),
                b -> requestStatus()).bounds(width - 132, 8, 56, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.chunkplan.close"),
                b -> onClose()).bounds(width - 68, 8, 58, 20).build());
    }

    private void buildPage() {
        if (page == 1) {
            buildAdmin();
        } else if (page == 2) {
            buildDimensions();
        }
    }

    private void switchPage(int p) {
        this.page = p;
        resetDropdownState(); // 跨页关闭下拉（展开状态按页锚定）；rebuild 由下行统一做
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
        // 维度独立模式下全局额度线不生效：档位区隐藏（issue #3，与命令层 config window* 阻止同步）；
        // 费率/倍率/豁免/重置/预设(按玩家)保持可用，档位配置由维度页接管
        boolean independent = status != null && status.dimensionMode() == 1;
        int gy;
        if (independent) {
            gy = 64; // 顶部渲染侧留一行提示文字
        } else {
            java.util.Arrays.fill(tierLimit, null); // 被下拉覆盖的行不建控件：先清引用防误用旧实例
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
                // 展开中的下拉覆盖该行时不建控件：被覆盖内容一律不画（文本层浮于填充之上，坑 #47）。
                // 探测框只取本行控件本身（高 20），不含行间空隙——否则会把展开源那一行自己也算作被覆盖
                if (coveredByDropdown(left, ry, 420, 20)) {
                    continue; // 逐行判定：下拉较短时其下方的行仍要正常显示
                }

                // 开关与窗口都是手绘下拉条（非 Button）：与用量页维度选择器同一观感（用户反馈过
                // 原生 Button 不像下拉框）
                int tx = left + 96;
                setRect(tierToggleRect[i], tx, ry, 52, 20);
                int wx = tx + 58;
                String win = pendingWindow[idx] != null ? pendingWindow[idx] : curWindow;
                setRect(tierWindowRect[i], wx, ry, 74, 20);
                tierWindowClickable[i] = effOn;

                int lx = wx + 80;
                tierLimit[idx] = new EditBox(font, lx, ry, 56, 20, Component.empty());
                tierLimit[idx].setValue(savedLimit[idx] != null ? savedLimit[idx] : fmtLimit(curLimit));
                tierLimit[idx].setResponder(v -> {
                    savedLimit[idx] = v.trim().isEmpty() ? null : v; // 清空视为无更改，重建回显服务端值
                    markTierDirtyIfChanged(tier);
                });
                addRenderableWidget(tierLimit[idx]);
                tierLimit[idx].active = effOn;

                int sx = lx + 62;
                tierLimitSet[i] = addButton(sx, ry, 42, 20,
                        Component.translatable("gui.chunkplan.set"), b -> applyTier(tier));
            }

            gy = 36 + 4 * rowH + 6;
            addButton(left + 96, gy, 66, 20, Component.translatable("gui.chunkplan.all_on"),
                    b -> allWindows(true));
            addButton(left + 168, gy, 66, 20, Component.translatable("gui.chunkplan.all_off"),
                    b -> allWindows(false));
            gy += 28;
        }
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

        // ---------- 预设区（issue #1、#2） ----------
        gy += 28;
        // 行 1：循环选择预设 → 应用到全体（写全局配置，需确认；独立模式下全局额度线不生效，按钮隐藏）
        // / 删除（本地确认，无服务端 confirm 流）
        presetCycle = addButton(left + 96, gy, 84, 20, Component.literal(selectedPresetName()), b -> cyclePreset());
        if (!independent) {
            addButton(left + 186, gy, 66, 20, Component.translatable("gui.chunkplan.preset_apply"),
                    b -> applyPresetAll());
        }
        addButton(left + 258, gy, 52, 20, Component.translatable("gui.chunkplan.preset_delete"),
                b -> deletePreset());
        gy += 28;
        // 行 2：把当前全局配置保存为预设（名称客户端预校验，服务端权威）
        presetNameEdit = new EditBox(font, left + 96, gy, 74, 20, Component.empty());
        presetNameEdit.setValue(savedPresetName != null ? savedPresetName : "");
        presetNameEdit.setResponder(v -> savedPresetName = v);
        addRenderableWidget(presetNameEdit);
        addButton(left + 176, gy, 52, 20, Component.translatable("gui.chunkplan.preset_save"),
                b -> savePreset());
        gy += 28;
        // 行 3：按玩家应用/恢复默认（目标默认在线玩家补全；预设名取行 1 当前选中）
        presetTarget = new EditBox(font, left + 96, gy, 74, 20, Component.empty());
        presetTarget.setValue(savedPresetTarget != null ? savedPresetTarget : "");
        presetTarget.setResponder(v -> savedPresetTarget = v);
        addRenderableWidget(presetTarget);
        addButton(left + 176, gy, 52, 20, Component.translatable("gui.chunkplan.preset_assign"),
                b -> assignPreset());
        addButton(left + 234, gy, 56, 20, Component.translatable("gui.chunkplan.preset_clear"),
                b -> clearPresetAssign());
    }

    private Button addButton(int x, int y, int w, int h, Component msg, Button.OnPress onPress) {
        return addRenderableWidget(Button.builder(msg, onPress).bounds(x, y, w, h).build());
    }

    private static void setRect(int[] rect, int x, int y, int w, int h) {
        rect[0] = x;
        rect[1] = y;
        rect[2] = w;
        rect[3] = h;
    }

    private static boolean inRect(double mx, double my, int[] r) {
        return inRect(mx, my, r[0], r[1], r[2], r[3]);
    }

    /**
     * 手绘下拉条（黑底 + 四边白描边 + 右端 ▼）：非 Button，与用量页维度选择器同一观感。
     * 用户反馈原生 Button 的开关/窗口控件不像下拉框，故统一为手绘条（坑 #49）。
     */
    private void drawSelectBar(GuiGraphicsExtractor g, int x, int y, int w, int h, Component label, boolean grey) {
        g.fill(x, y, x + w, y + h, COL_BG);
        g.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        g.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        g.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
        int color = grey ? COL_GRAY : COL_TEXT;
        String shown = font.plainSubstrByWidth(label.getString(), w - 20);
        g.text(font, Component.literal(shown), x + 4, y + (h - 8) / 2, color);
        g.text(font, Component.literal("▼"), x + w - 10, y + (h - 8) / 2, grey ? COL_GRAY : COL_ACCENT);
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

    /**
     * 输入框值确实变了才打「未保存」标记。原版 EditBox 的 mouseClicked → onClick → moveCursorTo
     * 会无条件调用 responder（EditBox.java:243），光聚焦不动也会回调一次——不加比对会出现
     * 「点一下输入框就红字报未保存」（用户实测反馈，遗留 bug）。
     */
    private void markTierDirtyIfChanged(int tier) {
        int i = tier - 1;
        QuotaTiers.Tier t = rawTier(tier);
        String saved = t == null ? "" : fmtLimit(t.limit());
        String typed = tierLimit[i] == null ? "" : tierLimit[i].getValue().trim();
        if (typed.isEmpty()) {
            return; // 清空视为无更改（applyTier 同样跳过空值），重建回显服务端值
        }
        if (!typed.equals(saved)) {
            markTierDirty(tier);
        }
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
        toggleTierRow = tier - 1;
        toggleTierDim = null;
        openDimDropdown(4, tierToggleRect[tier - 1]);
        rebuild(); // 被下拉覆盖的行不再建控件：该环境文本浮于后画填充之上，仅靠绘制顺序遮不住
    }

    /** 档位开关下拉选中：仅改本地待应用状态，仍须点「设置」才落盘（沿用既有语义） */
    private void setTierEnabled(int tier, boolean next) {
        int i = tier - 1;
        pendingEnabledSet[i] = true;
        pendingEnabled[i] = next;
        markTierDirty(tier);
        rebuild(); // 窗口条/额度输入框的灰显随开关联动，重建刷新
    }

    private void cycleWindow(int tier) {
        if (!effEnabled(tier)) {
            return;
        }
        toggleTierRow = tier - 1;
        toggleTierDim = null;
        openDimDropdown(5, tierWindowRect[tier - 1]);
        rebuild();
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

    // ---------- 预设区（issue #1、#2） ----------

    /** 预设名列表（仅管理员请求时服务端下发；非空才有预设可用） */
    private List<String> presetNames() {
        return status == null || status.presets() == null ? List.of() : status.presets();
    }

    /** 当前选中预设（选中项已被删除/不存在时回落首个；列表空返回 null） */
    private String currentPresetOrNull() {
        List<String> names = presetNames();
        if (names.isEmpty()) {
            return null;
        }
        int idx = names.indexOf(selectedPreset);
        return names.get(idx >= 0 ? idx : 0);
    }

    private String selectedPresetName() {
        String cur = currentPresetOrNull();
        return cur == null ? "—" : cur;
    }

    private void cyclePreset() {
        List<String> names = presetNames();
        if (names.isEmpty()) {
            return;
        }
        int idx = names.indexOf(selectedPreset);
        selectedPreset = names.get((idx + 1) % names.size());
        if (presetCycle != null) {
            presetCycle.setMessage(Component.literal(selectedPresetName()));
        }
    }

    /** 应用当前选中预设到全体（写全局配置）：服务端 apply 需 confirm，走批量派发 + 补 confirm */
    private void applyPresetAll() {
        String name = currentPresetOrNull();
        if (name == null) {
            return;
        }
        showConfirm(Component.translatable("gui.chunkplan.confirm.apply_preset", name));
        this.pendingBatch = List.of("chunkplan preset apply " + name);
    }

    /** 删除当前选中预设：服务端无 confirm 流，本地弹窗确认后直接派发（跳过补发 confirm） */
    private void deletePreset() {
        String name = currentPresetOrNull();
        if (name == null) {
            return;
        }
        showConfirm(Component.translatable("gui.chunkplan.confirm.delete_preset", name));
        this.pendingBatch = List.of("chunkplan preset delete " + name);
        this.pendingSkipConfirm = true;
    }

    private void savePreset() {
        String name = presetNameEdit.getValue().trim();
        // 与服务端 PresetStore.NAME_PATTERN 同规则：客户端预校验防误发，服务端权威
        if (!name.matches("[A-Za-z0-9_-]{1,32}")) {
            return;
        }
        sendCommand("chunkplan preset save " + name);
        // 保留名称输入：管理员常在微调配置后同名覆盖保存
    }

    private void assignPreset() {
        String target = presetTarget.getValue().trim();
        String name = currentPresetOrNull();
        if (target.isEmpty() || name == null) {
            return;
        }
        sendCommand("chunkplan preset player " + target + " " + name);
        // 保留目标输入：便于对多名玩家连续分配
    }

    private void clearPresetAssign() {
        String target = presetTarget.getValue().trim();
        if (target.isEmpty()) {
            return;
        }
        sendCommand("chunkplan preset player " + target + " default");
    }

    // ---------- 维度页（issue #3） ----------

    private static final int DIM_LIST_TOP = 70;
    private static final int DIM_ROW_H = 24;
    /** 底部档位编辑器整体占高（选择器行 18 + 间隔 6 + 4 档 × 22），可视行数推导共用 */
    private static final int DIM_EDITOR_H = 110;

    /** 维度列表可视行数：按窗口高度自适应，为保存按钮区(32)与底部编辑器(110)、底边距(6)留位 */
    private int dimVisibleRows() {
        return Math.max(1, (height - DIM_LIST_TOP - 32 - DIM_EDITOR_H - 6) / DIM_ROW_H);
    }

    /** 列表实际底部 y：行数按维度数封顶，维度少时保存按钮与编辑器整体上移（自适应布局） */
    private int dimListBottom() {
        int rows = Math.min(dimensionKeys().size(), dimVisibleRows());
        return DIM_LIST_TOP + Math.max(0, rows) * DIM_ROW_H;
    }

    private int dimSaveY() {
        return dimListBottom() + 6;
    }

    /** 维度页底部档位编辑器顶部 y（保存按钮下缘 + 6；build/render/滚动共用同一布局推导） */
    private int dimEditorTop() {
        return dimSaveY() + 26;
    }

    /** 维度键列表（管理员：live ∪ 已配置；普通玩家：服务端下发的 live 维度） */
    private List<String> dimensionKeys() {
        if (isAdmin() && status != null && status.dimConfig() != null) {
            return status.dimConfig().dims().stream().map(GuiStatus.DimEntry::dim).distinct().sorted().toList();
        }
        return status == null || status.dimensions() == null ? List.of() : status.dimensions();
    }

    private GuiStatus.DimEntry dimEntry(String dim) {
        if (dim == null || status == null || status.dimConfig() == null) {
            return null;
        }
        for (GuiStatus.DimEntry e : status.dimConfig().dims()) {
            if (e.dim().equals(dim)) {
                return e;
            }
        }
        return null;
    }

    private QuotaTiers.Tier dimTier(String dim, int tier) {
        GuiStatus.DimEntry e = dimEntry(dim);
        if (e == null || e.tiers() == null || tier < 1 || tier > e.tiers().size()) {
            return null;
        }
        return e.tiers().get(tier - 1);
    }

    private boolean dimTierEnabled(String dim, int tier) {
        QuotaTiers.Tier t = dimTier(dim, tier);
        return t != null && t.enabled();
    }

    private DimTierEdit dimEditState(String dim) {
        return dimEdits.computeIfAbsent(dim, k -> new DimTierEdit());
    }

    private static String shortDim(String dim) {
        return dim.startsWith("minecraft:") ? dim.substring("minecraft:".length()) : dim;
    }

    private static String fmtCoord(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private void buildDimensions() {
        if (!isAdmin()) {
            return;
        }
        if (status == null || status.dimConfig() == null) {
            return; // 状态未到达：页签可点，内容等待刷新
        }
        // 服务端模式已与待切换一致：消费待切换标记，回到服务端真相
        if (pendingDimMode != null && status.dimensionMode() == (pendingDimMode ? 1 : 0)) {
            pendingDimMode = null;
        }
        boolean independent = status.dimensionMode() == 1;
        int left = 12;
        List<String> dims = dimensionKeys();
        if (selectedDim == null || !dims.contains(selectedDim)) {
            selectedDim = dims.isEmpty() ? null : dims.get(0);
        }

        // 行 1：模式 + 重定向开关 + 3 个槽位（重定向相关仅独立模式可操作）
        dimModeButton = addButton(left, 36, 168, 20, dimModeLabel(), b -> toggleDimMode());
        boolean red = status.dimConfig().redirectOnExhaust();
        redirectButton = addButton(left + 174, 36, 118, 20,
                Component.translatable("gui.chunkplan.dim.redirect").append(Component.literal(": "))
                        .append(Component.translatable(red ? "gui.chunkplan.on" : "gui.chunkplan.off")),
                b -> sendCommand("chunkplan config redirect " + (red ? "off" : "on")));
        redirectButton.active = independent;
        // 3 个槽位：手绘下拉条（真下拉，可空选、不可重复、须自首选起连续填写——服务端同规则）
        List<String> order = status.dimConfig().redirectOrder();
        for (int s = 0; s < 3; s++) {
            String cur = s < order.size() ? order.get(s) : null;
            setRect(slotBarRect[s], left + 298 + s * 106, 36, 100, 20);
            // 次选需首选非空，备选需次选非空（用户规则）；共享模式下整行不可点
            slotBarClickable[s] = independent
                    && (s == 0 || (s < order.size() && order.get(s - 1) != null));
        }

        // 维度列表（滚轮滚动，见 mouseScrolled）：计费开关（两种模式通用）+ x/y/z 坐标输入
        int saveY = dimSaveY();
        int visibleRows = dimVisibleRows();
        int maxScroll = Math.max(0, dims.size() - visibleRows);
        if (dimScroll > maxScroll) {
            dimScroll = maxScroll;
        }
        for (int i = 0; i < dims.size(); i++) {
            if (i < dimScroll || i >= dimScroll + visibleRows) {
                continue;
            }
            String dim = dims.get(i);
            int ry = DIM_LIST_TOP + (i - dimScroll) * DIM_ROW_H;
            GuiStatus.DimEntry e = dimEntry(dim);
            boolean billing = e == null || e.billing();
            final String dimF = dim;
            // 坐标列被展开中的下拉覆盖时不建该行控件（重定向槽位下拉会压住前几行）
            if (coveredByDropdown(left + 212, ry, 192, 18)) {
                continue;
            }
            // 服务端已确认的待保存开关：消费保留值，回显服务端真相（与坐标/档位同规则）
            Boolean pb = pendingBilling.get(dim);
            boolean curBilling = pb != null ? pb : billing;
            if (pb != null && pb == billing) {
                pendingBilling.remove(dim);
            }
            addButton(left + 150, ry, 48, 18,
                    Component.translatable(curBilling ? "gui.chunkplan.on" : "gui.chunkplan.off"),
                    b -> toggleDimBilling(dimF, curBilling));
            for (int k = 0; k < 3; k++) {
                String[] saved = savedDimCoords.computeIfAbsent(dim, d -> new String[3]);
                GuiStatus.DimEntry fe = e;
                final int axis = k;
                // 服务端已确认的编辑值：消费保留输入，回显格式化服务端值（与管理页 savedLimit 同规则）；
                // 清空亦然——服务端已无坐标且本地为空时消费，"留空即清除"不会永远停在未保存
                if (saved[k] != null) {
                    if ((fe == null || !fe.hasSpawn()) && saved[k].trim().isEmpty()) {
                        saved[k] = null;
                    } else {
                        try {
                            double v = Double.parseDouble(saved[k]);
                            if (fe != null && fe.hasSpawn()
                                    && Double.compare(v, axis == 0 ? fe.x() : axis == 1 ? fe.y() : fe.z()) == 0) {
                                saved[k] = null;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                String serverValue = fe == null || !fe.hasSpawn() ? ""
                        : fmtCoord(axis == 0 ? fe.x() : axis == 1 ? fe.y() : fe.z());
                EditBox box = new EditBox(font, left + 212 + k * 60, ry, 56, 18, Component.empty());
                box.setValue(saved[k] != null ? saved[k] : serverValue);
                // 保留原始输入（含空串）：清空必须被记住，否则回落到服务端值就再也清不掉坐标；
                // 不给 responder 打脏标记——原版 EditBox 聚焦点击即回调（EditBox.java:243），
                // 未保存状态一律由 dimCoordsDirty() 现算（与服务端值比对），见坑 #49
                box.setResponder(v -> saved[axis] = v);
                box.setMaxLength(32);
                addRenderableWidget(box);
            }
        }

        // 保存按钮恒可点：坐标齐全是「切换独立模式」的前置条件，由服务端在切换时校验（不齐则拒绝
        // 并列出缺失维度）；客户端灰显会让「切独立 → 逐个填坐标 → 保存」这条正常路径走不通
        addButton(left, saveY, 110, 20, Component.translatable("gui.chunkplan.dim.save_coords"),
                b -> saveDimCoords());

        // 底部档位编辑器：选中维度 + 4 档（仅独立模式可编辑，共享模式灰显——服务端同语义）
        int et = dimEditorTop();
        dimEditCycle = addButton(left, et, 150, 18,
                Component.literal(selectedDim == null ? "—" : font.plainSubstrByWidth(selectedDim, 140)),
                b -> openDimDropdown(2, left, et, 150, 18));
        if (selectedDim == null) {
            return;
        }
        // 下拉展开期间不建档位行控件：该环境文本绘制层浮于后画填充之上（如字体合批 mod），会透出下拉黑底
        if (dimDropdownOpen && dimDropdownPage == 2) {
            return;
        }
        DimTierEdit st = dimEditState(selectedDim);
        java.util.Arrays.fill(dimTierLimit, null); // 被下拉覆盖的行不建控件：先清引用防误用旧实例
        for (int i = 0; i < 4; i++) {
            int tier = i + 1;
            int ry = et + 24 + i * 22;
            final int idx = i;
            // 重建回落（与管理页档位行同规则）
            QuotaTiers.Tier rt = dimTier(selectedDim, tier);
            String curWindow = rt == null ? "" : rt.window();
            double curLimit = rt == null ? 0 : rt.limit();
            if (st.pendingEnabledSet[idx] && st.pendingEnabled[idx] == dimTierEnabled(selectedDim, tier)) {
                st.pendingEnabledSet[idx] = false;
            }
            if (st.pendingWindow[idx] != null && st.pendingWindow[idx].equals(curWindow)) {
                st.pendingWindow[idx] = null;
            }
            if (st.savedLimit[idx] != null) {
                NumericParser.Parsed p = NumericParser.parseLimit(st.savedLimit[idx]);
                if (p.isOk() && Double.compare(p.value(), curLimit) == 0) {
                    st.savedLimit[idx] = null;
                }
            }
            boolean effOn = st.pendingEnabledSet[idx] ? st.pendingEnabled[idx] : dimTierEnabled(selectedDim, tier);
            setRect(dimTierToggleRect[i], left + 40, ry, 52, 20);
            String win = st.pendingWindow[idx] != null ? st.pendingWindow[idx] : curWindow;
            setRect(dimTierWindowRect[i], left + 98, ry, 64, 20);
            dimTierWindowClickable[i] = independent && effOn;
            dimTierLimit[i] = new EditBox(font, left + 168, ry, 50, 20, Component.empty());
            dimTierLimit[i].setValue(st.savedLimit[idx] != null ? st.savedLimit[idx] : fmtLimit(curLimit));
            String dimKey = selectedDim;
            dimTierLimit[i].setResponder(v -> {
                st.savedLimit[idx] = v.trim().isEmpty() ? null : v;
                markDimTierDirtyIfChanged(dimKey, tier);
            });
            dimTierLimit[i].setMaxLength(12);
            addRenderableWidget(dimTierLimit[i]);
            dimTierLimit[i].active = independent && effOn;
            dimTierSet[i] = addButton(left + 224, ry, 42, 20,
                    Component.translatable("gui.chunkplan.set"), b -> applyDimTier(dimKey, tier));
            dimTierSet[i].active = independent;
        }
    }

    private Component dimModeLabel() {
        boolean target = pendingDimMode != null ? pendingDimMode
                : (status != null && status.dimensionMode() == 1);
        net.minecraft.network.chat.MutableComponent c = Component.translatable("gui.chunkplan.dim.mode")
                .append(Component.literal(": "))
                .append(Component.translatable(target ? "gui.chunkplan.dim.independent"
                        : "gui.chunkplan.dim.shared"));
        if (pendingDimMode != null) {
            c.append(Component.translatable("gui.chunkplan.dim.mode_pending"));
        }
        return c;
    }

    private void toggleDimMode() {
        if (status == null) {
            return;
        }
        boolean next = status.dimensionMode() != 1;
        if (pendingDimMode != null && pendingDimMode == next) {
            pendingDimMode = null; // 点回当前模式：撤销待切换
        } else {
            pendingDimMode = next;
        }
        if (dimModeButton != null) {
            dimModeButton.setMessage(dimModeLabel());
        }
    }

    /** live 维度中缺合法落地坐标者（启用独立模式的前置条件，仅供提示与保存时跳过非法维度） */
    private List<String> missingSpawnDims() {
        if (status == null || status.dimensions() == null) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String dim : status.dimensions()) {
            if (!dimCoordsValid(dim)) {
                missing.add(dim);
            }
        }
        return missing;
    }

    /** 维度坐标当前生效值（编辑优先——含空串=玩家清空，未编辑回显服务端值；缺失返回空串） */
    private String coordText(String dim, int axis) {
        String[] saved = savedDimCoords.get(dim);
        if (saved != null && saved[axis] != null) {
            return saved[axis];
        }
        return serverCoordText(dim, axis);
    }

    /** 服务端已生效的坐标文本（忽略本地编辑缓存）——「未保存」判定与保存比对都以此为准 */
    private String serverCoordText(String dim, int axis) {
        GuiStatus.DimEntry e = dimEntry(dim);
        if (e == null || !e.hasSpawn()) {
            return "";
        }
        return fmtCoord(axis == 0 ? e.x() : axis == 1 ? e.y() : e.z());
    }

    /**
     * 该轴是否有真正改动过的编辑（与服务端值逐字比对）。
     *
     * <p>必须现算而不能靠 responder 打标记：原版 EditBox 的 mouseClicked → onClick → moveCursorTo
     * 会无条件调用 responder（EditBox.java:243），光聚焦不动也会回调一次，打标记就会出现
     * 「点一下输入框就红字报未保存」（用户实测反馈的遗留 bug，坑 #49）。
     */
    private boolean axisEdited(String dim, int axis) {
        String[] saved = savedDimCoords.get(dim);
        if (saved == null || saved[axis] == null) {
            return false;
        }
        String typed = saved[axis].trim();
        String server = serverCoordText(dim, axis).trim();
        if (typed.equals(server)) {
            return false;
        }
        // 数值等价视为无更改（输入 "1.0" 而服务端回显 "1" 不该报未保存）
        try {
            return Double.compare(Double.parseDouble(typed), Double.parseDouble(server)) != 0;
        } catch (NumberFormatException e) {
            return true; // 有一侧不是数字（含空串）：确属待处理
        }
    }

    /** 该维度是否有待保存的坐标更改（① 待清除 ② 待写入 ③ 填了一半/非法——都算） */
    private boolean dimEditPending(String dim) {
        return pendingBilling.containsKey(dim) || coordPending(dim);
    }

    /** 坐标三格是否有待保存更改（逐轴与服务端值比对；填了一半也算） */
    private boolean coordPending(String dim) {
        for (int k = 0; k < 3; k++) {
            if (axisEdited(dim, k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否有未保存的坐标/模式/计费开关更改（现算）。模式待切换本身也算未保存。
     * 未编辑过的维度不参与（点击输入框但没改字 = 无更改）。
     */
    private boolean dimCoordsDirty() {
        if (pendingDimMode != null || !pendingBilling.isEmpty()) {
            return true;
        }
        for (String dim : dimensionKeys()) {
            if (dimEditPending(dim)) {
                return true;
            }
        }
        return false;
    }

    /** 计费开关本地切换：仅改待保存值，随「保存」统一派发（原先即时生效，与坐标/档位不一致——用户反馈） */
    private void toggleDimBilling(String dim, boolean cur) {
        pendingBilling.put(dim, !cur);
        rebuild();
    }

    /** 该维度计费开关当前展示值（待保存优先） */
    private boolean dimBillingShown(String dim) {
        Boolean pb = pendingBilling.get(dim);
        if (pb != null) {
            return pb;
        }
        GuiStatus.DimEntry e = dimEntry(dim);
        return e == null || e.billing();
    }

    private boolean dimCoordsValid(String dim) {
        double[] c = new double[3];
        for (int k = 0; k < 3; k++) {
            String t = coordText(dim, k).trim();
            if (t.isEmpty()) {
                return false;
            }
            try {
                c[k] = Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return DimensionStore.isValidSpawn(c[0], c[1], c[2]);
    }

    private double[] parsedCoords(String dim) {
        double[] c = new double[3];
        for (int k = 0; k < 3; k++) {
            c[k] = Double.parseDouble(coordText(dim, k).trim());
        }
        return c;
    }

    /**
     * 保存坐标与模式：把「新值/清空/模式切换」一次派发给服务端。
     *
     * <p>标志必须在派发前落定（原先在派发后置位）：命令是异步回包的，服务端每条命令都回推一次
     * 状态（ChunkPlanNetwork.handleCommand），首个响应回来重建界面时就会把「刚保存」的乐观状态
     * 冲掉，出现「只有模式切了、坐标被回退」的假象（用户实测，坑 #49）。
     *
     * <p>坐标不区分模式存储（DimensionStore 单份）：这组坐标只对独立计费有意义，共享模式下也照常
     * 可见可改，切模式不会丢。
     */
    private void saveDimCoords() {
        List<String> cmds = new ArrayList<>();
        for (String dim : dimensionKeys()) {
            // 计费开关与本批坐标/模式同批派发（原先点击即时生效，与其它控件不一致——用户反馈）
            Boolean newBilling = pendingBilling.get(dim);
            if (newBilling != null) {
                cmds.add("chunkplan config dimension " + dim + " billing " + (newBilling ? "on" : "off"));
            }
            if (!coordPending(dim)) {
                continue; // 坐标未编辑/已与服务端一致：不派发
            }
            GuiStatus.DimEntry e = dimEntry(dim);
            boolean allBlank = true;
            for (int k = 0; k < 3; k++) {
                if (!coordText(dim, k).trim().isEmpty()) {
                    allBlank = false;
                    break;
                }
            }
            if (allBlank) {
                if (e != null && e.hasSpawn()) {
                    cmds.add("chunkplan config dimension " + dim + " spawn clear"); // 全空 = 清空该维度坐标
                }
                continue;
            }
            if (!dimCoordsValid(dim)) {
                continue; // 填了一半/非法：不派发（提示行仍显示未保存，下次填好再保存）
            }
            double[] c = parsedCoords(dim);
            cmds.add("chunkplan config dimension " + dim + " spawn "
                    + fmtCoord(c[0]) + " " + fmtCoord(c[1]) + " " + fmtCoord(c[2]));
        }
        if (pendingDimMode != null) {
            cmds.add("chunkplan config dimensionMode " + (pendingDimMode ? "independent" : "shared"));
        }
        if (cmds.isEmpty()) {
            rebuild();
            return;
        }
        // 只派发、不提前清乐观值：命令是异步的且服务端每条命令都回推状态，派发后立刻清会让界面
        // 在回包到达前回落到旧的服务端值（用户实测的「只有模式切了、坐标被回退」）。乐观值由
        // buildDimensions 按「服务端值 == 乐观值」逐项消费，未确认期间 dimCoordsDirty() 为真。
        dimCoordsSavedShown = true;
        cmds.forEach(this::sendCommand);
        rebuild();
    }

    /** 重定向槽位：改用真下拉（打开候选列表），选中经 acceptDimDropdown 派发 */
    private void openRedirectSlot(int slot) {
        if (status == null || status.dimConfig() == null || !slotBarClickable[slot]) {
            return;
        }
        redirectSlotEditing = slot;
        openDimDropdown(3, slotBarRect[slot]);
    }

    private void toggleDimTier(String dim, int tier) {
        toggleTierRow = tier - 1;
        toggleTierDim = dim;
        openDimDropdown(4, dimTierToggleRect[tier - 1]);
        rebuild(); // 被覆盖行不再建控件（同管理页）
    }

    /** 维度页档位开关下拉选中：仅改本地待应用状态，仍须点「设置」才落盘 */
    private void setDimTierEnabled(String dim, int tier, boolean next) {
        DimTierEdit st = dimEditState(dim);
        int i = tier - 1;
        st.pendingEnabledSet[i] = true;
        st.pendingEnabled[i] = next;
        st.dirty[i] = true;
        rebuild();
    }

    private void cycleDimWindow(String dim, int tier) {
        if (!dimTierEffOn(dim, tier)) {
            return;
        }
        toggleTierRow = tier - 1;
        toggleTierDim = dim;
        openDimDropdown(5, dimTierWindowRect[tier - 1]);
        rebuild();
    }

    /** 维度某档当前展示的开关状态（待应用优先） */
    private boolean dimTierEffOn(String dim, int tier) {
        DimTierEdit st = dimEditState(dim);
        int i = tier - 1;
        return st.pendingEnabledSet[i] ? st.pendingEnabled[i] : dimTierEnabled(dim, tier);
    }

    /**
     * 维度档位额度输入：值确实变了才打「未保存」标记（原版 EditBox 聚焦点击即回调 responder，
     * EditBox.java:243，不比对会出现「点一下输入框就红字报未保存」）。
     */
    private void markDimTierDirtyIfChanged(String dim, int tier) {
        DimTierEdit st = dimEditState(dim);
        int i = tier - 1;
        QuotaTiers.Tier t = dimTier(dim, tier);
        String saved = t == null ? "" : fmtLimit(t.limit());
        String typed = dimTierLimit[i] == null ? "" : dimTierLimit[i].getValue().trim();
        if (typed.isEmpty()) {
            st.dirty[i] = false; // 清空视为无更改（applyDimTier 同样跳过空值）
            return;
        }
        st.dirty[i] = !typed.equals(saved);
    }

    /** 维度档位行「设置」：与管理页 applyTier 同逻辑，命令换成 dimension 命令族 */
    private void applyDimTier(String dim, int tier) {
        DimTierEdit st = dimEditState(dim);
        int i = tier - 1;
        boolean curOn = dimTierEnabled(dim, tier);
        boolean effOn = st.pendingEnabledSet[i] ? st.pendingEnabled[i] : curOn;
        boolean enableChanged = st.pendingEnabledSet[i] && st.pendingEnabled[i] != curOn;
        QuotaTiers.Tier t = dimTier(dim, tier);
        String curWindow = t == null ? "" : t.window();
        double curLimit = t == null ? 0 : t.limit();
        List<String> cmds = new ArrayList<>();
        boolean needConfirm = false;
        Component confirmMsg = Component.empty();
        String base = "chunkplan config dimension " + dim;

        if (!effOn) {
            if (enableChanged) {
                cmds.add(base + " window tier" + tier + " off");
                needConfirm = true;
                confirmMsg = Component.translatable("gui.chunkplan.confirm.disable_dim_tier", dim, tier);
            }
        } else {
            // 开启/保持开启：先启用（服务端要求窗口开启后才能改时长/上限），再改其余项
            if (enableChanged) {
                cmds.add(base + " window tier" + tier + " on");
            }
            String pw = st.pendingWindow[i];
            if (pw != null && !pw.equals(curWindow)) {
                cmds.add(base + " windowTime tier" + tier + " " + pw);
            }
            String raw = dimTierLimit[i].getValue().trim();
            if (!raw.isEmpty()) {
                NumericParser.Parsed p = NumericParser.parseLimit(raw);
                if (p.isOk() && Double.compare(p.value(), curLimit) != 0) {
                    cmds.add(base + " windowLimit tier" + tier + " " + raw);
                    if (p.value() < curLimit) {
                        needConfirm = true;
                        confirmMsg = Component.translatable("gui.chunkplan.confirm.lower_dim_tier", dim, tier);
                    }
                }
            }
        }

        if (cmds.isEmpty()) {
            st.dirty[i] = false;
            return;
        }
        if (needConfirm) {
            showConfirm(confirmMsg);
            this.pendingBatch = cmds; // showConfirm 会清槽位，必须在其后挂载
            this.pendingBatchTier = tier;
            this.pendingBatchDim = dim;
        } else {
            cmds.forEach(this::sendCommand);
            st.dirty[i] = false;
            st.savedShown[i] = true;
        }
    }

    private void renderDimensions(GuiGraphicsExtractor g) {
        int x = 12;
        if (!isAdmin()) {
            g.text(font, Component.translatable("gui.chunkplan.no_permission"), x, 44, COL_RED);
            return;
        }
        if (status == null || status.dimConfig() == null) {
            g.text(font, Component.translatable(waiting ? "gui.chunkplan.fetching" : "gui.chunkplan.parse_error"),
                    x, 40, waiting ? COL_GRAY : COL_RED);
            return;
        }
        boolean independent = status.dimensionMode() == 1;
        boolean effIndependent = independent || Boolean.TRUE.equals(pendingDimMode);
        List<String> dims = dimensionKeys();
        // 重定向 3 槽位文字（手绘下拉条，见 buildDimensions；下拉展开期不画被覆盖者）
        List<String> order = status.dimConfig().redirectOrder();
        for (int s = 0; s < 3; s++) {
            String cur = s < order.size() ? order.get(s) : null;
            boolean expanded = dimDropdownOpen && dimDropdownPage == 3 && redirectSlotEditing == s;
            if (!coveredByDropdown(slotBarRect[s][0], slotBarRect[s][1], slotBarRect[s][2], slotBarRect[s][3])
                    && !expanded) {
                drawSelectBar(g, slotBarRect[s][0], slotBarRect[s][1], slotBarRect[s][2], slotBarRect[s][3],
                        Component.translatable("gui.chunkplan.dim.slot" + (s + 1))
                                .append(Component.literal(": " + (cur == null ? "—" : shortDim(cur)))),
                        !slotBarClickable[s]);
            }
        }
        // 列表头（y=58：上距模式行按钮 2px、下距列表首行 3px，原 y=56 与首行控件顶边重叠）
        g.text(font, Component.translatable("gui.chunkplan.dim.dim_header"), x + 2, 58, COL_GRAY);
        g.text(font, Component.translatable("gui.chunkplan.dim.billing"), x + 152, 58, COL_GRAY);
        g.text(font, Component.translatable("gui.chunkplan.dim.spawn_header"), x + 216, 58, COL_GRAY);
        // 行内容（控件之外的文字）
        int visibleRows = dimVisibleRows();
        for (int i = 0; i < dims.size(); i++) {
            if (i < dimScroll || i >= dimScroll + visibleRows) {
                continue;
            }
            String dim = dims.get(i);
            int ry = DIM_LIST_TOP + (i - dimScroll) * DIM_ROW_H;
            if (coveredByDropdown(x + 212, ry, 192, 18)) {
                continue; // 坐标列被展开中的下拉覆盖（重定向槽位下拉会压住前几行）
            }
            GuiStatus.DimEntry e = dimEntry(dim);
            g.text(font, Component.literal(font.plainSubstrByWidth(shortDim(dim), 130)),
                    x + 2, ry + 6, dimBillingShown(dim) ? COL_TEXT : COL_GRAY);
            // 坐标合法状态点：绿=合法，红=缺失/非法
            int dotX = x + 400;
            g.fill(dotX, ry + 6, dotX + 6, ry + 12, dimCoordsValid(dim) ? COL_GREEN : COL_RED);
        }
        // 保存区提示：未保存红字 > 已保存灰字 > 留空即清除的常驻说明
        int saveY = dimSaveY();
        if (dimCoordsDirty()) {
            g.text(font, Component.translatable("gui.chunkplan.dim.coords_dirty"), x + 120, saveY + 6, COL_RED);
        } else if (dimCoordsSavedShown) {
            g.text(font, Component.translatable("gui.chunkplan.dim.coords_saved"), x + 120, saveY + 6, COL_GRAY);
        } else {
            // 坐标是模式无关的单份存储：两种模式下都可改可清（避免"共享模式改不掉"）
            g.text(font, Component.translatable("gui.chunkplan.dim.spawn_hint"), x + 120, saveY + 6, COL_GRAY);
        }
        if (effIndependent && !missingSpawnDims().isEmpty()) {
            g.text(font, Component.translatable("gui.chunkplan.dim.coords_missing"), x + 300, saveY + 6, COL_RED);
        }
        // 编辑器标签
        int et = dimEditorTop();
        g.text(font, Component.translatable("gui.chunkplan.dim.tier_title"), x + 156, et + 6, COL_TEXT);
        if (!independent) {
            g.text(font, Component.translatable("gui.chunkplan.dim.shared_mode_hint"), x + 240, et + 6, COL_GRAY);
        }
        DimTierEdit st = selectedDim == null ? null : dimEdits.get(selectedDim);
        // 与 buildDimensions 同规则：下拉展开期间档位行控件未建，行文字一并隐藏
        if (st != null && !(dimDropdownOpen && dimDropdownPage == 2)) {
            for (int i = 0; i < 4; i++) {
                int ry = et + 24 + i * 22;
                if (coveredByDropdown(12, ry, 420, 22)) {
                    continue;
                }
                g.text(font, Component.literal("tier" + (i + 1)), x + 2, ry + 6, COL_TEXT);
                boolean toggleExpanded = dimDropdownOpen && dimDropdownPage == 4
                        && selectedDim.equals(toggleTierDim) && toggleTierRow == i;
                if (!toggleExpanded) {
                    drawSelectBar(g, dimTierToggleRect[i][0], dimTierToggleRect[i][1], dimTierToggleRect[i][2],
                            dimTierToggleRect[i][3],
                            Component.translatable(!independent ? "gui.chunkplan.disabled"
                                    : (dimTierEffOn(selectedDim, i + 1) ? "gui.chunkplan.enabled"
                                            : "gui.chunkplan.disabled")),
                            !independent);
                }
                QuotaTiers.Tier dt = dimTier(selectedDim, i + 1);
                String dwin = st.pendingWindow[i] != null ? st.pendingWindow[i] : (dt == null ? "" : dt.window());
                boolean winExpanded = dimDropdownOpen && dimDropdownPage == 5
                        && selectedDim.equals(toggleTierDim) && toggleTierRow == i;
                if (!winExpanded) {
                    drawSelectBar(g, dimTierWindowRect[i][0], dimTierWindowRect[i][1], dimTierWindowRect[i][2],
                            dimTierWindowRect[i][3], Component.literal(dwin.isEmpty() ? "—" : dwin),
                            !dimTierWindowClickable[i]);
                }
                if (st.dirty[i]) {
                    g.text(font, Component.translatable("gui.chunkplan.unsaved"), x + 320, ry + 6, COL_RED);
                } else if (st.savedShown[i]) {
                    g.text(font, Component.translatable("gui.chunkplan.saved"), x + 320, ry + 6, COL_GRAY);
                }
            }
        }
    }

    // ---------- 真下拉（用量页维度 / 维度页编辑器 / 重定向槽位 / 档位开关与窗口，共用一套展开状态） ----------

    private void openDimDropdown(int srcPage, int[] rect) {
        openDimDropdown(srcPage, rect[0], rect[1], rect[2], rect[3]);
    }

    private void openDimDropdown(int srcPage, int x, int y, int w, int h) {
        dimDropdownOpen = true;
        dimDropdownPage = srcPage;
        dimDropdownSelected = -1;
        dropSrcX = x;
        dropSrcY = y;
        dropSrcW = w;
        dropSrcH = h;
    }

    /** 关闭下拉并重建：展开期间被覆盖的行不建控件，必须重建才能恢复（调用方若随后自行重建，用 resetDropdownState） */
    private void closeDimDropdown() {
        boolean was = dimDropdownOpen;
        resetDropdownState();
        if (was) {
            rebuild();
        }
    }

    private void resetDropdownState() {
        dimDropdownOpen = false;
        dimDropdownSelected = -1;
        redirectSlotEditing = -1;
        toggleTierRow = -1;
        toggleTierDim = null;
    }

    /** 下拉候选显示文本（page 3 首项为「—」占位 = 清空该槽位） */
    private List<String> dropdownLabels() {
        switch (dimDropdownPage) {
            case 0:
                return status == null || status.dimensions() == null ? List.of() : status.dimensions();
            case 3:
                List<String> labels = new ArrayList<>();
                labels.add("—");
                labels.addAll(redirectSlotCandidates());
                return labels;
            case 4:
                return List.of(Component.translatable("gui.chunkplan.enabled").getString(),
                        Component.translatable("gui.chunkplan.disabled").getString());
            case 5:
                return toggleTierRow < 0 ? List.of() : presets(toggleTierRow + 1);
            default:
                return dimensionKeys();
        }
    }

    /** 与 {@link #dropdownLabels()} 一一对应的取值（page 3 首项 null = 清空；page 4 为 on/off） */
    private List<String> dropdownValues() {
        switch (dimDropdownPage) {
            case 3:
                List<String> values = new ArrayList<>();
                values.add(null);
                values.addAll(redirectSlotCandidates());
                return values;
            case 4:
                return List.of("on", "off");
            default:
                return dropdownLabels();
        }
    }

    /** 重定向槽位候选：全部维度去掉「已被其它槽位占用」者（用户要求不可重复） */
    private List<String> redirectSlotCandidates() {
        List<String> cands = new ArrayList<>(dimensionKeys());
        if (status != null && status.dimConfig() != null && redirectSlotEditing >= 0) {
            List<String> order = status.dimConfig().redirectOrder();
            for (int i = 0; i < order.size(); i++) {
                if (i != redirectSlotEditing) {
                    cands.remove(order.get(i));
                }
            }
        }
        return cands;
    }

    /** 当前展开的下拉列表矩形 {x,y,w,h}（未展开/无候选返回 null） */
    private int[] dropdownRect() {
        if (!dimDropdownOpen) {
            return null;
        }
        List<String> items = dropdownLabels();
        if (items.isEmpty()) {
            return null;
        }
        int sy = dropSrcY + dropSrcH + 2;
        int maxRows = Math.max(1, (height - sy - 6) / SUGGEST_ROW_H);
        int rows = Math.min(items.size(), maxRows);
        return new int[] {dropSrcX, sy, Math.max(dropSrcW, 120), rows * SUGGEST_ROW_H + 2};
    }

    /**
     * 该矩形是否会被展开中的下拉盖住。「盖住」= 不画而不是画在下层：用户环境里文本绘制层浮于
     * 后画填充之上（字体合批 mod，坑 #47 像素级实证），仅靠绘制顺序遮不住。
     */
    private boolean coveredByDropdown(int x, int y, int w, int h) {
        int[] r = dropdownRect();
        if (r == null) {
            return false;
        }
        return x < r[0] + r[2] && x + w > r[0] && y < r[1] + r[3] && y + h > r[1];
    }

    private void renderDimDropdown(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!dimDropdownOpen) {
            return;
        }
        List<String> items = dropdownLabels();
        if (items.isEmpty()) {
            resetDropdownState(); // 渲染路径内不 rebuild（会重入）；下个 tick 自然恢复
            return;
        }
        int sx = dropSrcX;
        int sy = dropSrcY + dropSrcH + 2;
        int sW = Math.max(dropSrcW, 120);
        int maxRows = Math.max(1, (height - sy - 6) / SUGGEST_ROW_H);
        int rows = Math.min(items.size(), maxRows);
        // 底部多留 2px：末行文字下缘原与下边框零间距（用户实测反馈"黑背景没有完美盖住底部"）
        int sH = rows * SUGGEST_ROW_H + 2;
        g.fill(sx, sy, sx + sW, sy + sH, 0xFF000000);
        g.fill(sx, sy, sx + sW, sy + 1, 0xFFFFFFFF);
        g.fill(sx, sy + sH - 1, sx + sW, sy + sH, 0xFFFFFFFF);
        g.fill(sx, sy, sx + 1, sy + sH, 0xFFFFFFFF);
        g.fill(sx + sW - 1, sy, sx + sW, sy + sH, 0xFFFFFFFF);
        for (int i = 0; i < rows; i++) {
            int rowY = sy + i * SUGGEST_ROW_H;
            boolean hover = inRect(mouseX, mouseY, sx, rowY, sW, SUGGEST_ROW_H);
            if (hover || i == dimDropdownSelected) {
                g.fill(sx + 1, rowY, sx + sW - 1, rowY + SUGGEST_ROW_H, 0xFF606060);
            }
            g.text(font, Component.literal(font.plainSubstrByWidth(items.get(i), sW - 12)),
                    sx + 6, rowY + 2, COL_TEXT);
        }
    }

    private void acceptDimDropdown(int idx) {
        List<String> values = dropdownValues();
        if (idx < 0 || idx >= values.size()) {
            return;
        }
        String value = values.get(idx);
        int page = dimDropdownPage;
        int row = toggleTierRow;
        String rowDim = toggleTierDim;
        int slot = redirectSlotEditing;
        resetDropdownState();
        switch (page) {
            case 0 -> usageDim = value;
            case 2 -> {
                selectedDim = value;
                rebuild();
            }
            case 3 -> {
                if (slot >= 0) {
                    String slotName = slot == 0 ? "primary" : slot == 1 ? "secondary" : "tertiary";
                    sendCommand("chunkplan config redirectTarget " + slotName
                            + (value == null ? " none" : " " + value));
                }
            }
            case 4 -> {
                if (row >= 0) {
                    if (rowDim == null) {
                        setTierEnabled(row + 1, "on".equals(value));
                    } else {
                        setDimTierEnabled(rowDim, row + 1, "on".equals(value));
                    }
                }
            }
            case 5 -> {
                if (row >= 0) {
                    if (rowDim == null) {
                        pendingWindow[row] = value;
                        markTierDirty(row + 1);
                    } else {
                        dimEditState(rowDim).pendingWindow[row] = value;
                        dimEditState(rowDim).dirty[row] = true;
                    }
                }
                rebuild();
            }
            default -> rebuild();
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

    // ---------- 目标自动补全（仅在线玩家名；resetTarget 与 presetTarget 共用一套机制） ----------

    /** 当前应展示补全的输入框（管理页中聚焦的那个；无则返回 null） */
    private EditBox activeSuggestBox() {
        if (page != 1) {
            return null;
        }
        if (resetTarget != null && resetTarget.isFocused()) {
            return resetTarget;
        }
        if (presetTarget != null && presetTarget.isFocused()) {
            return presetTarget;
        }
        return null;
    }

    private void refreshResetSuggestions() {
        EditBox box = activeSuggestBox();
        if (box == null) {
            resetSuggestions = List.of();
            suggestOwner = null;
            return;
        }
        String val = box.getValue().trim();
        if (val.isEmpty()) {
            resetSuggestions = List.of();
            suggestOwner = null;
            return;
        }
        suggestOwner = box;
        resetTargetX = box.getX();
        resetTargetY = box.getY();
        resetTargetW = box.getWidth();
        resetTargetH = box.getHeight();
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
                .map(p -> p.getProfile().name())
                .filter(n -> n != null)
                .toList();
    }

    private void acceptResetSuggestion(int idx) {
        if (idx < 0 || idx >= resetSuggestions.size() || suggestOwner == null) {
            return;
        }
        String name = resetSuggestions.get(idx);
        suggestOwner.setValue(name);
        suggestOwner.moveCursorToEnd(true);
        resetSuggestions = List.of();
        suggestOwner = null;
        resetSelected = -1;
    }

    private void renderResetSuggestions(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (page != 1 || resetSuggestions.isEmpty()) {
            return;
        }
        int sx = resetTargetX;
        int sW = Math.max(resetTargetW + 30, 120);
        // 底部多留 2px：末行文字下缘原与下边框零间距（与维度下拉同规则）
        int sH = resetSuggestions.size() * SUGGEST_ROW_H + 2;
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
            g.text(font, Component.literal(resetSuggestions.get(i)), sx + 6, rowY + 2, COL_TEXT);
        }
    }

    // ---------- 状态接收 ----------

    public void onStatus(GuiStatus s) {
        this.status = s;
        this.waiting = false;
        if (s == null) {
            return;
        }
        if (s.versionMismatch()) {
            // 版本不匹配：清掉控件进入兜底页（不 rebuild，避免空数据控件残影）
            clearWidgets();
            return;
        }
        // 用量页维度下拉无记忆：每次状态刷新重置为当前所在维度（issue #3）
        this.usageDim = s.dimensionMode() == 1 ? s.currentDim() : null;
        rebuild();
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
        this.pendingBatch = null; // 槽位只服务当前确认动作，防旧批残留被误派发
        this.pendingBatchTier = 0;
        this.pendingBatchDim = null;
        this.pendingSkipConfirm = false;
        this.pendingConfirm = true;
        this.confirmText = Component.empty().append(message)
                .append(Component.translatable("gui.chunkplan.confirm.hint"));
    }

    /** 用量页当前选中维度的额度数据（找不到回落 null：renderUsage 用顶层 currentDim 数据兜底） */
    private GuiStatus.DimLines usageDimLines(GuiStatus s) {
        if (s.dimLines() == null || usageDim == null) {
            return null;
        }
        for (GuiStatus.DimLines d : s.dimLines()) {
            if (d.dim().equals(usageDim)) {
                return d;
            }
        }
        return null;
    }

    // ---------- 渲染 ----------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 背景由渲染入口 renderWithTooltipAndSubtitles 在调用本方法前统一绘制，此处不得重复调用
        // （重复调用会让模糊后处理再次采样当前帧已画内容，出现整页模糊/幽灵重影）
        g.fill(0, 32, width, 33, 0xFF555555);
        if (isVersionMismatch()) {
            renderVersionMismatch(g);
            return;
        }
        if (page == 0) {
            renderUsage(g);
        } else if (page == 2) {
            renderDimensions(g);
        } else {
            renderAdmin(g);
        }
        if (page == 1) {
            refreshResetSuggestions();
            renderResetSuggestions(g, mouseX, mouseY);
        }
        renderDimDropdown(g, mouseX, mouseY);
        if (pendingConfirm) {
            renderConfirm(g);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** 版本不匹配兜底页：协议版本不一致时服务端回版本横幅，只显示两端版本号 */
    private void renderVersionMismatch(GuiGraphicsExtractor g) {
        int x = 12;
        int y = 40;
        g.text(font, Component.translatable("gui.chunkplan.usage.title"), x, y, COL_ACCENT);
        y += 16;
        GuiStatus s = status;
        String server = s != null && s.serverModVersion() != null && !s.serverModVersion().isEmpty()
                ? s.serverModVersion() : "?";
        g.text(font, Component.translatable("gui.chunkplan.version.server", server), x, y, COL_TEXT);
        y += 12;
        String client = ChunkPlanFabricClient.clientVersion();
        g.text(font, Component.translatable("gui.chunkplan.version.client",
                client == null || client.isEmpty() ? "?" : client), x, y, COL_TEXT);
        y += 14;
        g.text(font, Component.translatable("gui.chunkplan.version.mismatch"), x, y, COL_RED);
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
        // v3（issue #3）：维度独立模式顶部维度下拉（真下拉、无记忆：每次状态刷新重置为当前所在维度）
        boolean dimMode = s.dimensionMode() == 1;
        GuiStatus.DimLines dl = null;
        if (dimMode) {
            if (usageDim == null) {
                usageDim = s.currentDim();
            }
            dl = usageDimLines(s);
            g.text(font, Component.translatable("gui.chunkplan.dim.pick_dim"), x, y + 4, COL_TEXT);
            dimDropX = x + 74;
            dimDropY = y;
            dimDropW = Math.min(190, width - dimDropX - 12);
            dimDropH = 16;
            boolean expanded = dimDropdownOpen && dimDropdownPage == 0;
            if (!expanded) {
                drawSelectBar(g, dimDropX, dimDropY, dimDropW, dimDropH,
                        Component.literal(usageDim == null ? "—"
                                : font.plainSubstrByWidth(usageDim, dimDropW - 20)), false);
            }
            y += 22;
            // 下拉展开：把下方额度内容整体下移到列表之下，而不是不画（原先直接 return 会让整片
            // 可视化消失，用户实测反馈）。下移不依赖绘制顺序，字体合批环境同样成立（坑 #47 教训）。
            if (expanded) {
                int[] r = dropdownRect();
                int belowY = r == null ? y : r[1] + r[3] + 6;
                if (belowY + 90 > height) {
                    return; // 矮窗口放不下：退回"不画"，避免内容溢出屏幕
                }
                y = belowY;
            }
        }
        List<QuotaEngine.LineStatus> lines = dl == null ? s.lines() : dl.lines();
        if (lines.isEmpty()) {
            g.text(font, Component.translatable("gui.chunkplan.zero_line"), x, y, COL_GREEN);
        } else {
            // 维度数据无 allExceeded 字段：按 OR 语义现算（任一线满即满，坑 #25）
            boolean exceeded = dl != null ? lines.stream().anyMatch(l -> l.spent() > l.limit())
                    : s.allExceeded();
            long recovery = dl == null ? s.recoveryMillis() : dl.recoveryMillis();
            int worst = dl == null ? s.worstPercent() : dl.worstPercent();
            for (QuotaEngine.LineStatus l : lines) {
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
            if (exceeded) {
                g.text(font, Component.translatable("gui.chunkplan.exhausted",
                        ChunkPlanMessages.formatTime(recovery)), x, y, COL_RED);
            } else {
                int wp = worst;
                Component word = wp < 50 ? Component.translatable("gui.chunkplan.adequate")
                        : (wp < 75 ? Component.translatable("gui.chunkplan.moderate")
                        : Component.translatable("gui.chunkplan.low"));
                int wc = wp < 50 ? COL_GREEN : (wp < 75 ? COL_YELLOW : COL_RED);
                g.text(font, word, x, y, wc);
            }
        }
        // 按玩家预设覆盖（issue #2）：显示额度规则来源（null = 跟随全局 default，不显示）
        if (s.playerPreset() != null) {
            y += 12;
            g.text(font, Component.translatable("gui.chunkplan.preset_current", s.playerPreset()), x, y, COL_ACCENT);
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
        boolean independent = status != null && status.dimensionMode() == 1;
        int gy;
        if (independent) {
            g.text(font, Component.translatable("gui.chunkplan.admin_independent_hint"), x, 36, COL_YELLOW);
            gy = 64; // 与 buildAdmin 的费率行位置一一对应（下同）
        } else {
            for (int i = 0; i < 4; i++) {
                int ry = 36 + i * 32;
                // 展开中的下拉覆盖该行时不画标签与提示（与 buildAdmin 同步逐行判定，坑 #47 同规则）
                if (coveredByDropdown(12, ry, width - 24, 20)) {
                    continue;
                }
                g.text(font, Component.literal("tier" + (i + 1)), x, ry + 6, COL_TEXT);
                // 每档独立提示：未保存红 / 已保存灰（行下方 2px 处，不与行内控件重叠）
                if (tierDirty[i]) {
                    g.text(font, Component.translatable("gui.chunkplan.unsaved"), x, ry + 22, COL_RED);
                } else if (tierSavedShown[i]) {
                    g.text(font, Component.translatable("gui.chunkplan.saved"), x, ry + 22, COL_GRAY);
                }
                // 开关/窗口下拉条（Button 之外手绘，与用量页维度选择器同观感）
                boolean toggleExpanded = dimDropdownOpen && dimDropdownPage == 4
                        && toggleTierDim == null && toggleTierRow == i;
                if (!toggleExpanded) {
                    drawSelectBar(g, tierToggleRect[i][0], tierToggleRect[i][1], tierToggleRect[i][2],
                            tierToggleRect[i][3],
                            Component.translatable(effEnabled(i + 1) ? "gui.chunkplan.enabled"
                                    : "gui.chunkplan.disabled"), false);
                }
                QuotaTiers.Tier t = rawTier(i + 1);
                String win = pendingWindow[i] != null ? pendingWindow[i] : (t == null ? "" : t.window());
                boolean winExpanded = dimDropdownOpen && dimDropdownPage == 5
                        && toggleTierDim == null && toggleTierRow == i;
                if (!winExpanded) {
                    drawSelectBar(g, tierWindowRect[i][0], tierWindowRect[i][1], tierWindowRect[i][2],
                            tierWindowRect[i][3], Component.literal(win.isEmpty() ? "—" : win),
                            !tierWindowClickable[i]);
                }
            }
            gy = 36 + 4 * 32 + 6;
            g.text(font, Component.translatable("gui.chunkplan.all_windows"), x, gy + 6, COL_TEXT);
            gy += 28;
        }
        // 以下标签与 buildAdmin 的行位置一一对应（行 y + 6）
        g.text(font, Component.translatable("gui.chunkplan.fee_new"), x, gy + 6, COL_TEXT);
        g.text(font, Component.translatable("gui.chunkplan.fee_explored"), x, gy + 34, COL_TEXT);
        g.text(font, Component.translatable("gui.chunkplan.speed_mult"), x, gy + 62, COL_TEXT);
        g.text(font, Component.translatable("gui.chunkplan.reset_quota"), x, gy + 118, COL_TEXT);
        // 预设区标签（行 1 选择/应用/删除、行 2 保存、行 3 按玩家分配）
        g.text(font, Component.translatable("gui.chunkplan.preset_title"), x, gy + 146, COL_TEXT);
        g.text(font, Component.translatable("gui.chunkplan.preset_save_label"), x, gy + 174, COL_TEXT);
        g.text(font, Component.translatable("gui.chunkplan.preset_assign_label"), x, gy + 202, COL_TEXT);
        if (resetSuggestions.isEmpty()) { // 下拉弹出期间提示行被遮挡，收起后恢复（移至重置行右侧，下方为预设区）
            g.text(font, Component.translatable("gui.chunkplan.reset_hint"), x + 296, gy + 124, COL_GRAY);
        }
    }

    private void renderConfirm(GuiGraphicsExtractor g) {
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
        g.text(font, Component.translatable("gui.chunkplan.confirm.title"), bx + 8, by + 8, COL_ACCENT);
        int ty = by + 28;
        for (FormattedCharSequence line : lines) {
            g.text(font, line, bx + 8, ty, COL_TEXT);
            ty += font.lineHeight;
        }
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
                if (pendingBatch != null) {
                    // 批量命令需确认：先派发（服务端据此注册待确认动作），再补 /chunkplan confirm 执行
                    pendingBatch.forEach(this::sendCommand);
                    if (!pendingSkipConfirm) {
                        sendCommand("chunkplan confirm");
                    }
                    if (pendingBatchTier > 0) {
                        if (pendingBatchDim != null) {
                            DimTierEdit st = dimEdits.get(pendingBatchDim); // 维度页档位编辑器发起
                            if (st != null) {
                                st.dirty[pendingBatchTier - 1] = false;
                                st.savedShown[pendingBatchTier - 1] = true;
                            }
                        } else {
                            markSaved(pendingBatchTier);
                        }
                    }
                    pendingBatch = null;
                    pendingBatchTier = 0;
                    pendingBatchDim = null;
                    pendingSkipConfirm = false;
                } else {
                    sendCommand("chunkplan confirm");
                }
                pendingConfirm = false;
                return true;
            }
            if (inRect(event.x(), event.y(), noX, noY, noW, noH)) {
                pendingConfirm = false;
                pendingBatch = null;
                pendingBatchDim = null;
                pendingSkipConfirm = false;
                return true;
            }
            return true; // 弹窗期间拦截底层点击
        }
        if (dimDropdownOpen) {
            List<String> items = dropdownLabels();
            int sy = dropSrcY + dropSrcH + 2;
            int maxRows = Math.max(1, (height - sy - 6) / SUGGEST_ROW_H);
            int rows = Math.min(items.size(), maxRows);
            for (int i = 0; i < rows; i++) {
                int h = SUGGEST_ROW_H + (i == rows - 1 ? 2 : 0); // 末行含底部补白
                if (inRect(event.x(), event.y(), dropSrcX, sy + i * SUGGEST_ROW_H,
                        Math.max(dropSrcW, 120), h)) {
                    acceptDimDropdown(i);
                    return true;
                }
            }
            closeDimDropdown();
            return true;
        }
        // 用量页维度下拉按钮（手绘控件，非 Button）
        if (page == 0 && status != null && status.dimensionMode() == 1
                && inRect(event.x(), event.y(), dimDropX, dimDropY, dimDropW, dimDropH)) {
            openDimDropdown(0, dimDropX, dimDropY, dimDropW, dimDropH);
            return true;
        }
        // 维度页：重定向槽位 / 档位开关与窗口（手绘下拉条）
        if (page == 2 && status != null && status.dimConfig() != null) {
            for (int s = 0; s < 3; s++) {
                if (inRect(event.x(), event.y(), slotBarRect[s])) {
                    openRedirectSlot(s);
                    return true;
                }
            }
            for (int i = 0; i < 4; i++) {
                if (inRect(event.x(), event.y(), dimTierToggleRect[i])) {
                    toggleDimTier(selectedDim, i + 1);
                    return true;
                }
                if (dimTierWindowClickable[i] && inRect(event.x(), event.y(), dimTierWindowRect[i])) {
                    cycleDimWindow(selectedDim, i + 1);
                    return true;
                }
            }
        }
        // 管理页：档位开关与窗口（手绘下拉条）
        if (page == 1 && isAdmin() && status != null && status.dimensionMode() != 1) {
            for (int i = 0; i < 4; i++) {
                if (inRect(event.x(), event.y(), tierToggleRect[i])) {
                    toggleTier(i + 1);
                    return true;
                }
                if (tierWindowClickable[i] && inRect(event.x(), event.y(), tierWindowRect[i])) {
                    cycleWindow(i + 1);
                    return true;
                }
            }
        }
        if (!resetSuggestions.isEmpty()) {
            int paneY = resetTargetY + resetTargetH + 2;
            for (int i = 0; i < resetSuggestions.size(); i++) {
                int h = SUGGEST_ROW_H + (i == resetSuggestions.size() - 1 ? 2 : 0); // 末行含底部补白
                if (inRect(event.x(), event.y(), resetTargetX, paneY + i * SUGGEST_ROW_H,
                        Math.max(resetTargetW + 30, 120), h)) {
                    acceptResetSuggestion(i);
                    return true;
                }
            }
            // 点击建议列表之外的区域：失焦隐藏建议（点击输入框内不处理，交还 super 聚焦）
            if (suggestOwner != null
                    && !inRect(event.x(), event.y(), suggestOwner.getX(), suggestOwner.getY(),
                            suggestOwner.getWidth(), suggestOwner.getHeight())) {
                suggestOwner.setFocused(false);
                resetSuggestions = List.of();
                suggestOwner = null;
                resetSelected = -1;
            }
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (pendingConfirm && event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { // ESC 取消确认而非关闭界面
            pendingConfirm = false;
            pendingBatch = null;
            pendingBatchTier = 0;
            pendingBatchDim = null;
            return true;
        }
        if (dimDropdownOpen) {
            List<String> items = dropdownLabels();
            int sy = dropSrcY + dropSrcH + 2;
            int maxRows = Math.max(1, (height - sy - 6) / SUGGEST_ROW_H);
            int rows = Math.min(items.size(), maxRows);
            switch (event.key()) {
                case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> {
                    dimDropdownSelected = rows == 0 ? -1
                            : (dimDropdownSelected <= 0 ? rows - 1 : dimDropdownSelected - 1);
                    return true;
                }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> {
                    if (rows > 0) {
                        dimDropdownSelected = (dimDropdownSelected + 1) % rows;
                    }
                    return true;
                }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_TAB -> {
                    acceptDimDropdown(Math.max(dimDropdownSelected, 0));
                    return true;
                }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> {
                    closeDimDropdown();
                    return true;
                }
                default -> {
                    // 其他按键透传
                }
            }
        }
        if (!resetSuggestions.isEmpty()) {
            switch (event.key()) {
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
                    suggestOwner = null;
                    resetSelected = -1;
                    return true;
                }
                default -> {
                    // 其他按键透传（继续输入时实时刷新建议）
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // 维度页列表滚轮滚动（1.21.1 为 4 参签名；forge 1.20.1 为 3 参，移植时注意）
        if (page == 2 && !pendingConfirm) {
            int maxScroll = Math.max(0, dimensionKeys().size() - dimVisibleRows());
            if (deltaY < 0 && dimScroll < maxScroll) {
                dimScroll++;
                rebuild();
                return true;
            }
            if (deltaY > 0 && dimScroll > 0) {
                dimScroll--;
                rebuild();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
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
