package dev.chunkplan.neoforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.chunkplan.common.DimensionStore;
import dev.chunkplan.common.DurationParser;
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
    /** 管理页分区分界线：与页签分隔线同色，克制不抢眼（用户要求"不过度引起注意"） */
    private static final int COL_DIVIDER = 0xFF555555;
    /** 补全建议框每行高度 */
    private static final int SUGGEST_ROW_H = 12;
    /** 补全建议最多行数（显示在输入框下方，防小窗口溢出） */
    private static final int SUGGEST_MAX = 6;

    /** 反馈面板存活时长：到时自动消失（F1） */
    private static final long FEEDBACK_LINGER_MILLIS = 6000L;
    /** 反馈面板末尾的淡出时长：最后这段时间按 alpha 线性衰减（只做淡出，不做渐入动画） */
    private static final long FEEDBACK_FADE_MILLIS = 1500L;
    /** 档位行「已保存」灰字存活时长：到时自动消失（F5） */
    private static final long SAVED_LINGER_MILLIS = 4000L;
    /** 自家命令派发后的调和静默期：此期间的中间态不回退（见 reconcileSuppressUntilMillis） */
    private static final long RECONCILE_SUPPRESS_MILLIS = 2000L;
    /** 数值输入框的最小/最大值文本（与服务端 NumericParser 的各入口范围同源，仅用于本地预校验文案） */
    private static final String MULT_MIN = "1.00";
    private static final String MULT_MAX = "1000.00";
    private static final String FEE_MIN = "0.00";
    private static final String FEE_MAX = "999999999.99";

    private GuiStatus status;
    private boolean waiting;
    private long requestTimeMillis;
    private int page; // 0 = 用量，1 = 管理，2 = 维度（仅管理员，issue #3）

    // 界面内反馈面板（issue #1 等）：服务端 feedback（GuiStatus v5）与本地校验失败共用一条
    /** 面板当前内容（null = 不显示）；只保留最近一条 */
    private Component feedbackText;
    private boolean feedbackSuccess;
    private long feedbackAtMillis;
    /** 面板命中区（渲染时算出；点击面板即消失） */
    private int fbX, fbY, fbW, fbH;

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
    /** 本批确认后是否作废管理页全部待应用档位意图（「全部关闭」用：避免确认后又把某档打开） */
    private boolean pendingDiscardIntents;

    // 管理页控件
    private final EditBox[] tierLimit = new EditBox[4];
    private final Button[] tierLimitSet = new Button[4];
    /** 档位行手绘下拉条命中区（[tier] = {x,y,w,h}）：窗口条随该档开关启用（开关是 Button，非手绘条） */
    private final int[][] tierWindowRect = new int[4][4];
    private final boolean[] tierWindowClickable = new boolean[4];
    private EditBox multEdit;
    private EditBox newFeeEdit;
    private EditBox familiarFeeEdit;
    /** 重置层级手绘下拉条命中区（原为循环切换的 Button，用户要求真下拉） */
    private final int[] resetTierRect = new int[4];
    private EditBox resetTarget;
    private Button resetButton;
    private int resetTier; // 0 = all，1..4
    // 预设区控件（issue #1、#2）
    /** 预设选择条命中区（手绘下拉条 + 真下拉；原为循环切换的 Button，用户要求改成下拉框观感） */
    private final int[] presetSelectRect = new int[4];
    /** 「按玩家应用」行的预设选择条命中区（行 3 自有的下拉，与行 1 各自独立） */
    private final int[] assignSelectRect = new int[4];
    private EditBox presetNameEdit;
    private EditBox presetTarget;
    /** 预设区四个动作按钮（脏状态下置灰 + 悬停说明） */
    private Button presetApplyBtn;
    private Button presetDeleteBtn;
    private Button presetSaveBtn;
    private Button presetAssignBtn;
    /** 「放弃未保存更改」按钮（管理页预设区行末 + 维度页保存区） */
    private Button discardButton;
    private Button dimDiscardButton;

    // 档位行「设置」待应用状态（本地先改、点「设置」才派发命令；重建后按服务端值比对回落）
    private final boolean[] pendingEnabledSet = new boolean[4];
    private final boolean[] pendingEnabled = new boolean[4];
    private final String[] pendingWindow = new String[4];
    /** 每档各自未保存（红字）/ 已保存（灰字）提示，仅本次打开期间显示 */
    private final boolean[] tierDirty = new boolean[4];
    private final boolean[] tierSavedShown = new boolean[4];
    /** 「已保存」灰字落点时刻（到时自动消失；未显示时为 0） */
    private final long[] tierSavedAtMillis = new long[4];
    /**
     * 上次重建时该档的服务端三元组签名（enabled/window/limit）。
     * 用于区分「自己保存成功后服务端值变化」与「外部（命令/其他管理员/文件重载）变更」：
     * 后者要丢弃该档待应用意图，否则会出现「点 tier4 开关 → 全部关闭 → 再点设置 → tier4 又被打开」。
     */
    private final String[] tierServerSig = new String[4];
    /**
     * 自家命令派发后的「调和静默期」截止时刻。
     *
     * <p>一次保存会派发多条命令，服务端每条命令都单独回推状态（ChunkPlanNetwork.handleCommand），
     * 于是会出现「窗口已改、额度未改」的中间态——若把这种中间态当成外部变更去丢弃意图，
     * 会误报「配置已被外部变更」并让管理员的保存半途而废。故派发后一小段时间内只做消费、不做丢弃。
     */
    private long reconcileSuppressUntilMillis;

    // 重置目标自动补全（仅在线玩家名；每次打开界面不显示，输入后才出现；
    // 补全机制为"当前聚焦的补全输入框"统一服务：resetTarget 或 presetTarget）
    private List<String> resetSuggestions = List.of();
    private int resetSelected = -1;
    private int resetTargetX, resetTargetY, resetTargetW, resetTargetH;
    private EditBox suggestOwner;

    // 预设区状态（issue #1、#2）
    /** 行 1 当前选中预设名（跨重建保留；null/不在列表时显示首个） */
    private String selectedPreset;
    /** 「按玩家应用」行选中的预设名（null/已失效时回落行 1 的当前选中） */
    private String assignPreset;
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
    /** 维度页编辑器「选择维度」手绘下拉条命中区（与重定向槽位同一观感，坑 #53） */
    private final int[] dimSelectRect = new int[4];
    private final EditBox[] dimTierLimit = new EditBox[4];
    private final Button[] dimTierSet = new Button[4];
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

    // 维度真下拉（用量页查看维度 + 维度页编辑器选维度 + 重定向槽位 + 档位窗口 + 重置层级，共用一套展开状态）
    // dimDropdownPage：0 = 用量页维度 1 = 管理页补全（未用） 2 = 维度页编辑器 3 = 重定向槽 5 = 档位窗口
    //                  6 = 管理页预设选择 7 = 管理页分配预设选择 8 = 管理页重置层级
    //（4 曾是档位开关下拉，坑 #51 起开关改回点击切换，编号废弃不复用）
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
    /** 用量页当前选中维度（null = 跟随当前所在维度；跨状态刷新保留，失效才回落——issue #3 后用户反馈） */
    private String usageDim;
    /** 用量页维度选择是否已初始化（仅首次打开界面用 currentDim() 定初值，其后由 onStatus 保留/回落） */
    private boolean usageDimInitialized;
    /** 待确认批量命令所属维度（非 null = 维度页档位编辑器发起，确认后按维度状态标记已保存） */
    private String pendingBatchDim;

    /** 维度页档位编辑器状态（与管理页档位行的全局数组对应，按维度隔离） */
    private static final class DimTierEdit {
        final boolean[] pendingEnabledSet = new boolean[4];
        final boolean[] pendingEnabled = new boolean[4];
        final String[] pendingWindow = new String[4];
        final boolean[] dirty = new boolean[4];
        final boolean[] savedShown = new boolean[4];
        final long[] savedAtMillis = new long[4];
        final String[] savedLimit = new String[4];
        /** 上次重建时该档服务端三元组签名（调和用，语义同管理页 tierServerSig） */
        final String[] serverSig = new String[4];
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
            // 档位行不建：清掉旧引用，防 savePreset 读到上一模式的残留输入（null = 回落服务端值）
            java.util.Arrays.fill(tierLimit, null);
            gy = 64; // 顶部渲染侧留一行提示文字
        } else {
            java.util.Arrays.fill(tierLimit, null); // 被下拉覆盖的行不建控件：先清引用防误用旧实例
            reconcileResetTier(); // 重置层级候选随服务端档位变化，选中项失效时回落「全部」
            for (int i = 0; i < 4; i++) {
                int tier = i + 1;
                QuotaTiers.Tier rt = rawTier(tier);
                String curWindow = rt == null ? "" : rt.window();
                double curLimit = rt == null ? 0 : rt.limit();
                final int idx = i;

                // 重建时按服务端三元组签名调和待应用状态：平台值已等于界面意图（或本就无意图）即消费；
                // 平台值被外部改动且与意图不一致即丢弃意图并提示（防「点开关 → 全部关闭 → 再保存」把档位又打开）
                reconcileTierIntent(idx, tier, curWindow, curLimit);

                boolean effOn = effEnabled(tier);
                int ry = 36 + i * rowH;

                // 开关是 Button：点击直接切换待应用状态（非下拉——用户拍板，坑 #51）
                int tx = left + 96;
                addButton(tx, ry, 52, 20,
                        Component.translatable(effOn ? "gui.chunkplan.enabled" : "gui.chunkplan.disabled"),
                        b -> setTierEnabled(tier, !effEnabled(tier)));
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
        // 层级选择：手绘下拉条（与其余下拉同观感，几何与旧 Button 相同；候选按模式见 resetTierLabels），
        // 命中在 mouseClicked；条内标签过长时由 drawSelectBar 截断，完整窗口名在下拉列表中可见
        setRect(resetTierRect, left + 176, gy, 52, 20);
        resetButton = addButton(left + 234, gy, 56, 20, Component.translatable("gui.chunkplan.reset"),
                b -> doReset());
        // 目标为空时重置置灰（此时点下去只会静默失败，用户已反馈「点了没反应」）
        refreshResetButton();

        // ---------- 预设区（issue #1、#2） ----------
        gy += 28;
        // 行 1：选择预设（手绘下拉条 + 真下拉）→ 应用到全体（写全局配置，需确认；独立模式下全局额度线
        // 不生效，按钮隐藏）/ 删除（本地确认，无服务端 confirm 流）
        setRect(presetSelectRect, left + 96, gy, 84, 20);
        if (!independent) {
            presetApplyBtn = addButton(left + 186, gy, 66, 20, Component.translatable("gui.chunkplan.preset_apply"),
                    b -> applyPresetAll());
        }
        presetDeleteBtn = addButton(left + 258, gy, 52, 20, Component.translatable("gui.chunkplan.preset_delete"),
                b -> deletePreset());
        gy += 28;
        // 行 2：把界面当前档位值保存为预设（12 值形式；名称客户端预校验，服务端权威）
        presetNameEdit = new EditBox(font, left + 96, gy, 74, 20, Component.empty());
        presetNameEdit.setValue(savedPresetName != null ? savedPresetName : "");
        presetNameEdit.setResponder(v -> savedPresetName = v);
        addRenderableWidget(presetNameEdit);
        presetSaveBtn = addButton(left + 176, gy, 52, 20, Component.translatable("gui.chunkplan.preset_save"),
                b -> savePreset());
        gy += 28;
        // 行 3：按玩家应用（目标默认在线玩家补全）＝ 玩家名输入框 + 行内预设下拉 + 分配
        // 空框灰字提示「输入玩家名」由 renderAdmin 手绘（不用 EditBox.setHint：跨六端 API 未核实）
        presetTarget = new EditBox(font, left + 96, gy, 74, 20, Component.empty());
        presetTarget.setValue(savedPresetTarget != null ? savedPresetTarget : "");
        presetTarget.setResponder(v -> savedPresetTarget = v);
        addRenderableWidget(presetTarget);
        // 「分配用预设」下拉条：行 3 自有状态，与行 1 的选择互不影响
        setRect(assignSelectRect, left + 176, gy, 76, 20);
        presetAssignBtn = addButton(left + 258, gy, 52, 20, Component.translatable("gui.chunkplan.preset_assign"),
                b -> assignPreset());
        gy += 28;
        // 行 4：放弃未保存的档位更改（仅脏状态可点；只清本地意图，不发任何命令）
        discardButton = addButton(left + 96, gy, 106, 20,
                Component.translatable("gui.chunkplan.preset_discard"), b -> discardUnsavedChanges());
        refreshPresetGate();
    }

    /**
     * 预设区脏状态门禁：有未保存的档位意图时，预设的应用/删除/分配三个动作置灰。
     * 理由：这些动作会与「尚未落盘的档位意图」产生歧义（把两种意图组合成用户没想过的结果）；
     * 用灰显 + 悬停说明（renderControlHoverHint）而不是弹窗拦截——不打断手头操作，也照样可查原因。
     * 「保存为预设」刻意**不参与门禁**：它以界面当前值为准（见 savePreset），未保存的意图正是要存进
     * 预设的内容——置灰它会把「先草拟档位、存成另一个预设」这条正常路径堵死。
     */
    private void refreshPresetGate() {
        boolean blocked = tiersDirty();
        for (Button b : new Button[]{presetApplyBtn, presetDeleteBtn, presetAssignBtn}) {
            if (b != null) {
                b.active = !blocked;
            }
        }
        if (discardButton != null) {
            discardButton.active = blocked;
        }
    }

    /** 管理页是否有未保存的档位意图（任一无实际变更的待应用状态不算脏） */
    private boolean tiersDirty() {
        if (tierDirty[0] || tierDirty[1] || tierDirty[2] || tierDirty[3]) {
            return true;
        }
        for (int i = 0; i < 4; i++) {
            if (pendingEnabledSet[i] || pendingWindow[i] != null || savedLimit[i] != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 每帧刷新按钮可点性（脏状态是现算的，输入框编辑不会触发重建——若只在构建时算一次，
     * 灰显状态会滞后于实际状态）。
     */
    private void refreshGates() {
        if (page == 1) {
            refreshResetButton();
            refreshPresetGate();
            if (discardButton != null) {
                discardButton.active = tiersDirty();
            }
        } else if (page == 2 && dimDiscardButton != null) {
            dimDiscardButton.active = dimCoordsDirty();
        }
    }

    /** 重置按钮可点性：目标为空即置灰（输入框下方另有灰字提示，见 renderAdmin） */
    private void refreshResetButton() {
        if (resetButton != null) {
            resetButton.active = resetTarget != null && !resetTarget.getValue().trim().isEmpty();
        }
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
    private void drawSelectBar(GuiGraphics g, int x, int y, int w, int h, Component label, boolean grey) {
        g.fill(x, y, x + w, y + h, COL_BG);
        g.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        g.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        g.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
        int color = grey ? COL_GRAY : COL_TEXT;
        String shown = font.plainSubstrByWidth(label.getString(), w - 20);
        g.drawString(font, Component.literal(shown), x + 4, y + (h - 8) / 2, color);
        g.drawString(font, Component.literal("▼"), x + w - 10, y + (h - 8) / 2, grey ? COL_GRAY : COL_ACCENT);
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

    /** 预设名规则（与服务端 PresetStore.NAME_PATTERN 同规则：本地预校验防误发，服务端权威） */
    private static final String PRESET_NAME_PATTERN = "[A-Za-z0-9_-]{1,32}";

    /**
     * 数值输入本地预校验失败的面板红字：错误分类沿用 common {@link NumericParser}（EMPTY/FORMAT/RANGE），
     * 不自造口径；范围上下限与该字段的服务端入口一一对应，文案由 lang 渲染。
     */
    private void reportNumericFailure(NumericParser.Parsed p, String fieldKey, String min, String max) {
        setFeedback(Component.translatable("gui.chunkplan.feedback.num_invalid",
                Component.translatable(fieldKey), numericErrorText(p), min, max), false);
    }

    /** NumericParser 错误分类的中性短语（不重复范围数值，范围由调用方按字段补） */
    private static Component numericErrorText(NumericParser.Parsed p) {
        return switch (p.error()) {
            case EMPTY -> Component.translatable("gui.chunkplan.feedback.num_empty");
            case FORMAT -> Component.translatable("gui.chunkplan.feedback.num_format");
            default -> Component.translatable("gui.chunkplan.feedback.num_range");
        };
    }

    /** 栏位发生待应用更改：该档红字提示 */
    private void markTierDirty(int tier) {
        this.tierDirty[tier - 1] = true;
    }

    /**
     * 重建时按服务端三元组签名 (enabled, window, limit) 调和该档待应用状态（F6）：
     *
     * <ul>
     *   <li>服务端值已等于界面意图（或本就无意图）→ 消费待应用状态，界面回到服务端真相；</li>
     *   <li>服务端值已被外部改动（命令、其他管理员、文件 reload）且与本档意图不一致 → 丢弃该档意图
     *       并提示。否则会出现坏路径：点 tier4 开关（待应用=开）→ 点「全部关闭」→ 确认 →
     *       界面仍显示 tier4「已开启」+ 红字 → 再点「设置」→ 把 tier4 又打开。</li>
     * </ul>
     */
    private void reconcileTierIntent(int idx, int tier, String curWindow, double curLimit) {
        String sig = tierSignature(tierEnabled(tier), curWindow, curLimit);
        String prev = tierServerSig[idx];
        // 静默期内不刷新基线：多条命令会有「窗口已改、额度未改」的中间态，若把它记成新基线，
        // 静默期结束后就再也识别不出外部变更（基线已被中间态污染）
        if (System.currentTimeMillis() >= reconcileSuppressUntilMillis) {
            tierServerSig[idx] = sig;
        }
        boolean hasIntent = pendingEnabledSet[idx] || pendingWindow[idx] != null || savedLimit[idx] != null;
        if (!hasIntent) {
            return;
        }
        // 界面意图三元组：与服务端值逐项比对（enabled 取待应用态、window 取待应用态、limit 取输入框文本）
        NumericParser.Parsed p = savedLimit[idx] == null ? null : NumericParser.parseLimit(savedLimit[idx]);
        double wantLimit = p != null && p.isOk() ? p.value() : curLimit;
        String wantSig = tierSignature(effEnabled(tier),
                pendingWindow[idx] != null ? pendingWindow[idx] : curWindow, wantLimit);
        if (wantSig.equals(sig)) {
            consumeTierIntent(idx); // 已生效：消费意图（保留刚点出来的「已保存」灰字）
            return;
        }
        if (prev != null && !prev.equals(sig)
                && System.currentTimeMillis() >= reconcileSuppressUntilMillis) {
            // 服务端值与上次所见不同（外部变更）且仍未达到本档意图：丢弃意图，交由管理员重新操作
            discardTierIntent(idx);
            setFeedback(Component.translatable("gui.chunkplan.feedback.tier_discarded", tier), false);
        }
    }

    private static String tierSignature(boolean enabled, String window, double limit) {
        return enabled + "|" + window + "|" + limit;
    }

    /** 消费某一档的待应用意图：服务端已是该值，界面回到服务端真相（「已保存」灰字另行计时消失） */
    private void consumeTierIntent(int idx) {
        pendingEnabledSet[idx] = false;
        pendingWindow[idx] = null;
        savedLimit[idx] = null;
        tierDirty[idx] = false;
    }

    /** 丢弃某一档的待应用意图（外部变更导致）：连「已保存」灰字一并清掉 */
    private void discardTierIntent(int idx) {
        consumeTierIntent(idx);
        tierSavedShown[idx] = false;
    }

    /**
     * 清空全部档位的待应用意图（「放弃未保存更改」按钮 + 「全部关闭」确认后调用）。
     * 只清本地意图，不发任何命令。
     */
    private void discardAllTierIntents() {
        for (int i = 0; i < 4; i++) {
            discardTierIntent(i);
        }
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
            this.tierDirty[i] = false; // 清空视为无更改（applyTier 同样跳过空值），重建回显服务端值
            refreshPresetGate();
            return;
        }
        // 赋值式现算：改了又改回原值自动回到干净态（与维度页 markDimTierDirtyIfChanged 同构）
        this.tierDirty[i] = !typed.equals(saved);
        refreshPresetGate();
    }

    /** 保存后：清该档未保存标记并显示灰字「已保存」（数秒后自动消失，见 renderAdmin） */
    private void markSaved(int tier) {
        this.tierDirty[tier - 1] = false;
        this.tierSavedShown[tier - 1] = true;
        this.tierSavedAtMillis[tier - 1] = System.currentTimeMillis();
    }

    /** 「已保存」灰字是否仍在显示期（到时自动消失，无需清理标志） */
    private boolean tierSavedVisible(int i) {
        return tierSavedShown[i]
                && System.currentTimeMillis() - tierSavedAtMillis[i] < SAVED_LINGER_MILLIS;
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
                if (!p.isOk()) {
                    // 额度数值非法：面板红字指出非法项（原先静默跳过，管理员看不出为何没生效）
                    setFeedback(Component.translatable("gui.chunkplan.feedback.tier_limit_invalid", tier,
                            numericErrorText(p)), false);
                    return;
                }
                if (Double.compare(p.value(), curLimit) != 0) {
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
            reconcileSuppressUntilMillis = System.currentTimeMillis() + RECONCILE_SUPPRESS_MILLIS;
        }
    }

    /** 档位开关点击切换：仅改本地待应用状态，仍须点「设置」才落盘（坑 #51 起开关是 Button 非下拉） */
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
        NumericParser.Parsed p = NumericParser.parseMultiplier(raw);
        if (!p.isOk()) {
            reportNumericFailure(p, "gui.chunkplan.speed_mult", MULT_MIN, MULT_MAX);
            return; // 非法数值：不发送，交由玩家修正
        }
        sendCommand("chunkplan config highSpeedMultiplier " + raw);
        savedMult = null; // 派发后回读服务端确认值，不再保留输入
    }

    private void setNewFee() {
        String raw = newFeeEdit.getValue().trim();
        NumericParser.Parsed p = NumericParser.parseFee(raw);
        if (!p.isOk()) {
            reportNumericFailure(p, "gui.chunkplan.fee_new", FEE_MIN, FEE_MAX);
            return; // 非法数值：不发送，交由玩家修正
        }
        sendCommand("chunkplan config firstEntryFee " + raw);
        savedNewFee = null; // 派发后回读服务端确认值，不再保留输入
    }

    private void setFamiliarFee() {
        String raw = familiarFeeEdit.getValue().trim();
        NumericParser.Parsed p = NumericParser.parseFee(raw);
        if (!p.isOk()) {
            reportNumericFailure(p, "gui.chunkplan.fee_explored", FEE_MIN, FEE_MAX);
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
            // 「全部关闭」是整批档位的动作：确认后本地待应用意图一并作废，否则界面仍显示某档
            // 「已开启」+ 红字，管理员照做会把该档又打开（F6 的坏路径）。
            // 作废动作挂在确认分支（取消确认时不该丢管理员的未保存更改）。
            this.pendingDiscardIntents = true;
        } else {
            sendCommand("chunkplan config window all on");
        }
    }

    /** 重置层级下拉：展开真下拉（候选按模式见 dropdownLabels case 8） */
    private void openResetTierDropdown() {
        if (resetTierLabels().isEmpty()) {
            return;
        }
        openDimDropdown(8, resetTierRect);
    }

    /** 重置层级标签列表（共享模式：全部 + 各启用档的窗口名；独立模式：全部 + tier1..4，见 F8） */
    private List<String> resetTierLabels() {
        boolean independent = status != null && status.dimensionMode() == 1;
        List<String> labels = new ArrayList<>();
        labels.add(Component.translatable("gui.chunkplan.reset_tier_all").getString());
        if (independent) {
            for (int t = 1; t <= 4; t++) {
                labels.add("tier" + t);
            }
            return labels;
        }
        boolean zh = zh();
        for (int t = 1; t <= 4; t++) {
            long secs = tierWindowSeconds(t);
            if (secs > 0) {
                labels.add(ChunkPlanMessages.windowName(secs, zh));
            }
        }
        return labels;
    }

    /** 与 {@link #resetTierLabels()} 一一对应的档位值（0 = 全部） */
    private List<Integer> resetTierValues() {
        boolean independent = status != null && status.dimensionMode() == 1;
        List<Integer> values = new ArrayList<>();
        values.add(0);
        if (independent) {
            for (int t = 1; t <= 4; t++) {
                values.add(t);
            }
            return values;
        }
        for (int t = 1; t <= 4; t++) {
            if (tierWindowSeconds(t) > 0) {
                values.add(t);
            }
        }
        return values;
    }

    /**
     * 该档当前的窗口秒数（仅启用档有效，否则 -1）：取服务端原始档位配置，与 check 的窗口名口径同源。
     * 配置损坏时 DurationParser 会抛异常（服务端已回退默认，正常不会走到），此处兜底 -1 防界面崩溃。
     */
    private long tierWindowSeconds(int tier) {
        QuotaTiers.Tier rt = rawTier(tier);
        if (rt == null || !rt.enabled()) {
            return -1;
        }
        try {
            return DurationParser.parseSeconds(rt.window());
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** 当前选中层级显示名（选中的档位可能已被外部关闭 → 回落「全部」） */
    private String resetTierLabel() {
        List<Integer> values = resetTierValues();
        int idx = values.indexOf(resetTier);
        return idx >= 0 ? resetTierLabels().get(idx)
                : Component.translatable("gui.chunkplan.reset_tier_all").getString();
    }

    /** 选中层级回落到「全部」：候选里已无该档（被外部关闭）时调用，防命令带上已关档位被服务端拒 */
    private void reconcileResetTier() {
        if (!resetTierValues().contains(resetTier)) {
            resetTier = 0;
        }
    }

    private String resetTierName() {
        return resetTier == 0 ? "all" : "tier" + resetTier;
    }

    /** 确认文案里的重置范围词（与命令层 QuotaMessages 的 Reset 分支口径一致，见坑 #32/#36） */
    private String resetScopeName(boolean zh) {
        boolean independent = status != null && status.dimensionMode() == 1;
        if (resetTier == 0) {
            return Component.translatable(independent ? "gui.chunkplan.reset_scope_all_dims"
                    : "gui.chunkplan.reset_scope_all").getString();
        }
        if (independent) {
            // 独立模式各维度窗口可能不同，用档位身份表述（与命令层一致）
            return "tier" + resetTier;
        }
        long secs = tierWindowSeconds(resetTier);
        return secs > 0 ? ChunkPlanMessages.windowName(secs, zh) : "tier" + resetTier;
    }

    private void doReset() {
        String target = resetTarget.getValue().trim();
        if (target.isEmpty()) {
            // 按钮已置灰，此处兜底提示（本地校验失败必须可见）
            setFeedback(Component.translatable("gui.chunkplan.feedback.reset_no_target"), false);
            return;
        }
        boolean zh = zh();
        String cmd = "chunkplan reset " + target + (resetTier == 0 ? "" : " " + resetTierName());
        showConfirm(Component.translatable("gui.chunkplan.confirm.reset", target, resetScopeName(zh)));
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
        int idx = presetIndexOf(names, selectedPreset);
        return names.get(idx >= 0 ? idx : 0);
    }

    /** 「按玩家应用」行选中的预设（未选过/选中项已失效时回落行 1 的当前选中；列表空返回 null） */
    private String assignPresetOrNull() {
        List<String> names = presetNames();
        if (names.isEmpty()) {
            return null;
        }
        int idx = presetIndexOf(names, assignPreset);
        return idx >= 0 ? names.get(idx) : currentPresetOrNull();
    }

    /** 预设名在列表中的下标；name 为 null（从未选中过任何预设）时返回 -1。
     *  不可直接写 names.indexOf(name)：status.presets() 是 List.copyOf 产出的不可变列表，
     *  其 indexOf(null) 会抛 NPE（JDK ImmutableCollections 拒绝 null 实参，普通 ArrayList 才返回 -1），
     *  有预设后会在 buildAdmin 里炸掉整个预设区控件（坑 #54） */
    private int presetIndexOf(List<String> names, String name) {
        return name == null ? -1 : names.indexOf(name);
    }

    private String selectedPresetName() {
        String cur = currentPresetOrNull();
        return cur == null ? "—" : cur;
    }

    private String assignPresetLabel() {
        String cur = assignPresetOrNull();
        return cur == null ? "—" : cur;
    }

    /** 行 1 选择预设：展开真下拉，候选即预设名列表（列表空时条为灰、点不动） */
    private void openPresetDropdown() {
        if (presetNames().isEmpty()) {
            return;
        }
        openDimDropdown(6, presetSelectRect);
    }

    /** 行 3 分配用的预设：同一候选列表，选择结果只影响「分配」 */
    private void openAssignDropdown() {
        if (presetNames().isEmpty()) {
            return;
        }
        openDimDropdown(7, assignSelectRect);
    }

    /**
     * 应用当前选中预设到全体（写全局配置）：服务端 apply 需 confirm，走批量派发 + 补 confirm。
     * 弹窗正文按「只陈述事实」的词表口径写清影响面（F7）：写入目标、生效范围、关档侧的记录语义、
     * 以及可能当场踢出/传送。
     */
    private void applyPresetAll() {
        String name = currentPresetOrNull();
        if (name == null) {
            setFeedback(Component.translatable("gui.chunkplan.feedback.no_preset"), false);
            return;
        }
        String summary = presetSummaryOf(name);
        // 服务端未下发该预设内容（旧服务端/异常）时用无摘要的文案，避免弹窗出现空括号
        showConfirm(summary.isEmpty()
                ? Component.translatable("gui.chunkplan.confirm.apply_preset_nosum", name)
                : Component.translatable("gui.chunkplan.confirm.apply_preset", name, summary));
        this.pendingBatch = List.of("chunkplan preset apply " + name);
    }

    /** 预设的 12 值摘要（仅预设内容，不含全局的费率/倍率/豁免）：tier1 5h/500 · tier2 24h/2000 … */
    private String presetSummaryOf(String name) {
        for (GuiStatus.PresetInfo pi : presetInfos()) {
            if (pi.name().equals(name)) {
                return presetSummary(pi.tiers());
            }
        }
        return "";
    }

    private static String presetSummary(List<QuotaTiers.Tier> tiers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tiers.size() && i < 4; i++) {
            QuotaTiers.Tier t = tiers.get(i);
            if (i > 0) {
                sb.append(" · ");
            }
            sb.append("tier").append(i + 1).append(' ');
            if (!t.enabled()) {
                sb.append("off");
            } else {
                sb.append(t.window()).append('/').append(fmtLimit(t.limit()));
            }
        }
        return sb.toString();
    }

    /** 全局配置的 12 值摘要（管理页档位区对照说明用，与上面的预设摘要同格式） */
    private String globalTierSummary() {
        if (status == null || status.tiers() == null || status.tiers().isEmpty()) {
            return "";
        }
        return presetSummary(status.tiers());
    }

    /** 服务端下发的预设内容（旧字段 presets() 只有名字，内容仅 v5 的 presetInfos 提供） */
    private List<GuiStatus.PresetInfo> presetInfos() {
        return status == null || status.presetInfos() == null ? List.of() : status.presetInfos();
    }

    /** 删除当前选中预设：服务端无 confirm 流，本地弹窗确认后直接派发（跳过补发 confirm） */
    private void deletePreset() {
        String name = currentPresetOrNull();
        if (name == null) {
            setFeedback(Component.translatable("gui.chunkplan.feedback.no_preset"), false);
            return;
        }
        showConfirm(Component.translatable("gui.chunkplan.confirm.delete_preset", name));
        this.pendingBatch = List.of("chunkplan preset delete " + name);
        this.pendingSkipConfirm = true;
    }

    /**
     * 「保存为预设」：改为 12 值形式（所见即所存），12 值取自<b>界面当前展示值</b>而非配置文件快照——
     * 原先发单名形式，服务端从配置文件快照，管理员在界面上改了档位但没点「设置」时，存下的是旧值
     * （界面显示 1000、存下 500，且无任何提示）。
     *
     * <p>token 顺序与服务端 preset save 的 12 值形式一致：名称 + tier1{on,win,limit} … tier4{on,win,limit}；
     * 该命令只写预设文件、不改全局配置（"做一个不立刻应用的预设"的通道）。
     * 额度走 {@link NumericParser#parseLimit} 预校验，非法即面板红字并中止（不发命令）。
     */
    private void savePreset() {
        String name = presetNameEdit.getValue().trim();
        // 与服务端 PresetStore.NAME_PATTERN 同规则：客户端预校验防误发，服务端权威
        if (!name.matches(PRESET_NAME_PATTERN)) {
            setFeedback(Component.translatable("gui.chunkplan.feedback.preset_name_invalid"), false);
            return;
        }
        // 先整体校验 4 档额度：任一非法即中止，避免发出半截命令
        double[] limits = new double[4];
        for (int i = 0; i < 4; i++) {
            int tier = i + 1;
            QuotaTiers.Tier rt = rawTier(tier);
            double serverLimit = rt == null ? 0 : rt.limit();
            String typed = tierLimit[i] == null ? "" : tierLimit[i].getValue().trim();
            // 空输入回落服务端值（与 applyTier 的「清空视为无更改」一致）
            NumericParser.Parsed p = NumericParser.parseLimit(typed.isEmpty() ? fmtLimit(serverLimit) : typed);
            if (!p.isOk()) {
                setFeedback(Component.translatable("gui.chunkplan.feedback.preset_limit_invalid", tier,
                        numericErrorText(p)), false);
                return;
            }
            limits[i] = p.value();
        }
        StringBuilder cmd = new StringBuilder("chunkplan preset save ").append(name);
        for (int i = 0; i < 4; i++) {
            int tier = i + 1;
            QuotaTiers.Tier rt = rawTier(tier);
            String serverWindow = rt == null ? "" : rt.window();
            String win = pendingWindow[i] != null ? pendingWindow[i] : serverWindow;
            cmd.append(' ').append(effEnabled(tier) ? "true" : "false")
                    .append(' ').append(win)
                    .append(' ').append(fmtLimit(limits[i]));
        }
        sendCommand(cmd.toString());
        // 保留名称输入：管理员常在微调配置后同名覆盖保存
    }

    /** 分配选中预设给目标玩家（目标为空 / 无可用预设都走面板提示，不再静默失败） */
    private void assignPreset() {
        String target = presetTarget.getValue().trim();
        String name = assignPresetOrNull();
        if (target.isEmpty()) {
            setFeedback(Component.translatable("gui.chunkplan.feedback.assign_no_target"), false);
            return;
        }
        if (name == null) {
            setFeedback(Component.translatable("gui.chunkplan.feedback.no_preset"), false);
            return;
        }
        sendCommand("chunkplan preset player " + target + " " + name);
        // 保留目标输入：便于对多名玩家连续分配
    }

    /**
     * 放弃未保存的更改：清空本地全部待应用意图（管理页档位 + 维度页档位/坐标/计费开关/模式），
     * 然后重建。**只清本地意图，不发任何命令**——服务端状态本来就是被放弃的一方。
     */
    private void discardUnsavedChanges() {
        discardAllTierIntents();
        pendingDimMode = null;
        pendingBilling.clear();
        savedDimCoords.clear();
        dimCoordsSavedShown = false;
        for (DimTierEdit st : dimEdits.values()) {
            for (int i = 0; i < 4; i++) {
                st.pendingEnabledSet[i] = false;
                st.pendingEnabled[i] = false;
                st.pendingWindow[i] = null;
                st.dirty[i] = false;
                st.savedShown[i] = false;
                st.savedLimit[i] = null;
            }
        }
        rebuild();
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

    /**
     * 维度是否 live（在服务端当前注册维度列表中）。非 live 的已配置维度是陈旧条目：
     * 服务端对全部 config dimension 命令 requireLiveDim 拒绝，客户端同步禁控/跳过派发，
     * 防乐观值永不消费、「未保存」红字滞留整个会话（审查 MINOR）。
     */
    private boolean isLiveDim(String dim) {
        return status != null && status.dimensions() != null && status.dimensions().contains(dim);
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
            // 服务端已确认的待保存开关：消费保留值，回显服务端真相（与坐标/档位同规则）
            Boolean pb = pendingBilling.get(dim);
            boolean curBilling = pb != null ? pb : billing;
            if (pb != null && pb == billing) {
                pendingBilling.remove(dim);
            }
            boolean live = isLiveDim(dim);
            addButton(left + 150, ry, 48, 18,
                    Component.translatable(curBilling ? "gui.chunkplan.on" : "gui.chunkplan.off"),
                    b -> toggleDimBilling(dimF, curBilling)).active = live;
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
                box.active = live; // 非 live 维度：服务端 requireLiveDim 会拒绝，直接禁编辑防乐观值滞留
                addRenderableWidget(box);
            }
        }

        // 保存按钮恒可点：坐标齐全是「切换独立模式」的前置条件，由服务端在切换时校验（不齐则拒绝
        // 并列出缺失维度）；客户端灰显会让「切独立 → 逐个填坐标 → 保存」这条正常路径走不通
        addButton(left, saveY, 110, 20, Component.translatable("gui.chunkplan.dim.save_coords"),
                b -> saveDimCoords());
        // 放弃未保存的更改（维度页同构）：只清本地意图，不发命令
        dimDiscardButton = addButton(left + 116, saveY, 116, 20,
                Component.translatable("gui.chunkplan.preset_discard"), b -> discardUnsavedChanges());
        dimDiscardButton.active = dimCoordsDirty();

        // 底部档位编辑器：选中维度 + 4 档（仅独立模式可编辑，共享模式灰显——服务端同语义）
        int et = dimEditorTop();
        // 选择维度：手绘下拉条（黑底+白描边+▼，与重定向槽位/窗口条统一观感，坑 #53），命中在 mouseClicked
        setRect(dimSelectRect, left, et, 150, 18);
        if (selectedDim == null) {
            return;
        }
        DimTierEdit st = dimEditState(selectedDim);
        java.util.Arrays.fill(dimTierLimit, null); // 重建前清引用，防误用旧实例
        boolean live = isLiveDim(selectedDim); // 非 live 维度：服务端会拒绝全部 dimension 命令，编辑器整体禁用
        for (int i = 0; i < 4; i++) {
            int tier = i + 1;
            int ry = et + 24 + i * 22;
            final int idx = i;
            final String dimKey = selectedDim;
            // 重建回落（与管理页档位行同规则，含三元组签名调和）
            QuotaTiers.Tier rt = dimTier(selectedDim, tier);
            String curWindow = rt == null ? "" : rt.window();
            double curLimit = rt == null ? 0 : rt.limit();
            reconcileDimTierIntent(st, selectedDim, idx, tier, curWindow, curLimit);
            boolean effOn = st.pendingEnabledSet[idx] ? st.pendingEnabled[idx] : dimTierEnabled(selectedDim, tier);
            // tier 开关：点击直接切换待应用状态（非下拉——用户拍板，坑 #51）；共享模式/非 live 维度禁用
            addButton(left + 40, ry, 52, 20,
                    Component.translatable(effOn ? "gui.chunkplan.enabled" : "gui.chunkplan.disabled"),
                    b -> setDimTierEnabled(dimKey, tier, !dimTierEffOn(dimKey, tier))).active = independent && live;
            String win = st.pendingWindow[idx] != null ? st.pendingWindow[idx] : curWindow;
            setRect(dimTierWindowRect[i], left + 98, ry, 64, 20);
            dimTierWindowClickable[i] = independent && live && effOn;
            dimTierLimit[i] = new EditBox(font, left + 168, ry, 50, 20, Component.empty());
            dimTierLimit[i].setValue(st.savedLimit[idx] != null ? st.savedLimit[idx] : fmtLimit(curLimit));
            dimTierLimit[i].setResponder(v -> {
                st.savedLimit[idx] = v.trim().isEmpty() ? null : v;
                markDimTierDirtyIfChanged(dimKey, tier);
            });
            dimTierLimit[i].setMaxLength(12);
            addRenderableWidget(dimTierLimit[i]);
            dimTierLimit[i].active = independent && live && effOn;
            dimTierSet[i] = addButton(left + 224, ry, 42, 20,
                    Component.translatable("gui.chunkplan.set"), b -> applyDimTier(dimKey, tier));
            dimTierSet[i].active = independent && live;
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
            if (!isLiveDim(dim)) {
                continue; // 非 live 维度不参与：服务端拒绝其全部命令，不存在可消费的乐观值
            }
            if (dimEditPending(dim)) {
                return true;
            }
        }
        return false;
    }

    /** 计费开关本地切换：仅改待保存值，随「保存」统一派发（原先即时生效，与坐标/档位不一致——用户反馈） */
    private void toggleDimBilling(String dim, boolean cur) {
        if (!isLiveDim(dim)) {
            return; // 非 live 维度：服务端 requireLiveDim 拒绝，开关不可切（按钮已禁用，此处兜底）
        }
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
        int skippedInvalid = 0;
        for (String dim : dimensionKeys()) {
            if (!isLiveDim(dim)) {
                pendingBilling.remove(dim); // 非 live 维度：服务端拒绝其命令，乐观值直接丢弃防滞留
                continue;
            }
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
                skippedInvalid++; // 填了一半/越界：不派发（服务端权威拒绝），只统计条数供面板提示
                continue;
            }
            double[] c = parsedCoords(dim);
            cmds.add("chunkplan config dimension " + dim + " spawn "
                    + fmtCoord(c[0]) + " " + fmtCoord(c[1]) + " " + fmtCoord(c[2]));
        }
        if (skippedInvalid > 0) {
            // 静默跳过会让管理员以为坐标已存；服务端拒绝是权威，但必须让"被跳过"这件事可见
            setFeedback(Component.translatable("gui.chunkplan.feedback.spawn_skipped", skippedInvalid), false);
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

    /** 维度档位开关点击切换：仅改本地待应用状态，仍须点「设置」才落盘（坑 #51 起开关是 Button 非下拉） */
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
     * 维度页档位行按服务端三元组签名调和待应用状态（与管理页 reconcileTierIntent 同构）；
     * 外部变更（命令/另一管理员/文件 reload）即丢弃该维度该档的意图并提示。
     */
    private void reconcileDimTierIntent(DimTierEdit st, String dim, int idx, int tier,
                                        String curWindow, double curLimit) {
        String sig = tierSignature(dimTierEnabled(dim, tier), curWindow, curLimit);
        String prev = st.serverSig[idx];
        // 静默期内不刷新基线（同管理页 reconcileTierIntent 的说明）
        if (System.currentTimeMillis() >= reconcileSuppressUntilMillis) {
            st.serverSig[idx] = sig;
        }
        boolean hasIntent = st.pendingEnabledSet[idx] || st.pendingWindow[idx] != null || st.savedLimit[idx] != null;
        if (!hasIntent) {
            return;
        }
        NumericParser.Parsed p = st.savedLimit[idx] == null ? null : NumericParser.parseLimit(st.savedLimit[idx]);
        double wantLimit = p != null && p.isOk() ? p.value() : curLimit;
        String wantSig = tierSignature(
                st.pendingEnabledSet[idx] ? st.pendingEnabled[idx] : dimTierEnabled(dim, tier),
                st.pendingWindow[idx] != null ? st.pendingWindow[idx] : curWindow, wantLimit);
        if (wantSig.equals(sig)) {
            st.pendingEnabledSet[idx] = false;
            st.pendingWindow[idx] = null;
            st.savedLimit[idx] = null;
            st.dirty[idx] = false;
            return;
        }
        if (prev != null && !prev.equals(sig)
                && System.currentTimeMillis() >= reconcileSuppressUntilMillis) {
            st.pendingEnabledSet[idx] = false;
            st.pendingWindow[idx] = null;
            st.savedLimit[idx] = null;
            st.dirty[idx] = false;
            st.savedShown[idx] = false;
            setFeedback(Component.translatable("gui.chunkplan.feedback.dim_tier_discarded", dim, tier), false);
        }
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
        if (!isLiveDim(dim)) {
            return; // 非 live 维度：服务端 requireLiveDim 拒绝（按钮已禁用，此处兜底）
        }
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
                if (!p.isOk()) {
                    // 额度数值非法：面板红字指出非法项（原先静默跳过，管理员看不出为何没生效）
                    setFeedback(Component.translatable("gui.chunkplan.feedback.dim_limit_invalid", dim, tier,
                            numericErrorText(p)), false);
                    return;
                }
                if (Double.compare(p.value(), curLimit) != 0) {
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
            st.savedAtMillis[i] = System.currentTimeMillis();
        }
    }

    private void renderDimensions(GuiGraphics g) {
        int x = 12;
        if (!isAdmin()) {
            g.drawString(font, Component.translatable("gui.chunkplan.no_permission"), x, 44, COL_RED);
            return;
        }
        if (status == null || status.dimConfig() == null) {
            g.drawString(font, Component.translatable(waiting ? "gui.chunkplan.fetching" : "gui.chunkplan.parse_error"),
                    x, 40, waiting ? COL_GRAY : COL_RED);
            return;
        }
        boolean independent = status.dimensionMode() == 1;
        boolean effIndependent = independent || Boolean.TRUE.equals(pendingDimMode);
        List<String> dims = dimensionKeys();
        // 重定向 3 槽位文字（手绘下拉条，见 buildDimensions；展开时源条照常显示，列表浮层盖住下方内容）
        List<String> order = status.dimConfig().redirectOrder();
        for (int s = 0; s < 3; s++) {
            String cur = s < order.size() ? order.get(s) : null;
            drawSelectBar(g, slotBarRect[s][0], slotBarRect[s][1], slotBarRect[s][2], slotBarRect[s][3],
                    Component.translatable("gui.chunkplan.dim.slot" + (s + 1))
                            .append(Component.literal(": " + (cur == null ? "—" : shortDim(cur)))),
                    !slotBarClickable[s]);
        }
        // 列表头（y=58：上距模式行按钮 2px、下距列表首行 3px，原 y=56 与首行控件顶边重叠）
        g.drawString(font, Component.translatable("gui.chunkplan.dim.dim_header"), x + 2, 58, COL_GRAY);
        g.drawString(font, Component.translatable("gui.chunkplan.dim.billing"), x + 152, 58, COL_GRAY);
        g.drawString(font, Component.translatable("gui.chunkplan.dim.spawn_header"), x + 216, 58, COL_GRAY);
        g.drawString(font, Component.translatable("gui.chunkplan.dim.usage_header"), x + 420, 58, COL_GRAY);
        // 行内容（控件之外的文字）
        int visibleRows = dimVisibleRows();
        for (int i = 0; i < dims.size(); i++) {
            if (i < dimScroll || i >= dimScroll + visibleRows) {
                continue;
            }
            String dim = dims.get(i);
            int ry = DIM_LIST_TOP + (i - dimScroll) * DIM_ROW_H;
            GuiStatus.DimEntry e = dimEntry(dim);
            boolean live = isLiveDim(dim);
            // 编辑器正在编辑的行：左侧色块 + 行底分隔线（不铺整行底色，避免盖在坐标输入框上）
            if (dim.equals(selectedDim)) {
                g.fill(x, ry - 2, x + 3, ry + 18, COL_ACCENT);
                g.fill(x + 3, ry + 19, x + 440, ry + 20, 0x66FFFFFF);
            }
            g.drawString(font, Component.literal(font.plainSubstrByWidth(shortDim(dim), 130)),
                    x + 2, ry + 6, dimBillingShown(dim) ? COL_TEXT : COL_GRAY);
            // 行末列：非 live 维度（已配置但当前未注册）标注一句让语义自明（控件已置灰）；
            // live 维度则显示该维度当前最高占用档位用量（数据来自全体下发的 dimLines，截断不换行）
            int tailX = x + 420;
            int tailW = Math.max(40, width - tailX - 8);
            if (!live) {
                g.drawString(font, Component.literal(font.plainSubstrByWidth(
                                Component.translatable("gui.chunkplan.dim.not_live").getString(), tailW)),
                        tailX, ry + 6, COL_GRAY);
            } else {
                String usage = dimUsageText(dim);
                if (!usage.isEmpty()) {
                    g.drawString(font, Component.literal(font.plainSubstrByWidth(usage, tailW)),
                            tailX, ry + 6, dimUsageColor(dim));
                }
            }
            // 坐标合法状态点：绿=合法，红=缺失/非法
            int dotX = x + 400;
            g.fill(dotX, ry + 6, dotX + 6, ry + 12, dimCoordsValid(dim) ? COL_GREEN : COL_RED);
        }
        // 保存区提示：未保存红字 > 已保存灰字 > 留空即清除的常驻说明（浮层展开时被列表盖住即可）；
        // x 取 +248：右侧是新增的「放弃未保存更改」按钮（left+116 起、宽 116，止于 244）
        int saveY = dimSaveY();
        if (dimCoordsDirty()) {
            g.drawString(font, Component.translatable("gui.chunkplan.dim.coords_dirty"), x + 248, saveY + 6, COL_RED);
        } else if (dimCoordsSavedShown) {
            g.drawString(font, Component.translatable("gui.chunkplan.dim.coords_saved"), x + 248, saveY + 6, COL_GRAY);
        } else {
            // 坐标是模式无关的单份存储：两种模式下都可改可清（避免"共享模式改不掉"）
            g.drawString(font, Component.translatable("gui.chunkplan.dim.spawn_hint"), x + 248, saveY + 6, COL_GRAY);
        }
        if (effIndependent && !missingSpawnDims().isEmpty()) {
            g.drawString(font, Component.translatable("gui.chunkplan.dim.coords_missing"), x + 248, saveY + 20, COL_RED);
        }
        // 重定向开启但三槽全空：说明回退顺序（槽位条右侧灰字，条目最长处仍留余量）
        if (independent && status.dimConfig().redirectOnExhaust() && redirectOrderAllEmpty()) {
            g.drawString(font, Component.translatable("gui.chunkplan.dim.redirect_fallback_hint"),
                    x + 620, 42, COL_GRAY);
        }
        // 编辑器标签 + 维度选择条（手绘下拉条，展开时源条照常显示）
        int et = dimEditorTop();
        drawSelectBar(g, dimSelectRect[0], dimSelectRect[1], dimSelectRect[2], dimSelectRect[3],
                Component.literal(selectedDim == null ? "—" : selectedDim), false);
        g.drawString(font, Component.translatable("gui.chunkplan.dim.editing", selectedDim == null ? "—" : selectedDim),
                x + 156, et + 6, COL_TEXT);
        if (!independent) {
            g.drawString(font, Component.translatable("gui.chunkplan.dim.shared_mode_hint"), x + 300, et + 6, COL_GRAY);
        }
        DimTierEdit st = selectedDim == null ? null : dimEdits.get(selectedDim);
        // 与 buildDimensions 同规则：开关是 Button 自绘，窗口条常显（展开时列表浮层盖住下方行）
        if (st != null) {
            for (int i = 0; i < 4; i++) {
                int ry = et + 24 + i * 22;
                g.drawString(font, Component.literal("tier" + (i + 1)), x + 2, ry + 6, COL_TEXT);
                QuotaTiers.Tier dt = dimTier(selectedDim, i + 1);
                String dwin = st.pendingWindow[i] != null ? st.pendingWindow[i] : (dt == null ? "" : dt.window());
                drawSelectBar(g, dimTierWindowRect[i][0], dimTierWindowRect[i][1], dimTierWindowRect[i][2],
                        dimTierWindowRect[i][3], Component.literal(dwin.isEmpty() ? "—" : dwin),
                        !dimTierWindowClickable[i]);
                if (st.dirty[i]) {
                    g.drawString(font, Component.translatable("gui.chunkplan.unsaved"), x + 320, ry + 6, COL_RED);
                } else if (dimTierSavedVisible(st, i)) {
                    g.drawString(font, Component.translatable("gui.chunkplan.saved"), x + 320, ry + 6, COL_GRAY);
                }
            }
        }
    }

    /** 该维度当前用量文本「1.05 / 2.00（53%）」：取占比最高的一条线；无数据返回空串 */
    private String dimUsageText(String dim) {
        GuiStatus.DimLines dl = dimLinesOf(dim);
        if (dl == null || dl.lines().isEmpty()) {
            return "";
        }
        QuotaEngine.LineStatus worst = null;
        double worstPct = -1;
        for (QuotaEngine.LineStatus l : dl.lines()) {
            double pct = l.limit() > 0 ? l.spent() / l.limit() * 100.0 : 0;
            if (pct > worstPct) {
                worstPct = pct;
                worst = l;
            }
        }
        if (worst == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%.2f / %.2f (%.0f%%)", worst.spent(), worst.limit(),
                Math.max(0, worstPct));
    }

    /** 该维度用量的颜色分档（与用量页 drawBar/百分比渲染同口径） */
    private int dimUsageColor(String dim) {
        GuiStatus.DimLines dl = dimLinesOf(dim);
        if (dl == null) {
            return COL_GRAY;
        }
        int wp = dl.worstPercent();
        return wp < 50 ? COL_GREEN : (wp < 75 ? COL_YELLOW : COL_RED);
    }

    private GuiStatus.DimLines dimLinesOf(String dim) {
        if (status == null || status.dimLines() == null) {
            return null;
        }
        for (GuiStatus.DimLines d : status.dimLines()) {
            if (d.dim().equals(dim)) {
                return d;
            }
        }
        return null;
    }

    /** 重定向三槽是否全空（全空时按维度字典序兜底，界面据此提示） */
    private boolean redirectOrderAllEmpty() {
        List<String> order = status.dimConfig().redirectOrder();
        for (String s : order) {
            if (s != null) {
                return false;
            }
        }
        return true;
    }

    /** 维度页「已保存」灰字是否仍在显示期（与管理页同机制） */
    private boolean dimTierSavedVisible(DimTierEdit st, int i) {
        return st.savedShown[i]
                && System.currentTimeMillis() - st.savedAtMillis[i] < SAVED_LINGER_MILLIS;
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
            case 5:
                return toggleTierRow < 0 ? List.of() : presets(toggleTierRow + 1);
            case 6:
            case 7:
                return presetNames();
            case 8:
                return resetTierLabels();
            default:
                return dimensionKeys();
        }
    }

    /** 与 {@link #dropdownLabels()} 一一对应的取值（page 3 首项 null = 清空） */
    private List<String> dropdownValues() {
        switch (dimDropdownPage) {
            case 3:
                List<String> values = new ArrayList<>();
                values.add(null);
                values.addAll(redirectSlotCandidates());
                return values;
            case 8:
                // 重置层级：显示名（窗口名/全部）对应档位值 0~4
                return resetTierValues().stream().map(String::valueOf).toList();
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

    private void renderDimDropdown(GuiGraphics g, int mouseX, int mouseY) {
        if (!dimDropdownOpen) {
            return;
        }
        List<String> items = dropdownLabels();
        if (items.isEmpty()) {
            resetDropdownState(); // 渲染路径内不 rebuild（会重入）；下个 tick 自然恢复
            return;
        }
        g.flush(); // 提交此前内容：浮层与页面内容分开成批，不被合批 mod 混排
        // 浮层置顶：整体抬到 z=350——同 z 下后画的填充盖不住先画的文字（本机无第三方 mod 亦复现），
        // 必须靠 z 分层把浮层放到页面内容之上；原版 tooltip 占 z=400，取 350 让 tooltip 仍在最前
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 350.0F);
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
            g.drawString(font, Component.literal(font.plainSubstrByWidth(items.get(i), sW - 12)),
                    sx + 6, rowY + 2, COL_TEXT);
        }
        g.pose().popPose();
        g.flush(); // 提交浮层内容
    }

    /**
     * 「耗尽传送其它维度」开关的悬停说明：随鼠标的手绘浮层。
     * 不用 Button.setTooltip——原版 isMouseOver 带 active 判定，共享模式下该开关灰显就再也看不到说明，
     * 而"为什么点不动"恰恰最需要这段解释。置顶同下拉浮层（旧管线 z=350 / 新管线 nextStratum，坑 #51/#52）。
     */
    private void renderRedirectTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (page != 2 || status == null || status.dimConfig() == null || redirectButton == null
                || !inRect(mouseX, mouseY, redirectButton.getX(), redirectButton.getY(),
                        redirectButton.getWidth(), redirectButton.getHeight())) {
            return;
        }
        List<FormattedCharSequence> lines = font.split(
                Component.translatable("gui.chunkplan.dim.redirect_hint"), 220);
        int w = 232;
        int h = lines.size() * font.lineHeight + 8;
        int bx = Math.max(4, Math.min(mouseX + 12, width - w - 4));
        int by = Math.max(4, Math.min(mouseY + 12, height - h - 4));
        g.flush(); // 提交此前内容：浮层与页面内容分开成批，不被合批 mod 混排
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 350.0F);
        g.fill(bx, by, bx + w, by + h, COL_PANEL);
        g.fill(bx, by, bx + w, by + 1, 0xFFFFFFFF);
        g.fill(bx, by + h - 1, bx + w, by + h, 0xFFFFFFFF);
        g.fill(bx, by, bx + 1, by + h, 0xFFFFFFFF);
        g.fill(bx + w - 1, by, bx + w, by + h, 0xFFFFFFFF);
        int ty = by + 4;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, bx + 6, ty, COL_TEXT);
            ty += font.lineHeight;
        }
        g.pose().popPose();
        g.flush(); // 提交浮层内容
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
            case 6 -> selectedPreset = value; // 条为手绘控件，无需重建即显新值
            case 7 -> assignPreset = value;
            case 8 -> resetTier = value == null ? 0 : Integer.parseInt(value); // 条为手绘控件，无需重建
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
                .map(p -> p.getProfile().getName())
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

    private void renderResetSuggestions(GuiGraphics g, int mouseX, int mouseY) {
        if (page != 1 || resetSuggestions.isEmpty()) {
            return;
        }
        int sx = resetTargetX;
        int sW = Math.max(resetTargetW + 30, 120);
        // 底部多留 2px：末行文字下缘原与下边框零间距（与维度下拉同规则）
        int sH = resetSuggestions.size() * SUGGEST_ROW_H + 2;
        int sy = resetTargetY + resetTargetH + 2;
        g.flush(); // 提交此前内容：浮层与页面内容分开成批，不被合批 mod 混排
        // 浮层置顶：整体抬到 z=350——同 z 下后画的填充盖不住先画的文字（本机无第三方 mod 亦复现），
        // 必须靠 z 分层把浮层放到页面内容之上；原版 tooltip 占 z=400，取 350 让 tooltip 仍在最前
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 350.0F);
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
        g.pose().popPose();
        g.flush(); // 提交浮层内容
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
        // 服务端本批反馈（v5）：非 null 即替换面板内容并重置计时；null 表示本批无反馈，
        // 面板保持当前内容不动（展示到自然超时），否则每次状态回推都会把刚看到的结果抹掉
        GuiStatus.GuiFeedback fb = s.feedback();
        if (fb != null && fb.text() != null && !fb.text().isEmpty()) {
            setFeedback(Component.literal(fb.text()), fb.success());
        }
        // 用量页维度选择跨刷新记忆：仍在该次维度列表里就保留（否则回落当前所在维度）；
        // 仅首次打开界面用 currentDim() 定初值（用户反馈"看完末地一点刷新就跳回主世界"）
        if (s.dimensionMode() == 1) {
            if (!usageDimInitialized || usageDim == null
                    || s.dimensions() == null || !s.dimensions().contains(usageDim)) {
                this.usageDim = s.currentDim();
            }
        } else {
            this.usageDim = null;
        }
        this.usageDimInitialized = true;
        rebuild();
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
        this.pendingBatchDim = null;
        this.pendingSkipConfirm = false;
        this.pendingDiscardIntents = false;
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 1.21.1 的 Screen.render 会先调用 renderBackground（模糊后处理），只能在页面内容之前执行一次；
        // 先显式 renderBackground 再 super.render 会触发第二次模糊，把当前帧刚画的内容模糊进背景
        super.render(g, mouseX, mouseY, partialTick);
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
        refreshGates(); // 脏状态/目标为空等可点性每帧现算（输入编辑不触发重建）
        if (page == 1) {
            refreshResetSuggestions();
            renderResetSuggestions(g, mouseX, mouseY);
        }
        renderDimDropdown(g, mouseX, mouseY);
        renderFeedback(g);
        renderRedirectTooltip(g, mouseX, mouseY);
        renderControlHoverHint(g, mouseX, mouseY);
        // 小窗口提醒：管理页「设置」列与维度页槽位条在 320 宽会出屏。
        // 本轮只做最低成本处理（一行红字），不做横向滚动——布局大改留待后续
        if (width < 340) {
            g.drawString(font, Component.translatable("gui.chunkplan.narrow_window"), 12, height - 30, COL_RED);
        }
        if (pendingConfirm) {
            renderConfirm(g);
        }
    }

    /** 版本不匹配兜底页：协议版本不一致时服务端回版本横幅，只显示两端版本号 */
    private void renderVersionMismatch(GuiGraphics g) {
        int x = 12;
        int y = 40;
        g.drawString(font, Component.translatable("gui.chunkplan.usage.title"), x, y, COL_ACCENT);
        y += 16;
        GuiStatus s = status;
        String server = s != null && s.serverModVersion() != null && !s.serverModVersion().isEmpty()
                ? s.serverModVersion() : "?";
        g.drawString(font, Component.translatable("gui.chunkplan.version.server", server), x, y, COL_TEXT);
        y += 12;
        String client = ChunkPlanClient.clientVersion();
        g.drawString(font, Component.translatable("gui.chunkplan.version.client",
                client == null || client.isEmpty() ? "?" : client), x, y, COL_TEXT);
        y += 14;
        g.drawString(font, Component.translatable("gui.chunkplan.version.mismatch"), x, y, COL_RED);
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
        // v3（issue #3）：维度独立模式顶部维度下拉（真下拉、跨刷新保留选中维度，失效才回落当前所在维度）
        boolean dimMode = s.dimensionMode() == 1;
        GuiStatus.DimLines dl = null;
        if (dimMode) {
            if (usageDim == null) {
                usageDim = s.currentDim(); // 兜底：列表里没记录时跟随当前所在维度
            }
            dl = usageDimLines(s);
            g.drawString(font, Component.translatable("gui.chunkplan.dim.pick_dim"), x, y + 4, COL_TEXT);
            dimDropX = x + 74;
            dimDropY = y;
            dimDropW = Math.min(190, width - dimDropX - 12);
            dimDropH = 16;
            // 源条常显：展开时列表浮层直接盖住下方额度内容（z 上层浮层，不移动下方布局——坑 #51）
            drawSelectBar(g, dimDropX, dimDropY, dimDropW, dimDropH,
                    Component.literal(usageDim == null ? "—"
                            : font.plainSubstrByWidth(usageDim, dimDropW - 20)), false);
            y += 22;
        }
        List<QuotaEngine.LineStatus> lines = dl == null ? s.lines() : dl.lines();
        if (lines.isEmpty()) {
            g.drawString(font, Component.translatable("gui.chunkplan.zero_line"), x, y, COL_GREEN);
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
            if (exceeded) {
                g.drawString(font, Component.translatable("gui.chunkplan.exhausted",
                        ChunkPlanMessages.formatTime(recovery)), x, y, COL_RED);
            } else {
                int wp = worst;
                Component word = wp < 50 ? Component.translatable("gui.chunkplan.adequate")
                        : (wp < 75 ? Component.translatable("gui.chunkplan.moderate")
                        : Component.translatable("gui.chunkplan.low"));
                int wc = wp < 50 ? COL_GREEN : (wp < 75 ? COL_YELLOW : COL_RED);
                g.drawString(font, word, x, y, wc);
            }
        }
        // 按玩家预设覆盖（issue #2）：显示额度规则来源（null = 跟随全局 default，不显示）
        if (s.playerPreset() != null) {
            y += 12;
            g.drawString(font, Component.translatable("gui.chunkplan.preset_current", s.playerPreset()), x, y, COL_ACCENT);
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
        boolean independent = status != null && status.dimensionMode() == 1;
        int gy;
        if (independent) {
            g.drawString(font, Component.translatable("gui.chunkplan.admin_independent_hint"), x, 36, COL_YELLOW);
            gy = 64; // 与 buildAdmin 的费率行位置一一对应（下同）
        } else {
            for (int i = 0; i < 4; i++) {
                int ry = 36 + i * 32;
                g.drawString(font, Component.literal("tier" + (i + 1)), x, ry + 6, COL_TEXT);
                // 每档行三态提示（行下方 2px 处，不与行内控件重叠）：
                // 有未保存更改 → 红「未保存」；刚保存成功 → 灰「已保存」，数秒后自动消失；其余不显示
                if (tierDirty[i]) {
                    g.drawString(font, Component.translatable("gui.chunkplan.unsaved"), x, ry + 22, COL_RED);
                } else if (tierSavedVisible(i)) {
                    g.drawString(font, Component.translatable("gui.chunkplan.saved"), x, ry + 22, COL_GRAY);
                }
                // 窗口下拉条（开关是 Button 自绘）：常显，展开时列表浮层盖住下方行（坑 #51 浮层化）
                QuotaTiers.Tier t = rawTier(i + 1);
                String win = pendingWindow[i] != null ? pendingWindow[i] : (t == null ? "" : t.window());
                drawSelectBar(g, tierWindowRect[i][0], tierWindowRect[i][1], tierWindowRect[i][2],
                        tierWindowRect[i][3], Component.literal(win.isEmpty() ? "—" : win),
                        !tierWindowClickable[i]);
            }
            gy = 36 + 4 * 32 + 6;
            // 分区线：档位额度线区 | 费率与重置区（独立模式下档位区隐藏，此线随之消失）
            g.fill(0, gy - 7, width, gy - 6, COL_DIVIDER);
            g.drawString(font, Component.translatable("gui.chunkplan.all_windows"), x, gy + 6, COL_TEXT);
            gy += 28;
        }
        // 以下标签与 buildAdmin 的行位置一一对应（行 y + 6）
        g.drawString(font, Component.translatable("gui.chunkplan.fee_new"), x, gy + 6, COL_TEXT);
        g.drawString(font, Component.translatable("gui.chunkplan.fee_explored"), x, gy + 34, COL_TEXT);
        g.drawString(font, Component.translatable("gui.chunkplan.speed_mult"), x, gy + 62, COL_TEXT);
        g.drawString(font, Component.translatable("gui.chunkplan.reset_quota"), x, gy + 118, COL_TEXT);
        // 重置层级手绘下拉条（原为循环切换的 Button，用户要求真下拉）
        drawSelectBar(g, resetTierRect[0], resetTierRect[1], resetTierRect[2], resetTierRect[3],
                Component.literal(resetTierLabel()), false);
        // 目标为空：框内灰字提示（与「按玩家名分配」行同做法，不额外占版面）
        if (resetTarget != null && resetTarget.getValue().trim().isEmpty()) {
            g.drawString(font, Component.translatable("gui.chunkplan.reset_target_hint"),
                    resetTarget.getX() + 4, resetTarget.getY() + 6, COL_GRAY);
        }
        // 分区线：费率与重置区 | 预设区（gy + 136 = 重置行底与其下预设行首的中点，两种模式同式）
        g.fill(0, gy + 136, width, gy + 137, COL_DIVIDER);
        // 预设区标签（行 1 选择/应用/删除、行 2 保存、行 3 按玩家分配、行 4 放弃更改）
        g.drawString(font, Component.translatable("gui.chunkplan.preset_title"), x, gy + 146, COL_TEXT);
        g.drawString(font, Component.translatable("gui.chunkplan.preset_save_label"), x, gy + 174, COL_TEXT);
        g.drawString(font, Component.translatable("gui.chunkplan.preset_assign_label"), x, gy + 202, COL_TEXT);
        // 「保存为预设」所见即所存的对照说明（仅档位区可见——独立模式下档位行不显示，界面值无从谈起）：
        // 第一行说明存的是界面值，第二行把全局配置的 12 值摘要摆出来供比对（行 2 右侧，截断防出屏）
        if (!independent) {
            int noteX = x + 240;
            int noteW = Math.max(60, width - noteX - 8);
            g.drawString(font, Component.literal(font.plainSubstrByWidth(
                            Component.translatable("gui.chunkplan.preset_from_ui").getString(), noteW)),
                    noteX, gy + 174, COL_GRAY);
            g.drawString(font, Component.literal(font.plainSubstrByWidth(
                            Component.translatable("gui.chunkplan.preset_global_values", globalTierSummary()).getString(),
                            noteW)), noteX, gy + 186, COL_GRAY);
        }
        // 预设选择条（手绘下拉条：与其余下拉同观感；列表为空时灰显且点不动）
        drawSelectBar(g, presetSelectRect[0], presetSelectRect[1], presetSelectRect[2], presetSelectRect[3],
                Component.literal(selectedPresetName()), presetNames().isEmpty());
        // 「按玩家应用」行：空框灰字提示 + 该行自有的预设选择条（分配用）
        if (presetTarget != null && presetTarget.getValue().isEmpty()) {
            g.drawString(font, Component.translatable("gui.chunkplan.preset_target_hint"),
                    presetTarget.getX() + 4, presetTarget.getY() + 6, COL_GRAY);
        }
        drawSelectBar(g, assignSelectRect[0], assignSelectRect[1], assignSelectRect[2], assignSelectRect[3],
                Component.literal(assignPresetLabel()), presetNames().isEmpty());
        g.drawString(font, Component.translatable("gui.chunkplan.reset_hint"), x + 296, gy + 124, COL_GRAY);
        // 脏状态门禁说明：有未保存档位更改时，预设四个动作置灰（原因可悬停查看，见 renderControlHoverHint）；
        // 放在新增的「放弃未保存更改」行（gy + 224）右侧，不压任何控件
        if (tiersDirty()) {
            g.drawString(font, Component.translatable("gui.chunkplan.preset_gate_hint"), x + 240, gy + 230, COL_YELLOW);
        }
    }

    /**
     * 灰显控件的悬停说明：仓库既有做法（font.split 自动换行 + 面板 + z=350 浮层）。
     * 不用 Button.setTooltip——原版 isMouseOver 带 active 判定，灰显的按钮反而看不到说明，
     * 而"为什么点不动"恰恰最需要这段解释（与 renderRedirectTooltip 同因）。
     */
    private void renderControlHoverHint(GuiGraphics g, int mouseX, int mouseY) {
        Component hint = null;
        if (page == 1 && tiersDirty()) {
            // 预设三动作置灰原因（setTooltip 在灰显按钮上不触发，故手绘）；「保存为预设」不受门禁
            if (hoverButton(presetApplyBtn, mouseX, mouseY) || hoverButton(presetDeleteBtn, mouseX, mouseY)
                    || hoverButton(presetAssignBtn, mouseX, mouseY)) {
                hint = Component.translatable("gui.chunkplan.preset_gate_reason");
            }
        } else if (page == 2 && status != null && status.dimConfig() != null) {
            if (hoverButton(dimModeButton, mouseX, mouseY)) {
                hint = Component.translatable("gui.chunkplan.dim.mode_hint");
            } else {
                // 槽位滞空：前置槽未填 → 该槽灰着点不动（重定向开关的说明见 renderRedirectTooltip）
                List<String> order = status.dimConfig().redirectOrder();
                for (int s = 1; s < 3; s++) {
                    if (inRect(mouseX, mouseY, slotBarRect[s])
                            && (s >= order.size() || order.get(s - 1) == null)) {
                        hint = Component.translatable("gui.chunkplan.dim.slot_gap_hint");
                        break;
                    }
                }
            }
        }
        if (hint == null) {
            return;
        }
        List<FormattedCharSequence> lines = font.split(hint, 220);
        int w = 232;
        int h = lines.size() * font.lineHeight + 8;
        int bx = Math.max(4, Math.min(mouseX + 12, width - w - 4));
        int by = Math.max(4, Math.min(mouseY + 12, height - h - 4));
        g.flush(); // 提交此前内容：浮层与页面内容分开成批，不被合批 mod 混排
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 350.0F);
        g.fill(bx, by, bx + w, by + h, COL_PANEL);
        g.fill(bx, by, bx + w, by + 1, 0xFFFFFFFF);
        g.fill(bx, by + h - 1, bx + w, by + h, 0xFFFFFFFF);
        g.fill(bx, by, bx + 1, by + h, 0xFFFFFFFF);
        g.fill(bx + w - 1, by, bx + w, by + h, 0xFFFFFFFF);
        int ty = by + 4;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, bx + 6, ty, COL_TEXT);
            ty += font.lineHeight;
        }
        g.pose().popPose();
        g.flush(); // 提交浮层内容
    }

    private boolean hoverButton(Button b, double mouseX, double mouseY) {
        return b != null && inRect(mouseX, mouseY, b.getX(), b.getY(), b.getWidth(), b.getHeight());
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
        // 两个按钮等宽等样式（同款描边与命中区，仅填充色保留语义差异：确认绿 / 取消灰）——
        // 原先行直接铺纯灰块且无描边，观感上像被禁用
        int btnY = by + h - 24;
        noX = bx + w - 118;
        noY = btnY;
        noW = 52;
        noH = 18;
        yesX = bx + w - 60;
        yesY = btnY;
        yesW = 52;
        yesH = 18;
        drawDialogButton(g, noX, noY, noW, noH, Component.translatable("gui.chunkplan.confirm.cancel"), 0xFF555555);
        drawDialogButton(g, yesX, yesY, yesW, yesH, Component.translatable("gui.chunkplan.confirm.yes"), 0xFF2E7D32);
    }

    /** 确认弹窗按钮：同款描边 + 居中文字（fill 色区分确认/取消） */
    private void drawDialogButton(GuiGraphics g, int x, int y, int w, int h, Component label, int fillColor) {
        g.fill(x, y, x + w, y + h, fillColor);
        g.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        g.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        g.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
        g.drawCenteredString(font, label, x + w / 2, y + 5, COL_TEXT);
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
        // 反馈面板最早判定：点它即消失并吞掉本次点击（其余命中判定不受影响）
        if (clickFeedbackPanel(mouseX, mouseY)) {
            return true;
        }
        if (pendingConfirm) {
            if (inRect(mouseX, mouseY, yesX, yesY, yesW, yesH)) {
                if (pendingBatch != null) {
                    // 批量命令需确认：先派发（服务端据此注册待确认动作），再补 /chunkplan confirm 执行
                    pendingBatch.forEach(this::sendCommand);
                    reconcileSuppressUntilMillis = System.currentTimeMillis() + RECONCILE_SUPPRESS_MILLIS;
                    if (!pendingSkipConfirm) {
                        sendCommand("chunkplan confirm");
                    }
                    if (pendingBatchTier > 0) {
                        if (pendingBatchDim != null) {
                            DimTierEdit st = dimEdits.get(pendingBatchDim); // 维度页档位编辑器发起
                            if (st != null) {
                                st.dirty[pendingBatchTier - 1] = false;
                                st.savedShown[pendingBatchTier - 1] = true;
                                st.savedAtMillis[pendingBatchTier - 1] = System.currentTimeMillis();
                            }
                        } else {
                            markSaved(pendingBatchTier);
                        }
                    }
                    pendingBatch = null;
                    pendingBatchTier = 0;
                    pendingBatchDim = null;
                    pendingSkipConfirm = false;
                    if (pendingDiscardIntents) {
                        // 「全部关闭」确认：整批档位动作落地，本地待应用意图一并作废（F6 坏路径）
                        pendingDiscardIntents = false;
                        discardAllTierIntents();
                        rebuild();
                    }
                } else {
                    sendCommand("chunkplan confirm");
                }
                pendingConfirm = false;
                return true;
            }
            if (inRect(mouseX, mouseY, noX, noY, noW, noH)) {
                pendingConfirm = false;
                pendingBatch = null;
                pendingBatchDim = null;
                pendingSkipConfirm = false;
                pendingDiscardIntents = false; // 取消确认：保留管理员的未保存更改（不代为作废）
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
                if (inRect(mouseX, mouseY, dropSrcX, sy + i * SUGGEST_ROW_H,
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
                && inRect(mouseX, mouseY, dimDropX, dimDropY, dimDropW, dimDropH)) {
            openDimDropdown(0, dimDropX, dimDropY, dimDropW, dimDropH);
            return true;
        }
        // 维度页：重定向槽位 / 维度选择条 / 档位窗口（开关是 Button 自管点击）
        if (page == 2 && status != null && status.dimConfig() != null) {
            for (int s = 0; s < 3; s++) {
                if (inRect(mouseX, mouseY, slotBarRect[s])) {
                    openRedirectSlot(s);
                    return true;
                }
            }
            if (inRect(mouseX, mouseY, dimSelectRect)) {
                openDimDropdown(2, dimSelectRect);
                return true;
            }
            for (int i = 0; i < 4; i++) {
                if (dimTierWindowClickable[i] && inRect(mouseX, mouseY, dimTierWindowRect[i])) {
                    cycleDimWindow(selectedDim, i + 1);
                    return true;
                }
            }
        }
        // 管理页：档位窗口（开关是 Button 自管点击）
        if (page == 1 && isAdmin() && status != null && status.dimensionMode() != 1) {
            for (int i = 0; i < 4; i++) {
                if (tierWindowClickable[i] && inRect(mouseX, mouseY, tierWindowRect[i])) {
                    cycleWindow(i + 1);
                    return true;
                }
            }
        }
        // 管理页：重置层级手绘下拉条
        if (page == 1 && isAdmin() && inRect(mouseX, mouseY, resetTierRect)) {
            openResetTierDropdown();
            return true;
        }
        // 管理页：预设选择条 /「按玩家应用」预设选择条（手绘下拉条，展开真下拉）
        if (page == 1 && isAdmin() && inRect(mouseX, mouseY, presetSelectRect)) {
            openPresetDropdown();
            return true;
        }
        if (page == 1 && isAdmin() && inRect(mouseX, mouseY, assignSelectRect)) {
            openAssignDropdown();
            return true;
        }
        if (!resetSuggestions.isEmpty()) {
            int paneY = resetTargetY + resetTargetH + 2;
            for (int i = 0; i < resetSuggestions.size(); i++) {
                int h = SUGGEST_ROW_H + (i == resetSuggestions.size() - 1 ? 2 : 0); // 末行含底部补白
                if (inRect(mouseX, mouseY, resetTargetX, paneY + i * SUGGEST_ROW_H,
                        Math.max(resetTargetW + 30, 120), h)) {
                    acceptResetSuggestion(i);
                    return true;
                }
            }
            // 点击建议列表之外的区域：失焦隐藏建议（点击输入框内不处理，交还 super 聚焦）
            if (suggestOwner != null
                    && !inRect(mouseX, mouseY, suggestOwner.getX(), suggestOwner.getY(),
                            suggestOwner.getWidth(), suggestOwner.getHeight())) {
                suggestOwner.setFocused(false);
                resetSuggestions = List.of();
                suggestOwner = null;
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
            pendingBatchDim = null;
            pendingSkipConfirm = false;
            pendingDiscardIntents = false;
            return true;
        }
        if (dimDropdownOpen) {
            List<String> items = dropdownLabels();
            int sy = dropSrcY + dropSrcH + 2;
            int maxRows = Math.max(1, (height - sy - 6) / SUGGEST_ROW_H);
            int rows = Math.min(items.size(), maxRows);
            switch (keyCode) {
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
                    suggestOwner = null;
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

    // ---------- 界面内反馈面板（反馈不只进聊天） ----------

    /**
     * 设置反馈面板内容：服务端 feedback 回包（{@link GuiStatus.GuiFeedback}）与本地校验失败共用一条。
     * 只保留最近一条：服务端每次命令只回一条反馈，本地校验失败也一次只报一项。
     */
    private void setFeedback(Component text, boolean success) {
        this.feedbackText = text;
        this.feedbackSuccess = success;
        this.feedbackAtMillis = System.currentTimeMillis();
    }

    /** 面板是否仍在显示期（超过存活时长即不画，无需额外清理） */
    private boolean feedbackVisible() {
        return feedbackText != null && System.currentTimeMillis() - feedbackAtMillis < FEEDBACK_LINGER_MILLIS;
    }

    /**
     * 面板文字颜色：成功绿、失败红；末尾淡出期按剩余时间线性衰减 alpha（只做淡出，不做渐入动画）。
     * 淡出用 alpha 而非直接消失，是为了让"刚发生过的操作结果"不显得突兀。
     */
    private int feedbackColor() {
        long age = System.currentTimeMillis() - feedbackAtMillis;
        long fadeStart = FEEDBACK_LINGER_MILLIS - FEEDBACK_FADE_MILLIS;
        long remain = FEEDBACK_LINGER_MILLIS - age;
        int alpha = age <= fadeStart ? 255 : (int) Math.max(0, 255L * remain / FEEDBACK_FADE_MILLIS);
        int rgb = (feedbackSuccess ? COL_GREEN : COL_RED) & 0xFFFFFF;
        return (alpha << 24) | rgb;
    }

    /**
     * 反馈面板：管理页与维度页底部一行（用量页为只读展示，不占位）。
     * 置于 z=350 浮层（与下拉/tooltip 同惯例）：同 z 下后画的填充盖不住先画的文字，面板必须抬层才干净。
     * 确认弹窗期间不画——浮层比弹窗的 z 高，会透在模态之上。
     */
    private void renderFeedback(GuiGraphics g) {
        if (page != 1 && page != 2) {
            return;
        }
        if (!feedbackVisible() || pendingConfirm) {
            return;
        }
        int x = 12;
        int h = 14;
        int y = height - (h + 6);
        int w = Math.max(80, width - 24);
        fbX = x;
        fbY = y;
        fbW = w;
        fbH = h;
        g.flush(); // 提交此前内容：浮层与页面内容分开成批，不被合批 mod 混排
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 350.0F);
        g.fill(x, y, x + w, y + h, COL_PANEL);
        g.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        g.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        g.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
        String shown = font.plainSubstrByWidth(feedbackText.getString(), w - 10);
        g.drawString(font, Component.literal(shown), x + 5, y + 3, feedbackColor());
        g.pose().popPose();
        g.flush(); // 提交浮层内容
    }

    /** 点击反馈面板：立即消失（面板自身不承载任何动作；确认弹窗期间面板不显示也不拦截点击） */
    private boolean clickFeedbackPanel(double mouseX, double mouseY) {
        if (pendingConfirm || !feedbackVisible() || !inRect(mouseX, mouseY, fbX, fbY, fbW, fbH)) {
            return false;
        }
        feedbackText = null;
        return true;
    }

    // ---------- 工具 ----------

    /** 中文判定（仅用于 ChunkPlanMessages.windowName 的窗口名本地化） */
    private boolean zh() {
        return ChunkPlanMessages.isChinese(Minecraft.getInstance().getLanguageManager().getSelected());
    }
}
