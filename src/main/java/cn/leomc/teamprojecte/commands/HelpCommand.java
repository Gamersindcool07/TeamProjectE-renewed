package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.Command;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * {@code team_projecte help} - prints a full, explained overview of every command.
 * <p>
 * This replaces the previous flat {@code commands.teamprojecte.help} string with
 * a detailed, colour-coded reference (command, description, who may run it).
 */
public class HelpCommand extends CommandBase {

    public static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("help")
                .requires(CommandBase::requiresPlayer)
                .executes(HelpCommand::help);
    }

    private static int help(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = getPlayer(ctx);

        MutableComponent heading = Component.literal("team_projecte - team management").withStyle(ChatFormatting.GREEN);
        MutableComponent title = Component.literal("Available commands:").withStyle(ChatFormatting.WHITE);

        String[] cmds = {
            "invite <players>", "leave", "members", "kick <players>",
            "transfer_ownership <member>", "accept <team>", "decline <team>",
            "settings share_emc [true|false]", "settings share_knowledge [true|false]",
            "settings name [value]", "settings free_join [true|false]",
            "create <name>", "info",
            "delete", "rename <name>", "join <team_name>", "vanish [on|off|true|false]", "admin migrate", "help"
        };
        String[] desc = {
            "Invite players to your team", "Leave your team", "List team members",
            "Kick players from your team", "Transfer team ownership",
            "Accept an invitation", "Decline an invitation",
            "Share EMC (bare = toggle for owner, query for others)",
            "Share knowledge (bare = toggle for owner, query for others)",
            "Show/set team name", "Accept free joins (bare = toggle for owner)",
            "Create your team (name required)", "Show team details", "Delete your team",
            "Rename the team", "Join a free team by name (case-insensitive; UUID also works)", "Hide yourself from team listings (bare = toggle)",
            "Force re-migration of NBT teams to SQL (idempotent)", "Show this help"
        };
        String[] who = {
            "any player", "in team", "in team", "in team, owner",
            "in team, owner", "any player", "any player",
            "in team (toggle: owner)", "in team (toggle: owner)",
            "in team (set: owner)", "in team (toggle: owner)",
            "any player", "in team", "in team, owner",
            "in team, owner", "not in team", "in team", "admin (permission level 2)", "any player"
        };

        MutableComponent body = Component.literal("Available commands:\n");
        for (int i = 0; i < cmds.length; i++) {
            body.append(Component.literal("  ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(cmds[i]).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("   -   ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(desc[i]).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("\n")));
            body.append(Component.literal("      ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(who[i]).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("\n")));
        }

        body.append(Component.literal("\n"))
                .append(Component.literal("Requirements:").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\n")
                        .append(Component.literal("  - in team: invite / leave / members / info / settings / delete / rename / vanish"))
                        .append(Component.literal("\n"))
                        .append(Component.literal("  - in team, owner: kick / transfer_ownership / settings / rename / delete"))
                        .append(Component.literal("\n"))
                        .append(Component.literal("  - not in team: join"))
                        .append(Component.literal("\n"))
                        .append(Component.literal("  - admin (permission level 2): admin migrate"))
                        .append(Component.literal("\n").append(Component.literal("\n")))
                        .append(Component.literal("Settings: share_emc / share_knowledge / free_join (bare = toggle for owner, query otherwise; explicit value sets) / name (bare = query)"))
                        .append(Component.literal("\n").append(Component.literal("Test server: join and type /team_projecte help")))
                );

        player.sendSystemMessage(heading.append(Component.literal("\n").append(Component.literal("\n")).append(title).append(body)));
        return Command.SINGLE_SUCCESS;
    }
}
