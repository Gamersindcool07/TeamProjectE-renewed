package cn.leomc.teamprojecte.mixin;

import cn.leomc.teamprojecte.TeamKnowledgeProvider;
import moze_intel.projecte.impl.capability.KnowledgeImpl;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.fml.util.thread.SidedThreadGroups;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.lang.reflect.Field;

@Mixin(KnowledgeImpl.Provider.class)
public class KnowledgeEventsProviderMixin {

    private static Field PLAYER_FIELD;

    static {
        try {
            PLAYER_FIELD = Class.forName("moze_intel.projecte.impl.capability.KnowledgeImpl$DefaultImpl").getDeclaredField("player");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @ModifyArg(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lmoze_intel/projecte/capability/managing/SerializableCapabilityResolver;<init>(Lnet/minecraftforge/common/util/INBTSerializable;)V"),
            index = 0,
            remap = false)
    private static INBTSerializable<CompoundTag> onInit(INBTSerializable<CompoundTag> internal) throws IllegalAccessException {
        // Transmutation table fix: the capability is created once per session at login,
        // so checking team membership here only covers players already in a team at
        // login. Create/join/leave mid-session would otherwise never change routing,
        // leaving the table rendering the player's personal EMC instead of the team's.
        // Install the dispatcher unconditionally (server side only): it delegates to
        // the team pool for team members and behaves exactly like vanilla ProjectE for
        // non-team players (ephemeral, non-persisted, vanilla NBT schema).
        if (Thread.currentThread().getThreadGroup() == SidedThreadGroups.SERVER && internal instanceof KnowledgeImpl.DefaultImpl impl && PLAYER_FIELD.get(impl) instanceof ServerPlayer player) {
            try {
                return new TeamKnowledgeProvider(player);
            } catch (Exception ignored) {
                // If provider construction fails, fall through to vanilla.
            }
        }
        return internal;
    }
}
