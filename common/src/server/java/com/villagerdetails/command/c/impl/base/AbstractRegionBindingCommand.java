package com.villagerdetails.command.c.impl.base;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.command.c.server.RegisterServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * 「区域绑定」类规则命令的抽象基类（和 {@link AbstractRuleCommand} 一个思路）：
 *
 * <pre>
 *   /ec c &lt;规则&gt;               → show
 *   /ec c &lt;规则&gt; bind          → 把缓存的选区绑成一条区域
 *   /ec c &lt;规则&gt; show          → 列出所有已绑定区域（每条前面有 #ID）
 *   /ec c &lt;规则&gt; view &lt;id&gt;     → 把某条区域推给客户端高亮
 *   /ec c &lt;规则&gt; delete &lt;id&gt;   → 删除某条区域
 *   /ec c &lt;规则&gt; set           → 把当前选区缓存成「本维度待绑定」（可选，{@link #addSetCommand}）
 *   /ec c &lt;规则&gt; clear         → 清空全部区域（可选，{@link #addClearCommand}）
 * </pre>
 *
 * <p>命令树、参数解析、「不是玩家」提示这些纯机械的东西全在基类里；
 * <b>子类只描述「用哪个字面量」「挂哪些子命令」和「业务交给谁」</b> ——
 * 真正的业务（改配置、拼消息、推高亮）一律写在处理器里，命令类不碰。
 *
 * <p><b>{@code <id>} 是自增数字 ID</b>（{@code "1"}、{@code "2"}…），由配置层分配，
 * 只增不减、删除不回收，所以「别人增删之后按钮指到别的条目」这种事从根上不存在，
 * 也就不需要列表版本号那套校验。{@code view} / {@code delete} 两个参数都挂
 * {@link IdSuggestionProvider}：按 Tab 会列出当前存在的 ID（{@link #availableIds()} 提供）。
 *
 * <p>ID 按<b>字符串</b>原样传给业务方法，不在这里做数字解析 ——
 * 「ID 不存在」由处理器按「查不到」处理并给提示，命令层不用知道列表怎么存。
 *
 * <p>两种典型形态：
 * <ul>
 *     <li>单段绑定（一次选区一条区域，可清空）—— 如 {@code PortalPiglinNoSpawn}；</li>
 *     <li>两侧配对（主世界 set 一次、地狱 set 一次，再 bind）—— 如 {@code PortalBinding}。</li>
 * </ul>
 *
 * <p><b>额外子命令</b>：基类只认识 bind / show / view / delete 这几个标准形态，
 * 外加 set / clear 两个 helper。某个子类需要别的子命令（比如 {@code PortalBinding} 的
 * {@code refresh}）时，重写 {@link #addExtraSubcommands(LiteralArgumentBuilder)}
 * 自己往 {@code root} 上挂即可——基类不参与、不认识具体子命令。
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
     * 当前全部可用 ID，按展示顺序；供 Tab 补全用。
     *
     * <p>每次按 Tab 都重新问一遍（{@link IdSuggestionProvider} 里是 {@code Supplier}），
     * 所以删掉 {@code #2} 之后补全里立刻就没有 {@code 2} 了。
     *
     * <p>子类直接返回自己配置类的 {@code getIds()} 即可。
     */
    protected abstract List<String> availableIds();

    // ==============================================================
    // 业务：全部委托给处理器实现
    // ==============================================================

    /**
     * {@code set} 的业务钩子，只在子类调用 {@link #addSetCommand(LiteralArgumentBuilder)} 时才会被挂出去。
     */
    protected int set(ServerPlayer player) {
        return 0;
    }

    protected abstract int bind(ServerPlayer player);

    protected abstract int show(ServerPlayer player);

    /**
     * @param id 玩家输入的 ID（{@code show} 列表里那个 {@code #ID} 的数字部分）
     */
    protected abstract int view(ServerPlayer player, String id);

    /**
     * @param id 玩家输入的 ID（{@code show} 列表里那个 {@code #ID} 的数字部分）
     */
    protected abstract int delete(ServerPlayer player, String id);

    /**
     * {@code clear} 的业务钩子，只在子类调用 {@link #addClearCommand(LiteralArgumentBuilder)} 时才会被挂出去。
     */
    protected int clear(ServerPlayer player) {
        return 0;
    }

    // ==============================================================
    // 声明式挂载点：调用了哪个 helper，就挂哪个子命令
    // ==============================================================

    /**
     * 子类在 {@code addExtraSubcommands} 里调用本方法，才会挂上 {@code set} 子命令。
     *
     * <p>「不是玩家」的判空与提示由本方法统一处理，子类只写
     * {@link #set(ServerPlayer)} 的业务。
     *
     * <p><b>返回构建器，调用方必须接住</b>：{@code ArgumentBuilder#then} 的契约是
     * 「返回挂好子节点的构建器」，原版 Brigadier 目前是原地修改并返回 {@code this}，
     * 但这是个接口约定、不是保证 —— 换一个会返回新实例的构建器（例如补全/权限包装层）
     * 时，「忽略返回值」就等于把刚挂上的 {@code set} 直接丢掉。统一写成
     * {@code root = addSetCommand(root);} 就不依赖这条隐含假设。
     */
    protected final LiteralArgumentBuilder<CommandSourceStack> addSetCommand(
            LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(Commands.literal("set").executes(ctx -> {
            ServerPlayer player = playerOrNull(ctx.getSource());
            return player == null ? 0 : set(player);
        }));
    }

    /**
     * 子类在 {@code addExtraSubcommands} 里调用本方法，才会挂上 {@code clear} 子命令。
     *
     * <p>「不是玩家」的判空与提示由本方法统一处理，子类只写
     * {@link #clear(ServerPlayer)} 的业务。
     *
     * <p>返回构建器，调用方必须接住（原因同 {@link #addSetCommand}）。
     */
    protected final LiteralArgumentBuilder<CommandSourceStack> addClearCommand(
            LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(Commands.literal("clear").executes(ctx -> {
            ServerPlayer player = playerOrNull(ctx.getSource());
            return player == null ? 0 : clear(player);
        }));
    }

    /**
     * 给自定义子命令用的「ID 补全器」：数据源就是 {@link #availableIds()}。
     *
     * <p>{@code view} / {@code delete} 已经由基类挂好；子类再挂别的带 ID 的子命令
     * （比如 {@code PortalBinding} 的 {@code refresh}）时直接用这个，不用自己 new。
     */
    protected final IdSuggestionProvider idSuggestions() {
        return new IdSuggestionProvider(this::availableIds);
    }

    // ==============================================================
    // 扩展点：子类自己挂额外子命令
    // ==============================================================

    /**
     * 子类追加自定义子命令的入口。
     *
     * <p>默认什么都不加、原样把 {@code root} 还回去。需要 {@code set} / {@code clear} 的子类在这里
     * 调用 {@link #addSetCommand(LiteralArgumentBuilder)} / {@link #addClearCommand(LiteralArgumentBuilder)}；
     * 需要别的子命令（例如 {@code refresh}）则自己往 {@code root} 上挂，
     * 基类不参与、不认识具体子命令。
     *
     * <p><b>必须返回挂完之后的构建器</b>：{@code register()} 会拿返回值覆盖 {@code root}
     * （{@code root = addExtraSubcommands(root);}）。原版 Brigadier 的 {@code then} 是原地修改，
     * 但这属于实现细节；按「返回值才代表最新构建器」写，换任何包装层都不会丢子命令。
     * 子类实现里同样要写成 {@code root = ...}，最后 {@code return root;}。
     *
     * <p>钩子在所有标准子命令挂完之后调用；自定义子命令需要自己处理
     * 「不是玩家」的判空（用 {@link #playerOrNull(CommandSourceStack)}）
     * 和 ID 补全（用 {@link #idSuggestions()}）。
     */
    protected LiteralArgumentBuilder<CommandSourceStack> addExtraSubcommands(
            LiteralArgumentBuilder<CommandSourceStack> root) {
        return root;
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
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(idSuggestions())
                                .executes(this::viewCmd)))
                .then(Commands.literal("delete")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(idSuggestions())
                                .executes(this::deleteCmd)));

        // 子类自定义子命令挂载点（set / clear / refresh 等都在这里声明）。
        // 必须接住返回值：由它决定最终的命令树，别依赖「then 原地修改」这个实现细节。
        root = addExtraSubcommands(root);

        return root;
    }

    // ==============================================================
    // 机械包装：取玩家 → 交给业务
    // ==============================================================

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
        return player == null ? 0 : view(player, StringArgumentType.getString(ctx, "id"));
    }

    private int deleteCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx.getSource());
        return player == null ? 0 : delete(player, StringArgumentType.getString(ctx, "id"));
    }

    /**
     * 取执行指令的玩家；不是玩家（控制台 / 命令方块）时发一条提示并返回 {@code null}。
     *
     * <p>{@code protected} 是给子类的自定义子命令复用的（例如 {@code refresh}），
     * 免得每个子命令都把这段判空再抄一遍。
     */
    protected final ServerPlayer playerOrNull(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            sendOrBroadcast(null, Component.translatable(kNotPlayer()));
        }
        return player;
    }
}
