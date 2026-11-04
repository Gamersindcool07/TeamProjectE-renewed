package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamProjectE;
import com.mojang.brigadier.Command;

/**
 * {@code team_projecte create <name>} - create a new team for the player.
 * The name is required (Requirement 2): Brigadier rejects the command without it.
 */
public class CreateCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("create")
                .requires(CommandBase::requiresPlayer)
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(CommandBase::suggestTeamNames)
                        .executes(CreateCommand::create));
    }

    private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        UUID uuid = TeamProjectE.getPlayerUUID(player);

        if (TPTeam.getTeamByMember(uuid) != null) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.create.already_exist"));
            return Command.SINGLE_SUCCESS;
        }

        String name = StringArgumentType.getString(ctx, "name").trim();
        if (!TPTeam.isValidTeamName(name)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.create.invalid_name", name));
            return Command.SINGLE_SUCCESS;
        }
        if (TPTeam.isNameTaken(name)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.create.name_taken", name));
            return Command.SINGLE_SUCCESS;
        }

        TPTeam.createTeam(uuid, name);
        sendSuccess(ctx, Component.translatable("commands.teamprojecte.create.success", name));
        return Command.SINGLE_SUCCESS;
    }
}
