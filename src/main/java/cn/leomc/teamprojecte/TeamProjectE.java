package cn.leomc.teamprojecte;

import com.mojang.logging.LogUtils;
import moze_intel.projecte.api.capabilities.PECapabilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import cn.leomc.teamprojecte.commands.CommandRegistry;
import cn.leomc.teamprojecte.persistence.TeamConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Mod("teamprojecte")
public class TeamProjectE {

    public static final Logger LOGGER = LogUtils.getLogger();

    public TeamProjectE() {
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, TeamConfig.SPEC, "teamprojecte.toml");
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommand(RegisterCommandsEvent event) {
        CommandRegistry.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Pluggable persistence: initialise backend + run one-time migration.
        TPSavedData.initStore();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        cn.leomc.teamprojecte.commands.CommandBase.clearInvitations();
        TPSavedData.onServerStopped();
    }

    @SubscribeEvent
    public void onPlayerJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().dimension() == Level.OVERWORLD && event.getEntity() instanceof ServerPlayer player)
            sync(player);
    }

    public static List<ServerPlayer> getAllOnline(List<UUID> uuids) {
        if (ServerLifecycleHooks.getCurrentServer() == null) return List.of();
        return uuids.stream()
                .map(uuid -> ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(uuid))
                .filter(Objects::nonNull)
                .toList();
    }

    public static void sync(ServerPlayer player) {
        // Non-team players: vanilla ProjectE sync only (no team creation).
        player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).ifPresent(provider -> provider.sync(player));
    }

    public static List<ServerPlayer> getOnlineTeamMembers(UUID uuid) {
        return getOnlineTeamMembers(uuid, true);
    }

    public static List<ServerPlayer> getOnlineTeamMembers(UUID uuid, boolean includeOwner) {
        // Requirement 5: never create a team for a non-team player.
        TPTeam team = TPTeam.getTeamByMember(uuid);
        if (team == null) return List.of();
        return TeamProjectE.getAllOnline(includeOwner ? team.getAll() : team.getMembers());
    }

    public static UUID getPlayerUUID(Player player) {
        UUID uuid = player.getGameProfile().getId();
        if (uuid == null)
            uuid = player.getUUID();
        return uuid;
    }

}
