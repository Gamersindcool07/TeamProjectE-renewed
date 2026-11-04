package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import cn.leomc.teamprojecte.TPCommand;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamChangeEvent;
import cn.leomc.teamprojecte.TPSavedData;
import cn.leomc.teamprojecte.TeamProjectE;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/**
 * {@code team_projecte delete} - delete the team (owner only).
 * <p>
 * Owner-only. Splits the team EMC fairly among all members, then removes the
 * team. The shared knowledge pool is discarded with the team.
 */
public class DeleteCommand extends CommandBase {

    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CONFIRM_TTL_MS = 30_000L;

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("delete")
                .requires(CommandBase::requiresOwner)
                .executes(DeleteCommand::confirm)
                .then(Commands.literal("confirm").executes(DeleteCommand::delete));
    }

    private static int confirm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        PENDING.put(TeamProjectE.getPlayerUUID(player), System.currentTimeMillis() + CONFIRM_TTL_MS);
        Component msg = Component.translatable("commands.teamprojecte.delete.confirm_prompt")
                .withStyle(style -> style.applyFormat(ChatFormatting.RED)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/team_projecte delete confirm")));
        sendFailure(ctx, msg);
        return 0;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);
        TPTeam team = getTeam(ctx);
        if (team == null || !checkOwner(team, player)) {
            if (team == null) {
                player.sendSystemMessage(Component.translatable("commands.teamprojecte.delete.not_in_team").withStyle(ChatFormatting.RED));
            } else {
                player.sendSystemMessage(Component.translatable("commands.teamprojecte.delete.not_owner").withStyle(ChatFormatting.RED));
            }
            return Command.SINGLE_SUCCESS;
        }

        Long exp = PENDING.remove(TeamProjectE.getPlayerUUID(player));
        if (exp == null || exp < System.currentTimeMillis()) {
            sendFailure(ctx, Component.translatable("commands.teamprojecte.delete.confirm_expired"));
            return 0;
        }
        cn.leomc.teamprojecte.TeamProjectE.LOGGER.info("Delete team confirmed: {} team {}", player.getName().getString(), team.getUUID());
        UUID owner = team.getOwner();
        List<UUID> all = team.getAll();

        // 1. Split the shared team EMC fairly among all members
        if (team.isSharingEMC()) {
            team.splitEmcFairly(owner);
        }

        // 2. Remove the team (shared knowledge pool is discarded with the team;
        // personal knowledge stays with each member as it was stored).
        cn.leomc.teamprojecte.persistence.TeamDataStore store = TPSavedData.getStore();
        if (store != null) {
            try { store.deleteTeam(team.getUUID()); } catch (Exception ignored) {}
        }
        TPSavedData.getData().teams.remove(team.getUUID());
        TPSavedData.getData().setDirty();

        // 3. Notify
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.delete.success").withStyle(ChatFormatting.GREEN));
        TeamProjectE.getAllOnline(all).forEach(p -> p.sendSystemMessage(
                Component.translatable("commands.teamprojecte.delete.by", player.getDisplayName()).withStyle(ChatFormatting.GREEN)));

        return Command.SINGLE_SUCCESS;
    }
}
