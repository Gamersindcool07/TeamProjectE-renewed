package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import cn.leomc.teamprojecte.TeamChangeEvent;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamProjectE;
import net.minecraftforge.eventbus.api.Event;
import java.util.UUID;

import net.minecraftforge.common.MinecraftForge;
import java.util.List;
import cn.leomc.teamprojecte.TPCommand;

/**
 * {@code team_projecte kick <members>} - kick players from the team.
 */
public class KickCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("kick")
                .requires(CommandBase::requiresOwner)
                .then(Commands.argument("members", EntityArgument.players())
                        .suggests(KickCommand::suggestTeamMembers)
                        .executes(KickCommand::kick));
    }

    private static int kick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
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

        List<ServerPlayer> kick = EntityArgument.getPlayers(ctx, "members").stream()
                .filter(p -> team.getMembers().contains(TeamProjectE.getPlayerUUID(p)))
                .toList();

        kick.forEach(p -> {
            team.removeMember(TeamProjectE.getPlayerUUID(p));
            p.sendSystemMessage(Component.translatable("commands.teamprojecte.kicked").withStyle(ChatFormatting.RED));
            UUID uuid = TeamProjectE.getPlayerUUID(p);
            cn.leomc.teamprojecte.TeamProjectE.LOGGER.info("Kick: {} kicked {} from {}", player.getName().getString(), p.getName().getString(), team.getUUID());
            postTeamMemberChangeEvent(uuid, team, null);
        });
        if (!kick.isEmpty()) {
            sendSuccess(ctx, Component.translatable("commands.teamprojecte.kick.success", kick.size()));
        } else {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.players_not_found"));
        }
        return kick.size();
    }

    protected static void postTeamMemberChangeEvent(UUID playerUUID, TPTeam oldTeam, TPTeam newTeam) {
        MinecraftForge.EVENT_BUS.post(new TeamChangeEvent(playerUUID, oldTeam, newTeam));
    }
}
