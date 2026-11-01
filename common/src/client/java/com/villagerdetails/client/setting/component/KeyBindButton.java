package com.villagerdetails.client.setting.component;

import com.mojang.blaze3d.platform.InputConstants;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.input.KeyBindConflicts;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 快捷键绑定文本框（支持组合键）：<b>「依次按下暂存、再点空白处确认」</b>的编辑模式。
 *
 * <p>为什么要有确认这一步：以前是「按下键 → 立刻写配置并退出编辑」，手一抖按错就只能重来，
 * 而且从按下到保存之间任何一次界面重建都会让显示跳回旧值（看起来像「刚按完又变回请绑定」）。
 *
 * <p>现在的流程：
 * <ol>
 *     <li>鼠标左键点一下按钮 → 进入编辑（监听）状态，显示「按下按键…」；</li>
 *     <li>按键是<b>依次按</b>的，不需要同时按住：每按下一个键就 toggle 一次暂存列表
 *         {@link #pendingKeys} —— <b>按的是新键就追加到末尾，按的是已经暂存的键就把它去掉</b>，
 *         最多 {@link #MAX_BIND_KEYS} 个。所以「按 K，松开，再按 L」就是 {@code K + L}，
 *         而「按 K，再按 K」就把它撤销掉；</li>
 *     <li>点击界面其它空白处（失焦）→ <b>确认</b>：暂存值写回配置并刷新冲突 / 重置按钮
 *         （把键都 toggle 掉了的话，确认的结果就是「未绑定」）；</li>
 *     <li>按 <b>ESC</b> → <b>直接清空绑定</b>（设为未绑定）并立刻保存。</li>
 * </ol>
 *
 * <p><b>视觉上只靠按钮文字体现状态</b>：编辑中显示「按下按键…」或暂存值，其它状态显示已生效的绑定
 * 或「未绑定」。这里<b>不画任何额外的描边 / 高亮</b>，并且刻意不让按钮在点击后抢焦点
 * （{@link #shouldTakeFocusAfterInteraction()} 返回 false）——否则原版会给聚焦的按钮画上
 * {@code hovered_or_focused} 那一张精灵，看起来就像多了一圈框。
 *
 * <p>绑定完成后会和原版及其它模组已注册的按键比对，冲突时文字变色并在悬停提示里说明来源。
 * 外观沿用原版按钮（原版精灵），材质包可替换。
 */
public class KeyBindButton extends Button.Plain {

    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    /**
     * 部分冲突（别人的组合包含我的）
     */
    private static final int COLOR_PARTIAL = 0xFFFFFF55;
    /**
     * 完全重复
     */
    private static final int COLOR_EXACT = 0xFFFF5555;

    /**
     * 一个绑定最多允许几个键（依次按下累加，超出的按键被忽略）
     */
    public static final int MAX_BIND_KEYS = 3;

    private final ConfigEntryType entry;
    private final Consumer<int[]> onBound;

    /**
     * 已经生效的绑定（= 配置里当前的值）
     */
    private int[] keys;

    /**
     * 当前暂存、还没确认的按键（按按下的顺序）。
     *
     * <p>{@code null} = 这次编辑还没按过任何键；
     * <b>空数组</b> = 按过键又全部 toggle 掉了（确认后等于「未绑定」）。
     */
    private int[] pendingKeys;

    private boolean listening;

    private KeyBindConflicts.Result conflict = new KeyBindConflicts.Result(KeyBindConflicts.Level.NONE, List.of());

    /**
     * 只给 {@link #create} 用。
     *
     * <p>初始状态（冲突检查 + 按钮文字）故意<b>不</b>在这里刷：刷新过程中会调
     * {@code setTooltip}，那是可被子类覆盖的方法，在构造里调会让 {@code this} 提前逸出
     * （javac 的 {@code this-escape} 告警）。
     */
    private KeyBindButton(int x, int y, int width, int height, ConfigEntryType entry, Consumer<int[]> onBound) {
        super(x, y, width, height, Component.empty(),
                button -> ((KeyBindButton) button).toggleListening(),
                DEFAULT_NARRATION);
        this.entry = entry;
        this.onBound = onBound;
        this.keys = entry.getKeyBind();
    }

    /**
     * 造一个绑定按钮：构造完成之后再刷初始状态
     */
    public static KeyBindButton create(int x, int y, int width, int height,
                                       ConfigEntryType entry, Consumer<int[]> onBound) {
        KeyBindButton button = new KeyBindButton(x, y, width, height, entry, onBound);
        button.refreshConflict();
        button.refreshMessage();
        return button;
    }

    // ==============================================================
    // 状态查询（界面用）
    // ==============================================================

    public boolean isListening() {
        return listening;
    }

    /**
     * 这一行对应的配置项注册名（界面按它把「正在编辑」的状态传给重建后的新按钮）
     */
    public String entryName() {
        return entry.getRegisterName();
    }

    /**
     * 当前暂存的按键；没按过就是 {@code null}
     */
    public int[] pendingKeys() {
        return pendingKeys == null ? null : pendingKeys.clone();
    }

    // ==============================================================
    // 编辑状态切换
    // ==============================================================

    /**
     * 点击按钮：进入编辑 / 再次点击 = 退出编辑（不保存，保持原有绑定）
     */
    private void toggleListening() {
        if (listening) {
            cancelListening();
            return;
        }
        startListening();
    }

    /**
     * 点击按钮时不抢焦点。
     *
     * <p>原版按钮的精灵是 {@code isHoveredOrFocused()} 二选一的：一聚焦就会画成「悬停」那一张，
     * 看起来就像监听状态多了一圈高亮框。这里放弃焦点，编辑状态只靠按钮文字表达。
     * （代价是字符输入不再由焦点控件拦住，所以 {@code BaseConfigScreen.charTyped} 在监听时会直接吃掉。）
     */
    @Override
    public boolean shouldTakeFocusAfterInteraction() {
        return false;
    }

    /**
     * 进入编辑状态：清空暂存
     */
    private void startListening() {
        this.pendingKeys = null;
        this.listening = true;
        refreshMessage();
        refreshTooltip();
    }

    /**
     * <b>退出</b>编辑但不改绑定（再次点击这一格）：丢掉暂存值，恢复显示已生效的绑定。
     */
    public void cancelListening() {
        if (!listening) {
            return;
        }
        this.listening = false;
        this.pendingKeys = null;
        refreshMessage();
        refreshConflict();
    }

    /**
     * <b>确认</b>编辑（点击界面空白处 = 失焦）：
     * 有暂存值就写回配置并通知界面保存 / 刷新；没按过任何键就只是退出编辑（保持原绑定）。
     *
     * <p>暂存值是<b>空数组</b>时同样算「有暂存」—— 那是「清空绑定」的意思（ESC 走的就是这条路）。
     */
    public void confirmListening() {
        if (!listening) {
            return;
        }
        int[] staged = pendingKeys;
        this.listening = false;
        this.pendingKeys = null;
        if (staged != null) {
            this.keys = staged.clone();
            if (onBound != null) {
                onBound.accept(this.keys);
            }
        }
        refreshMessage();
        refreshConflict();
    }

    /**
     * 界面重建后把「正在编辑」的状态接回来（重建会换掉按钮实例）。
     *
     * @param pending 重建前暂存的按键；{@code null} 表示那会儿还没按键
     */
    public void restoreListening(int[] pending) {
        if (listening) {
            return;
        }
        this.pendingKeys = pending == null ? null : pending.clone();
        this.listening = true;
        refreshMessage();
        refreshTooltip();
    }

    // ==============================================================
    // 冲突 / 提示
    // ==============================================================

    /**
     * 重新检查冲突并更新文字颜色 + 悬停提示。
     *
     * <p>什么时候要调：本行绑定完成时（冲突结果变了），以及<b>别的行</b>绑定完成时 ——
     * 冲突是相互的，A 刚绑成 {@code X+C}，B 原来那行 {@code X+C} 的红字/提示也得跟着变。
     * 界面用 {@code refreshKeyBindConflicts()} 统一刷一遍。
     *
     * <p>注意它<b>不碰按钮文字</b>：编辑中的暂存文字不会被外部刷新覆盖掉。
     */
    public final void refreshConflict() {
        this.conflict = KeyBindConflicts.check(entry);
        refreshTooltip();
    }

    /**
     * 编辑中提示操作方法（暂存为空时显示通用操作说明，有暂存时显示「已暂存 xxx」）；平时显示冲突来源
     */
    private void refreshTooltip() {
        if (listening) {
            boolean staged = pendingKeys != null && pendingKeys.length > 0;
            setTooltip(Tooltip.create(staged
                    ? Component.translatable("villagerdetails.ui.keybind.pending.hint",
                            ConfigEntryType.displayKeys(pendingKeys))
                    : Component.translatable("villagerdetails.ui.keybind.listening.hint")));
        } else {
            setTooltip(conflict.hasConflict() ? Tooltip.create(conflict.tooltip()) : null);
        }
    }

    /**
     * 刷新按钮文字：编辑中显示暂存值（没按过就显示「按下按键…」），否则显示已生效的绑定。
     *
     * <p>两种「空」要分开：
     * <ul>
     *     <li>{@code pendingKeys == null}：这次编辑还没按过键 → 显示「按下按键…」；</li>
     *     <li>{@code pendingKeys} 是<b>空数组</b>：按过又全都 toggle 掉了 → 走
     *         {@link ConfigEntryType#displayKeys} 显示「未绑定」，等于<b>预览</b>一下确认后的结果。</li>
     * </ul>
     * 非编辑状态下绑定为空（{@code keys.length == 0}）同样显示「未绑定」（ESC 清空后就是这一档）。
     */
    private void refreshMessage() {
        if (listening) {
            setMessage(pendingKeys == null
                    ? Component.translatable("villagerdetails.ui.keybind.listening")
                    : ConfigEntryType.displayKeys(pendingKeys));
        } else {
            setMessage(ConfigEntryType.displayKeys(keys));
        }
    }

    // ==============================================================
    // 按键
    // ==============================================================

    /**
     * 编辑中按下键：<b>只暂存、不保存</b>，并且<b>继续留在编辑状态</b>。
     *
     * <p>按键是<b>依次按</b>的（不需要同时按住）：每按一个键就 toggle 一次暂存列表 ——
     * 新键追加到末尾、已经暂存的键则被移除，最多 {@link #MAX_BIND_KEYS} 个。
     * 所以「按 K、松开、再按 L」得到 {@code K + L}，「按 K 再按 K」就把它撤销。
     *
     * <p><b>ESC = 直接清空绑定</b>（设成未绑定）并立刻保存，这是玩家在按键界面里最习惯的语义。
     */
    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        if (!listening) {
            return super.keyPressed(event);
        }

        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            // ESC = 清空这个绑定：把「空绑定」当成一次暂存，然后立刻确认保存
            this.keys = ClientRenderConfig.NO_KEYS;
            this.pendingKeys = ClientRenderConfig.NO_KEYS;
            confirmListening();
            return true;
        }

        togglePendingKey(key);
        refreshMessage();
        refreshTooltip();
        return true;
    }

    /**
     * 把一个键 toggle 进 / 出暂存列表。
     *
     * @param key 这次按下的键（GLFW keycode）
     */
    private void togglePendingKey(int key) {
        List<Integer> staged = new ArrayList<>(pendingKeys == null ? List.of() : toList(pendingKeys));
        int existing = staged.indexOf(key);
        if (existing >= 0) {
            // 已经暂存过 → 再按一次就是撤销（按错键时不用退出编辑重来）
            staged.remove(existing);
        } else if (staged.size() < MAX_BIND_KEYS) {
            // 还没满 → 追加到末尾（依次按下就能凑成组合键）
            staged.add(key);
        }
        // 满了再按新键：忽略（保持当前三个键不动）
        this.pendingKeys = toArray(staged);
    }

    // ==============================================================
    // 绘制
    // ==============================================================

    /**
     * 文字颜色：只按「已生效绑定」的冲突等级上色。
     *
     * <p>编辑状态不再用颜色区分（只靠文字本身表达），避免监听时多出一圈/一层视觉噪音。
     */
    private int textColor() {
        return switch (conflict.level()) {
            case EXACT -> COLOR_EXACT;
            case PARTIAL -> COLOR_PARTIAL;
            case NONE -> COLOR_NORMAL;
        };
    }

    @Override
    protected void extractContents(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 自己画文字是为了按冲突等级上色（原版按钮默认只有白色）
        extractDefaultSprite(graphics);
        SettingWidgets.plainText(graphics, this, textColor());
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) {
            result.add(value);
        }
        return result;
    }
}
