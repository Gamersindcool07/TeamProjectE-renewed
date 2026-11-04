package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import cn.leomc.teamprojecte.TeamProjectE;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import cn.leomc.teamprojecte.TeamChangeEvent;
import cn.leomc.teamprojecte.TeamProjectE;
import cn.leomc.teamprojecte.TPTeam;
import com.mojang.brigadier.Command;
import net.minecraftforge.eventbus.api.Event;
import java.util.UUID;

/**
 * {@code team_projecte leave} - leave the current team.
 */
public class LeaveCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("leave")
                .requires(CommandBase::requiresInTeam)
                .executes(LeaveCommand::leave);
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.leave.not_in_team").withStyle(ChatFormatting.RED));
            return Command.SINGLE_SUCCESS;
        }

        UUID uuid = TeamProjectE.getPlayerUUID(player);
        if (team.getOwner().equals(uuid)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.leave.owner_blocked"));
            cn.leomc.teamprojecte.TeamProjectE.LOGGER.info("Owner-leave blocked: {} team {}", player.getName().getString(), team.getUUID());
            return 0;
        }
        String teamName = team.getName();
        team.removeMember(uuid);
        postTeamMemberChangeEvent(uuid, team, null);
        sendSuccess(ctx, Component.translatable("commands.teamprojecte.leave.success", teamName));
        return Command.SINGLE_SUCCESS;
    }
}
