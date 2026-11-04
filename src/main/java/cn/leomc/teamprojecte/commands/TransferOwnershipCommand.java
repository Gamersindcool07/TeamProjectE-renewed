package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.Command;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import java.util.List;
import java.util.UUID;
import cn.leomc.teamprojecte.TeamChangeEvent;
import cn.leomc.teamprojecte.TeamProjectE;
import cn.leomc.teamprojecte.TPTeam;
import net.minecraftforge.eventbus.api.Event;

/**
 * {@code team_projecte transfer_ownership <member>} - transfer team ownership.
 */
public class TransferOwnershipCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("transfer_ownership")
                .requires(CommandBase::requiresOwner)
                .then(Commands.argument("member", EntityArgument.player())
                        .suggests(TransferOwnershipCommand::suggestTeamMembers)
                        .executes(TransferOwnershipCommand::transferOwnership));
    }

    private static int transferOwnership(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
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

        ServerPlayer newOwner = EntityArgument.getPlayer(ctx, "member");
        UUID newOwnerUUID = TeamProjectE.getPlayerUUID(newOwner);

        if (!team.getAll().contains(newOwnerUUID)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.transfer_ownership.not_in_team"));
            return 0;
        }
        if (team.getOwner().equals(newOwnerUUID)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.transfer_ownership.already_owner"));
            return 0;
        }
        if (team.isVanished(newOwnerUUID)) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.transfer_ownership.vanished"));
            return 0;
        }
        cn.leomc.teamprojecte.TeamProjectE.LOGGER.info("TransferOwnership: {} -> {} team {}", player.getName().getString(), newOwner.getName().getString(), team.getUUID());

        team.transferOwner(newOwnerUUID);
        newOwner.sendSystemMessage(Component.translatable("commands.teamprojecte.transfer_ownership.new_owner").withStyle(ChatFormatting.GREEN));
        sendSuccess(ctx, Component.translatable("commands.teamprojecte.transfer_ownership.success", newOwner.getName()));
        postTeamAttributeChangeEvent(team);
        return Command.SINGLE_SUCCESS;
    }

    private static void postTeamAttributeChangeEvent(TPTeam team) {
        MinecraftForge.EVENT_BUS.post(new TeamChangeEvent(team.getOwner(), team, team));
    }
}
