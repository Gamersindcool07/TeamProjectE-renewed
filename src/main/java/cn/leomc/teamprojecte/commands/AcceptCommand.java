package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.arguments.UuidArgument;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamProjectE;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;

/**
 * {@code team_projecte accept <team>} - accept a team invitation.
 */
public class AcceptCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("accept")
                .requires(CommandBase::requiresNotInTeam)
                .then(Commands.argument("team", UuidArgument.uuid())
                        .suggests(AcceptCommand::suggestInvitations)
                        .executes(AcceptCommand::accept));
    }

    private static int accept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        UUID uuid = UuidArgument.getUuid(ctx, "team");

        UUID invitee = TeamProjectE.getPlayerUUID(player);
        TPTeam currentTeam = TPTeam.getTeamByMember(invitee);
        if (currentTeam != null && currentTeam.getOwner().equals(invitee)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.leave.owner_blocked"));
            return 0;
        }
        if (!consumeInvitation(invitee, uuid)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.invitation.not_found"));
            return -1;
        }

        TPTeam team = TPTeam.getTeam(uuid);
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.team_not_found"));
            return -1;
        }

        if (team.getAll().contains(TeamProjectE.getPlayerUUID(player))) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.join.already_in_team"));
            return -1;
        }
        TPTeam originalTeam = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
        if (originalTeam != null) {
            team.addMemberWithKnowledge(originalTeam, player);
        } else {
            // No prior team: the existing addMemberWithKnowledge path does not
            // run, so transfer the player's EMC here. Read + zero the source
            // before addMember (membership changes capability routing).
            java.math.BigInteger joiningEmc = TPTeam.readAndZeroPlayerEmc(player);
            team.addMember(TeamProjectE.getPlayerUUID(player));
            team.transferJoiningPlayerEmc(joiningEmc, TeamProjectE.getPlayerUUID(player));
        }

        sendSuccess(ctx, Component.translatable("commands.teamprojecte.invite.accepted").withStyle(ChatFormatting.GREEN));
        Component joined = Component.translatable("commands.teamprojecte.joined_team", player.getDisplayName()).withStyle(ChatFormatting.GREEN);
        TeamProjectE.getAllOnline(team.getAll()).forEach(p -> p.sendSystemMessage(joined));
        postTeamMemberChangeEvent(uuid, originalTeam, team);
        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggestInvitations(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) throws CommandSyntaxException {
        try {
            ServerPlayer player = getPlayer(ctx);
            UUID key = player.getGameProfile().getId();
            List<String> ids = new ArrayList<>(invitationsFor(key).stream().map(UUID::toString).toList());
            if (ids.isEmpty()) {
                SharedSuggestionProvider.suggest(new String[0], builder);
                return builder.buildFuture();
            }
            String[] names = ids.toArray(String[]::new);
            SharedSuggestionProvider.suggest(names, builder);
        } catch (Exception e) {
            SharedSuggestionProvider.suggest(new String[0], builder);
        }
        return builder.buildFuture();
    }
}
