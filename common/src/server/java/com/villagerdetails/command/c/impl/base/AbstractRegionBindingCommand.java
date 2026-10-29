package com.villagerdetails.command.c.impl.base;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.command.c.server.RegisterServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * 「区域绑定」类规则命令的抽象基类（和 {@link AbstractRuleCommand} 一个思路）：
 *
 * <pre>
 *   /ec c &lt;规则&gt;               → show
 *   /ec c &lt;规则&gt; set           → 把当前选区缓存成「本维度待绑定」（可选，{@link #supportsSet()}）
 *   /ec c &lt;规则&gt; bind          → 把缓存的选区绑成一条区域
 *   /ec c &lt;规则&gt; show          → 列出所有已绑定区域
 *   /ec c &lt;规则&gt; view &lt;序号&gt;   → 把某条区域推给客户端高亮
 *   /ec c &lt;规则&gt; delete &lt;序号&gt; → 删除某条区域
 *   /ec c &lt;规则&gt; delete &lt;序号&gt; &lt;版本&gt; → 同上，{@code show} 按钮点的就是这个形式
 *   /ec c &lt;规则&gt; clear         → 清空全部区域（可选，{@link #supportsClear()}）
 * </pre>
 *
 * <p>命令树、参数解析、「不是玩家」提示这些纯机械的东西全在基类里；
 * <b>子类只描述「用哪个字面量」「挂哪些子命令」和「业务交给谁」</b> ——
 * 真正的业务（改配置、拼消息、推高亮）一律写在处理器里，命令类不碰。
 *
 * <p><b>按钮过期</b>：聊天里的 [刷新] / [删除] 只带序号，而序号是位置，
 * 别人在你看到列表之后增删一条，序号就指到别的条目上了。支持版本校验的规则
 * （{@link #structureVersion()}）会把「渲染这一刻的结构版本」写进按钮，
 * 点的时候带回来比对，对不上就拒绝执行、让你重新 {@code show}，不会删错/刷错。
 *
 * <p>序号参数按玩家输入的 1 起算原样传给业务方法，减一与越界判断由处理器负责；
 * 这样命令层不用知道「列表怎么存」。
 *
 * <p>两种典型形态：
 * <ul>
 *     <li>单段绑定（一次选区一条区域，可清空）—— 如 {@code PortalPiglinNoSpawn}；</li>
 *     <li>两侧配对（主世界 set 一次、地狱 set 一次，再 bind）—— 如 {@code PortalBinding}。</li>
 * </ul>
 *
 * <p><b>额外子命令</b>：基类只认识 set / bind / show / view / delete / clear 这几个标准形态。
 * 某个子类需要额外子命令（比如 {@code PortalBinding} 的 {@code refresh}）时，
 * 重写 {@link #addExtraSubcommands(LiteralArgumentBuilder)} 自己往 {@code root} 上挂即可——
 * 基类不参与、不认识具体子命令。
 */
public abstract class AbstractRegionBindingCommand implements RegisterServer {

    // ==============================================================
    // 子类差异点
    // ==============================================================

    /**
     * 命令字面量，例如 "PortalPiglinNoSpawn"、"PortalBinding"。
     */
    protected abstract String literalName();

    /**
     * 不是玩家执行时发哪条提示。
     */
    protected abstract String kNotPlayer();

    /**
     * 是否挂 {@code set} 子命令（默认不挂）。
     */
    protected boolean supportsSet() {
        return false;
    }

    /**
     * 是否挂 {@code clear} 子命令（默认挂）。
     */
    protected boolean supportsClear() {
        return true;
    }

    // ==============================================================
    // 业务：全部委托给处理器实现
    // ==============================================================

    /**
     * 只在 {@link #supportsSet()} 为 {@code true} 时被调用。
     */
    protected int set(ServerPlayer player) {
        return 0;
    }

    protected abstract int bind(ServerPlayer player);

    protected abstract int show(ServerPlayer player);

    /**
     * @param displayIndex 玩家输入的序号（1 起）
     */
    protected abstract int view(ServerPlayer player, int displayIndex);

    /**
     * @param displayIndex 玩家输入的序号（1 起）
     */
    protected abstract int delete(ServerPlayer player, int displayIndex);

    /**
     * 只在 {@link #supportsClear()} 为 {@code true} 时被调用。
     */
    protected int clear(ServerPlayer player) {
        return 0;
    }

    // ==============================================================
    // 按钮过期校验（列表结构版本）
    // ==============================================================

    /**
     * 列表的「结构版本」：{@code show} 把它写进每个条目的 [刷新] / [删除] 按钮，
     * 点按钮时再带回来比对。版本对不上 = 「你看到列表之后又有人增删过」，
     * 此时序号已经指向别的条目了。
     *
     * <p>默认 {@code -1} 表示本规则不支持版本校验：按钮不带版本，也不校验，
     * 行为与以前完全一致。子类（例如 {@code PortalBinding}）返回自己配置里的版本号即可。
     */
    protected int structureVersion() {
        return -1;
    }

    /**
     * 按钮带回来的版本是否仍然有效。
     *
     * <p>返回 {@code false} 表示列表已过期，调用方应提示玩家重新 {@code show} 再点，
     * <b>不要再按序号执行任何操作</b>（那个序号现在可能指向别的条目）。
     */
    protected final boolean versionMatches(int expected) {
        int current = structureVersion();
        return current < 0 || current == expected;
    }

    /**
     * 列表过期时的提示键（按钮带回来的版本与当前版本不一致）。
     *
     * <p>直接沿用「区域绑定」这一族规则的通用文案，各规则不需要自己实现。
     */
    protected String kStaleList() {
        return "command.region_binding.failure.stale_list";
    }

    // ==============================================================
    // 扩展点：子类自己挂额外子命令
    // ==============================================================

    /**
     * 子类追加自定义子命令的入口。
     *
     * <p>默认什么都不加。需要额外子命令的子类在这里往 {@code root} 上挂，
     * 基类不参与、不认识具体子命令。
     *
     * <p>钩子在所有标准子命令挂完之后调用；自定义子命令需要自己处理
     * 「不是玩家」的判空（参考 {@link #kNotPlayer()} 用法）。
     */
    protected void addExtraSubcommands(LiteralArgumentBuilder<CommandSourceStack> root) {
    }

    // ==============================================================
    // 命令树
    // ==============================================================

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(literalName())
                .executes(this::showCmd)
                .then(Commands.literal("bind").executes(this::bindCmd))
                .then(Commands.literal("view")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(this::viewCmd)))
                .then(Commands.literal("delete")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(this::deleteCmd)
                                // show 渲染的按钮会多带一个「列表结构版本」，用来发现列表过期
                                .then(Commands.argument("version", IntegerArgumentType.integer(0))
                                        .executes(this::deleteWithVersionCmd))));

        if (supportsSet()) {
            root = root.then(Commands.literal("set").executes(this::setCmd));
        }
        if (supportsClear()) {
            root = root.then(Commands.literal("clear").executes(this::clearCmd));
        }

        // 子类自定义子命令挂载点
        addExtraSubcommands(root);

        return root;
    }

    // ==============================================================
    // 机械包装：取玩家 → 交给业务
    // ==============================================================

    private int setCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx.getSource());
        return player == null ? 0 : set(player);
    }

    private int bindCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx.getSource());
        return player == null ? 0 : bind(player);
    }

    private int showCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx.getSource());
        return player == null ? 0 : show(player);
    }

    private int viewCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx.getSource());
        return player == null ? 0 : view(player, IntegerArgumentType.getInteger(ctx, "index"));
    }

    private int deleteCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx.getSource());
        return player == null ? 0 : delete(player, IntegerArgumentType.getInteger(ctx, "index"));
    }

    /**
     * {@code delete <序号> <结构版本>}：按钮点下来的形式。
     *
     * <p>版本对不上就不执行 —— 序号可能已经指到别的条目上了，宁可让玩家重新
     * {@code show} 一次，也不要删错一条绑定。
     */
    private int deleteWithVersionCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx.getSource());
        if (player == null) {
            return 0;
        }
        if (!versionMatches(IntegerArgumentType.getInteger(ctx, "version"))) {
            sendOrBroadcast(player, Component.translatable(kStaleList(), literalName()));
            return 0;
        }
        return delete(player, IntegerArgumentType.getInteger(ctx, "index"));
    }

    private int clearCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx.getSource());
        return player == null ? 0 : clear(player);
    }

    /**
     * 取执行指令的玩家；不是玩家（控制台 / 命令方块）时发一条提示并返回 {@code null}。
     */
    private ServerPlayer playerOrNull(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            sendOrBroadcast(null, Component.translatable(kNotPlayer()));
        }
        return player;
    }
}