package cn.leomc.teamprojecte;

import com.google.common.base.Suppliers;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.api.event.PlayerKnowledgeChangeEvent;
import moze_intel.projecte.emc.EMCMappingHandler;
import moze_intel.projecte.emc.nbt.NBTManager;
import moze_intel.projecte.gameObjs.items.Tome;
import moze_intel.projecte.network.PacketHandler;
import moze_intel.projecte.network.packets.IPEPacket;
import moze_intel.projecte.network.packets.to_client.knowledge.KnowledgeSyncChangePKT;
import moze_intel.projecte.network.packets.to_client.knowledge.KnowledgeSyncEmcPKT;
import moze_intel.projecte.network.packets.to_client.knowledge.KnowledgeSyncInputsAndLocksPKT;
import moze_intel.projecte.network.packets.to_client.knowledge.KnowledgeSyncPKT;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Supplier;

/**
 * Server-side knowledge capability dispatcher.
 *
 * <p>Routing is dynamic per-call: it resolves {@link TPTeam#getTeamByMember} on every
 * method invocation, so create/join/leave mid-session take effect immediately.
 * For team members, {@link #getEmc()} returns the team pool (owner slot)
 * regardless of the share_emc toggle: gains are always credited to the
 * pool (see {@link TPTeam#addEmcGain}) and spending draws from it too,
 * while knowledge sharing keeps its own toggle. For
 * non-team players it is vanilla ProjectE behaviour backed by a non-persisted
 * ephemeral team (never creates a team row).
 *
 * <p>Client note: the ProjectE client keeps its own vanilla capability; the team's EMC
 * reaches the transmutation table via {@code KnowledgeSyncPKT}/{@code KnowledgeSyncEmcPKT},
 * which this provider sends from {@link #sync}, {@link #syncEmc} and team operations that
 * call {@code TPTeam.sync()}.
 */
public class TeamKnowledgeProvider implements IKnowledgeProvider {

    private final Supplier<UUID> playerUUID;

    private final ItemStackHandler inputLocks = new ItemStackHandler(9);
    private volatile TPTeam ephemeralTeam;
    private volatile boolean reconciling = false;
    private volatile boolean refreshing = false;
    // Last authoritative snapshot seen by refreshKnowledge(); null = uninitialized
    // (first refresh baselines without firing, so pre-existing team knowledge
    // loaded before this provider existed never produces a spurious event).
    private volatile Set<ItemInfo> lastKnowledgeSnapshot;
    private volatile boolean lastFullKnowledgeSnapshot;
    private volatile BigInteger lastEmcSnapshot;
    private volatile UUID lastTeamSnapshot;
    // Sharing flag at last snapshot (null = uninitialized). Included in the
    // refresh comparison so a share-knowledge toggle is reported as a change
    // even when the resulting visible set is identical (e.g. both members
    // already knew the same items privately) — see
    // .hermes/plans/2026-11-04-share-knowledge-live-sync.md.
    private volatile Boolean lastSharingKnowledgeSnapshot;

    public TeamKnowledgeProvider(@NotNull ServerPlayer player) {
        this.playerUUID = Suppliers.memoize(() -> TeamProjectE.getPlayerUUID(player));
    }

    public TeamKnowledgeProvider(UUID uuid) {
        this.playerUUID = () -> uuid;
    }

    private void fireChangedEvent() {
        getTeam().getAll()
                .forEach(uuid -> MinecraftForge.EVENT_BUS.post(new PlayerKnowledgeChangeEvent(uuid)));
    }

    /**
     * Absorb the current authoritative state into the refresh snapshot.
     * Called from this provider's own mutators ({@link #tryAdd},
     * {@link #tryRemove}, {@link #setFullKnowledge}, {@link #clearKnowledge},
     * tome learn/unlearn) so that a later {@link #refreshKnowledge()} (e.g.
     * via the {@code TPTeam.sync(player)} that {@code TPTeam.addKnowledge}
     * triggers for the learner) does not re-report — and re-fire — a change
     * this provider already fired for. Changes made by other members or
     * directly via {@code TPTeam} are deliberately not absorbed here, so
     * refresh still detects and reports them.
     */
    private void updateSnapshot() {
        TPTeam team = getTeam();
        lastKnowledgeSnapshot = new HashSet<>(team.getKnowledge(playerUUID.get()));
        lastFullKnowledgeSnapshot = team.hasFullKnowledge(playerUUID.get());
        BigInteger emc = getEmc();
        lastEmcSnapshot = emc == null ? BigInteger.ZERO : emc;
        lastTeamSnapshot = team.getUUID();
        lastSharingKnowledgeSnapshot = team.isSharingKnowledge();
    }

    private TPTeam getTeam() {
        TPTeam team = TPTeam.getTeamByMember(playerUUID.get());
        if (team != null) return team;
        // Non-team player: never create/persist a team implicitly.
        // The dispatcher is installed for all players; this ephemeral, non-persisted
        // team provides vanilla ProjectE behaviour instead of NPE/auto-creating.
        if (ephemeralTeam == null) ephemeralTeam = new TPTeam(playerUUID.get());
        return ephemeralTeam;
    }

    @Override
    public boolean hasFullKnowledge() {
        return getTeam().hasFullKnowledge(playerUUID.get());
    }

    @Override
    public void setFullKnowledge(boolean fullKnowledge) {
        boolean changed = hasFullKnowledge() != fullKnowledge;
        getTeam().setFullKnowledge(fullKnowledge, playerUUID.get());
        if (changed) {
            fireChangedEvent();
            updateSnapshot();
            // Full-knowledge changes alter what the transmutation table can
            // extract, so push the updated model to clients now instead of
            // waiting for the next incidental sync. In sharing mode the
            // provider sync broadcasts the team-level flag to every member;
            // in non-sharing mode only this player is synced.
            getTeam().sync(playerUUID.get());
        }
    }

    @Override
    public void clearKnowledge() {
        boolean hasKnowledge = hasFullKnowledge() || !getTeam().getKnowledge(playerUUID.get()).isEmpty();
        getTeam().clearKnowledge(playerUUID.get());
        getTeam().setFullKnowledge(false, playerUUID.get());
        if (hasKnowledge) {
            //If we previously had any knowledge fire the fact that our knowledge changed
            fireChangedEvent();
            updateSnapshot();
        }
    }

    @Nullable
    private ItemInfo getIfPersistent(@NotNull ItemInfo info) {
        if (!info.hasNBT() || EMCMappingHandler.hasEmcValue(info)) {
            //If we have no NBT or the base mapping has an emc value for our item with the given NBT
            // then we don't have an extended state
            return null;
        }
        ItemInfo cleanedInfo = NBTManager.getPersistentInfo(info);
        if (cleanedInfo.hasNBT() && !EMCMappingHandler.hasEmcValue(cleanedInfo)) {
            //If we still have NBT after unimportant parts being stripped and it doesn't
            // directly have an EMC value, then we it has some persistent information
            return cleanedInfo;
        }
        return null;
    }

    /**
     * Whether this player can extract (transmute) {@code info}. This is the
     * extraction gate used by the transmutation table's knowledge checks:
     * it consults only the viewing player's own knowledge view
     * ({@link #getKnowledge()} / {@link TPTeam#getKnowledge(UUID)} with this
     * player's UUID — private knowledge plus shared knowledge when sharing),
     * never the team aggregate, so a non-sharing member extracts their own
     * items but never another member's.
     */
    @Override
    public boolean hasKnowledge(@NotNull ItemInfo info) {
        if (getTeam().hasFullKnowledge(playerUUID.get())) {
            //If we have all knowledge, check if the item has extra data and
            // may not actually be in our knowledge set but can be added to it
            ItemInfo persistentInfo = getIfPersistent(info);
            return persistentInfo == null || getTeam().getKnowledge(playerUUID.get()).contains(persistentInfo);
        }
        return getTeam().getKnowledge(playerUUID.get()).contains(NBTManager.getPersistentInfo(info));
    }

    /**
     * Whether {@code info} is already in this player's player-knowledge-data
     * (or is covered by their full knowledge, in which case no explicit entry
     * is needed). This is the single "check-if-in-player-knowledge" entry point
     * shared by every put/learn path ({@link #receiveInputsAndLocks(Map)},
     * {@link #syncInputAndLocks(ServerPlayer, List, TargetUpdateType)},
     * {@link #sync(ServerPlayer)} table-open/sync,
     * {@link #syncKnowledgeChange(ServerPlayer, ItemInfo, boolean)},
     * {@link #deserializeNBT(CompoundTag)} and the offline-cache refresh),
     * so callers do not duplicate the contains/full-knowledge logic and no-op
     * learns never fire events.
     *
     * <p>Null-safe: returns {@code false} for null or unresolvable infos so a
     * bad input is treated as "not known" and routed to {@link #ensureKnowledge}.
     */
    public boolean isInPlayerKnowledge(@NotNull ItemInfo info) {
        if (info == null) return false;
        try {
            if (getTeam().hasFullKnowledge(playerUUID.get())) {
                ItemInfo persistentInfo = getIfPersistent(info);
                return persistentInfo == null || getTeam().getKnowledge(playerUUID.get()).contains(persistentInfo);
            }
            ItemInfo persistent;
            try {
                persistent = NBTManager.getPersistentInfo(info);
            } catch (Exception e) {
                return false;
            }
            if (persistent == null) return false;
            return getTeam().getKnowledge(playerUUID.get()).contains(persistent);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Add-if-missing: the reusable put/learn entry point. No-ops (returns
     * {@code false} and fires no event) when the item is already in
     * player-knowledge-data or covered by full knowledge, per
     * {@link #isInPlayerKnowledge(ItemInfo)}; otherwise delegates to
     * {@link #addKnowledge(ItemInfo)}, which routes through
     * {@code getTeam().addKnowledge(...)} so private/shared semantics and
     * sync events fire exactly as for any other learn.
     */
    public boolean ensureKnowledge(@NotNull ItemInfo info) {
        if (info == null) return false;
        if (isInPlayerKnowledge(info)) return false;
        return addKnowledge(info);
    }

    @Override
    public boolean addKnowledge(@NotNull ItemInfo info) {
        if (getTeam().hasFullKnowledge(playerUUID.get())) {
            ItemInfo persistentInfo = getIfPersistent(info);
            if (persistentInfo == null) {
                //If the item doesn't have extra data, and we have all knowledge, don't actually add any
                return false;
            }
            //If it does have extra data, pretend we don't have full knowledge and try adding it as what we have is persistent.
            // Note: We ignore the tome here being a separate entity because it should not have any persistent info
            return tryAdd(persistentInfo);
        }
        if (info.getItem() instanceof Tome) {
            // Already have full knowledge: tome learn is a no-op (no event).
            if (getTeam().hasFullKnowledge(playerUUID.get())) {
                return true;
            }
            if (info.hasNBT()) {
                //Make sure we don't have any NBT as it doesn't have any effect for the tome
                info = ItemInfo.fromItem(info.getItem());
            }
            //Note: We don't bother checking if we already somehow know the tome without having full knowledge
            // as we are learning it without any NBT which means that it doesn't have any extra persistent info
            // so can just check if it is already in it by nature of it being a set
            getTeam().addKnowledge(info, playerUUID.get());
            getTeam().setFullKnowledge(true, playerUUID.get());
            fireChangedEvent();
            updateSnapshot();
            return true;
        }
        // Explicit check-if-in-player-knowledge guard: no-op learns must not
        // fire events or churn persistence/sync (KnowledgeData.addKnowledge
        // would return false anyway; the guard makes the intent explicit).
        if (isInPlayerKnowledge(info)) return false;
        return tryAdd(NBTManager.getPersistentInfo(info));
    }

    private boolean tryAdd(@NotNull ItemInfo cleanedInfo) {
        if (getTeam().addKnowledge(cleanedInfo, playerUUID.get())) {
            fireChangedEvent();
            updateSnapshot();
            return true;
        }
        return false;
    }

    @Override
    public boolean removeKnowledge(@NotNull ItemInfo info) {
        if (getTeam().hasFullKnowledge(playerUUID.get())) {
            if (info.getItem() instanceof Tome) {
                //If we have full knowledge and are trying to remove the tome allow it
                if (info.hasNBT()) {
                    //Make sure we don't have any NBT as it doesn't have any effect for the tome
                    info = ItemInfo.fromItem(info.getItem());
                }
                getTeam().removeKnowledge(info, playerUUID.get());
                getTeam().setFullKnowledge(false, playerUUID.get());
                fireChangedEvent();
                updateSnapshot();
                return true;
            }
            //Otherwise check if we have any persistent information, and if so try removing that
            // as we may have it known as an "extra" item
            ItemInfo persistentInfo = getIfPersistent(info);
            return persistentInfo != null && tryRemove(persistentInfo);
        }
        return tryRemove(NBTManager.getPersistentInfo(info));
    }

    private boolean tryRemove(@NotNull ItemInfo cleanedInfo) {
        if (getTeam().removeKnowledge(cleanedInfo, playerUUID.get())) {
            fireChangedEvent();
            updateSnapshot();
            return true;
        }
        return false;
    }

    @NotNull
    @Override
    public Set<ItemInfo> getKnowledge() {
        if (getTeam().hasFullKnowledge(playerUUID.get())) {
            Set<ItemInfo> allKnowledge = EMCMappingHandler.getMappedItems();
            //Make sure we include any extra items they have learned such as various enchanted items.
            allKnowledge.addAll(getTeam().getKnowledge(playerUUID.get()));
            return Collections.unmodifiableSet(allKnowledge);
        }
        return Collections.unmodifiableSet(getTeam().getKnowledge(playerUUID.get()));
    }

    @NotNull
    @Override
    public IItemHandlerModifiable getInputAndLocks() {
        return inputLocks;
    }

    @Override
    public BigInteger getEmc() {
        TPTeam team = getTeam();
        if (!inTeam()) return team.getEmc(playerUUID.get());
        // Team members see the team pool (owner slot) regardless of the
        // share_emc spending toggle, so pooled gains are visible/spendable
        // by every member. For sharing teams this is identical to before.
        BigInteger pool = team.getEmc(team.getOwner());
        return pool == null ? BigInteger.ZERO : pool;
    }

    @Override
    public void setEmc(BigInteger emc) {
        BigInteger target = emc == null ? BigInteger.ZERO : emc;
        if (!inTeam()) {
            getTeam().setEmc(target, playerUUID.get());
            // Refresh the table view (EMC snapshot + learned reconciliation)
            // so a pool mutation is reflected without a full re-sync.
            refreshKnowledge();
            return;
        }
        TPTeam team = getTeam();
        // ProjectE only exposes setEmc: detect a gain as target > current
        // (current = team pool) and route the delta to the team pool via
        // addEmcGain. A set at/below current is a spend/adjustment against
        // the same pool — spending draws from the pool regardless of the
        // share_emc toggle, otherwise pooled gains would be unspendable by
        // non-owners (decided: pooled gains imply pooled spending).
        // An intentional increase (e.g. admin) lands on the same pool.
        synchronized (team) {
            BigInteger current = getEmc();
            if (target.compareTo(current) > 0) {
                team.addEmcGain(target.subtract(current), playerUUID.get());
            } else {
                team.setEmc(target, team.getOwner());
                // Push the new pool value to every online member so the
                // transmutation table never shows a stale/0 EMC when a caller
                // mutates EMC without a subsequent syncEmc (e.g. third-party
                // mods that only call setEmc). KnowledgeSyncEmcPKT handling on
                // the client sets cap EMC + refreshes TransmutationInventory.
                team.syncEmcToTeam();
            }
        }
        // Refresh the table view after the pool mutation (gain or spend):
        // updates the EMC snapshot and reconciles any table-learned items.
        // No duplicate knowledge event: refresh only fires on an actual
        // knowledge/full/team change not already fired by a learn.
        refreshKnowledge();
    }

    @Override
    public void sync(@NotNull ServerPlayer player) {
        // Reconcile learned-from-table knowledge before syncing so any
        // backfilled items appear in the KnowledgeSyncPKT payload.
        refreshKnowledge();
        if (TPTeam.getTeamByMember(playerUUID.get()) == null) {
            // Non-team player: sync only to themselves (vanilla behaviour).
            sendKnowledgeSync(player);
            return;
        }
        if (!getTeam().isSharingEMC() && !getTeam().isSharingKnowledge())
            sendKnowledgeSync(player);
        else
            TeamProjectE.getOnlineTeamMembers(TeamProjectE.getPlayerUUID(player))
                    .forEach(TeamKnowledgeProvider::sendKnowledgeSync);
    }

    private static void sendKnowledgeSync(ServerPlayer player) {
        player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)
                .ifPresent(cap -> PacketHandler.sendTo(new KnowledgeSyncPKT(((TeamKnowledgeProvider) cap).serializeForClient()), player));
    }

    /**
     * Serialize this provider's knowledge model for one viewing player.
     *
     * <p>The knowledge list is the viewing player's own view
     * ({@link TPTeam#getKnowledge(UUID)} with that player's UUID — private plus
     * shared-as-applicable), so a non-sharing client's transmutation table
     * matches what the server's {@link #hasKnowledge(ItemInfo)} gate allows
     * that player to extract. Full knowledge is sent as a flag; the client
     * expands it like vanilla ProjectE does.
     */
    private CompoundTag serializeForClient() {
        CompoundTag properties = new CompoundTag();
        BigInteger emc = getEmc();
        if (TeamProjectE.LOGGER.isDebugEnabled())
            TeamProjectE.LOGGER.debug("[teamprojecte] serializeForClient player={} team={} sharingEMC={} emc={}",
                    playerUUID.get(), getTeam() == null ? null : getTeam().getUUID(), getTeam().isSharingEMC(), emc);
        properties.putString("transmutationEmc", emc.toString());
        ListTag knowledgeWrite = new ListTag();
        for (ItemInfo i : getTeam().getKnowledge(playerUUID.get()))
            knowledgeWrite.add(i.write(new CompoundTag()));

        properties.put("knowledge", knowledgeWrite);
        properties.put("inputlock", this.inputLocks.serializeNBT());
        properties.putBoolean("fullknowledge", getTeam().hasFullKnowledge(playerUUID.get()));
        return properties;
    }


    @Override
    public void syncEmc(@NotNull ServerPlayer player) {
        // Non-team players: single-recipient vanilla behaviour.
        // Team members: the pool is team-wide (gains always pooled), so
        // broadcast to all online members regardless of share_emc.
        if (TeamProjectE.LOGGER.isDebugEnabled())
            TeamProjectE.LOGGER.debug("[teamprojecte] syncEmc player={} team={} emc={}",
                    player.getName().getString(), inTeam() ? getTeam().getUUID() : null, getEmc());
        sendPacket(new KnowledgeSyncEmcPKT(getEmc()), player, inTeam());
    }

    @Override
    public void syncKnowledgeChange(@NotNull ServerPlayer player, ItemInfo change, boolean learned) {
        // Reconcile table-learned items first so client state stays consistent.
        refreshKnowledge();
        // Non-team players: single-recipient vanilla behaviour.
        // Unlearn (learned=false) in sharing mode: only broadcast the removal
        // delta if the item actually left the viewing player's shared view.
        // When a second member still knows the item, unlearning removes it
        // only from the unlearner's private set (team-knowledge-data and the
        // shared view keep it), so the client table must keep showing it —
        // sending learned=false here would delete it from every member's
        // client model until the next full sync. The full sync already issued
        // by TPTeam.removeKnowledge carries the correct union view.
        if (!learned && inTeam() && getTeam().isSharingKnowledge()
                && hasKnowledge(change)) {
            getTeam().sync(playerUUID.get());
            return;
        }
        sendPacket(new KnowledgeSyncChangePKT(change, learned), player, inTeam() && getTeam().isSharingKnowledge());
    }

    /** Whether this provider currently resolves to a persisted team. */
    private boolean inTeam() {
        return TPTeam.getTeamByMember(playerUUID.get()) != null;
    }

    private static void sendPacket(IPEPacket packet, ServerPlayer player, boolean team) {
        if (team)
            TeamProjectE.getOnlineTeamMembers(TeamProjectE.getPlayerUUID(player))
                    .forEach(p -> PacketHandler.sendTo(packet, p));
        else
            PacketHandler.sendTo(packet, player);
    }

    @Override
    public void syncInputAndLocks(@NotNull ServerPlayer player, List<Integer> slotsChanged, TargetUpdateType updateTargets) {
        // Input-handler mutations (container quick-move / direct slot puts that
        // bypass receiveInputsAndLocks) also count as "put/learn": reconcile
        // first so items placed through the handler are backfilled into
        // player-knowledge-data before the client sync payload is built.
        refreshKnowledge();
        if (!slotsChanged.isEmpty()) {
            int slots = inputLocks.getSlots();
            Map<Integer, ItemStack> stacksToSync = new HashMap<>();
            for (int slot : slotsChanged) {
                if (slot >= 0 && slot < slots) {
                    //Validate the slot is a valid index
                    stacksToSync.put(slot, inputLocks.getStackInSlot(slot));
                }
            }
            if (!stacksToSync.isEmpty()) {
                //Validate it is not empty in case we were fed bad indices
                PacketHandler.sendTo(new KnowledgeSyncInputsAndLocksPKT(stacksToSync, updateTargets), player);
            }
        }
    }

    @Override
    public void receiveInputsAndLocks(Map<Integer, ItemStack> changes) {
        int slots = inputLocks.getSlots();
        for (Map.Entry<Integer, ItemStack> entry : changes.entrySet()) {
            int slot = entry.getKey();
            if (slot >= 0 && slot < slots) {
                //Validate the slot is a valid index
                inputLocks.setStackInSlot(slot, entry.getValue());
            }
        }
        refreshKnowledge();
    }

    /**
     * Reconcile learned-from-transmutation-table knowledge.
     *
     * <p>Items placed in the transmutation table's input-lock slots are
     * considered "learned" by virtue of being present there. If such an item
     * is missing from the authoritative knowledge-data
     * ({@link TPTeam#getKnowledge(UUID)}), it is added via
     * {@link #addKnowledge(ItemInfo)} so future {@code hasKnowledge} /
     * {@code getKnowledge} lookups and client syncs reflect it.
     *
     * <p>Rules (per plan):
     * <ul>
     *   <li>Additive only — never removes knowledge.</li>
     *   <li>Respects full-knowledge / persistent-info handling in
     *       {@link #addKnowledge(ItemInfo)}.</li>
     *   <li>Skips items without an EMC value (and no persistent extra),
     *       matching existing learning rules.</li>
     *   <li>Fires {@code PlayerKnowledgeChangeEvent} only when something
     *       was actually added (via {@code addKnowledge} → {@code tryAdd}).</li>
     *   <li>Synchronized on the team to avoid duplicate events under
     *       concurrency.</li>
     * </ul>
     *
     * <p>Called from {@link #refreshKnowledge()}, which is in turn called from
     * {@link #sync(ServerPlayer)} (table-open/sync path),
     * {@link #syncKnowledgeChange(ServerPlayer, ItemInfo, boolean)},
     * {@link #receiveInputsAndLocks(Map)} (every client put, including
     * single-slot edits and lock toggles; reconciles unconditionally after
     * applying all changes),
     * {@link #syncInputAndLocks(ServerPlayer, List, TargetUpdateType)}
     * (direct handler mutations / container quick-move),
     * {@link #setEmc(BigInteger)}, {@link #deserializeNBT(CompoundTag)} and
     * the offline-cache refresh in {@code TransmutationOfflineMixin}, so
     * table-open, sync, membership-change and load paths all converge.
     *
     * @return true if at least one item was added
     */
    public boolean reconcileLearnedKnowledge() {
        if (reconciling) return false;
        reconciling = true;
        try {
            TPTeam team = getTeam();
            synchronized (team) {
                boolean anyAdded = false;
                // Candidate items from the table state (input locks).
                for (int i = 0; i < inputLocks.getSlots(); i++) {
                    ItemStack stack = inputLocks.getStackInSlot(i);
                    if (stack == null || stack.isEmpty()) continue;
                    ItemInfo info;
                    try {
                        info = ItemInfo.fromStack(stack);
                    } catch (Exception e) {
                        continue;
                    }
                    if (info == null) continue;
                    // Skip items without EMC value / non-persistent NBT
                    // (existing learning rules). Items with a persistent
                    // extra are still eligible via addKnowledge.
                    ItemInfo persistent;
                    try {
                        persistent = NBTManager.getPersistentInfo(info);
                    } catch (Exception e) {
                        continue;
                    }
                    if (persistent == null) continue;
                    boolean hasEmc;
                    try {
                        hasEmc = EMCMappingHandler.hasEmcValue(persistent);
                    } catch (Exception e) {
                        hasEmc = false;
                    }
                    ItemInfo persistentExtra = null;
                    try {
                        persistentExtra = getIfPersistent(persistent);
                    } catch (Exception ignored) {}
                    if (!hasEmc && persistentExtra == null) {
                        continue;
                    }
                    // Skip when already in player-knowledge-data (or covered
                    // by full knowledge) via the shared explicit check, so
                    // known items never reach addKnowledge / fire events.
                    if (isInPlayerKnowledge(info)) continue;
                    // Full-knowledge players: addKnowledge already returns
                    // false for non-persistent items; the hasEmc / extra
                    // filter above mirrors that.
                    boolean added = ensureKnowledge(info);
                    if (added) anyAdded = true;
                }
                return anyAdded;
            }
        } finally {
            reconciling = false;
        }
    }

    /**
     * Refresh/reconcile the transmutation-table knowledge view against the
     * authoritative {@link TPTeam} state.
     *
     * <p>Plan 2026-11-04-transmutation-table-knowledge-refresh: this is the
     * broader refresh that {@link #reconcileLearnedKnowledge()} (input-lock →
     * knowledge only) is modelled on. It:
     * <ul>
     *   <li>Synchronizes on the team, avoiding duplicate events under
     *       concurrency (and is reentrancy-guarded via {@code refreshing} so a
     *       refresh triggered from a sync path cannot recurse).</li>
     *   <li>Reconciles learned knowledge from input-lock slots by reusing
     *       {@link #reconcileLearnedKnowledge()} (additive only, respects
     *       full-knowledge / persistent-info handling in
     *       {@link #addKnowledge(ItemInfo)}).</li>
     *   <li>Re-pulls the authoritative team knowledge for this player — the
     *       full-knowledge flag and the shared/private knowledge set via
     *       {@link TPTeam#getKnowledge(UUID)} — so the table's view converges
     *       with {@code TPTeam} after any membership change, sharing toggle or
     *       external learn, without requiring a full re-sync. Routing is
     *       dynamic per-call, so a team switch (join/leave) is detected via
     *       the team UUID changing between snapshots.</li>
     *   <li>Re-reads {@code transmutationEmc} from
     *       {@link TPTeam#getEmc(UUID)} (via {@link #getEmc()}, i.e. the team
     *       pool for members) for the table display. EMC-only changes do not
     *       fire a knowledge event; pushing the EMC value itself stays with
     *       the existing {@code setEmc}/{@code syncEmc} paths.</li>
     *   <li>The share-knowledge flag is part of the snapshot: toggling it
     *       counts as a knowledge change (fires the event) even when the
     *       visible set is identical, so an open transmutation table
     *       re-renders immediately without rejoin.</li>
     *   <li>Fires {@code PlayerKnowledgeChangeEvent} only when knowledge
     *       state actually changed relative to the last snapshot, and never
     *       twice for the same change: learns performed by the embedded
     *       {@link #reconcileLearnedKnowledge()} already fire via
     *       {@code addKnowledge} → {@code tryAdd}, so refresh suppresses its
     *       own event in that case. Additive only — never removes knowledge.</li>
     * </ul>
     *
     * <p>Called from {@link #sync(ServerPlayer)} (table-open/sync path, which
     * membership and sharing changes already reach via {@code TPTeam.sync()}),
     * {@link #setEmc(BigInteger)}, {@link #syncKnowledgeChange(ServerPlayer,
     * ItemInfo, boolean)}, {@link #syncInputAndLocks(ServerPlayer, List,
     * TargetUpdateType)}, {@link #receiveInputsAndLocks(Map)},
     * {@link #deserializeNBT(CompoundTag)} and the offline-cache refresh in
     * {@code TransmutationOfflineMixin}. It deliberately never calls
     * {@code TPTeam.sync()}/{@code provider.sync()} itself (that would
     * recurse through {@link #sync(ServerPlayer)}); client payloads are
     * pushed by the caller's ensuing sync.
     *
     * @return true if knowledge, full-knowledge, team or EMC state changed
     *         relative to the previous snapshot (or a table-learned item was
     *         added); false when already converged
     */
    public boolean refreshKnowledge() {
        if (refreshing) return false;
        refreshing = true;
        try {
            TPTeam team = getTeam();
            synchronized (team) {
                boolean initialized = lastKnowledgeSnapshot != null;
                Set<ItemInfo> beforeKnowledge = initialized ? lastKnowledgeSnapshot : Set.of();
                boolean beforeFull = initialized && lastFullKnowledgeSnapshot;
                BigInteger beforeEmc = initialized ? lastEmcSnapshot : null;
                UUID beforeTeam = initialized ? lastTeamSnapshot : null;
                Boolean beforeSharing = initialized ? lastSharingKnowledgeSnapshot : null;

                boolean learnedAdded = reconcileLearnedKnowledge();

                TPTeam afterTeam = getTeam();
                Set<ItemInfo> afterKnowledge = new HashSet<>(afterTeam.getKnowledge(playerUUID.get()));
                boolean afterFull = afterTeam.hasFullKnowledge(playerUUID.get());
                BigInteger afterEmc = getEmc();
                if (afterEmc == null) afterEmc = BigInteger.ZERO;
                UUID afterTeamUuid = afterTeam.getUUID();
                boolean afterSharing = afterTeam.isSharingKnowledge();

                boolean knowledgeChanged = learnedAdded
                        || !beforeKnowledge.equals(afterKnowledge)
                        || beforeFull != afterFull
                        || !Objects.equals(beforeTeam, afterTeamUuid)
                        // Sharing toggle changes the visibility routing even
                        // when the visible set is unchanged.
                        || (initialized && !Objects.equals(beforeSharing, afterSharing));
                boolean emcChanged = initialized && !Objects.equals(beforeEmc, afterEmc);

                // Update the snapshot unconditionally so a change is only
                // ever reported (and fired) once.
                lastKnowledgeSnapshot = afterKnowledge;
                lastFullKnowledgeSnapshot = afterFull;
                lastEmcSnapshot = afterEmc;
                lastTeamSnapshot = afterTeamUuid;
                lastSharingKnowledgeSnapshot = afterSharing;

                if (initialized && knowledgeChanged && !learnedAdded) {
                    fireChangedEvent();
                }
                // First refresh only baselines: report a change only if a
                // table-learned item was actually added during it.
                return initialized ? (knowledgeChanged || emcChanged) : learnedAdded;
            }
        } finally {
            refreshing = false;
        }
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag properties = new CompoundTag();
        properties.put("inputlock", inputLocks.serializeNBT());
        if (!inTeam()) {
            // Non-team player: vanilla ProjectE NBT schema, so personal EMC/knowledge
            // persist exactly as vanilla and are never stored in a team row.
            properties.putString("transmutationEmc", getTeam().getEmc(playerUUID.get()).toString());
            ListTag knowledgeWrite = new ListTag();
            for (ItemInfo i : getTeam().getKnowledge(playerUUID.get()))
                knowledgeWrite.add(i.write(new CompoundTag()));
            properties.put("knowledge", knowledgeWrite);
            properties.putBoolean("fullknowledge", getTeam().hasFullKnowledge(playerUUID.get()));
        }
        return properties;
    }

    @Override
    public void deserializeNBT(CompoundTag properties) {
        for (int i = 0; i < inputLocks.getSlots(); i++) {
            inputLocks.setStackInSlot(i, ItemStack.EMPTY);
        }
        if (properties.contains("inputlock"))
            inputLocks.deserializeNBT(properties.getCompound("inputlock"));
        if (inTeam()) {
            refreshKnowledge();
            return;
        }
        // Non-team player: mirror vanilla ProjectE load into the ephemeral team.
        String emc = properties.getString("transmutationEmc");
        getTeam().setEmc(emc.isEmpty() ? BigInteger.ZERO : new BigInteger(emc), playerUUID.get());
        properties.getList("knowledge", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .forEach(t -> {
                    ItemInfo info = ItemInfo.read((CompoundTag) t);
                    if (info != null) getTeam().addKnowledge(info, playerUUID.get());
                });
        getTeam().setFullKnowledge(properties.getBoolean("fullknowledge"), playerUUID.get());
        refreshKnowledge();
    }
}