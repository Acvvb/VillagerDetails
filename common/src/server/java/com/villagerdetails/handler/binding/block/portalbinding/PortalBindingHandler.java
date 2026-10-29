package com.villagerdetails.handler.binding.block.portalbinding;

import com.villagerdetails.cache.impl.BBSectionStateCache;
import com.villagerdetails.cache.impl.PortalSelectionCache;
import com.villagerdetails.config.impl.section.item.PortalBindingConfig;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.network.sync.clientbound.PortalViewSyncChannel;
import com.villagerdetails.pojo.dto.PortalBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.villagerdetails.command.CommandConstants.COMMAND_BASE;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * 「传送门绑定」的处理器：{@code /ec c PortalBinding} 的业务全在这里，命令类只负责挂命令树。
 *
 * <p>命令形态是「两侧配对」：
 * <ul>
 *     <li>{@code set} —— 把当前维度的 BB 选区缓存成这一侧的待绑定（主世界 / 地狱各一次）；</li>
 *     <li>{@code bind} —— 两侧都缓存好了才真正写入一条绑定，然后清掉缓存；</li>
 *     <li>{@code show / view / delete} —— 列出、高亮、删除已有绑定。</li>
 * </ul>
 *
 * <p>回给玩家的每条消息都走 {@link com.villagerdetails.util.SendMessengerUtils}：
 * 由它内部用 {@code ServerTranslations} 按服务端语言翻好再发，
 * 所以这里只写 {@code Component.translatable(...)}，不要自己拼中文，
 * 也不要用 {@code source.sendSuccess / sendFailure}（那样会绕过服务端翻译表）。
 */
public final class PortalBindingHandler {

    private PortalBindingHandler() {
    }

    private static final String CMD_PREFIX = COMMAND_BASE + " c PortalBinding";

    /**
     * 一条绑定的第二、三行缩进，对齐到第一行「[n] 」之后（个位数下标）
     */
    private static final String ENTRY_INDENT = "      ";

    // ============================================================
    // 翻译键
    // ============================================================

    private static final String K_DIMENSION_OVERWORLD = "command.portal_binding.dimension.overworld";
    private static final String K_DIMENSION_NETHER = "command.portal_binding.dimension.nether";

    private static final String K_BUTTON_DELETE = "command.portal_binding.button.delete";

    private static final String K_HOVER_VIEW = "command.portal_binding.show.hover_view";
    private static final String K_HOVER_DELETE = "command.portal_binding.delete.hover";

    private static final String K_FAILURE_UNSUPPORTED_DIMENSION = "command.portal_binding.failure.unsupported_dimension";
    private static final String K_FAILURE_NO_SELECTION = "command.portal_binding.failure.no_selection";
    private static final String K_FAILURE_INCOMPLETE = "command.portal_binding.failure.incomplete";
    private static final String K_FAILURE_OUT_OF_RANGE = "command.portal_binding.failure.out_of_range";

    private static final String K_SET_SUCCESS = "command.portal_binding.set_success";
    private static final String K_BIND_SUCCESS = "command.portal_binding.bind_success";
    private static final String K_SHOW_EMPTY = "command.portal_binding.show.empty";
    private static final String K_SHOW_HEADER = "command.portal_binding.show.header";
    private static final String K_VIEW_SUCCESS = "command.portal_binding.view.success";
    private static final String K_DELETE_SUCCESS = "command.portal_binding.delete.success";

    // ============================================================
    // 业务
    // ============================================================

    /**
     * set：把当前维度选区写进缓存
     */
    public static int set(ServerPlayer player) {
        ServerLevel level = player.level();
        ResourceKey<Level> dim = level.dimension();
        boolean inOverworld = dim.equals(Level.OVERWORLD);
        boolean inNether = dim.equals(Level.NETHER);
        if (!inOverworld && !inNether) {
            sendOrBroadcast(player, Component.translatable(K_FAILURE_UNSUPPORTED_DIMENSION));
            return 0;
        }

        BlockPos pos1 = BBSectionStateCache.getPos1(player);
        BlockPos pos2 = BBSectionStateCache.getPos2(player);
        if (pos1 == null || pos2 == null) {
            sendOrBroadcast(player, Component.translatable(K_FAILURE_NO_SELECTION));
            return 0;
        }

        if (inOverworld) {
            PortalSelectionCache.setOverworld(player.getUUID(), pos1, pos2);
        } else {
            PortalSelectionCache.setNether(player.getUUID(), pos1, pos2);
        }

        sendOrBroadcast(player, Component.translatable(K_SET_SUCCESS,
                dim.identifier().toString(),
                String.valueOf(pos1.getX()), String.valueOf(pos1.getY()), String.valueOf(pos1.getZ()),
                String.valueOf(pos2.getX()), String.valueOf(pos2.getY()), String.valueOf(pos2.getZ())));
        return 1;
    }

    /**
     * bind：从缓存取出两侧并正式绑定
     */
    public static int bind(ServerPlayer player) {
        PortalSelectionCache.Region ow = PortalSelectionCache.getOverworld(player.getUUID());
        PortalSelectionCache.Region ne = PortalSelectionCache.getNether(player.getUUID());

        if (ow == null || ne == null) {
            sendOrBroadcast(player, Component.translatable(K_FAILURE_INCOMPLETE));
            return 0;
        }

        PortalBindingService.bind(
                ow.pos1(), ow.pos2(),
                ne.pos1(), ne.pos2()
        );

        PortalSelectionCache.clear(player.getUUID());

        sendOrBroadcast(player, Component.translatable(K_BIND_SUCCESS));
        return 1;
    }

    /**
     * show：列出所有绑定（可点击）
     */
    public static int show(ServerPlayer player) {
        List<PortalBinding> bindings = PortalBindingManager.getInstance().getBindings();
        if (bindings.isEmpty()) {
            sendOrBroadcast(player, Component.translatable(K_SHOW_EMPTY));
            return 0;
        }

        MutableComponent out = Component.translatable(K_SHOW_HEADER,
                String.valueOf(bindings.size()));
        for (int i = 0; i < bindings.size(); i++) {
            out.append("\n").append(bindingEntryComponent(i + 1, bindings.get(i)));
        }
        sendOrBroadcast(player, out);
        return bindings.size();
    }

    /**
     * view：把某条绑定的两侧都推给客户端高亮
     *
     * @param displayIndex 玩家输入的序号（1 起）
     */
    public static int view(ServerPlayer player, int displayIndex) {
        PortalBinding b = PortalBindingManager.getInstance().getBinding(displayIndex - 1);
        if (b == null) {
            sendOrBroadcast(player, Component.translatable(K_FAILURE_OUT_OF_RANGE,
                    String.valueOf(displayIndex)));
            return 0;
        }

        // 两侧各推一个包。玩家现在只站在其中一个维度，但客户端会把两个维度都留着，
        // 等他走到另一侧时那一侧的高亮照样画得出来。
        PortalViewSyncChannel.INSTANCE.syncTo(player, b.dimA(), b.minA(), b.maxA());
        PortalViewSyncChannel.INSTANCE.syncTo(player, b.dimB(), b.minB(), b.maxB());

        sendOrBroadcast(player, Component.translatable(K_VIEW_SUCCESS,
                String.valueOf(displayIndex)));
        return 1;
    }

    /**
     * delete：删除某条绑定并持久化
     *
     * @param displayIndex 玩家输入的序号（1 起）
     */
    public static int delete(ServerPlayer player, int displayIndex) {
        PortalBinding removed = PortalBindingManager.getInstance().removeBinding(displayIndex - 1);
        if (removed == null) {
            sendOrBroadcast(player, Component.translatable(K_FAILURE_OUT_OF_RANGE,
                    String.valueOf(displayIndex)));
            return 0;
        }

        // 刚删掉的这条很可能正被这个玩家高亮着：顺手清掉客户端那侧的框，
        // 否则会一直留到下次 view 或断线（清的是所有维度，另一侧的框一并消失）
        PortalViewSyncChannel.INSTANCE.clear(player);

        sendOrBroadcast(player, Component.translatable(K_DELETE_SUCCESS,
                String.valueOf(displayIndex)));
        return 1;
    }

    // ============================================================
    // 组件构造
    // ============================================================

    /**
     * 一条绑定占两行：
     * <pre>
     *   [1] 主世界 100,64,200 ~ 110,70,210
     *       地狱   12,60,25 ~ 14,62,27
     *       [删除]
     * </pre>
     *
     * <p>两行坐标<b>整行都能点</b>：点哪一行都是执行 {@code <前缀> view <n>} 把这条绑定的
     * 两侧推去高亮 —— 所以不再单独摆一个 [高亮] 按钮，只留 [删除]。
     *
     * <p>每行是「维度 + 两个角点坐标」：两个角点就是选区的 min / max，
     * 维度名跟着<b>实际维度</b>走（不是按 A/B 位置硬写），所以手改过顺序的配置也不会标错。
     *
     * <p>命令必须带 {@link #CMD_PREFIX}（{@code /ec c PortalBinding}）——
     * 规则命令全都挂在 {@code c} 下面，只写 {@code /ec view} 是解析不到的。
     *
     * <p>维度名、按钮文字、悬停提示全是翻译键，代码里不写死中文。
     */
    private static MutableComponent bindingEntryComponent(int displayIndex, PortalBinding b) {
        String viewCommand = CMD_PREFIX + " view " + displayIndex;

        Style viewStyle = Style.EMPTY
                .withColor(TextColor.fromRgb(0xAAAAAA))
                .withClickEvent(new ClickEvent.RunCommand(viewCommand))
                .withHoverEvent(hover(K_HOVER_VIEW));

        MutableComponent head = Component.literal("  [" + displayIndex + "] ")
                .append(dimensionName(b.dimA()))
                .append(Component.literal(" " + formatCoords(b.minA(), b.maxA())))
                .withStyle(viewStyle);

        // 使用 " ".repeat(n) 优雅地生成空格
        MutableComponent other = Component.literal(ENTRY_INDENT + " ".repeat(2))
                .append(dimensionName(b.dimB()))
                .append(Component.literal(" " + formatCoords(b.minB(), b.maxB())))
                .withStyle(viewStyle);

        MutableComponent deleteBtn = button(
                CMD_PREFIX + " delete " + displayIndex);

        return head.append("\n").append(other).append(" ".repeat(12)).append(deleteBtn);
    }

    /**
     * 维度的显示名：主世界 / 地狱各一条翻译键，其余维度退回注册名（手改配置才可能出现）
     */
    private static MutableComponent dimensionName(ResourceKey<Level> dim) {
        if (Level.OVERWORLD.equals(dim)) {
            return Component.translatable(K_DIMENSION_OVERWORLD);
        }
        if (Level.NETHER.equals(dim)) {
            return Component.translatable(K_DIMENSION_NETHER);
        }
        return Component.literal(dim.identifier().getPath());
    }

    /**
     * 可点击按钮：{@code [标签]} + 颜色 + 点击执行指令 + 悬停说明。
     *
     * <p>标签和悬停都只收翻译键；方括号写在语言文件里，
     * 免得别的语言想换个括号样式还得改代码。
     */
    private static MutableComponent button(String command) {
        return Component.translatable(K_BUTTON_DELETE)
                .withStyle(style -> style.withColor(TextColor.fromRgb(15158332)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)))
                .withStyle(style -> style.withHoverEvent(hover(K_HOVER_DELETE)));
    }

    /**
     * 悬停提示。
     *
     * <p>按 {@code RuleCommand} 的规范，<b>先在服务端把翻译键解析成文本</b>
     * （{@link ServerTranslations#resolve}）再包成 literal：
     * 悬停内容不会被 {@code SendMessengerUtils} 那套翻译走到，
     * 直接塞 {@code Component.translatable} 的话，客户端语言表里没有这条键时
     * 就会把键名（{@code command.portal_binding.xxx}）原样显示出来。
     */
    private static HoverEvent hover(String hoverKey) {
        return new HoverEvent.ShowText(
                Component.literal(ServerTranslations.resolve(Component.translatable(hoverKey))));
    }

    public static int refresh(ServerPlayer player, int displayIndex) {
        PortalBinding binding = PortalBindingConfig.INSTANCE.get(displayIndex - 1);
        if (binding == null) {
            sendOrBroadcast(player, Component.translatable(
                    "command.portal_binding.refresh.out_of_range", displayIndex));
            return 0;
        }

        PortalBinding refreshed = PortalBindingService.refreshCachedPortals(binding);
        if (refreshed == null) {
            sendOrBroadcast(player, Component.translatable(
                    "command.portal_binding.refresh.failure"));
            return 0;
        }

        sendOrBroadcast(player, Component.translatable(
                "command.portal_binding.refresh.done",
                displayIndex,
                formatCached(refreshed.portalA()),
                formatCached(refreshed.portalB())));
        return 1;
    }

    private static Component formatCached(@Nullable BlockPos pos) {
        if (pos == null) return Component.literal("-");
        return Component.literal(pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    /**
     * 两个角点写成 {@code x,y,z ~ x,y,z}（数据，不是文案，不用翻译）
     */
    private static String formatCoords(BlockPos min, BlockPos max) {
        return formatPos(min) + " ~ " + formatPos(max);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
