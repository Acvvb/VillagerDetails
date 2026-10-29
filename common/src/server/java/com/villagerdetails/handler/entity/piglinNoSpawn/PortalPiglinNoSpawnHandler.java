package com.villagerdetails.handler.entity.piglinNoSpawn;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.cache.impl.BBSectionStateCache;
import com.villagerdetails.config.impl.section.item.PortalPiglinNoSpawnConfig;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.network.sync.clientbound.PortalViewSyncChannel;
import com.villagerdetails.pojo.dto.PortalPiglinNoSpawnRegion;
import com.villagerdetails.rule.type.RuleType;
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

import java.util.List;

import static com.villagerdetails.command.CommandConstants.COMMAND_BASE;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * 「禁用地狱门刷僵尸猪灵」的处理器：运行时判定 + 命令业务全在这里，命令类只负责挂命令树。
 *
 * <p>运行时：原版 {@code NetherPortalBlock.randomTick} 在维度属性
 * {@code minecraft:gameplay/nether_portal_spawns_piglin} 为真时，会以 1/2000 × 难度 的概率
 * 从这扇门的底部刷出一只<b>僵尸猪灵</b>（主世界维度默认开启，地狱默认关闭）。
 * 规则就是把这个随机刻掐掉 —— 拦截点在 {@code NetherPortalBlock#randomTick} 的 HEAD，
 * 判定这里是同一份结论，两边不会一个拦一个放。
 *
 * <p>命令业务：{@code /ec c PortalPiglinNoSpawn bind|show|view|delete|clear} 对应的
 * 区域增删查、消息拼装、客户端高亮推送都在这里。
 *
 * <p>判定顺序和别的逐条规则一致：先看规则开关，再看区域表。
 * 规则关掉就等于对原版零影响，区域数据留着，下次开规则继续用。
 *
 * <p><b>绑定不检查选区内容</b>：选区里有没有地狱门方块、有多大，一概不看，
 * 选多大就存多大（只看规则开没开）。区域里现在没门也无所谓，之后建在里面的门一样受它管。
 */
public final class PortalPiglinNoSpawnHandler {

    private PortalPiglinNoSpawnHandler() {
    }

    // ============================================================
    // 命令相关常量
    // ============================================================

    private static final String CMD_PREFIX = COMMAND_BASE + " c PortalPiglinNoSpawn";

    private static final String ENTRY_INDENT = "  ";

    private static final String K_DIMENSION_OVERWORLD = "command.portal_piglin_no_spawn.dimension.overworld";
    private static final String K_DIMENSION_NETHER = "command.portal_piglin_no_spawn.dimension.nether";

    private static final String K_BUTTON_DELETE = "command.portal_piglin_no_spawn.button.delete";

    private static final String K_HOVER_VIEW = "command.portal_piglin_no_spawn.show.hover_view";
    private static final String K_HOVER_DELETE = "command.portal_piglin_no_spawn.delete.hover";

    private static final String K_FAILURE_NO_SELECTION = "command.portal_piglin_no_spawn.failure.no_selection";
    private static final String K_FAILURE_OUT_OF_RANGE = "command.portal_piglin_no_spawn.failure.out_of_range";

    private static final String K_BIND_SUCCESS = "command.portal_piglin_no_spawn.bind.success";
    private static final String K_BIND_EXISTS = "command.portal_piglin_no_spawn.bind.already_exists";

    private static final String K_SHOW_EMPTY = "command.portal_piglin_no_spawn.show.empty";
    private static final String K_SHOW_HEADER = "command.portal_piglin_no_spawn.show.header";
    private static final String K_VIEW_SUCCESS = "command.portal_piglin_no_spawn.view.success";
    private static final String K_DELETE_SUCCESS = "command.portal_piglin_no_spawn.delete.success";
    private static final String K_CLEAR_SUCCESS = "command.portal_piglin_no_spawn.clear.success";

    // ============================================================
    // 运行时：地狱门随机刻要不要跳过
    // ============================================================

    /**
     * 这个地狱门方块这次随机刻要不要跳过（要不要刷僵尸猪灵）
     *
     * @param level 方块所在的服务端世界
     * @param pos   刚刚随机刻到的地狱门方块坐标
     */
    public static boolean isBlocked(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        // 最便宜的一步放最前面：一次枚举状态比较
        if (!RuleCache.isEnabled(RuleType.PORTAL_PIGLIN_NO_SPAWN)) {
            return false;
        }
        return PortalPiglinNoSpawnConfig.INSTANCE.contains(level.dimension(), pos);
    }

    // ============================================================
    // 命令业务
    // ============================================================

    /**
     * bind：把玩家当前的 BB 选区绑成一条区域（维度取玩家当前所在维度）
     */
    public static int bind(ServerPlayer player) {
        if (!BBSectionStateCache.hasSelection(player)) {
            sendOrBroadcast(player, Component.translatable(K_FAILURE_NO_SELECTION));
            return 0;
        }

        ServerLevel level = player.level();
        BlockPos pos1 = BBSectionStateCache.getPos1(player);
        BlockPos pos2 = BBSectionStateCache.getPos2(player);

        PortalPiglinNoSpawnRegion region = PortalPiglinNoSpawnRegion.of(level.dimension(), pos1, pos2);
        boolean added = PortalPiglinNoSpawnConfig.INSTANCE.add(region);
        // 列表里第几条（1 起）；重复绑定也能用它告诉玩家「已经绑过第几条了」
        int displayIndex = PortalPiglinNoSpawnConfig.INSTANCE.snapshot().indexOf(region) + 1;

        if (!added) {
            sendOrBroadcast(player, Component.translatable(K_BIND_EXISTS, String.valueOf(displayIndex)));
            return 1;
        }

        sendOrBroadcast(player, Component.translatable(K_BIND_SUCCESS,
                dimensionName(region.dimension()),
                formatCoords(region.min(), region.max()),
                String.valueOf(displayIndex)));
        return 1;
    }

    /**
     * show：列出所有已绑定区域
     */
    public static int show(ServerPlayer player) {
        List<PortalPiglinNoSpawnRegion> regions = PortalPiglinNoSpawnConfig.INSTANCE.snapshot();
        if (regions.isEmpty()) {
            sendOrBroadcast(player, Component.translatable(K_SHOW_EMPTY));
            return 0;
        }

        MutableComponent out = Component.translatable(K_SHOW_HEADER, String.valueOf(regions.size()));
        for (int i = 0; i < regions.size(); i++) {
            out.append("\n").append(entryComponent(i + 1, regions.get(i)));
        }
        sendOrBroadcast(player, out);
        return regions.size();
    }

    /**
     * view：把某条区域推给客户端高亮
     *
     * @param displayIndex 玩家输入的序号（1 起）
     */
    public static int view(ServerPlayer player, int displayIndex) {
        PortalPiglinNoSpawnRegion region = PortalPiglinNoSpawnConfig.INSTANCE.get(displayIndex - 1);
        if (region == null) {
            sendOutOfRange(player, displayIndex);
            return 0;
        }

        // 复用传送门绑定那套「视图高亮」：客户端按维度存着，人走到那个维度就能看到框
        // （颜色沿用传送门高亮的主世界 / 地狱两套配置）。
        PortalViewSyncChannel.INSTANCE.syncTo(player, region.dimension(), region.min(), region.max());

        sendOrBroadcast(player, Component.translatable(K_VIEW_SUCCESS, String.valueOf(displayIndex)));
        return 1;
    }

    /**
     * delete：删除某条区域并落盘，顺手把客户端上的高亮框清掉
     *
     * <p>区域记录里没有缓存字段（只有维度 + 两个角点），所以不需要像
     * {@code /ec c PortalBinding delete} 那样先刷缓存；这里真正的残留只有一个 ——
     * <b>客户端高亮</b>：它是纯客户端状态（{@code PortalViewClientState}），
     * 谁点过 {@code view} 谁的客户端里就存着一份，不光删记录不会带走它，
     * 只清执行者一个人也不够（别人屏幕上那块框会一直留到下次 {@code view} / 断线，
     * 看着就像这条被删掉的区域还生效着）。所以统一广播一次清空。
     *
     * @param displayIndex 玩家输入的序号（1 起）
     */
    public static int delete(ServerPlayer player, int displayIndex) {
        PortalPiglinNoSpawnRegion removed = PortalPiglinNoSpawnConfig.INSTANCE.removeAt(displayIndex - 1);
        if (removed == null) {
            sendOutOfRange(player, displayIndex);
            return 0;
        }

        // 这条区域已经不存在了：所有客户端都不该再画它的框
        PortalViewSyncChannel.INSTANCE.clearAll();

        sendOrBroadcast(player, Component.translatable(K_DELETE_SUCCESS, String.valueOf(displayIndex)));
        return 1;
    }

    /**
     * clear：清空全部区域
     *
     * <p>和 {@link #delete} 同理，区域都没了，客户端上对应的高亮框也要一起清，
     * 否则清空之后屏幕上还留着最后一两条的框。
     */
    public static int clear(ServerPlayer player) {
        int count = PortalPiglinNoSpawnConfig.INSTANCE.snapshot().size();
        if (count == 0) {
            sendOrBroadcast(player, Component.translatable(K_SHOW_EMPTY));
            return 0;
        }
        PortalPiglinNoSpawnConfig.INSTANCE.clear();
        PortalViewSyncChannel.INSTANCE.clearAll();
        sendOrBroadcast(player, Component.translatable(K_CLEAR_SUCCESS, String.valueOf(count)));
        return 1;
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private static void sendOutOfRange(ServerPlayer player, int displayIndex) {
        sendOrBroadcast(player, Component.translatable(K_FAILURE_OUT_OF_RANGE, String.valueOf(displayIndex)));
    }

    /**
     * 一条区域一行：{@code [n] 维度 x,y,z ~ x,y,z [删除]}。
     *
     * <p>坐标整行都能点：点了执行 {@code <前缀> view <n>}，把这条区域推去客户端高亮。
     */
    private static MutableComponent entryComponent(int displayIndex, PortalPiglinNoSpawnRegion region) {
        String viewCommand = CMD_PREFIX + " view " + displayIndex;

        Style viewStyle = Style.EMPTY
                .withColor(TextColor.fromRgb(0xAAAAAA))
                .withClickEvent(new ClickEvent.RunCommand(viewCommand))
                .withHoverEvent(hover(K_HOVER_VIEW));

        MutableComponent head = Component.literal(ENTRY_INDENT + "[" + displayIndex + "] ")
                .append(dimensionName(region.dimension()))
                .append(Component.literal(" " + formatCoords(region.min(), region.max())))
                .withStyle(viewStyle);

        return head.append(" ".repeat(4)).append(deleteButton(CMD_PREFIX + " delete " + displayIndex));
    }

    /**
     * 维度的显示名：主世界 / 地狱各一条翻译键，其余维度退回注册名
     */
    private static MutableComponent dimensionName(ResourceKey<Level> dim) {
        if (Level.OVERWORLD.equals(dim)) {
            return Component.translatable(K_DIMENSION_OVERWORLD);
        }
        if (Level.NETHER.equals(dim)) {
            return Component.translatable(K_DIMENSION_NETHER);
        }
        return Component.literal(dim.identifier().toString());
    }

    /**
     * 可点击的 [删除] 按钮
     */
    private static MutableComponent deleteButton(String command) {
        return Component.translatable(K_BUTTON_DELETE)
                .withStyle(style -> style.withColor(TextColor.fromRgb(15158332)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)))
                .withStyle(style -> style.withHoverEvent(hover(K_HOVER_DELETE)));
    }

    /**
     * 悬停提示：先在服务端把翻译键解析成文本再包 literal，
     * 免得客户端语言表里没有这条键时把键名原样显示出来。
     */
    private static HoverEvent hover(String hoverKey) {
        return new HoverEvent.ShowText(
                Component.literal(ServerTranslations.resolve(Component.translatable(hoverKey))));
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
