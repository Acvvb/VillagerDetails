package com.villagerdetails.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.villagerdetails.util.VillagerBedUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Optional;
import java.util.UUID;

public class VillageCommand {

    /**
     * 注册 /village 指令及其子命令
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("village")
                        // 子命令：/village get <uuid>
                        .then(Commands.literal("get")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .executes(VillageCommand::executeGet)
                                )
                        )
                        // 子命令：/village set <uuid> <x> <y> <z>
                        .then(Commands.literal("set")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(VillageCommand::executeSet)
                                                        )
                                                )
                                        )
                                )
                        )
        );
    }

    /**
     * 执行 /village get <uuid> 的逻辑
     */
    private static int executeGet(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        UUID villagerUuid = UuidArgument.getUuid(context, "uuid");
        CommandSourceStack source = context.getSource();

        Villager foundVillager = findVillager(source, villagerUuid);
        if (foundVillager == null) {
            source.sendFailure(Component.literal("未找到 UUID 为 " + villagerUuid + " 的村民"));
            return 0;
        }

        Optional<GlobalPos> homeOpt = foundVillager.getBrain().getMemory(MemoryModuleType.HOME);

        if (homeOpt.isEmpty()) {
            source.sendFailure(Component.literal("该村民没有绑定床"));
            return 0;
        }

        GlobalPos homePos = homeOpt.get();
        String msg = String.format(
                "村民 %s 绑定的床位于 [%s]",
                villagerUuid,
                homePos.pos()
        );
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /**
     * 执行 /village set <uuid> <x> <y> <z> 的逻辑
     */
    private static int executeSet(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        UUID villagerUuid = UuidArgument.getUuid(context, "uuid");
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        CommandSourceStack source = context.getSource();

        // 使用公共方法查找村民
        Villager foundVillager = findVillager(source, villagerUuid);
        if (foundVillager == null) {
            source.sendFailure(Component.literal("未找到 UUID 为 " + villagerUuid + " 的村民"));
            return 0;
        }

        // 从村民所在维度获取 ServerLevel（findVillager 已记录 level）
        ServerLevel villagerLevel = (ServerLevel) foundVillager.level();

        BlockPos bedPos = new BlockPos(x, y, z);

        return VillagerBedUtils.changeVillagerBed(villagerLevel, source.getPlayer(), foundVillager, bedPos) ? 1 :0;
    }

    // ==================== 公共方法 ====================

    /**
     * 跨所有维度查找指定 UUID 的村民
     *
     * @param source       命令源
     * @param villagerUuid 目标村民 UUID
     * @return 找到的 Villager，未找到返回 null
     */
    private static Villager findVillager(CommandSourceStack source, UUID villagerUuid) {
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Villager villager && villager.getUUID().equals(villagerUuid)) {
                    return villager;
                }
            }
        }
        return null;
    }
}