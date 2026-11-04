package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamProjectE;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * {@code team_projecte join <team_name>} - join a team that has {@code free_join} enabled.
 * <p>
 * The {@code <team_name>} argument is a team <b>name</b> (case-insensitive, may contain
 * spaces) and autocompletes to all teams where {@code free_join} is true.
 * Backward compatibility: a team UUID is also accepted and resolved first.
 * <p>
 * Only works when the player is not already in a team.
 */
public class JoinCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("join")
                .requires(CommandBase::requiresNotInTeam)
                .then(Commands.argument("team", StringArgumentType.greedyString())
                        .suggests(CommandBase::suggestFreeJoinTeams)
                        .executes(JoinCommand::join));
    }

    private static int join(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        UUID playerUUID = TeamProjectE.getPlayerUUID(player);
        String teamName = StringArgumentType.getString(ctx, "team").trim();

        // Backward-compat: a UUID still resolves via getTeam(UUID) first.
        TPTeam team = null;
        try {
            team = TPTeam.getTeam(UUID.fromString(teamName));
        } catch (IllegalArgumentException ignored) {
            // Not a UUID string: fall through to name lookup.
        }

        if (team == null) {
            if (!TPTeam.isValidTeamName(teamName)) {
                sendFailure(ctx, Component.translatable("commands.teamprojecte.join.invalid_name", teamName));
                return Command.SINGLE_SUCCESS;
            }
            team = TPTeam.getTeamByName(teamName);
        }
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.join.not_found"));
            return Command.SINGLE_SUCCESS;
        }
        if (!team.isFreeJoin()) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.join.not_free"));
            return Command.SINGLE_SUCCESS;
        }
        if (TPTeam.getTeamByMember(playerUUID) != null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.join.already_in_team"));
            return Command.SINGLE_SUCCESS;
        }

        // Read + zero the joining player's EMC before addMember: membership
        // changes capability routing, so the source must be captured first.
        java.math.BigInteger joiningEmc = TPTeam.readAndZeroPlayerEmc(player);
        team.addMember(playerUUID);
        team.transferJoiningPlayerEmc(joiningEmc, playerUUID);
        sendSuccess(ctx, Component.translatable("commands.teamprojecte.join.success", team.getName()));
        TeamProjectE.getAllOnline(team.getAll()).forEach(p -> p.sendSystemMessage(
                Component.translatable("commands.teamprojecte.join.joined", player.getDisplayName()).withStyle(ChatFormatting.GREEN)));
        postTeamMemberChangeEvent(playerUUID, null, team);
        return Command.SINGLE_SUCCESS;
    }
}
