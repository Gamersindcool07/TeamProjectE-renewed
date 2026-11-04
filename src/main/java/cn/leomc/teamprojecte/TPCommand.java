package cn.leomc.teamprojecte;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.UsernameCache;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import cn.leomc.teamprojecte.commands.CommandRegistry;

/**
 * Legacy command tree. Deprecated: {@link TeamProjectE} registers commands via
 * {@link CommandRegistry} (per-command classes such as
 * {@code cn.leomc.teamprojecte.commands.InfoCommand}). This class is kept only
 * because a few helpers (e.g. {@code checkOwner}) are still referenced from
 * {@code commands/*.java}. Do not add new subcommands here.
 *
 * @deprecated use {@link CommandRegistry} / {@code commands/*Command} instead
 */
@Deprecated
public class TPCommand {

    public static final Multimap<UUID, UUID> INVITATIONS = HashMultimap.create();

    /**
     * @deprecated legacy duplicate tree; delegates to {@link CommandRegistry}
     * so any stale caller gets the current {@code InfoCommand} implementation
     * (3-arg {@code info.settings}) instead of the buggy legacy copy.
     */
    @Deprecated
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandRegistry.register(dispatcher);
    }


    private static boolean requiresPlayer(CommandSourceStack stack) {
        return stack.getEntity() instanceof ServerPlayer;
    }


    private static boolean requiresInTeam(CommandSourceStack stack) {
        if (stack.getEntity() instanceof ServerPlayer player) {
            TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
            return team != null;
        }
        return false;
    }

    private static boolean requiresOwner(CommandSourceStack stack) {
        if (!requiresInTeam(stack))
            return false;
        if (stack.getEntity() instanceof ServerPlayer player) {
            TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
            return team != null && TeamProjectE.getPlayerUUID(player).equals(team.getOwner());
        }
        return false;
    }


    private static int transferOwnership(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null || !checkOwner(team, player))
            return 0;

        ServerPlayer newOwner = EntityArgument.getPlayer(context, "member");
        UUID newOwnerUUID = TeamProjectE.getPlayerUUID(newOwner);

        if (!team.getAll().contains(newOwnerUUID)) {
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.transfer_ownership.not_in_team"));
            return 0;
        }
        if (team.getOwner().equals(newOwnerUUID)) {
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.transfer_ownership.already_owner"));
            return 0;
        }

        team.transferOwner(newOwnerUUID);
        newOwner.sendSystemMessage(Component.translatable("commands.teamprojecte.transfer_ownership.new_owner").withStyle(ChatFormatting.GREEN));
        context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.transfer_ownership.success", newOwner.getName()), true);
        postTeamAttributeChangeEvent(team);

        return Command.SINGLE_SUCCESS;
    }

    private static int kick(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null || !checkOwner(team, player))
            return 0;

        List<ServerPlayer> kick = EntityArgument.getPlayers(context, "members").stream()
                .filter(p -> team.getMembers().contains(TeamProjectE.getPlayerUUID(p)))
                .toList();
        kick.forEach(p -> {
            team.removeMember(TeamProjectE.getPlayerUUID(p));
            p.sendSystemMessage(Component.translatable("commands.teamprojecte.kicked").withStyle(ChatFormatting.RED));
            UUID uuid = TeamProjectE.getPlayerUUID(p);
            postTeamMemberChangeEvent(uuid, team, null);
        });

        if (!kick.isEmpty())
            context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.kick.success", kick.size()), true);
        else
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.players_not_found"));

        return kick.size();
    }

    private static int members(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null)
            return 0;

        player.sendSystemMessage(Component.translatable("commands.teamprojecte.members", getNames(team.getOwner(), team.getAll())));
        return Command.SINGLE_SUCCESS;
    }

    private static Component getNames(UUID owner, List<UUID> uuids) {
        List<Component> components = new ArrayList<>();
        PlayerList playerList = ServerLifecycleHooks.getCurrentServer().getPlayerList();

        for (UUID uuid : uuids) {
            MutableComponent component;
            ServerPlayer player = playerList.getPlayer(uuid);
            if (player != null)
                component = player.getName().copy()
                        .withStyle(ChatFormatting.GREEN)
                        .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("commands.teamprojecte.members.member_online"))));
            else if (UsernameCache.containsUUID(uuid))
                component = Component.literal(UsernameCache.getLastKnownUsername(uuid))
                        .withStyle(ChatFormatting.RED)
                        .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("commands.teamprojecte.members.member_offline"))));
            else
                component = Component.literal(uuid.toString())
                        .withStyle(ChatFormatting.GRAY)
                        .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("commands.teamprojecte.members.member_unknown"))));
            if (uuid.equals(owner))
                component.withStyle(ChatFormatting.BOLD);
            components.add(component);
        }
        return ComponentUtils.formatList(components, c -> c);
    }

    private static int leave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null)
            return 0;

        UUID uuid = TeamProjectE.getPlayerUUID(player);
        if (team.getOwner().equals(uuid)) {
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.leave.owner_blocked"));
            return 0;
        }
        team.removeMember(uuid);
        postTeamMemberChangeEvent(uuid, team, null);
        return Command.SINGLE_SUCCESS;
    }

    private static int delete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
        if (team == null) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.delete.not_in_team").withStyle(ChatFormatting.RED));
            return Command.SINGLE_SUCCESS;
        }
        if (!team.getOwner().equals(TeamProjectE.getPlayerUUID(player))) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.delete.not_owner").withStyle(ChatFormatting.RED));
            return Command.SINGLE_SUCCESS;
        }

        UUID owner = team.getOwner();
        List<UUID> allMembers = team.getAll();

        // 1. Split the team EMC fairly among all members
        if (team.isSharingEMC()) {
            team.splitEmcFairly(owner);
        }

        // 2. Remove the team (shared knowledge pool is discarded with the team; personal knowledge stays)
        TPTeam existingTeam = TPTeam.getTeam(team.getUUID());
        if (existingTeam != null) {
            TPSavedData.getData().getTeams().remove(existingTeam.getUUID());
            TPSavedData.getData().invalidateCache(player.getGameProfile().getId());
        }
        TPSavedData.getData().setDirty();

        // 3. Notify
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.delete.success").withStyle(ChatFormatting.GREEN));
        TeamProjectE.getAllOnline(allMembers).forEach(p -> p.sendSystemMessage(
                Component.translatable("commands.teamprojecte.delete.by", player.getDisplayName()).withStyle(ChatFormatting.GREEN)));
        return Command.SINGLE_SUCCESS;
    }

    private static int accept(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        UUID uuid = UuidArgument.getUuid(context, "team");
        UUID playerUUID = TeamProjectE.getPlayerUUID(player);
        TPTeam currentTeam = TPTeam.getTeamByMember(playerUUID);
        if (currentTeam != null && currentTeam.getOwner().equals(playerUUID)) {
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.leave.owner_blocked"));
            return 0;
        }
        if (!INVITATIONS.get(TeamProjectE.getPlayerUUID(player)).contains(uuid)) {
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.invitation.not_found"));
            return -1;
        }

        INVITATIONS.remove(TeamProjectE.getPlayerUUID(player), uuid);

        TPTeam team = TPTeam.getTeam(uuid);
        if (team == null) {
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.team_not_found"));
            return -1;
        }
        TPTeam originalTeam = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
        if (originalTeam != null)
            team.addMemberWithKnowledge(originalTeam, player);
        else {
            java.math.BigInteger joiningEmc = TPTeam.readAndZeroPlayerEmc(player);
            team.addMember(TeamProjectE.getPlayerUUID(player));
            team.transferJoiningPlayerEmc(joiningEmc, TeamProjectE.getPlayerUUID(player));
        }

        context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.invite.accepted").withStyle(ChatFormatting.GREEN), false);
        Component component = Component.translatable("commands.teamprojecte.joined_team", player.getDisplayName()).withStyle(ChatFormatting.GREEN);
        TeamProjectE.getAllOnline(team.getAll()).forEach(p -> p.sendSystemMessage(component));
        postTeamMemberChangeEvent(uuid, originalTeam, team);

        return Command.SINGLE_SUCCESS;
    }


    private static int decline(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        UUID uuid = UuidArgument.getUuid(context, "team");
        if (!INVITATIONS.get(TeamProjectE.getPlayerUUID(player)).contains(uuid)) {
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.invitation.not_found"));
            return -1;
        }

        INVITATIONS.remove(TeamProjectE.getPlayerUUID(player), uuid);

        TPTeam team = TPTeam.getTeam(uuid);
        if (team == null) {
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.team_not_found"));
            return -1;
        }

        player.sendSystemMessage(Component.translatable("commands.teamprojecte.invite.declined").withStyle(ChatFormatting.RED));
        TeamProjectE.getAllOnline(Collections.singletonList(team.getOwner())).forEach(p ->
                p.sendSystemMessage(Component.translatable("commands.teamprojecte.invitation.declined", player.getDisplayName())));

        return Command.SINGLE_SUCCESS;
    }
    private static int create(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        UUID uuid = TeamProjectE.getPlayerUUID(player);
        if (TPTeam.getTeamByMember(uuid) != null) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.create.already_exist"));
            return Command.SINGLE_SUCCESS;
        }
        String name = StringArgumentType.getString(context, "name").trim();
        if (!TPTeam.isValidTeamName(name)) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.create.invalid_name", name));
            return Command.SINGLE_SUCCESS;
        }
        if (TPTeam.isNameTaken(name)) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.create.name_taken", name));
            return Command.SINGLE_SUCCESS;
        }
        TPTeam team = TPTeam.createTeam(uuid, name);
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.create.success", name));
        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> createSuggestionsForInvitation(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) throws CommandSyntaxException {
        Player player = checkPlayer(context);
        return SharedSuggestionProvider.suggest(INVITATIONS.get(TeamProjectE.getPlayerUUID(player)).stream().map(UUID::toString), builder);
    }

    private static List<String> getOnlinePlayerNames(Player player) {
        List<String> names = new ArrayList<>();
        PlayerList playerList = ServerLifecycleHooks.getCurrentServer().getPlayerList();
        if (playerList == null) {
            return names;
        }
        for (Player p : playerList.getPlayers()) {
            names.add(p.getName().getString());
        }
        return names;
    }

    private static CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) throws CommandSyntaxException {
        try {
            Player player = checkPlayer(context);
            SharedSuggestionProvider.suggest(getOnlinePlayerNames(player).toArray(new String[0]), builder);
        } catch (Exception e) {
            return SharedSuggestionProvider.suggest(new String[0], builder);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestTeamMembers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) throws CommandSyntaxException {
        try {
            Player player = checkPlayer(context);
            TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
            if (team == null) {
                SharedSuggestionProvider.suggest(new String[0], builder);
                return builder.buildFuture();
            }
            List<String> names = new ArrayList<>();
            for (UUID uuid : team.getAll()) {
                ServerPlayer online = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(uuid);
                if (online != null) {
                    names.add(online.getName().getString());
                } else if (UsernameCache.containsUUID(uuid)) {
                    names.add(UsernameCache.getLastKnownUsername(uuid));
                }
            }
            SharedSuggestionProvider.suggest(names.toArray(new String[0]), builder);
        } catch (Exception e) {
            return SharedSuggestionProvider.suggest(new String[0], builder);
        }
        return builder.buildFuture();
    }

    private static int invite(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = checkPlayer(context);
        TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
        if (team == null) {
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.invite.not_in_team"));
            return 0;
        }


        Collection<ServerPlayer> players =
                EntityArgument.getPlayers(context, "players").stream()
                        .filter(p -> !team.getAll().contains(TeamProjectE.getPlayerUUID(p)))
                        .toList();

        Component component = Component.translatable("commands.teamprojecte.invitation",
                player.getDisplayName(),
                Component.translatable("commands.teamprojecte.invite.option.accept")
                        .withStyle(style -> style.applyFormat(ChatFormatting.GREEN)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/team_projecte accept " + team.getUUID()))),
                Component.translatable("commands.teamprojecte.invite.option.decline")
                        .withStyle(style -> style.applyFormat(ChatFormatting.RED)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/team_projecte decline " + team.getUUID())))
        );

        for (ServerPlayer p : players) {
            INVITATIONS.put(TeamProjectE.getPlayerUUID(p), team.getUUID());
            p.sendSystemMessage(component);
        }
        if (!players.isEmpty())
            context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.invite.success", players.size()), true);
        else
            context.getSource().sendFailure(Component.translatable("commands.teamprojecte.players_not_found"));
        return players.size();
    }

    private static ServerPlayer checkPlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (context.getSource().getEntity() instanceof ServerPlayer player)
            return player;
        throw CommandSourceStack.ERROR_NOT_PLAYER.create();
    }

    private static TPTeam checkInTeam(Player player) {
        TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
        if (team == null) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.leave.not_in_team").withStyle(ChatFormatting.RED));
            return null;
        }
        return team;
    }

    public static boolean checkOwner(TPTeam team, ServerPlayer player) {
        if (!TeamProjectE.getPlayerUUID(player).equals(team.getOwner())) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.not_owner").withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    private static int queryTeamName(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null)
            return 0;
        context.getSource().sendSuccess(() -> Component.literal(team.getName()), true);
        return Command.SINGLE_SUCCESS;
    }

    /** @deprecated legacy duplicate; use {@code cn.leomc.teamprojecte.commands.InfoCommand} */
    @Deprecated
    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
        if (team == null) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.not_in_team").withStyle(ChatFormatting.RED));
            return Command.SINGLE_SUCCESS;
        }
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.team",
                team.getUUID().toString().substring(0, 8), team.getName()).withStyle(ChatFormatting.GREEN));
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.owner", getDisplayName(team.getOwner())).withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.member_count", team.getAll().size()).withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.info.members", getNames(team.getOwner(), team.getAll())));
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

    private static Component bool(boolean v) {
        return Component.literal(v ? "True" : "False")
                .withStyle(v ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static Component getDisplayName(UUID uuid) {
        PlayerList playerList = ServerLifecycleHooks.getCurrentServer().getPlayerList();
        ServerPlayer player = playerList != null ? playerList.getPlayer(uuid) : null;
        if (player != null) {
            return player.getDisplayName();
        }
        if (UsernameCache.containsUUID(uuid)) {
            return Component.literal(UsernameCache.getLastKnownUsername(uuid)).withStyle(ChatFormatting.RED);
        }
        return Component.literal(uuid.toString()).withStyle(ChatFormatting.GRAY);
    }

    private static int setTeamName(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null || !checkOwner(team, player))
            return 0;
        String value = StringArgumentType.getString(context, "value");
        if (value.equals(team.getName())) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.already.name", value), true);
            return Command.SINGLE_SUCCESS;
        }
        team.setName(value);
        context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.set.team_name", value), true);
        postTeamAttributeChangeEvent(team);
        return Command.SINGLE_SUCCESS;
    }

    private static int queryShareEMC(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null)
            return 0;

        context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.query.sharing_emc." + team.isSharingEMC()), true);

        return Command.SINGLE_SUCCESS;
    }

    private static int setShareEMC(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null || !checkOwner(team, player))
            return 0;

        boolean value = BoolArgumentType.getBool(context, "value");
        if (team.isSharingEMC() == value) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.already.sharing_emc"), true);
            return Command.SINGLE_SUCCESS;
        }
        team.setShareEMC(value);
        context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.set.sharing_emc." + team.isSharingEMC()), true);
        postTeamAttributeChangeEvent(team);

        return Command.SINGLE_SUCCESS;
    }

    private static int queryShareKnowledge(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null)
            return 0;

        context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.query.sharing_knowledge." + team.isSharingKnowledge()), true);

        return Command.SINGLE_SUCCESS;
    }

    private static int setShareKnowledge(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null || !checkOwner(team, player))
            return 0;

        boolean value = BoolArgumentType.getBool(context, "value");
        if (team.isSharingKnowledge() == value) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.already.sharing_knowledge"), true);
            return Command.SINGLE_SUCCESS;
        }
        team.setShareKnowledge(value);
        context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.set.sharing_knowledge." + team.isSharingKnowledge()), true);
        postTeamAttributeChangeEvent(team);

        return Command.SINGLE_SUCCESS;
    }

    private static int queryFreeJoin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null) return 0;
        context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.query.free_join." + team.isFreeJoin()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setFreeJoin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = checkInTeam(player);
        if (team == null || !checkOwner(team, player)) return 0;
        boolean value = BoolArgumentType.getBool(context, "value");
        if (team.isFreeJoin() == value) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.already.free_join"), true);
            return Command.SINGLE_SUCCESS;
        }
        team.setFreeJoin(value);
        context.getSource().sendSuccess(() -> Component.translatable("commands.teamprojecte.settings.set.free_join." + team.isFreeJoin()), true);
        postTeamAttributeChangeEvent(team);
        return Command.SINGLE_SUCCESS;
    }

    private static void postTeamMemberChangeEvent(UUID playerUUID, TPTeam oldTeam, TPTeam newTeam) {
        MinecraftForge.EVENT_BUS.post(new TeamChangeEvent(playerUUID, oldTeam, newTeam));
    }

    private static void postTeamAttributeChangeEvent(TPTeam team) {
        postTeamMemberChangeEvent(null, team, null);
    }

    private static int rename(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        TPTeam team = TPTeam.getTeamByMember(TeamProjectE.getPlayerUUID(player));
        if (team == null || !checkOwner(team, player)) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.not_owner").withStyle(ChatFormatting.RED));
            return Command.SINGLE_SUCCESS;
        }
        String value = StringArgumentType.getString(context, "value");
        if (value.equals(team.getName())) {
            player.sendSystemMessage(Component.translatable("commands.teamprojecte.settings.already.name", value).withStyle(ChatFormatting.RED));
            return Command.SINGLE_SUCCESS;
        }
        team.setName(value);
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.settings.set.team_name", value).withStyle(ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private static int help(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = checkPlayer(context);
        player.sendSystemMessage(Component.translatable("commands.teamprojecte.help"));
        return Command.SINGLE_SUCCESS;
    }
}