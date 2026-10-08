package com.villagerdetails.client.setting.config;

/**
 * 设置界面的<b>页面级布局配置</b>：所有决定「哪一块画在哪、多大、间距多少」的全局变量都集中在这里。
 *
 * <p>只放布局数字（坐标偏移、宽高、间距、滚动参数）：颜色在各组件里，
 * 需要落盘的玩家配置在 {@link ClientRenderConfig}，组件<b>内部</b>的布局在 {@link ComponentLayout}。
 *
 * <pre>
 * y=0 ┌────────────────────── 顶栏 HEADER_HEIGHT = 33 ───────────────────────┐
 *     │                        标题（居中，y = ComponentLayout.TITLE_Y）      │
 *     ├────────────────┬─────────────────────────────────────────────────────┤
 *     │ 搜索框          │  二级分类那一行：CONTENT_TOP = 43                     │
 *     │ SIDEBAR_X=16    │  ─── 分隔线：CONTENT_TOP + HEADER_LINE_OFFSET ───    │
 *     │ SIDEBAR_TOP=43  │  第一行配置项：CONTENT_TOP + FIRST_ROW_OFFSET        │
 *     │ SIDEBAR_WIDTH   │  行高 ROW_HEIGHT，行内控件高 WIDGET_HEIGHT            │
 *     └────────────────┴─────────────────────────────────────────────────────┘
 *     │            底部按钮：footerButtonY(height) = height - 27              │
 *     └──────────────────── 底栏 FOOTER_HEIGHT = 33 ──────────────────────────┘
 * </pre>
 */
public final class UiLayout {

    private UiLayout() {
    }

    // ==============================================================
    // 原版顶栏 / 底栏
    // ==============================================================

    /** 原版 HeaderAndFooterLayout 的顶栏高度：标题区有多高，也是左栏 / 内容区起始 y 的基准 */
    public static final int HEADER_HEIGHT = 33;
    /** 原版底栏高度：底部那条分隔条画在「界面底边往上这么高」的位置 */
    public static final int FOOTER_HEIGHT = 33;

    // ==============================================================
    // 左侧一级分类栏（含搜索框）
    // ==============================================================

    /** 左栏左边界：离屏幕左边多少像素 */
    public static final int SIDEBAR_X = 16;
    /** 左栏第一个控件（搜索框）的上边界：顶栏下面留 10px */
    public static final int SIDEBAR_TOP = HEADER_HEIGHT + 10;
    /** 左栏宽度：搜索框和每一个一级分类按钮都是这个宽度 */
    public static final int SIDEBAR_WIDTH = 100;
    /** 左栏一级分类按钮的行距（按钮高 WIDGET_HEIGHT + 4px 空隙） */
    public static final int SIDEBAR_ENTRY_HEIGHT = 24;

    // ==============================================================
    // 通用控件高度
    // ==============================================================

    /** 所有行内控件（按钮、搜索框、输入框、滑条、色块行）统一的高度 */
    public static final int WIDGET_HEIGHT = 20;

    // ==============================================================
    // 右上角二级分类（一级分类为「配置项」时的那排页签）
    // ==============================================================

    /** 二级分类按钮之间的水平间距 */
    public static final int SUBTAB_GAP = 4;
    /** 二级分类一行最多显示几个，超过就出现 ◀ ▶ 翻页按钮（也可以滚轮翻） */
    public static final int SUBTAB_MAX_VISIBLE = 5;
    /** 二级分类按钮的最小宽度（分类名很短时也不至于挤成一条） */
    public static final int SUBTAB_MIN_WIDTH = 40;
    /** 二级分类末尾 ◀ ▶ 翻页按钮的宽度 */
    public static final int SUBTAB_ARROW_WIDTH = 18;

    // ==============================================================
    // 右侧内容区
    // ==============================================================

    /** 左栏右边界到内容区左边界之间的距离（两栏之间的空隙） */
    public static final int CONTENT_GAP = 16;
    /** 内容区上边界：二级分类那一行的上边（和左栏搜索框同一水平线） */
    public static final int CONTENT_TOP = HEADER_HEIGHT + 10;
    /** 二级分类按钮行往下多少像素画那条分隔线 */
    public static final int HEADER_LINE_OFFSET = 26;
    /** 分隔线再往下多少像素是第一行配置项 */
    public static final int FIRST_ROW_OFFSET = 36;
    /** 每一行配置项占的高度 */
    public static final int ROW_HEIGHT = 26;
    /** 内容区右边距：右边界离屏幕右边多少像素 */
    public static final int MARGIN = 16;

    // ==============================================================
    // 一行里的各个控件（同一行从右往左依次排：重置 → 快捷键 → 开关 → 值 → 标签）
    // ==============================================================

    /** 行尾「重置」按钮的宽度 */
    public static final int RESET_WIDTH = 40;
    /** 行内快捷键绑定框的宽度 */
    public static final int KEYBIND_WIDTH = 100;
    /** 行内「是 / 否」开关按钮的宽度 */
    public static final int SWITCH_WIDTH = 50;
    /** 滑块行那个齿轮（切换滑条 / 手输数值）按钮的边长，正方形 */
    public static final int GEAR_SIZE = 20;
    /** 行内「值控件」的宽度：数值滑条、颜色值按钮、列表占位按钮都用它 */
    public static final int VALUE_WIDTH = 110;
    /** 行内颜色预览色块的宽度 */
    public static final int SWATCH_WIDTH = 12;
    /** 行内颜色预览色块的高度 */
    public static final int SWATCH_HEIGHT = 12;
    /** 行内色块相对行顶的垂直偏移：把 SWATCH_HEIGHT 的色块在 WIDGET_HEIGHT 的行里居中 */
    public static final int SWATCH_Y_OFFSET = (WIDGET_HEIGHT - SWATCH_HEIGHT) / 2;
    /** 同一行里相邻控件之间的间距（重置 / 快捷键 / 开关 / 值控件之间） */
    public static final int WIDGET_GAP = 4;

    // ==============================================================
    // 内容行的滚动 / 裁剪
    // ==============================================================

    /** 内容行区域底部与底部按钮之间的间距 */
    public static final int ROW_SCROLL_MARGIN = 6;
    /** 滚轮滚一格等于滚几行 */
    public static final int ROW_SCROLL_STEP = 3;
    /**
     * 平滑滚动的快慢：<b>每 tick</b> 朝目标位置逼近的比例（越大越跟手，0.5 大约 0.2 秒到位）。
     *
     * <p>实际按帧推进时会折算成帧率无关的形式 {@code 1-(1-EASE)^dt}，所以 20fps 和 240fps 看到的
     * 滚动时长是一样的，不会因为帧率高就变快。
     */
    public static final float ROW_SCROLL_EASE = 0.5F;
    /** 平滑滚动的收敛阈值：与目标的差值小于它就吸附过去（单位：行） */
    public static final float ROW_SCROLL_EPSILON = 0.02F;
    /** 单帧最多按多少个 tick 折算滚动动画（防止偶发大卡顿后的超长帧间隔让滚动直接瞬移） */
    public static final float ROW_SCROLL_MAX_FRAME_TICKS = 2.0F;
    /** 相邻两行控件之间的竖向空隙（行高 − 控件高）：行画布上下各留这么多，好在滚动时露出半个项 */
    public static final int ROW_GAP_Y = ROW_HEIGHT - WIDGET_HEIGHT;

    // ==============================================================
    // 内容行的分页上限 / 右侧滚动条
    // ==============================================================

    /** 一页最多显示几行配置项（服务器规则也是 8 条），超出的靠滚动 / 右侧滚动条查看 */
    public static final int MAX_ROWS_PER_PAGE = 8;
    /** 右侧滚动条的宽度（和原版 {@code widget/scroller} 精灵的宽度一致） */
    public static final int SCROLLBAR_WIDTH = 6;
    /** 滚动条与行内控件之间的间距：显示滚动条时，行控件整体左移「滚动条宽 + 这个间距」 */
    public static final int SCROLLBAR_GAP = 6;

    // ==============================================================
    // 底部按钮（左：全局重置，右：完成）
    // ==============================================================

    /** 底部按钮距离界面底边的距离 */
    public static final int FOOTER_BUTTON_Y_FROM_BOTTOM = 27;
    /** 底部按钮的宽度 */
    public static final int FOOTER_BUTTON_WIDTH = 120;
    /** 底部两个按钮之间的间距（以屏幕中线为界，左右各一半） */
    public static final int FOOTER_BUTTON_GAP = 20;

    /** 底部按钮的 y（离屏幕底边 FOOTER_BUTTON_Y_FROM_BOTTOM） */
    public static int footerButtonY(int screenHeight) {
        return screenHeight - FOOTER_BUTTON_Y_FROM_BOTTOM;
    }

    /** 底部左边那个按钮（全局重置）的 x */
    public static int footerLeftButtonX(int screenWidth) {
        return screenWidth / 2 - FOOTER_BUTTON_GAP / 2 - FOOTER_BUTTON_WIDTH;
    }

    /** 底部右边那个按钮（完成）的 x */
    public static int footerRightButtonX(int screenWidth) {
        return screenWidth / 2 + FOOTER_BUTTON_GAP / 2;
    }

    // ==============================================================
    // 半透明面板（左栏底色、内容区底色）
    // ==============================================================

    /** 面板比里面的内容外扩多少像素 */
    public static final int PANEL_PADDING = 6;
    /** 面板右边界至少离屏幕右边多少像素 */
    public static final int PANEL_RIGHT_MARGIN = 4;

    // ==============================================================
    // 颜色项详情页（RGB 滑条那一页）
    // ==============================================================

    /** 详情页说明文字的上边界 */
    public static final int DETAIL_DESC_TOP = 56;
    /** 详情页第一条（红）滑条的上边界 */
    public static final int DETAIL_SLIDER_TOP = 96;
    /** 详情页相邻两条滑条之间的行距 */
    public static final int DETAIL_SLIDER_STEP = 24;
    /** 详情页预览色块的宽度（高度用 WIDGET_HEIGHT） */
    public static final int DETAIL_SWATCH_WIDTH = 40;
    /** 详情页预览色块与右边色值输入框之间的间距 */
    public static final int DETAIL_VALUE_GAP = 8;
    /** 详情页内容区的最大宽度 */
    public static final int DETAIL_CONTENT_MAX_WIDTH = 260;
    /** 详情页内容区左右至少留出的边距（窗口很窄时用得上） */
    public static final int DETAIL_CONTENT_SIDE_MARGIN = 20;
    /** 详情页「色块 + 色值框」这一行与下面按钮之间的间距 */
    public static final int DETAIL_ROW_GAP = 16;
    /** 详情页按钮组与上一行之间的间距 */
    public static final int DETAIL_BUTTON_GAP = 12;
    /** 详情页按钮距离界面底边的最小间距 */
    public static final int DETAIL_BUTTON_BOTTOM_MARGIN = 30;

    // ==============================================================
    // 列表项详情页（可以滚动的值列表）
    // ==============================================================

    /** 列表页里列表区域的上边界（和颜色页第一条滑条同一水平线） */
    public static final int DETAIL_LIST_TOP = 96;
    /** 列表页里每个值右边那个「×」删除按钮的宽度（正方形，高度用 WIDGET_HEIGHT） */
    public static final int DETAIL_DELETE_WIDTH = 20;
    /** 列表页的补全弹窗最多显示几条候选 */
    public static final int LIST_MAX_SUGGESTIONS = 8;

    // ==============================================================
    // 「服务器配置项」的键值列表页（id → 值）
    // ==============================================================

    /** 键值列表页：内容区最大宽度（比普通详情页宽一点，一行要放 id + 值 + 删除） */
    public static final int RULE_DATA_CONTENT_MAX_WIDTH = 320;
    /** 键值列表页：列表区域的上边界 */
    public static final int RULE_DATA_LIST_TOP = 96;
    /** 键值列表页：值控件（数字框 / 是-否按钮）的宽度 */
    public static final int RULE_DATA_VALUE_WIDTH = 60;
}
