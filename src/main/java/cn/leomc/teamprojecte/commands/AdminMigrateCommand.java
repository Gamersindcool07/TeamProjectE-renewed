package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code team_projecte admin migrate} - force (idempotent) re-migration of NBT teams to SQL.
 * Operator only (permission level 2).
 */
public class AdminMigrateCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("admin")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("migrate")
                        .executes(AdminMigrateCommand::confirm)
                        .then(Commands.literal("confirm").executes(AdminMigrateCommand::migrate)));
    }

    private static int confirm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Component msg = Component.translatable("commands.teamprojecte.admin.migrate.confirm_prompt")
                .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/team_projecte admin migrate confirm")));
        sendFailure(ctx, msg);
        return 0;
    }

    private static int migrate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String actor = ctx.getSource().getEntity() != null ? ctx.getSource().getEntity().getName().getString() : ctx.getSource().getTextName();
        cn.leomc.teamprojecte.TeamProjectE.LOGGER.info("Admin migrate requested by {}", actor);
        int count = cn.leomc.teamprojecte.persistence.MigrationUtil.forceMigrate();
        if (count <= 0) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.admin.migrate.not_sql"));
            return 0;
        }
        sendSuccess(ctx, Component.translatable("commands.teamprojecte.admin.migrate.success", count));
        return Command.SINGLE_SUCCESS;
    }
}
