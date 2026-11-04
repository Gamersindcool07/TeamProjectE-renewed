package cn.leomc.teamprojecte.commands;

import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamProjectE;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import java.util.List;
import java.util.UUID;

/**
 * {@code team_projecte rename <name>} - rename the team (owner only).
 */
public class RenameCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("rename")
                .requires(CommandBase::requiresOwner)
                .then(Commands.argument("value", StringArgumentType.greedyString())
                        .suggests(CommandBase::suggestTeamNames)
                        .executes(RenameCommand::rename));
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null || !checkOwner(team, player)) {
            if (team == null) {
                player.sendSystemMessage(Component.translatable("commands.teamprojecte.leave.not_in_team").withStyle(ChatFormatting.RED));
            } else {
                player.sendSystemMessage(Component.translatable("commands.teamprojecte.not_owner").withStyle(ChatFormatting.RED));
            }
            return Command.SINGLE_SUCCESS;
        }

        String value = StringArgumentType.getString(ctx, "value").trim();
        if (value.equals(team.getName())) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.settings.already.name", value).withStyle(ChatFormatting.RED));
            return Command.SINGLE_SUCCESS;
        }

        if (!TPTeam.isValidTeamName(value)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.create.invalid_name", value));
            return 0;
        }
        if (TPTeam.isNameTaken(value) && !value.equalsIgnoreCase(team.getName())) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.create.name_taken", value));
            return 0;
        }
        team.setName(value);
        sendSuccess(ctx, Component.translatable("commands.teamprojecte.rename.success", value));
        return Command.SINGLE_SUCCESS;
    }
}
