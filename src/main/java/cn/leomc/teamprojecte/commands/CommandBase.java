package cn.leomc.teamprojecte.commands;

import cn.leomc.teamprojecte.TeamProjectE;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamChangeEvent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.UsernameCache;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class for all team_projecte commands. Provides shared helpers and the
 * {@code requires} predicates used to gate each command.
 */
public abstract class CommandBase {

    /** Unified invitation store: player -> (team -> expiryEpochMs). Thread-safe, 5-min TTL. */
    protected static final long INVITATION_TTL_MS = 5L * 60L * 1000L;
    protected static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Long>> INVITATIONS = new ConcurrentHashMap<>();

    protected static boolean isInvitationLive(long expiry) { return expiry > System.currentTimeMillis(); }
    protected static void purgeExpired() {
        long now = System.currentTimeMillis();
        INVITATIONS.values().forEach(m -> m.entrySet().removeIf(e -> e.getValue() <= now));
        INVITATIONS.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    protected static void addInvitation(UUID player, UUID team) {
        purgeExpired();
        INVITATIONS.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .put(team, System.currentTimeMillis() + INVITATION_TTL_MS);
    }
    protected static boolean hasInvitation(UUID player, UUID team) {
        var m = INVITATIONS.get(player);
        if (m == null) return false;
        Long exp = m.get(team);
        if (exp == null) return false;
        if (!isInvitationLive(exp)) { m.remove(team, exp); return false; }
        return true;
    }
    /** Atomically consume an invitation; returns true only for the first caller (race-safe). */
    protected static boolean consumeInvitation(UUID player, UUID team) {
        var m = INVITATIONS.get(player);
        if (m == null) return false;
        Long exp = m.remove(team);
        return exp != null && isInvitationLive(exp);
    }
    protected static List<UUID> invitationsFor(UUID player) {
        var m = INVITATIONS.get(player);
        if (m == null) return List.of();
        long now = System.currentTimeMillis();
        return m.entrySet().stream().filter(e -> e.getValue() > now).map(Map.Entry::getKey).toList();
    }
    public static void clearInvitations() { INVITATIONS.clear(); }

    protected static ServerPlayer getPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            return player;
        }
        throw CommandSourceStack.ERROR_NOT_PLAYER.create();
    }

    protected static TPTeam getTeam(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(getPlayer(ctx)));
    }

    protected static boolean requiresPlayer(CommandSourceStack stack) {
        return stack.getEntity() instanceof ServerPlayer;
    }

    protected static boolean requiresInTeam(CommandSourceStack stack) {
        if (stack.getEntity() instanceof ServerPlayer player) {
            return TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player)) != null;
        }
        return false;
    }

    protected static boolean requiresOwner(CommandSourceStack stack) {
        if (stack.getEntity() instanceof ServerPlayer player) {
            TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
            return team != null && TeamProjectE.getPlayerUUID(player).equals(team.getOwner());
        }
        return false;
    }

    protected static boolean requiresNotInTeam(CommandSourceStack stack) {
        if (stack.getEntity() instanceof ServerPlayer player) {
            return TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player)) == null;
        }
        return false;
    }

    protected static boolean checkOwner(TPTeam team, ServerPlayer player) {
        return team != null && TeamProjectE.getPlayerUUID(player).equals(team.getOwner());
    }

    protected static void postTeamMemberChangeEvent(UUID playerUUID, TPTeam oldTeam, TPTeam newTeam) {
        MinecraftForge.EVENT_BUS.post(new TeamChangeEvent(playerUUID, oldTeam, newTeam));
    }

    protected static void sendFailure(CommandContext<CommandSourceStack> ctx, Component msg) {
        ctx.getSource().sendFailure(msg);
    }

    protected static void sendSuccess(CommandContext<CommandSourceStack> ctx, Component msg) {
        ctx.getSource().sendSuccess(() -> msg, true);
    }

    protected static void sendQuery(CommandContext<CommandSourceStack> ctx, Component msg) {
        ctx.getSource().sendSuccess(() -> msg, false);
    }

    protected static CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        try {
            Player player = getPlayer(ctx);
            PlayerList list = ServerLifecycleHooks.getCurrentServer().getPlayerList();
            if (list == null) {
                return SharedSuggestionProvider.suggest(new String[0], builder);
            }
            String[] names = list.getPlayers().stream().map(p -> p.getName().getString()).toArray(String[]::new);
            SharedSuggestionProvider.suggest(names, builder);
        } catch (Exception e) {
            return SharedSuggestionProvider.suggest(new String[0], builder);
        }
        return builder.buildFuture();
    }

    protected static CompletableFuture<Suggestions> suggestTeamMembers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        try {
            Player player = getPlayer(ctx);
            TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
            if (team == null) {
                return SharedSuggestionProvider.suggest(new String[0], builder);
            }
            String[] names = team.getVisibleAll(TeamProjectE.getPlayerUUID(player)).stream()
                    .map(uuid -> {
                        ServerPlayer online = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(uuid);
                        if (online != null) return online.getName().getString();
                        return UsernameCache.getLastKnownUsername(uuid);
                    })
                    .filter(Objects::nonNull)
                    .toArray(String[]::new);
            SharedSuggestionProvider.suggest(names, builder);
        } catch (Exception e) {
            return SharedSuggestionProvider.suggest(new String[0], builder);
        }
        return builder.buildFuture();
    }

    protected static CompletableFuture<Suggestions> suggestFreeJoinTeams(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        try {
            if (ServerLifecycleHooks.getCurrentServer() == null) {
                return SharedSuggestionProvider.suggest(new String[0], builder);
            }
            List<String> names = new ArrayList<>();
            for (TPTeam team : TPTeam.getTeams()) {
                if (!team.isFreeJoin()) continue;
                if (team.getName() != null) names.add(team.getName());
            }
            SharedSuggestionProvider.suggest(names.toArray(new String[0]), builder);
        } catch (Exception e) {
            return SharedSuggestionProvider.suggest(new String[0], builder);
        }
        return builder.buildFuture();
    }

    /** Suggest {@code true} / {@code false} for boolean settings toggles. */
    protected static CompletableFuture<Suggestions> suggestBoolean(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(new String[]{"true", "false"}, builder);
    }

    /** Suggest all team UUIDs (used by join/invite-style args). */
    protected static CompletableFuture<Suggestions> suggestTeams(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        try {
            List<String> ids = new ArrayList<>();
            for (TPTeam team : TPTeam.getTeams()) ids.add(team.getUUID().toString());
            SharedSuggestionProvider.suggest(ids.toArray(new String[0]), builder);
        } catch (Exception e) {
            return SharedSuggestionProvider.suggest(new String[0], builder);
        }
        return builder.buildFuture();
    }

    /** Suggest existing team names (informational completion for name/rename/create args). */
    protected static CompletableFuture<Suggestions> suggestTeamNames(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        try {
            List<String> names = new ArrayList<>();
            for (TPTeam team : TPTeam.getTeams()) names.add(team.getName());
            SharedSuggestionProvider.suggest(names.toArray(new String[0]), builder);
        } catch (Exception e) {
            return SharedSuggestionProvider.suggest(new String[0], builder);
        }
        return builder.buildFuture();
    }
}
