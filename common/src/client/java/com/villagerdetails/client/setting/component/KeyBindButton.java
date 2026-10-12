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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 快捷键绑定文本框（支持组合键）。
 *
 * <ul>
 *     <li>鼠标左键点一下进入「监听」状态</li>
 *     <li>监听中按下按键会被记录，<b>等所有按下的键都松开</b>后完成绑定 ——
 *         所以先按住 X 再按 C、然后一起松开，就会绑成 {@code X + C}</li>
 *     <li>按 <b>ESC</b> → 把这个绑定<b>清空</b>（设为未绑定），并立刻写回配置</li>
 *     <li>监听中<b>左键点空白处</b> → 退出监听但<b>保留</b>原来的绑定
 *         （这一下按键/点击会被吃掉，界面不会因此关闭）</li>
 *     <li>绑定完成后会和原版及其它模组已注册的按键比对，冲突时文字变色并在悬停提示里说明来源</li>
 * </ul>
 *
 * <p>外观沿用原版按钮（原版精灵），材质包可替换。
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

    private final ConfigEntryType entry;
    private final Consumer<int[]> onBound;

    /**
     * 本次监听中记录下来的按键（按按下顺序）
     */
    private final List<Integer> recorded = new ArrayList<>();
    /**
     * 目前还按着的键
     */
    private final Set<Integer> held = new LinkedHashSet<>();

    private int[] keys;
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

    public boolean isListening() {
        return listening;
    }

    /**
     * 退出监听且不写入绑定（监听中点空白处）
     */
    public void cancelListening() {
        if (!listening) {
            return;
        }
        listening = false;
        recorded.clear();
        held.clear();
        refreshMessage();
        refreshTooltip();
    }

    /**
     * 监听中按 ESC：把这个绑定<b>清空</b>（等于解绑），并通知行去存盘。
     *
     * <p>注意和 {@link #cancelListening()} 的区别：那个是「保留原绑定、只是不录了」。
     */
    private void clearBinding() {
        this.keys = ClientRenderConfig.NO_KEYS;
        this.listening = false;
        recorded.clear();
        held.clear();
        if (onBound != null) {
            onBound.accept(this.keys);
        }
        refreshConflict();
        refreshMessage();
    }

    /**
     * 重新检查冲突并更新文字颜色 + 悬停提示（构造时、绑定完成后各调一次）。
     *
     * <p>private：构造里要调它，公开 / 可覆盖的方法在构造里调用会让 {@code this} 提前逸出
     * （javac 的 {@code this-escape} 告警）。
     */
    private void refreshConflict() {
        this.conflict = KeyBindConflicts.check(entry);
        refreshTooltip();
    }

    /**
     * 监听中提示操作方法；平时显示冲突来源
     */
    private void refreshTooltip() {
        if (listening) {
            setTooltip(Tooltip.create(Component.translatable("villagerdetails.ui.keybind.listening.hint")));
        } else {
            setTooltip(conflict.hasConflict() ? Tooltip.create(conflict.tooltip()) : null);
        }
    }

    private void toggleListening() {
        if (listening) {
            cancelListening();
            return;
        }
        listening = true;
        recorded.clear();
        held.clear();
        refreshMessage();
        refreshTooltip();
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        if (!listening) {
            return super.keyPressed(event);
        }

        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            // ESC = 清空这个绑定（ESC 本身当然不会被绑进去）
            clearBinding();
            return true;
        }

        if (!recorded.contains(key)) {
            recorded.add(key);
        }
        held.add(key);
        refreshMessage();
        return true;
    }

    @Override
    public boolean keyReleased(@NonNull KeyEvent event) {
        if (!listening) {
            return super.keyReleased(event);
        }

        held.remove(event.key());
        if (!held.isEmpty() || recorded.isEmpty()) {
            // 还有键按着（或者压根没记录到键），继续等
            return true;
        }

        // 所有键都松开了 → 绑定完成
        int[] bound = new int[recorded.size()];
        for (int i = 0; i < bound.length; i++) {
            bound[i] = recorded.get(i);
        }
        this.keys = bound;
        this.listening = false;
        recorded.clear();
        held.clear();
        if (onBound != null) {
            onBound.accept(bound);
        }
        refreshConflict();
        refreshMessage();
        return true;
    }

    private void refreshMessage() {
        if (listening) {
            setMessage(recorded.isEmpty()
                    ? Component.translatable("villagerdetails.ui.keybind.listening")
                    : ConfigEntryType.displayKeys(toArray(recorded)));
        } else {
            setMessage(ConfigEntryType.displayKeys(keys));
        }
    }

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
}
