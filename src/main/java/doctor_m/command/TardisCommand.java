package doctor_m.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /doctor_m} 命令树。
 *
 * <h2>本类只负责"结构"</h2>
 * 这里只声明命令层级与参数，业务实现按领域分散在：
 * <ul>
 *   <li>{@link TardisLifecycleCommands} —— 创建 / 传送 / 删除 / 查询 / 列表</li>
 *   <li>{@link TardisInteractionCommands} —— 门开关、外观 / 内饰界面</li>
 *   <li>{@link TardisFlightCommands} —— 目的地 / 油门 / 制动</li>
 *   <li>{@link TardisCommandSupport} —— 参数建议、执行器封装、ID / 维度解析</li>
 *   <li>{@link TardisDebugCommand} —— {@code debug} 子命令</li>
 * </ul>
 *
 * <p>「附近」与「按 ID」两种寻址都通过 {@code executeNearby} / {@code executeById}
 * 统一处理，业务方法不必重复解析目标。
 */
public final class TardisCommand {

    private TardisCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("doctor_m")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("tardis")
                        .then(Commands.literal("create")
                                .executes(TardisLifecycleCommands::createTardis))
                        .then(Commands.literal("tp")
                                .executes(TardisLifecycleCommands::tpNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TardisCommandSupport.TARDIS_IDS)
                                        .executes(TardisLifecycleCommands::tpById)))
                        .then(Commands.literal("release")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TardisCommandSupport.TARDIS_IDS)
                                        .executes(TardisLifecycleCommands::releaseById)))
                        .then(Commands.literal("info")
                                .executes(TardisLifecycleCommands::infoNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TardisCommandSupport.TARDIS_IDS)
                                        .executes(TardisLifecycleCommands::infoById)))
                        .then(Commands.literal("list")
                                .executes(TardisLifecycleCommands::listAll))
                        .then(Commands.literal("door")
                                .then(Commands.literal("open")
                                        .executes(ctx -> TardisCommandSupport.executeNearby(ctx,
                                                (s, p, d) -> TardisInteractionCommands.setDoor(s, d, true)))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TardisCommandSupport.TARDIS_IDS)
                                                .executes(ctx -> TardisCommandSupport.executeById(ctx,
                                                        (s, p, d) -> TardisInteractionCommands.setDoor(s, d, true)))))
                                .then(Commands.literal("close")
                                        .executes(ctx -> TardisCommandSupport.executeNearby(ctx,
                                                (s, p, d) -> TardisInteractionCommands.setDoor(s, d, false)))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TardisCommandSupport.TARDIS_IDS)
                                                .executes(ctx -> TardisCommandSupport.executeById(ctx,
                                                        (s, p, d) -> TardisInteractionCommands.setDoor(s, d, false))))))
                        .then(Commands.literal("appearance")
                                .executes(TardisInteractionCommands::openAppearanceNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TardisCommandSupport.TARDIS_IDS)
                                        .executes(TardisInteractionCommands::openAppearanceById)))
                        .then(Commands.literal("interior")
                                .executes(TardisInteractionCommands::openInteriorNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TardisCommandSupport.TARDIS_IDS)
                                        .executes(TardisInteractionCommands::openInteriorById)))
                        .then(Commands.literal("dest")
                                // 形式一：x y z [dim] —— 作用于上下文 TARDIS
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(TardisFlightCommands::setDestNearby)
                                                        .then(Commands.argument("dim", StringArgumentType.word())
                                                                .suggests(TardisCommandSupport.DIMENSION_IDS)
                                                                .executes(TardisFlightCommands::setDestNearbyWithDim)))))
                                // 形式二：id x y z [dim] —— 显式指定 TARDIS
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TardisCommandSupport.TARDIS_IDS)
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                                .executes(TardisFlightCommands::setDestById)
                                                                .then(Commands.argument("dim", StringArgumentType.word())
                                                                        .suggests(TardisCommandSupport.DIMENSION_IDS)
                                                                        .executes(TardisFlightCommands::setDestByIdWithDim)))))))
                        .then(Commands.literal("throttle")
                                .then(Commands.argument("tier", IntegerArgumentType.integer(0, 4))
                                        .executes(ctx -> TardisCommandSupport.executeNearby(ctx,
                                                (s, p, d) -> TardisFlightCommands.setThrottle(s, d,
                                                        IntegerArgumentType.getInteger(ctx, "tier"))))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TardisCommandSupport.TARDIS_IDS)
                                                .executes(ctx -> TardisCommandSupport.executeById(ctx,
                                                        (s, p, d) -> TardisFlightCommands.setThrottle(s, d,
                                                                IntegerArgumentType.getInteger(ctx, "tier")))))))
                        .then(Commands.literal("brake")
                                .then(Commands.literal("on")
                                        .executes(ctx -> TardisCommandSupport.executeNearby(ctx,
                                                (s, p, d) -> TardisFlightCommands.setParkingBrake(s, d, true)))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TardisCommandSupport.TARDIS_IDS)
                                                .executes(ctx -> TardisCommandSupport.executeById(ctx,
                                                        (s, p, d) -> TardisFlightCommands.setParkingBrake(s, d, true)))))
                                .then(Commands.literal("off")
                                        .executes(ctx -> TardisCommandSupport.executeNearby(ctx,
                                                (s, p, d) -> TardisFlightCommands.setParkingBrake(s, d, false)))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TardisCommandSupport.TARDIS_IDS)
                                                .executes(ctx -> TardisCommandSupport.executeById(ctx,
                                                        (s, p, d) -> TardisFlightCommands.setParkingBrake(s, d, false))))))
                ));

        TardisDebugCommand.register(dispatcher, root);
    }
}
