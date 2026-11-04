package cn.leomc.teamprojecte.commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;

/**
 * Registers the {@code settings} fork: {@code share_emc}, {@code share_knowledge},
 * {@code name}, and {@code free_join}.
 */
public class SettingsCommand {

    public static ArgumentBuilder<CommandSourceStack, LiteralArgumentBuilder<CommandSourceStack>> create() {
        return Commands.literal("settings")
                .then(ShareEmcCommand.create())
                .then(ShareKnowledgeCommand.create())
                .then(NameCommand.create())
                .then(FreeJoinCommand.create());
    }
}
