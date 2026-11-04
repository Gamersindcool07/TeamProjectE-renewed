package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamProjectE;

/**
 * {@code team_projecte vanish [on|off|true|false]} - hide yourself from team
 * listings (members / info) while keeping membership, EMC and knowledge.
 * Bare {@code vanish} toggles; explicit value sets the state.
 */
public class VanishCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("vanish")
                .requires(CommandBase::requiresInTeam)
                .executes(VanishCommand::toggle)
                .then(Commands.literal("on").executes(ctx -> set(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> set(ctx, false)))
                .then(Commands.literal("true").executes(ctx -> set(ctx, true)))
                .then(Commands.literal("false").executes(ctx -> set(ctx, false)));
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.vanish.not_in_team"));
            return 0;
        }
        return set(ctx, !team.isVanished(TeamProjectE.getPlayerUUID(player)));
    }

    private static int set(CommandContext<CommandSourceStack> ctx, boolean hidden) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.vanish.not_in_team"));
            return 0;
        }
        if (team.isVanished(TeamProjectE.getPlayerUUID(player)) == hidden) {
            sendSuccess(ctx, Component.translatable("commands.teamprojecte.vanish.already." + hidden));
            return Command.SINGLE_SUCCESS;
        }
        team.setVanished(TeamProjectE.getPlayerUUID(player), hidden);
        sendSuccess(ctx, Component.translatable("commands.teamprojecte.vanish." + (hidden ? "on" : "off")));
        return Command.SINGLE_SUCCESS;
    }
}
