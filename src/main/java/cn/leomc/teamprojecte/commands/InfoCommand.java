package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.UUID;
import java.util.List;

import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamProjectE;
import com.mojang.brigadier.Command;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.common.UsernameCache;

/**
 * {@code team_projecte info} - show detailed team information.
 */
public class InfoCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("info")
                .requires(CommandBase::requiresInTeam)
                .executes(InfoCommand::info);
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.not_in_team").withStyle(ChatFormatting.RED));
            return Command.SINGLE_SUCCESS;
        }

        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.team",
                team.getUUID().toString().substring(0, 8), team.getName()).withStyle(ChatFormatting.GREEN));

        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.owner",
                getOwnerName(team.getOwner())).withStyle(ChatFormatting.YELLOW));
        java.util.List<java.util.UUID> visible = team.getVisibleAll(TeamProjectE.getPlayerUUID(player));
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.member_count",
                visible.size()).withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.members",
                getMemberList(team.getOwner(), visible)));
        if (team.isVanished(TeamProjectE.getPlayerUUID(player))) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.vanish.on").withStyle(ChatFormatting.YELLOW));
        }
        // No parent YELLOW style: it can recolor the True/False args. Bool
        // components carry their own GREEN/RED styles and must win.
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.settings",
                bool(team.isSharingEMC()), bool(team.isSharingKnowledge()), bool(team.isFreeJoin())));

        // Team EMC: shared pool when sharing, otherwise total across members.
        BigInteger teamEmc = team.isSharingEMC()
                ? team.getEmc(team.getOwner())
                : team.getAll().stream().map(team::getEmc).reduce(BigInteger.ZERO, BigInteger::add);
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.team_emc",
                teamEmc.toString()).withStyle(ChatFormatting.YELLOW));

        // Shared knowledge: shared set size when sharing, otherwise aggregate distinct count (private).
        int knowledgeCount;
        Component knowledgeSuffix;
        if (team.isSharingKnowledge()) {
            knowledgeCount = team.getKnowledge(Util.NIL_UUID).size();
            knowledgeSuffix = team.hasFullKnowledge(Util.NIL_UUID)
                    ? Component.translatable("commands.teamprojecte.info.full_knowledge_suffix")
                    : Component.literal("");
        } else {
            knowledgeCount = team.getTeamKnowledge().size();
            knowledgeSuffix = Component.translatable("commands.teamprojecte.info.private_knowledge_suffix");
        }
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.team_knowledge",
                knowledgeCount, knowledgeSuffix).withStyle(ChatFormatting.YELLOW));

        return Command.SINGLE_SUCCESS;
    }

    private static Component getOwnerName(UUID owner) {
        PlayerList list = ServerLifecycleHooks.getCurrentServer().getPlayerList();
        ServerPlayer p = list != null ? list.getPlayer(owner) : null;
        if (p != null) return p.getDisplayName();
        if (UsernameCache.containsUUID(owner)) return Component.literal(UsernameCache.getLastKnownUsername(owner)).withStyle(ChatFormatting.RED);
        return Component.literal(owner.toString()).withStyle(ChatFormatting.GRAY);
    }

    private static Component getMemberList(UUID owner, List<UUID> uuids) {
        return MembersCommand.getNames(owner, uuids);
    }

    private static Component bool(boolean v) {
        return Component.literal(v ? "True" : "False")
                .withStyle(v ? ChatFormatting.GREEN : ChatFormatting.RED);
    }
}
