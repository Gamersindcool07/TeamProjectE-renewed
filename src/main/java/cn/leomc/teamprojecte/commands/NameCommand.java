package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import cn.leomc.teamprojecte.TPTeam;
import com.mojang.brigadier.Command;
import static cn.leomc.teamprojecte.TPCommand.checkOwner;
import java.util.UUID;

/**
 * {@code team_projecte settings name [value]} - rename the team.
 */
public class NameCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("name")
                .executes(NameCommand::query)
                .then(Commands.argument("value", StringArgumentType.greedyString())
                        .suggests(CommandBase::suggestTeamNames)
                        .requires(CommandBase::requiresOwner)
                        .executes(NameCommand::set));
    }

    private static int query(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.info.not_in_team"));
            return 0;
        }
        sendQuery(ctx, Component.translatable("commands.teamprojecte.settings.query.name", team.getName()));
        return Command.SINGLE_SUCCESS;
    }

    private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.info.not_in_team"));
            return 0;
        }
        if (!checkOwner(team, player)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.not_owner"));
            return 0;
        }

        String value = StringArgumentType.getString(ctx, "value").trim();
        if (!TPTeam.isValidTeamName(value)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.create.invalid_name", value));
            return 0;
        }
        if (TPTeam.isNameTaken(value) && !value.equalsIgnoreCase(team.getName())) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.create.name_taken", value));
            return 0;
        }
        if (value.equals(team.getName())) {
            sendSuccess(ctx, Component.translatable("commands.teamprojecte.settings.already.name", value));
            return Command.SINGLE_SUCCESS;
        }
        team.setName(value);
        sendSuccess(ctx, Component.translatable("commands.teamprojecte.settings.set.team_name", value));
        return Command.SINGLE_SUCCESS;
    }
}
