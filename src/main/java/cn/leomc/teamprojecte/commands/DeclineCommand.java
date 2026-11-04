package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.SharedSuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamProjectE;

/**
 * {@code team_projecte decline <team>} - decline a team invitation.
 */
public class DeclineCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("decline")
                .requires(CommandBase::requiresNotInTeam)
                .then(Commands.argument("team", UuidArgument.uuid())
                        .suggests(DeclineCommand::suggestInvitations)
                        .executes(DeclineCommand::decline));
    }

    private static int decline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        UUID uuid = UuidArgument.getUuid(ctx, "team");

        UUID invitee = TeamProjectE.getPlayerUUID(player);
        if (!consumeInvitation(invitee, uuid)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.invitation.not_found"));
            return -1;
        }

        TPTeam team = TPTeam.getTeam(uuid);
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.team_not_found"));
            return -1;
        }

        player.sendSystemMessage(Component.translatable("commands.teamprojecte.invite.declined").withStyle(ChatFormatting.RED));
        TeamProjectE.getAllOnline(java.util.Collections.singletonList(team.getOwner())).forEach(p ->
                p.sendSystemMessage(Component.translatable("commands.teamprojecte.invitation.declined", player.getDisplayName())));

        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggestInvitations(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) throws CommandSyntaxException {
        try {
            Player player = getPlayer(ctx);
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
