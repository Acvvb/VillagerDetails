package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.cache.BBSectionStateCache;
import com.villagerdetails.cache.PortalBinding;
import com.villagerdetails.cache.PortalSelectionCache;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.handler.binding.block.portalbinding.PortalBindingManager;
import com.villagerdetails.handler.binding.block.portalbinding.PortalBindingService;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.network.sync.clientbound.PortalViewSyncChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;

import static com.villagerdetails.command.CommandConstants.COMMAND_BASE;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * 传送门绑定指令。
 * <p>
 * 用法：
 * /ec c PortalBinding set    → 把当前维度的拴绳选区写入缓存
 * /ec c PortalBinding bind   → 把主世界 + 地狱两个缓存正式绑定
 * /ec c PortalBinding show   → 列出所有绑定（点坐标行即高亮，另有 [删除]）
 *
 * <p>回给玩家的每一条消息都走 {@link com.villagerdetails.util.SendMessengerUtils}：
 * 由它内部用 {@code ServerTranslations} 按服务端语言翻好再发，
 * 所以这里只写 {@code Component.translatable(...)}，不要自己拼中文，
 * 也不要用 {@code source.sendSuccess / sendFailure}（那样会绕过服务端翻译表）。
 */
public class PortalBindingServerImpl implements RegisterServer {

    public static final PortalBindingServerImpl INSTANCE = new PortalBindingServerImpl();

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

    private static final String K_FAILURE_NOT_PLAYER = "command.portal_binding.failure.not_player";
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

    private PortalBindingServerImpl() {
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("PortalBinding")
                .then(Commands.literal("set").executes(this::onSet))
                .then(Commands.literal("bind").executes(this::onBind))
                .then(Commands.literal("show").executes(this::onShow))
                .then(Commands.literal("view")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(this::onView)))
                .then(Commands.literal("delete")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(this::onDelete)));
    }

    /* ---------- set：把当前维度选区写进缓存 ---------- */

    private int onSet(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrLog(ctx.getSource());
        if (player == null) return 0;

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

    /* ---------- bind：从缓存取出两侧并正式绑定 ---------- */

    private int onBind(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrLog(ctx.getSource());
        if (player == null) return 0;

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

    /* ---------- show：列出所有绑定（可点击） ---------- */

    private int onShow(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrLog(ctx.getSource());
        if (player == null) return 0;

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
     * 一条绑定占三行：
     * <pre>
     *   [1] 主世界 100,64,200 ~ 110,70,210
     *       地狱   12,60,25 ~ 14,62,27
     *       [删除]
     * </pre>
     *
     * <p>两行坐标<b>整行都能点</b>：点哪一行都是执行 {@code <前缀> view <n>} 把这条绑定的
     * 两侧推去高亮 —— 所以不再单独摆一个 [高亮] 按钮，第三行只留 [删除]。
     *
     * <p>每行是「维度 + 两个角点坐标」：两个角点就是选区的 min / max，
     * 维度名跟着<b>实际维度</b>走（不是按 A/B 位置硬写），所以手改过顺序的配置也不会标错。
     *
     * <p>命令必须带 {@link #CMD_PREFIX}（{@code /ec c PortalBinding}）——
     * 规则命令全都挂在 {@code c} 下面，只写 {@code /ec view} 是解析不到的。
     *
     * <p>维度名、按钮文字、悬停提示全是翻译键，代码里不写死中文。
     */
    private MutableComponent bindingEntryComponent(int displayIndex, PortalBinding b) {
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
    private MutableComponent dimensionName(ResourceKey<Level> dim) {
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
    private MutableComponent button(String command) {
        return Component.translatable(PortalBindingServerImpl.K_BUTTON_DELETE)
                .withStyle(style -> style.withColor(TextColor.fromRgb(15158332)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)))
                .withStyle(style -> style.withHoverEvent(hover(PortalBindingServerImpl.K_HOVER_DELETE)));
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
    private HoverEvent hover(String hoverKey) {
        return new HoverEvent.ShowText(
                Component.literal(ServerTranslations.resolve(Component.translatable(hoverKey))));
    }

    /**
     * 两个角点写成 {@code x,y,z ~ x,y,z}（数据，不是文案，不用翻译）
     */
    private String formatCoords(BlockPos min, BlockPos max) {
        return formatPos(min) + " ~ " + formatPos(max);
    }

    private String formatPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /* ---------- view：把某条绑定的两侧都推给客户端高亮 ---------- */

    private int onView(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrLog(ctx.getSource());
        if (player == null) return 0;

        int index = IntegerArgumentType.getInteger(ctx, "index") - 1;
        PortalBinding b = PortalBindingManager.getInstance().getBinding(index);
        if (b == null) {
            sendOrBroadcast(player, Component.translatable(K_FAILURE_OUT_OF_RANGE,
                    String.valueOf(index + 1)));
            return 0;
        }

        // 两侧各推一个包。玩家现在只站在其中一个维度，但客户端会把两个维度都留着，
        // 等他走到另一侧时那一侧的高亮照样画得出来。
        PortalViewSyncChannel.INSTANCE.syncTo(player, b.dimA(), b.minA(), b.maxA());
        PortalViewSyncChannel.INSTANCE.syncTo(player, b.dimB(), b.minB(), b.maxB());

        sendOrBroadcast(player, Component.translatable(K_VIEW_SUCCESS,
                String.valueOf(index + 1)));
        return 1;
    }

    /* ---------- delete：删除某条绑定并持久化 ---------- */

    private int onDelete(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrLog(ctx.getSource());
        if (player == null) return 0;

        int index = IntegerArgumentType.getInteger(ctx, "index") - 1;
        PortalBinding removed = PortalBindingManager.getInstance().removeBinding(index);
        if (removed == null) {
            sendOrBroadcast(player, Component.translatable(K_FAILURE_OUT_OF_RANGE,
                    String.valueOf(index + 1)));
            return 0;
        }

        // 刚删掉的这条很可能正被这个玩家高亮着：顺手清掉客户端那侧的框，
        // 否则会一直留到下次 view 或断线（清的是所有维度，另一侧的框一并消失）
        PortalViewSyncChannel.INSTANCE.clear(player);

        sendOrBroadcast(player, Component.translatable(K_DELETE_SUCCESS,
                String.valueOf(index + 1)));
        return 1;
    }

    /* ---------- 工具方法 ---------- */

    /**
     * 取执行指令的玩家；不是玩家（控制台 / 命令方块）时按规范记一条日志并返回 {@code null}。
     *
     * <p>刻意不用 {@code source.sendFailure}：所有输出统一走
     * {@link com.villagerdetails.util.SendMessengerUtils}，没有玩家时它退化成打日志。
     */
    private ServerPlayer playerOrLog(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            sendOrBroadcast(null, Component.translatable(K_FAILURE_NOT_PLAYER));
        }
        return player;
    }
}
