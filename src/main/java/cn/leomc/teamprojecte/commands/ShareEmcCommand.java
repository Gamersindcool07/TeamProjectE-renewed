package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import cn.leomc.teamprojecte.TPTeam;
import com.mojang.brigadier.Command;
import static cn.leomc.teamprojecte.TPCommand.checkOwner;
import java.util.UUID;

/**
 * {@code team_projecte settings share_emc [value]} - share or receive projecte EMC.
 */
public class ShareEmcCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("share_emc")
                .executes(ShareEmcCommand::query)
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .suggests(CommandBase::suggestBoolean)
                        .requires(CommandBase::requiresOwner)
                        .executes(ShareEmcCommand::set));
    }

    private static int query(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.info.not_in_team"));
            return 0;
        }
        // Toggleable: owner running bare `share_emc` flips the setting; others query it.
        if (checkOwner(team, player)) {
            team.setShareEMC(!team.isSharingEMC());
            sendSuccess(ctx, Component.translatable("commands.teamprojecte.settings.toggle.sharing_emc." + team.isSharingEMC()));
            return Command.SINGLE_SUCCESS;
        }
        sendQuery(ctx, Component.translatable("commands.teamprojecte.settings.query.sharing_emc." + team.isSharingEMC()));
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
        if (team.isSharingEMC() == value) {
            sendSuccess(ctx, Component.translatable("commands.teamprojecte.settings.already.sharing_emc"));
            return Command.SINGLE_SUCCESS;
        }
        team.setShareEMC(value);
        sendSuccess(ctx, Component.translatable("commands.teamprojecte.settings.set.sharing_emc." + team.isSharingEMC()));
        return Command.SINGLE_SUCCESS;
    }
}
