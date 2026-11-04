package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TPCommand;

/**
 * {@code team_projecte settings free_join [value]} - set whether this team accepts free joins.
 * <p>Default is {@code true} (a team accepts anyone who is not already in a team).
 */
public class FreeJoinCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("free_join")
                .executes(FreeJoinCommand::query)
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .suggests(CommandBase::suggestBoolean)
                        .requires(CommandBase::requiresOwner)
                        .executes(FreeJoinCommand::set));
    }

    private static int query(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.info.not_in_team"));
            return 0;
        }
        // Toggleable: owner running bare `free_join` flips it; others query it.
        if (checkOwner(team, player)) {
            team.setFreeJoin(!team.isFreeJoin());
            sendSuccess(ctx, Component.translatable("commands.teamprojecte.settings.toggle.free_join." + team.isFreeJoin()));
            return Command.SINGLE_SUCCESS;
        }
        sendQuery(ctx, Component.translatable("commands.teamprojecte.settings.query.free_join." + team.isFreeJoin()));
        return Command.SINGLE_SUCCESS;
    }

    private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
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

        boolean value = BoolArgumentType.getBool(ctx, "value");
        if (team.isFreeJoin() == value) {
            sendSuccess(ctx, Component.translatable("commands.teamprojecte.settings.already.free_join"));
            return Command.SINGLE_SUCCESS;
        }
        team.setFreeJoin(value);
        sendSuccess(ctx, Component.translatable("commands.teamprojecte.settings.set.free_join." + team.isFreeJoin()));
        return Command.SINGLE_SUCCESS;
    }
}
