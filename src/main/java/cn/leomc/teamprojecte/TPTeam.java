package cn.leomc.teamprojecte;

import cn.leomc.teamprojecte.data.TeamEntity;
import cn.leomc.teamprojecte.data.TeamSettings;
import com.google.common.collect.Lists;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.PECapabilities;
import net.minecraft.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class TPTeam {
    private final UUID teamUUID;
    private UUID owner;
    private String name;
    private final List<UUID> members;

    private KnowledgeData knowledge;
    private EMCData emc;

    /** Whether this team accepts free joins (defaults to true). */
    private boolean freeJoin = true;

    /** Members hidden from team listings while vanished (membership itself is retained). */
    private final Set<UUID> vanished = new HashSet<>();

    /**
     * Ephemeral (non-persisted) team used as the vanilla-compatible fallback for
     * players without a real team. Ephemeral teams must never be written to
     * TPSavedData or the team store, and must never incur team-wide syncs.
     */
    private boolean ephemeral = false;

    public boolean isEphemeral() {
        return ephemeral;
    }

    public TPTeam(UUID teamUUID, UUID owner) {
        this(teamUUID, owner, "Team " + teamUUID.toString().substring(0, 8));
    }

    public TPTeam(UUID teamUUID, UUID owner, String name) {
        this.teamUUID = teamUUID;
        this.owner = owner;
        this.name = (name == null || name.isBlank()) ? "Team " + teamUUID.toString().substring(0, 8) : name.trim();
        this.members = new ArrayList<>();
        this.knowledge = new KnowledgeData.Sharing();
        this.emc = new EMCData.Sharing();
    }

    public TPTeam(UUID owner) {
        this(UUID.randomUUID(), owner);
        this.ephemeral = true;
    }

    public UUID getUUID() {
        return teamUUID;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        markDirty();
        this.name = name;
        sync();
    }

    public void addMemberWithKnowledge(TPTeam originalTeam, Player player) {
        markDirty();
        UUID playerUUID = TeamProjectE.getPlayerUUID(player);
        addMember(playerUUID);

        if (originalTeam.getOwner().equals(playerUUID)) {
            setEmc(getEmc(playerUUID).add(originalTeam.getEmc(playerUUID)), playerUUID);
            originalTeam.setEmc(BigInteger.ZERO, playerUUID);
            if (originalTeam.hasFullKnowledge(playerUUID)) {
                setFullKnowledge(true, playerUUID);
                originalTeam.setFullKnowledge(false, playerUUID);
            }
            // Transfer only what the player personally added (private set).
            // KnowledgeData.removeMember retains private data, so clear explicitly.
            Set<ItemInfo> priv = originalTeam.getPrivateKnowledge(playerUUID);
            priv.forEach(k -> addKnowledge(k, playerUUID));
            originalTeam.clearKnowledge(playerUUID);
        } else {
            if (!originalTeam.isSharingEMC()) {
                setEmc(originalTeam.getEmc(playerUUID), playerUUID);
                originalTeam.setEmc(BigInteger.ZERO, playerUUID);
            }
            if (!originalTeam.isSharingKnowledge()) {
                if (originalTeam.hasFullKnowledge(playerUUID)) {
                    setFullKnowledge(true, playerUUID);
                    originalTeam.setFullKnowledge(false, playerUUID);
                }
                originalTeam.getPrivateKnowledge(playerUUID).forEach(k -> addKnowledge(k, playerUUID));
                originalTeam.clearKnowledge(playerUUID);
            } else {
                // Sharing team: carry the player's private entries into the
                // new team without inheriting the old shared set.
                if (originalTeam.hasPrivateFullKnowledge(playerUUID)) {
                    setFullKnowledge(true, playerUUID);
                    originalTeam.setFullKnowledge(false, playerUUID);
                }
                originalTeam.getPrivateKnowledge(playerUUID).forEach(k -> addKnowledge(k, playerUUID));
                originalTeam.clearKnowledge(playerUUID);
            }
        }
        originalTeam.removeMember(playerUUID);
        sync();
    }

    public void addMember(UUID uuid) {
        markDirty();
        TPSavedData.getData().invalidateCache(uuid);
        clearOfflineKnowledgeCache(uuid);
        members.add(uuid);
        sync();
    }

    private static void clearOfflineKnowledgeCache(UUID uuid) {
        // Keep ProjectE's offline knowledge cache consistent with membership changes.
        try { moze_intel.projecte.impl.TransmutationOffline.clear(uuid); } catch (Exception ignored) {}
    }

    /**
     * Transfer a joining player's EMC into this team.
     * <p>Sharing team: added to the shared pool (owner slot). Non-sharing team:
     * credited to the player's personal slot. Zero/negative amounts are a no-op
     * (no write, no sync). Callers are responsible for zeroing the source EMC
     * (see {@link #readAndZeroPlayerEmc(ServerPlayer)}) <em>before</em> the
     * player is added, since membership changes capability routing.
     *
     * @return the amount actually transferred ({@link BigInteger#ZERO} if skipped)
     */
    public BigInteger transferJoiningPlayerEmc(BigInteger playerEmc, UUID playerUUID) {
        if (playerEmc == null || playerUUID == null || playerEmc.signum() <= 0)
            return BigInteger.ZERO;
        if (isSharingEMC()) {
            setEmc(getEmc(owner).add(playerEmc), owner);
        } else {
            // New member's personal slot is empty, so add == set here; adding
            // conserves EMC if a stale slot entry somehow exists.
            setEmc(getEmc(playerUUID).add(playerEmc), playerUUID);
        }
        sync();
        return playerEmc;
    }

    /**
     * Read a player's current EMC from their knowledge capability and zero the
     * source (vanilla ProjectE capability for a non-team player, or the
     * ephemeral/personal team EMC behind {@code TeamKnowledgeProvider}).
     * Must be called <em>before</em> {@link #addMember(UUID)}: once the player
     * joins, the capability routes to the new team, so zeroing afterwards
     * would wipe the destination instead of the source.
     *
     * @return the EMC read (zero if absent, null, or non-positive — in which
     *         case the source is left untouched)
     */
    public static BigInteger readAndZeroPlayerEmc(ServerPlayer player) {
        if (player == null)
            return BigInteger.ZERO;
        BigInteger[] amount = {BigInteger.ZERO};
        player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).ifPresent(cap -> {
            BigInteger emc = cap.getEmc();
            if (emc != null && emc.signum() > 0) {
                amount[0] = emc;
                cap.setEmc(BigInteger.ZERO);
            }
        });
        return amount[0];
    }

    public void removeMember(UUID uuid) {
        // Capture private knowledge before removal so the leaving player keeps it.
        Set<ItemInfo> privateKeep = getPrivateKnowledge(uuid);
        boolean privateFullKeep = hasPrivateFullKnowledge(uuid);
        markDirty();
        TPSavedData.getData().invalidateCache(uuid);
        clearOfflineKnowledgeCache(uuid);
        // Keep non-shared EMC on the team pool (owner) when a member leaves.
        // Gains are pooled on the owner slot, so a leaving member's (legacy)
        // slice is folded into the pool; the pool itself stays with the team.
        BigInteger teamPool = getEmc(owner);
        if (!isSharingEMC() && !uuid.equals(owner)) {
            BigInteger slice = getEmc(uuid);
            teamPool = teamPool.add(slice == null ? BigInteger.ZERO : slice);
            setEmc(teamPool, owner);
        }
        if (!isSharingEMC()) {
            setEmc(java.math.BigInteger.ZERO, uuid);
        }
        knowledge.removeMember(uuid);
        emc.removeMember(uuid);
        vanished.remove(uuid);
        if (owner.equals(uuid)) {
            if (members.isEmpty()) {
                TPSavedData d = TPSavedData.getData();
                if (d != null) {
                    d.teams.remove(teamUUID);
                    d.setDirty();
                }
                cn.leomc.teamprojecte.persistence.TeamDataStore store = TPSavedData.getStore();
                if (store != null) {
                    try { store.deleteTeam(teamUUID); } catch (Exception ignored) {}
                }
                return;
            }
            UUID newOwner = members.get(ThreadLocalRandom.current().nextInt(members.size()));
            owner = newOwner;
            members.remove(newOwner);
            // Gains pooled on the departing owner's slot must survive the
            // handover: move the pool to the new owner's slot (no-op for
            // sharing teams, where EMC is a single value).
            if (!isSharingEMC()) {
                setEmc(teamPool == null ? BigInteger.ZERO : teamPool, newOwner);
            }
        } else
            members.remove(uuid);
        // Hand private knowledge to the leaving player's personal capability
        // (ephemeral, non-persisted team) so they keep what they added.
        transferPrivateKnowledgeToPersonal(uuid, privateKeep, privateFullKeep);
        sync(uuid);
        // Refresh remaining members if sharing knowledge was affected.
        if (isSharingKnowledge()) {
            sync();
        }
    }

    /**
     * Give the leaving player's private knowledge to their personal
     * (ephemeral) team via the live capability. Best-effort: silently
     * skipped when the player is offline or no server is running.
     */
    private static void transferPrivateKnowledgeToPersonal(UUID uuid, Set<ItemInfo> privateKeep, boolean privateFullKeep) {
        if (uuid == null || privateKeep == null) return;
        try {
            if (net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() == null) return;
            ServerPlayer player = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(uuid);
            if (player == null) return;
            player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).ifPresent(cap -> {
                // Capability now routes to the ephemeral/personal team.
                privateKeep.forEach(info -> cap.addKnowledge(info));
                if (privateFullKeep) {
                    cap.setFullKnowledge(true);
                }
                cap.sync(player);
            });
        } catch (Exception ignored) {}
    }

    public void transferOwner(UUID newOwner) {
        if (owner.equals(newOwner) || !members.contains(newOwner))
            return;
        members.add(owner);
        owner = newOwner;
        members.remove(newOwner);
    }

    public void splitEmcFairly(UUID owner) {
        if (!isSharingEMC()) return;
        BigInteger teamPool = getEmc(owner);
        if (teamPool.signum() <= 0) return;
        List<UUID> all = getAll();
        int count = all.size();
        BigInteger share = teamPool.divide(BigInteger.valueOf(count));
        BigInteger ownerRemainder = teamPool.subtract(share.multiply(BigInteger.valueOf(count - 1)));
        // Convert to personal (NotSharing) so each player gets a real EMC
        emc = emc.convert(owner);
        setEmc(ownerRemainder, owner);
        for (UUID uuid : all) {
            if (uuid.equals(owner)) continue;
            setEmc(share, uuid);
        }
        sync();
    }

    public List<UUID> getMembers() {
        return List.copyOf(members);
    }

    public List<UUID> getAll() {
        return Lists.asList(owner, members.toArray(UUID[]::new));
    }

    public boolean isVanished(UUID uuid) {
        return uuid != null && vanished.contains(uuid);
    }

    public Set<UUID> getVanished() {
        return Set.copyOf(vanished);
    }

    public void setVanished(UUID uuid, boolean hidden) {
        if (uuid == null) return;
        if (hidden) vanished.add(uuid); else vanished.remove(uuid);
        markDirty();
        sync();
    }

    /** Team roster as seen by {@code viewer}: vanished members are hidden from everyone but themselves. */
    public List<UUID> getVisibleAll(UUID viewer) {
        // Owner can always see vanished members (prevents hiding from owner).
        if (viewer != null && viewer.equals(owner)) return getAll();
        return getAll().stream().filter(u -> !isVanished(u) || u.equals(viewer)).toList();
    }


    public boolean addKnowledge(ItemInfo info, UUID player) {
        markDirty();
        boolean added = knowledge.addKnowledge(info, player);
        if (added && player != null && !player.equals(Util.NIL_UUID)) {
            // Private entry must reach the owner's table even when not sharing.
            sync(player);
        }
        return added;
    }

    public boolean removeKnowledge(ItemInfo info, UUID player) {
        markDirty();
        boolean removed = knowledge.removeKnowledge(info, player);
        if (removed && player != null && !player.equals(Util.NIL_UUID)) {
            sync(player);
        }
        return removed;
    }

    public void clearKnowledge(UUID player) {
        markDirty();
        knowledge.clearKnowledge(player);
        if (player != null && !player.equals(Util.NIL_UUID)) {
            sync(player);
        }
    }


    public Set<ItemInfo> getKnowledge(UUID player) {
        return knowledge.getKnowledge(player);
    }

    public Set<ItemInfo> getPrivateKnowledge(UUID player) {
        return knowledge.getPrivateKnowledge(player);
    }

    public boolean hasPrivateFullKnowledge(UUID player) {
        return knowledge.hasPrivateFullKnowledge(player);
    }

    /** Team aggregate knowledge view (stats/UI only, never extraction eligibility — see {@link KnowledgeData#getTeamKnowledge()}). */
    public Set<ItemInfo> getTeamKnowledge() {
        return knowledge.getTeamKnowledge();
    }

    public int getMemberCount(ItemInfo info) {
        return knowledge.getMemberCount(info);
    }

    public void setEmc(BigInteger emc, UUID player) {
        markDirty();
        this.emc.setEMC(emc, player);
    }

    public BigInteger getEmc(UUID player) {
        return emc.getEMC(player);
    }

    /**
     * Credit an EMC gain to the team pool (owner slot), regardless of the
     * share_emc spending toggle. For sharing teams the owner slot is the
     * single shared pool; for non-sharing teams the owner slot acts as the
     * team pool holding all gains. Zero/negative/null amounts are a no-op.
     * Ephemeral (non-team) teams are untouched — vanilla behaviour.
     *
     * @return the amount actually credited
     */
    public synchronized BigInteger addEmcGain(BigInteger amount, UUID player) {
        if (ephemeral || amount == null || amount.signum() <= 0)
            return BigInteger.ZERO;
        BigInteger pool = getEmc(owner);
        BigInteger newPool = (pool == null ? BigInteger.ZERO : pool).add(amount);
        setEmc(newPool, owner);
        syncEmcToTeam();
        return amount;
    }

    /** Broadcast the current team-pool EMC to all online team members. */
    public void syncEmcToTeam() {
        if (ephemeral) return;
        try {
            BigInteger pool = getEmc(owner);
            if (pool == null) pool = BigInteger.ZERO;
            moze_intel.projecte.network.packets.to_client.knowledge.KnowledgeSyncEmcPKT pkt =
                    new moze_intel.projecte.network.packets.to_client.knowledge.KnowledgeSyncEmcPKT(pool);
            for (ServerPlayer p : TeamProjectE.getAllOnline(getAll())) {
                moze_intel.projecte.network.PacketHandler.sendTo(pkt, p);
            }
        } catch (Exception ignored) {
            // No server / packets unavailable (e.g. unit context): pool already persisted.
        }
    }

    public void setFullKnowledge(boolean fullKnowledge, UUID player) {
        markDirty();
        knowledge.setFullKnowledge(fullKnowledge, player);
    }

    public boolean hasFullKnowledge(UUID player) {
        return knowledge.hasFullKnowledge(player);
    }

    public boolean isSharingEMC() {
        return emc instanceof EMCData.Sharing;
    }

    public boolean isSharingKnowledge() {
        return knowledge instanceof KnowledgeData.Sharing;
    }

    public boolean isFreeJoin() {
        return freeJoin;
    }

    public void setFreeJoin(boolean freeJoin) {
        this.freeJoin = freeJoin;
        markDirty();
    }


    public void setShareEMC(boolean share) {
        if (isSharingEMC() == share)
            return;
        emc = emc.convert(getOwner());
        markDirty();
        sync();
        // Re-push the (possibly re-pooled) EMC value to every online member
        // so the transmutation table reflects the new routing immediately.
        // syncEmcToTeam is a no-op for ephemeral teams.
        syncEmcToTeam();
    }

    public void setShareKnowledge(boolean share) {
        if (isSharingKnowledge() == share)
            return;
        knowledge = knowledge.convert(getOwner());
        markDirty();
        // Flush offline knowledge cache so cached offline providers route
        // through the converted data structure on next use.
        for (UUID uuid : getAll()) {
            clearOfflineKnowledgeCache(uuid);
        }
        sync();
        // Force a live re-sync per online member after the conversion,
        // mirroring setShareEMC's extra syncEmcToTeam() push: each
        // provider's sync() runs refreshKnowledge() (which now treats the
        // sharing-flag change as a knowledge change and fires
        // PlayerKnowledgeChangeEvent) then sends KnowledgeSyncPKT with the
        // post-toggle per-viewer view, so a member with the transmutation
        // table open sees the new private/shared items immediately
        // instead of only on rejoin. The second pass is idempotent —
        // refreshKnowledge() reports no further change once converged.
        TeamProjectE.getAllOnline(getAll()).forEach(TeamProjectE::sync);
    }

    public void markDirty() {
        if (ephemeral) return; // vanilla fallback for non-team players: never persisted
        TPSavedData d = TPSavedData.getData();
        if (d != null) d.setDirty();
        cn.leomc.teamprojecte.persistence.TeamDataStore store = TPSavedData.getStore();
        if (store != null) {
            try { store.save(this); } catch (Exception e) { TeamProjectE.LOGGER.warn("Failed to persist team", e); }
        }
    }

    /** Plain persistence entity (no NBT). The DAL owns serialization. */
    public TeamEntity toEntity() {
        TeamEntity e = new TeamEntity(teamUUID, owner, name);
        e.addAllMembers(members);
        e.addAllVanished(vanished);
        e.setSettings(new TeamSettings(isSharingKnowledge(), isSharingEMC(), freeJoin));
        e.setKnowledge(knowledge.snapshot());
        e.setEmc(emc.snapshot());
        return e;
    }

    /** Hydrate a runtime team from a persistence entity (no NBT). */
    public static TPTeam fromEntity(TeamEntity e) {
        if (e == null) return null;
        TPTeam team = new TPTeam(e.getUuid(), e.getOwner(), e.getName());
        team.members.addAll(e.getMembers());
        team.vanished.addAll(e.getVanished());
        team.freeJoin = e.getSettings().freeJoin();
        team.knowledge = KnowledgeData.restore(e.getKnowledge());
        team.emc = EMCData.restore(e.getEmc());
        return team;
    }

    public static TPTeam getOrCreateTeam(UUID uuid) {
        TPTeam team = getTeamByMember(uuid);
        if (team == null)
            team = createTeam(uuid);
        return team;
    }

    public static TPTeam createTeam(UUID uuid) {
        return createTeam(uuid, "Team " + uuid.toString().substring(0, 8));
    }

    /** Create a team with the required name. Validates length and uniqueness (case-insensitive). */
    public static TPTeam createTeam(UUID uuid, String name) {
        String clean = name == null ? "" : name.trim();
        if (!isValidTeamName(clean)) throw new IllegalArgumentException("Invalid team name (1-32 chars): " + name);
        if (isNameTaken(clean)) throw new IllegalArgumentException("Team name already taken: " + clean);
        TPTeam team = new TPTeam(UUID.randomUUID(), uuid, clean);
        TPSavedData d = TPSavedData.getData();
        if (d != null) {
            d.teams.put(team.getUUID(), team);
            d.setDirty();
        }
        cn.leomc.teamprojecte.persistence.TeamDataStore store = TPSavedData.getStore();
        if (store != null) {
            try { store.save(team); } catch (Exception e) { TeamProjectE.LOGGER.warn("Failed to persist new team", e); }
        }
        // Push the fresh team EMC to the owner: a player who creates a team mid-session
        // must immediately see team EMC in the transmutation table, not stale personal EMC.
        team.sync();
        return team;
    }

    private static final java.util.regex.Pattern NAME_PATTERN = java.util.regex.Pattern.compile("[\\p{L}\\p{N} _\\-]+");
    public static boolean isValidTeamName(String name) {
        if (name == null) return false;
        String clean = name.trim();
        return clean.length() >= 1 && clean.length() <= 32 && NAME_PATTERN.matcher(clean).matches();
    }

    public static boolean isNameTaken(String name) {
        if (name == null) return false;
        String clean = name.trim();
        for (TPTeam t : getTeams()) {
            if (t.getName() != null && t.getName().trim().equalsIgnoreCase(clean)) return true;
        }
        return false;
    }

    public static TPTeam getTeam(UUID uuid) {
        TPSavedData d = TPSavedData.getData();
        if (d == null || uuid == null) return null;
        return d.teams.get(uuid);
    }

    /**
     * Look up a team by name (case-insensitive, trimmed).
     *
     * @return the matching team, or {@code null} if none (or name is null/blank)
     */
    public static TPTeam getTeamByName(String name) {
        if (name == null) return null;
        String clean = name.trim();
        if (clean.isEmpty()) return null;
        for (TPTeam t : getTeams()) {
            if (t.getName() != null && t.getName().trim().equalsIgnoreCase(clean)) return t;
        }
        return null;
    }

    /** Alias for {@link #getTeamByName(String)}. */
    public static TPTeam getTeamByNameIgnoreCase(String name) {
        return getTeamByName(name);
    }

    public static boolean isInTeam(UUID uuid) {
        return getTeamByMember(uuid) != null;
    }

    public static TPTeam getTeamByMember(UUID uuid) {
        TPSavedData d = TPSavedData.getData();
        if (d == null || uuid == null) return null;
        UUID teamUUID = d.playerTeamCache.get(uuid);

        if (teamUUID == null)
            for (Map.Entry<UUID, TPTeam> entry : d.teams.entrySet()) {
                if (entry.getValue().getAll().contains(uuid)) {
                    teamUUID = entry.getKey();
                    d.playerTeamCache.put(uuid, teamUUID);
                    break;
                }
            }

        if (teamUUID != null)
            return d.teams.get(teamUUID);
        return null;
    }

    public void sync() {
        TeamProjectE.getAllOnline(getAll()).forEach(TeamProjectE::sync);
    }

    public void sync(UUID uuid) {
        TeamProjectE.getAllOnline(List.of(uuid)).forEach(TeamProjectE::sync);
    }

    public static List<TPTeam> getTeams() {
        TPSavedData d = TPSavedData.getData();
        if (d == null) return List.of();
        return new ArrayList<>(d.teams.values());
    }
}
