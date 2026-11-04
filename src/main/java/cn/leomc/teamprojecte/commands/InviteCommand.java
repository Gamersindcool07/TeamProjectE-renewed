package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamProjectE;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.UsernameCache;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * {@code team_projecte invite <players>} - invite players to (create) the team.
 */
public class InviteCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("invite")
                .requires(CommandBase::requiresInTeam)
                .then(Commands.argument("players", EntityArgument.players())
                        .suggests(CommandBase::suggestPlayers)
                        .executes(InviteCommand::invite));
    }

    private static int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.invite.not_in_team"));
            return 0;
        }

        UUID inviter = TeamProjectE.getPlayerUUID(player);
        List<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players").stream()
                .filter(p -> {
                    UUID pu = TeamProjectE.getPlayerUUID(p);
                    if (pu.equals(inviter)) return false; // no self-invite
                    if (team.getAll().contains(pu)) return false; // already member
                    if (TPTeam.getTeamByMember(pu) != null) return false; // already in another team
                    if (hasInvitation(pu, team.getUUID())) return false; // dedup / anti-spam
                    return true;
                })
                .distinct()
                .toList();

        Component component = Component.translatable("commands.teamprojecte.invitation",
                player.getDisplayName(),
                Component.translatable("commands.teamprojecte.invite.option.accept")
                        .withStyle(style -> style.applyFormat(ChatFormatting.GREEN)
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/team_projecte accept " + team.getUUID()))),
                Component.translatable("commands.teamprojecte.invite.option.decline")
                        .withStyle(style -> style.applyFormat(ChatFormatting.RED)
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/team_projecte decline " + team.getUUID()))));

        for (ServerPlayer p : targets) {
            addInvitation(TeamProjectE.getPlayerUUID(p), team.getUUID());
            p.sendSystemMessage(component);
        }

        if (!targets.isEmpty()) {
            sendSuccess(ctx, Component.translatable("commands.teamprojecte.invite.success", targets.size()));
        } else {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.players_not_found"));
        }
        return targets.size();
    }

}
