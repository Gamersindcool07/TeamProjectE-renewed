package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.server.ServerLifecycleHooks;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamProjectE;
import net.minecraftforge.common.UsernameCache;

import java.util.*;

/**
 * {@code team_projecte members} - list team members (online/offline/unknown).
 */
public class MembersCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("members")
                .requires(CommandBase::requiresInTeam)
                .executes(MembersCommand::members);
    }

    private static int members(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.info.not_in_team"));
            return 0;
        }

        player.sendSystemMessage(Component.translatable("commands.teamprojecte.members",
                getNames(team.getOwner(), team.getVisibleAll(TeamProjectE.getPlayerUUID(player)))));
        return Command.SINGLE_SUCCESS;
    }

    public static Component getNames(UUID owner, List<UUID> uuids) {
        List<Component> components = new ArrayList<>();
        PlayerList list = ServerLifecycleHooks.getCurrentServer().getPlayerList();
        for (UUID uuid : uuids) {
            MutableComponent c;
            ServerPlayer p = list != null ? list.getPlayer(uuid) : null;
            if (p != null) {
                c = p.getName().copy().withStyle(ChatFormatting.GREEN)
                        .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("commands.teamprojecte.members.member_online"))));
            } else if (UsernameCache.containsUUID(uuid)) {
                c = Component.literal(UsernameCache.getLastKnownUsername(uuid)).withStyle(ChatFormatting.RED)
                        .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("commands.teamprojecte.members.member_offline"))));
            } else {
                c = Component.literal(uuid.toString()).withStyle(ChatFormatting.GRAY)
                        .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("commands.teamprojecte.members.member_unknown"))));
            }
            if (uuid.equals(owner)) c = c.withStyle(ChatFormatting.BOLD);
            components.add(c);
        }
        return ComponentUtils.formatList(components, c -> c);
    }
}
