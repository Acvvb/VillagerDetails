package com.villagerdetails.client.setting.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.screen.VillagerBedConfigScreen;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.type.style.StyleType;
import com.villagerdetails.rule.RuleSwitches;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.Map;

/**
 * 快捷键：
 * <ul>
 *     <li>打开配置界面的键（默认 {@code J}）注册在原版按键绑定里，
 *         位置在「选项 → 控制 → 按键绑定 → 村民详情」，玩家可以自己改键</li>
 *     <li>功能开关在设置界面里自己绑的快捷键：游戏内按下即切换该开关（开着界面时不响应）</li>
 * </ul>
 */
public final class VillagerDetailsKeyMappings {

    /**
     * 打开设置界面的默认按键
     */
    private static final int DEFAULT_OPEN_KEY = InputConstants.KEY_J;

    /**
     * 旧版本的默认按键，用来做一次性迁移
     */
    private static final int LEGACY_OPEN_KEY = InputConstants.KEY_K;

    /**
     * 按键分类（原版按键设置界面里的分组标题）
     */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("villagerdetails", "main"));

    /**
     * 注册进原版按键绑定表：会出现在原版的「按键绑定」界面里
     */
    public static final KeyMapping OPEN_CONFIG = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.villagerdetails.open_config",
            InputConstants.Type.KEYSYM,
            DEFAULT_OPEN_KEY,
            CATEGORY));

    /**
     * 上一次 tick 各功能开关快捷键的按下状态（做上升沿判断，避免按住时反复触发）
     */
    private static final Map<ConfigEntryType, Boolean> HOTKEY_DOWN = new EnumMap<>(ConfigEntryType.class);

    private VillagerDetailsKeyMappings() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            migrateLegacyDefaultKey(client);
            while (OPEN_CONFIG.consumeClick()) {
                client.gui.setScreen(new VillagerBedConfigScreen(client.gui.screen()));
            }
            handleToggleHotkeys(client);
        });
    }

    /**
     * 一次性迁移：默认键从 K 改成 J 之前，如果玩家没改过键（还停在旧默认 K），
     * 就顺手改成新的默认 J 并存进原版 options，免得升级后看到的还是旧键。改过的键不动。
     */
    private static void migrateLegacyDefaultKey(Minecraft client) {
        ClientRenderConfig config = ClientRenderConfig.get();
        if (config.isOpenKeyMigrated()) {
            return;
        }
        config.setOpenKeyMigrated(true);
        config.save();

        InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(OPEN_CONFIG);
        if (bound.getType() != InputConstants.Type.KEYSYM) {
            return;
        }
        if (bound.getValue() == LEGACY_OPEN_KEY) {
            OPEN_CONFIG.setKey(InputConstants.Type.KEYSYM.getOrCreate(DEFAULT_OPEN_KEY));
            client.options.save();
        }
    }

    private static void handleToggleHotkeys(Minecraft client) {
        boolean inGame = client.gui.screen() == null;

        for (ConfigEntryType entry : ConfigEntryType.values()) {
            StyleType style = entry.getStyle();
            if (style != StyleType.TOGGLE && style != StyleType.TRIGGER) {
                continue;
            }

            int[] keys = entry.getKeyBind();
            boolean down = inGame && keys.length > 0 && allKeysDown(client, keys);

            if (down && !HOTKEY_DOWN.getOrDefault(entry, Boolean.FALSE)) {
                if (style == StyleType.TRIGGER) {
                    entry.getAction().run();
                } else {
                    boolean enabled = !entry.getToggle();
                    entry.setToggle(enabled);
                    ClientRenderConfig.get().save();
                    notifyToggled(client, entry, enabled);
                }
            }
            HOTKEY_DOWN.put(entry, down);
        }
    }

    /**
     * 快捷键切换完之后在动作栏提醒玩家：<b>只有「已开启 / 已关闭」这几个字变色</b>
     * （开启绿、关闭红），前面的开关名保持默认的白色。
     *
     * <p>这里走的是<b>客户端</b>的提示。功能开关存在 {@link ClientRenderConfig} 里（本机配置），
     * 客户端手上没有 {@code ServerPlayer}（多人游戏下更不可能有），所以用不了
     * {@code SendMessengerUtils.sendOverlayOrBroadcast}（那个要 ServerPlayer）——
     * {@link LocalPlayer#sendOverlayMessage} 就是它的客户端等价写法，同样是动作栏浮层。
     */
    private static void notifyToggled(Minecraft client, ConfigEntryType entry, boolean enabled) {
        if (client.player == null) {
            return;
        }
        // 状态那一段单独上色；"名字 + 冒号" 不设颜色，就是默认的白色
        Component state = Component.translatable(enabled
                        ? "command.entity_binder.status.enabled"
                        : "command.entity_binder.status.disabled")
                .withColor(enabled ? RuleSwitches.COLOR_SELECTED_ON : RuleSwitches.COLOR_SELECTED_OFF);
        client.player.sendOverlayMessage(
                Component.translatable("villagerdetails.ui.hotkey.status", entry.getDisplayName(), state));
    }

    /**
     * 组合键要求所有键同时按住
     */
    private static boolean allKeysDown(Minecraft client, int[] keys) {
        for (int key : keys) {
            if (!InputConstants.isKeyDown(client.getWindow(), key)) {
                return false;
            }
        }
        return true;
    }
}
