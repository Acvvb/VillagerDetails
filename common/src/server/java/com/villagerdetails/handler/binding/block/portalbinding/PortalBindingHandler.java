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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

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
 *     <li>{@code show / view / delete} —— 列出、高亮、删除已有绑定；</li>
 *     <li>{@code refresh} —— 清掉某条绑定两侧缓存的门坐标并立即重扫（{@code show} 里也有按钮）。</li>
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
    private static final String K_BUTTON_REFRESH = "command.portal_binding.button.refresh";

    private static final String K_HOVER_VIEW = "command.portal_binding.show.hover_view";
    private static final String K_HOVER_DELETE = "command.portal_binding.delete.hover";
    private static final String K_HOVER_REFRESH = "command.portal_binding.refresh.hover";

    /**
     * 按钮颜色，和 {@code [删除]} 的红色区分开
     */
    private static final int COLOR_REFRESH = 0x55FF55;
    private static final int COLOR_DELETE = 15158332;

    private static final String K_FAILURE_UNSUPPORTED_DIMENSION = "command.portal_binding.failure.unsupported_dimension";
    private static final String K_FAILURE_NO_SELECTION = "command.portal_binding.failure.no_selection";
    private static final String K_FAILURE_INCOMPLETE = "command.portal_binding.failure.incomplete";
    private static final String K_FAILURE_OUT_OF_RANGE = "command.portal_binding.failure.out_of_range";
    private static final String K_FAILURE_REGION_TOO_LARGE = "command.portal_binding.failure.region_too_large";

    private static final String K_SET_SUCCESS = "command.portal_binding.set_success";
    private static final String K_BIND_SUCCESS = "command.portal_binding.bind_success";
    private static final String K_SHOW_EMPTY = "command.portal_binding.show.empty";
    private static final String K_SHOW_HEADER = "command.portal_binding.show.header";
    private static final String K_VIEW_SUCCESS = "command.portal_binding.view.success";
    private static final String K_DELETE_SUCCESS = "command.portal_binding.delete.success";

    private static final String K_REFRESH_DONE = "command.portal_binding.refresh.done";
    private static final String K_REFRESH_FAILURE = "command.portal_binding.refresh.failure";
    private static final String K_REFRESH_OUT_OF_RANGE = "command.portal_binding.refresh.out_of_range";
    private static final String K_REFRESH_FOUND = "command.portal_binding.refresh.found";
    private static final String K_REFRESH_NO_PORTAL = "command.portal_binding.refresh.no_portal";
    private static final String K_REFRESH_KEPT = "command.portal_binding.refresh.kept";
    private static final String K_REFRESH_UNTOUCHED = "command.portal_binding.refresh.untouched";
    private static final String K_REFRESH_INCOMPLETE = "command.portal_binding.refresh.incomplete";

    // ============================================================
    // 业务
    // ============================================================

    /**
     * set：把当前维度选区写进缓存。
     *
     * <p>写缓存之前先校验尺寸：单侧区域上限 {@link PortalBinding#MAX_SIZE_X} ×
     * {@link PortalBinding#MAX_SIZE_Y} × {@link PortalBinding#MAX_SIZE_Z}（含两端格数）。
     *
     * <p>这个上限是<b>扫描一定能看全</b>的前提：POI 扫描的覆盖半径取决于区域中心到角点的
     * 水平距离（上限 64），远小于单次扫描的安全半径 128，所以合法区域一次就扫完，
     * 不会出现「区域里明明有门却扫不到 → 误建第二扇门 / 刷新把好缓存刷没」。
     * 超限的选区在这里直接挡下，不让它进缓存。
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

        PortalBinding.Size size = PortalBinding.sizeOf(pos1, pos2);
        if (!PortalBinding.withinSizeLimits(pos1, pos2)) {
            sendOrBroadcast(player, Component.translatable(K_FAILURE_REGION_TOO_LARGE,
                    String.valueOf(size.x()), String.valueOf(size.y()), String.valueOf(size.z()),
                    String.valueOf(PortalBinding.MAX_SIZE_X),
                    String.valueOf(PortalBinding.MAX_SIZE_Y),
                    String.valueOf(PortalBinding.MAX_SIZE_Z)));
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

        // 渲染这一刻的结构版本：写进每个条目的按钮，点的时候带回来校验。
        // 增删 / 重载之后版本就变了，旧按钮点下去会被拒绝，不会按过期序号刷错删错。
        int version = PortalBindingConfig.INSTANCE.structureVersion();

        MutableComponent out = Component.translatable(K_SHOW_HEADER,
                String.valueOf(bindings.size()));
        for (int i = 0; i < bindings.size(); i++) {
            out.append("\n").append(bindingEntryComponent(i + 1, bindings.get(i), version));
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
     * delete：先刷掉这一条的缓存，再删除并持久化。
     *
     * <p>顺序是有意的：<b>先清缓存、再删记录</b>。
     * <ul>
     *     <li>缓存（{@code portalA} / {@code portalB}：两侧已解析出的门底坐标）是跟着记录一起走的，
     *         删记录本身就会带走它们；这里显式再清一遍，是为了让「这条绑定的缓存已作废」成为
     *         删除流程里明确的一步 —— 和 {@code refresh} 的清缓存是同一个动作
     *         （{@link PortalBindingConfig#clearCachedPortals}），
     *         将来若改成「删除时保留历史记录」也不会漏清；</li>
     *     <li>清完缓存再删，位置解析不会再命中这条已经要消失的记录，
     *         任何排队中的回写（增强模式异步传送的 {@code cachePortal}）都会被
     *         {@link PortalBindingConfig#updateCachedPortal} 判成「找不到」而丢弃。</li>
     * </ul>
     *
     * <p>删完还要把<b>所有客户端</b>上这条绑定的高亮框清掉：高亮是纯客户端缓存
     * （{@code PortalViewClientState}），谁点过 {@code view} 谁的客户端里就存着一份，
     * 只清执行删除的人，别人屏幕上那块框会一直留到下次 {@code view} / 断线 ——
     * 看着就像这条被删掉的绑定还生效着。
     *
     * @param displayIndex 玩家输入的序号（1 起）
     */
    public static int delete(ServerPlayer player, int displayIndex) {
        PortalBinding binding = PortalBindingManager.getInstance().getBinding(displayIndex - 1);
        if (binding == null) {
            sendOrBroadcast(player, Component.translatable(K_FAILURE_OUT_OF_RANGE,
                    String.valueOf(displayIndex)));
            return 0;
        }

        // 1) 刷掉这一条的缓存门坐标（和 refresh 的清缓存同一步），落盘
        PortalBindingConfig.INSTANCE.clearCachedPortals(binding);

        // 2) 删记录本身（removeAt 内部会再落一次盘）
        PortalBindingManager.getInstance().removeBinding(displayIndex - 1);

        // 3) 清掉所有客户端上这条绑定的高亮框，别让被删掉的区域还画在那儿
        PortalViewSyncChannel.INSTANCE.clearAll();

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
     *       [刷新]  [删除]
     * </pre>
     *
     * <p>两行坐标<b>整行都能点</b>：点哪一行都是执行 {@code <前缀> view <n>} 把这条绑定的
     * 两侧推去高亮 —— 所以不再单独摆一个 [高亮] 按钮，只留 [刷新] / [删除]。
     *
     * <p>[刷新] 走 {@code <前缀> refresh <n>}，语义和 {@link #refresh} 完全一致：
     * 清掉这条绑定两侧缓存的门坐标，再按当前区域状态重扫回写（点一下就等于手打一遍指令）。
     *
     * <p>每行是「维度 + 两个角点坐标」：两个角点就是选区的 min / max，
     * 维度名跟着<b>实际维度</b>走（不是按 A/B 位置硬写），所以手改过顺序的配置也不会标错。
     *
     * <p>命令必须带 {@link #CMD_PREFIX}（{@code /ec c PortalBinding}）——
     * 规则命令全都挂在 {@code c} 下面，只写 {@code /ec view} 是解析不到的。
     *
     * <p>两个按钮还各自多带一个「列表结构版本」（{@code structureVersion}）：
     * 序号是位置，别人在你看到列表之后增删一条，序号就指到别的条目上了；
     * 带上版本，命令执行时一对就能发现列表过期并拒绝，而不是刷错门 / 删错绑定。
     *
     * <p>维度名、按钮文字、悬停提示全是翻译键，代码里不写死中文。
     *
     * @param structureVersion 渲染这一刻 {@code PortalBindingConfig#structureVersion()} 的值
     */
    private static MutableComponent bindingEntryComponent(int displayIndex, PortalBinding b,
                                                          int structureVersion) {
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

        MutableComponent refreshBtn = button(
                K_BUTTON_REFRESH, K_HOVER_REFRESH, COLOR_REFRESH,
                CMD_PREFIX + " refresh " + displayIndex + " " + structureVersion);

        MutableComponent deleteBtn = button(
                K_BUTTON_DELETE, K_HOVER_DELETE, COLOR_DELETE,
                CMD_PREFIX + " delete " + displayIndex + " " + structureVersion);

        return head.append("\n").append(other)
                .append(" ".repeat(12)).append(refreshBtn)
                .append("  ").append(deleteBtn);
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
     *
     * @param labelKey  按钮文字的翻译键，例如 {@link #K_BUTTON_DELETE}
     * @param hoverKey  悬停说明的翻译键，例如 {@link #K_HOVER_DELETE}
     * @param color     文字颜色（{@code 0xRRGGBB}）
     * @param command   点击后执行的完整指令（含 {@link #CMD_PREFIX}）
     */
    private static MutableComponent button(String labelKey, String hoverKey, int color, String command) {
        return Component.translatable(labelKey)
                .withStyle(style -> style.withColor(TextColor.fromRgb(color)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)))
                .withStyle(style -> style.withHoverEvent(hover(hoverKey)));
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

    /**
     * refresh：按当前区域状态重扫某条绑定两侧，并把结果写回缓存。
     *
     * <p>{@code show} 里那条 {@code [刷新]} 按钮点下去执行的就是
     * {@code <前缀> refresh <n> <版本>}，最终也走到这里 —— 按钮和指令的行为完全一致。
     *
     * <p>反馈只告诉玩家「这一侧有没有门」：坐标 / 暂无门 / 沿用原缓存，
     * 区域太大没扫全时再加一行提醒。底层是「扫到了 / 沿用 / 清空」哪种情况不需要玩家理解，
     * 细节留在日志里（{@link PortalBindingService.SideOutcome}）。
     *
     * <p>三种失败各自一条消息：序号越界、绑定已被删掉、服务端不可用
     * （后两种由 {@link PortalBindingService#refreshCachedPortals} 返回 {@code null} 表示）。
     *
     * @param displayIndex 玩家输入的序号（1 起）
     */
    public static int refresh(ServerPlayer player, int displayIndex) {
        PortalBinding binding = PortalBindingManager.getInstance().getBinding(displayIndex - 1);
        if (binding == null) {
            sendOrBroadcast(player, Component.translatable(K_REFRESH_OUT_OF_RANGE,
                    String.valueOf(displayIndex)));
            return 0;
        }

        PortalBindingService.RefreshResult result = PortalBindingService.refreshCachedPortals(binding);
        if (result == null) {
            // 什么都没改（绑定已消失 / 服务端未就绪），别报「已刷新」
            sendOrBroadcast(player, Component.translatable(K_REFRESH_FAILURE));
            return 0;
        }

        // 每侧一行：维度名 + 状态。维度名走原版 dimension.<ns>.<path> 翻译键（官方译名：主世界 / 下界），
        // 模组维度只要补了这条键就能显示本地化名字；没有就退回本项目那两条键 / 注册名。
        String lineA = sideLine(binding.dimA(), result.sideA());
        String lineB = sideLine(binding.dimB(), result.sideB());

        MutableComponent message = Component.translatable(K_REFRESH_DONE,
                String.valueOf(displayIndex), lineA, lineB);

        if (result.anyIncomplete()) {
            // 区域超出扫描预算：只提示「可能漏了」，不展开讲底层逻辑
            message.append("\n").append(Component.translatable(K_REFRESH_INCOMPLETE));
        }

        sendOrBroadcast(player, message);
        return 1;
    }

    /**
     * 反馈里的一行：{@code §f维度名: 状态}。
     */
    private static String sideLine(ResourceKey<Level> dim, PortalBindingService.SideResult side) {
        return "§f" + dimensionLabel(dim) + ": " + sideText(side);
    }

    /**
     * 某一侧在反馈里的显示文本：<b>玩家只关心「有没有门」</b>，
     * 所以这里只给三种结果 —— 坐标 / 暂无门 / 沿用原缓存，其余细节留在日志里。
     *
     * <p>必须在服务端先把翻译键解析成纯文本（{@link ServerTranslations#resolve}）：
     * 专用服务器的 {@code Language} 是空的，直接 {@code Component.getString()} 会把键名打出来。
     */
    private static String sideText(PortalBindingService.SideResult side) {
        if (side.outcome() == PortalBindingService.SideOutcome.UNTOUCHED) {
            // 极端情况：连目标维度都拿不到（主世界 / 地狱实际上碰不到）
            return resolve(K_REFRESH_UNTOUCHED);
        }
        if (side.portal() == null) {
            return resolve(K_REFRESH_NO_PORTAL);
        }
        String coords = formatPos(side.portal());
        return side.outcome() == PortalBindingService.SideOutcome.KEPT
                ? resolve(K_REFRESH_KEPT, coords)
                : resolve(K_REFRESH_FOUND, coords);
    }

    /**
     * 维度显示名（走服务端翻译表），按顺序取第一个能用的：
     * <ol>
     *   <li>原版 {@code dimension.<命名空间>.<路径>} 翻译键 —— 官方译名（主世界 / 下界 / 末地），
     *       模组维度只要在自己的语言文件里补上这条键也能显示本地化名字，不需要改代码；</li>
     *   <li>本项目自己的「主世界 / 地狱」两条键 —— 和 {@code show} 列表的叫法保持一致；</li>
     *   <li>注册名 {@code modid:path} —— 模组维度没翻译时的兜底（不会显示成翻译键）。</li>
     * </ol>
     */
    private static String dimensionLabel(ResourceKey<Level> dim) {
        String vanillaKey = dimensionLanguageKey(dim);
        String translated = ServerTranslations.resolve(Component.translatable(vanillaKey));
        if (!translated.equals(vanillaKey)) {
            // 表里有这条键（没翻出来时 resolve 会把键名原样返回，故用相等判断）
            return translated;
        }
        if (Level.OVERWORLD.equals(dim)) {
            return resolve(K_DIMENSION_OVERWORLD);
        }
        if (Level.NETHER.equals(dim)) {
            return resolve(K_DIMENSION_NETHER);
        }
        return dim.identifier().toString();
    }

    /**
     * 原版维度翻译键：{@code dimension.<命名空间>.<路径>}，例如
     * {@code dimension.minecraft.overworld}、{@code dimension.mymod.my_dim}。
     */
    private static String dimensionLanguageKey(ResourceKey<Level> dim) {
        Identifier id = dim.identifier();
        return "dimension." + id.getNamespace() + "." + id.getPath();
    }

    /**
     * 服务端翻译：按服务端语言把翻译键渲染成可拼进消息的纯文本
     */
    private static String resolve(String key, String... args) {
        return ServerTranslations.resolve(Component.translatable(key, (Object[]) args));
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
