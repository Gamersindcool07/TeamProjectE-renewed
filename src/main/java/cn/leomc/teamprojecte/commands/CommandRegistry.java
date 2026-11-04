package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.UsernameCache;

import java.util.concurrent.CompletableFuture;

/**
 * Central registration for all {@code team_projecte} subcommands.
 *
 * <p>Each subcommand has its own file (one class per command) and is wired in
 * here. The tree is:</p>
 *
 * <pre>
 * team_projecte
 * ├── invite <players>
 * ├── leave
 * ├── members
 * ├── kick <members>
 * ├── transfer_ownership <member>
 * ├── accept <team>
 * ├── decline <team>
 * ├── settings
 * │   ├── share_emc [value]
 * │   ├── share_knowledge [value]
 * │   ├── name [value]
 * │   └── free_join [value]
 * ├── create <name>
 * ├── info
 * ├── delete
 * ├── rename <name>
 * ├── join <team_name>
 * ├── vanish [on|off|true|false]
 * ├── admin
 * │   └── migrate
 * └── help
 * </pre>
 */
public final class CommandRegistry {

    private CommandRegistry() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("team_projecte")
                .then(InviteCommand.create())
                .then(LeaveCommand.create())
                .then(MembersCommand.create())
                .then(KickCommand.create())
                .then(TransferOwnershipCommand.create())
                .then(AcceptCommand.create())
                .then(DeclineCommand.create())
                .then(SettingsCommand.create())
                .then(CreateCommand.create())
                .then(InfoCommand.create())
                .then(DeleteCommand.create())
                .then(RenameCommand.create())
                .then(JoinCommand.create())
                .then(VanishCommand.create())
                .then(AdminMigrateCommand.create())
                .then(HelpCommand.create()));
    }

    // ---------------------------------------------------------------------
    // Tab completion for top-level args
    // ---------------------------------------------------------------------

    /**
     * Autocomplete for {@code join <team_name>} - lists only teams where
     * {@code free_join} is true. Runs only when the player is not already in a
     * team (guarded by {@link JoinCommand}).
     * <p>
     * Delegates to {@link CommandBase#suggestFreeJoinTeams} so there is a single
     * source of truth (team names, not UUIDs).
     */
    public static CompletableFuture<Suggestions> suggestFreeJoinTeams(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return CommandBase.suggestFreeJoinTeams(ctx, builder);
    }
}
