package com.villagerdetails.command.c.server;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;

/**
 * 规则状态补全提供者（类似 {@link RegisterServer} 之于 /ec c 子命令）。
 * <p>
 * 规则在 RuleType 里挂一个实现，/ec &lt;rule&gt; &lt;state&gt; 的状态参数补全即由它接管；
 * 未挂载时回退到 quickSwitches 列表补全。
 */
public interface StateSuggestionServer {

    SuggestionProvider<CommandSourceStack> getStateSuggester();
}
