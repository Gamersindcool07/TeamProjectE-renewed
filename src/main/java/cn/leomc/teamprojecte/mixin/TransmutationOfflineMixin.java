package cn.leomc.teamprojecte.mixin;

import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.TeamKnowledgeProvider;
import com.google.common.base.Preconditions;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.impl.TransmutationOffline;
import net.minecraftforge.fml.util.thread.SidedThreadGroups;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.UUID;

@Mixin(TransmutationOffline.class)
public class TransmutationOfflineMixin {

    @Shadow(remap = false)
    @Final
    private static Map<UUID, IKnowledgeProvider> cachedKnowledgeProviders;

    @Inject(
            method = "cacheOfflineData",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void onCacheOfflinePlayerData(UUID playerUUID, CallbackInfoReturnable<Boolean> cir) {
        // Requirement 5: non-team players fall through to vanilla ProjectE.
        // Do not intercept (and do not create a team) when the player has no team.
        try {
            if (TPTeam.getTeamByMember(playerUUID) == null) return;
        } catch (Exception ignored) {
            return;
        }
        cir.cancel();
        Preconditions.checkState(Thread.currentThread().getThreadGroup() == SidedThreadGroups.SERVER);
        // Cached offline providers are TeamKnowledgeProvider instances, so
        // offline EMC gains route through the same setEmc gain detection
        // (delta -> TPTeam.addEmcGain -> team pool) as online players.
        TeamKnowledgeProvider provider = new TeamKnowledgeProvider(playerUUID);
        cachedKnowledgeProviders.put(playerUUID, provider);
        // Offline cache: re-run the knowledge refresh when the cached provider
        // is (re)created so table-learned items that drifted from team
        // knowledge-data after membership/share changes are backfilled, and
        // the table view converges with TPTeam (knowledge/full/EMC).
        try {
            provider.refreshKnowledge();
        } catch (Exception ignored) {}
        cir.setReturnValue(true);
    }

}
