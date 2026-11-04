package cn.leomc.teamprojecte;

import cn.leomc.teamprojecte.data.ItemKey;
import cn.leomc.teamprojecte.data.KnowledgeSnapshot;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.Util;

import java.util.*;
import java.util.stream.Collectors;

public interface KnowledgeData {

    boolean addKnowledge(ItemInfo info, UUID player);

    boolean removeKnowledge(ItemInfo info, UUID player);

    void clearKnowledge(UUID player);

    Set<ItemInfo> getKnowledge(UUID player);

    /** Knowledge the player personally added (private set). In Sharing mode this is the per-player private map; in NotSharing mode this equals {@link #getKnowledge}. */
    default Set<ItemInfo> getPrivateKnowledge(UUID player) {
        return getKnowledge(player);
    }

    /** Whether the player personally has full knowledge (private flag). */
    default boolean hasPrivateFullKnowledge(UUID player) {
        return hasFullKnowledge(player);
    }

    void setFullKnowledge(boolean fullKnowledge, UUID player);

    boolean hasFullKnowledge(UUID player);

    void removeMember(UUID player);

    KnowledgeData convert(UUID owner);

    /** Plain persistence snapshot (no NBT). */
    KnowledgeSnapshot snapshot();

    /** Build runtime data from a persistence snapshot (no NBT). */
    void restoreFrom(KnowledgeSnapshot snap);

    /**
     * Team-level knowledge view: items held by at least one member
     * (count &gt; 0 for private knowledge, the full set for shared knowledge).
     *
     * <p>Stats/UI only. Extraction eligibility must always use the viewing
     * player's own view ({@link #getKnowledge(UUID)} with that player's UUID,
     * mirroring {@code TeamKnowledgeProvider.hasKnowledge}), never this team
     * aggregate — otherwise a non-sharing member would be blocked from
     * extracting their own items or granted another member's.
     */
    default Set<ItemInfo> getTeamKnowledge() {
        return Set.copyOf(getKnowledge(Util.NIL_UUID));
    }

    /** How many distinct members currently hold {@code info} (0 if none). */
    default int getMemberCount(ItemInfo info) {
        return getTeamKnowledge().contains(info) ? 1 : 0;
    }

    static KnowledgeData restore(KnowledgeSnapshot snap) {
        KnowledgeData data = snap.sharing() ? new Sharing() : new NotSharing();
        data.restoreFrom(snap);
        return data;
    }


    class Sharing implements KnowledgeData {
        private final Set<ItemInfo> knowledge = new HashSet<>();
        private boolean fullKnowledge = false;
        private final Map<UUID, Set<ItemInfo>> privateKnowledge = new HashMap<>();
        private final Map<ItemInfo, Integer> privateMemberCounts = new HashMap<>();
        private final Set<UUID> privateFullKnowledge = new HashSet<>();

        private void incrementPrivateCount(ItemInfo info) {
            int count = privateMemberCounts.getOrDefault(info, 0) + 1;
            privateMemberCounts.put(info, count);
            if (count == 1)
                knowledge.add(info);
        }

        private void decrementPrivateCount(ItemInfo info) {
            int count = privateMemberCounts.getOrDefault(info, 0) - 1;
            if (count <= 0) {
                privateMemberCounts.remove(info);
                knowledge.remove(info);
            } else {
                privateMemberCounts.put(info, count);
            }
        }

        private void rebuildPrivateCounts() {
            privateMemberCounts.clear();
            for (Set<ItemInfo> playerItems : privateKnowledge.values()) {
                if (playerItems == null)
                    continue;
                for (ItemInfo info : playerItems) {
                    if (info == null)
                        continue;
                    privateMemberCounts.merge(info, 1, Integer::sum);
                }
            }
            // Ensure every counted item is in the shared team view.
            privateMemberCounts.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .forEach(e -> knowledge.add(e.getKey()));
        }

        @Override
        public boolean addKnowledge(ItemInfo info, UUID player) {
            if (player == null || player.equals(Util.NIL_UUID)) {
                // Shared-only add (legacy / conversion seeding): no private contributor.
                return knowledge.add(info);
            }
            Set<ItemInfo> priv = privateKnowledge.computeIfAbsent(player, k -> new HashSet<>());
            if (priv.add(info)) {
                incrementPrivateCount(info);
                return true;
            }
            return false;
        }

        @Override
        public boolean removeKnowledge(ItemInfo info, UUID player) {
            Set<ItemInfo> priv = player == null ? null : privateKnowledge.get(player);
            boolean hadPrivate = priv != null && priv.remove(info);
            if (hadPrivate) {
                decrementPrivateCount(info);
                return true;
            }
            // Plan: unknowledging an item the player doesn't hold is a no-op,
            // unless it is a legacy shared-only entry (count == 0, no private
            // contributor at all) or a NIL_UUID shared-only removal.
            if (player == null || player.equals(Util.NIL_UUID)) {
                return knowledge.remove(info);
            }
            if (privateMemberCounts.getOrDefault(info, 0) == 0) {
                return knowledge.remove(info);
            }
            return false;
        }

        @Override
        public void clearKnowledge(UUID player) {
            if (player != null && player.equals(Util.NIL_UUID)) {
                knowledge.clear();
                privateKnowledge.clear();
                privateMemberCounts.clear();
                privateFullKnowledge.clear();
                fullKnowledge = false;
                return;
            }
            Set<ItemInfo> removed = privateKnowledge.remove(player);
            privateFullKnowledge.remove(player);
            fullKnowledge = !privateFullKnowledge.isEmpty();
            if (removed != null) {
                for (ItemInfo info : removed)
                    decrementPrivateCount(info);
            }
        }

        @Override
        public Set<ItemInfo> getKnowledge(UUID player) {
            Set<ItemInfo> union = new HashSet<>(knowledge);
            if (player != null && !player.equals(Util.NIL_UUID)) {
                union.addAll(privateKnowledge.getOrDefault(player, Collections.emptySet()));
            }
            return Set.copyOf(union);
        }

        @Override
        public Set<ItemInfo> getPrivateKnowledge(UUID player) {
            if (player == null || player.equals(Util.NIL_UUID)) {
                return Set.copyOf(knowledge);
            }
            return Set.copyOf(privateKnowledge.getOrDefault(player, Collections.emptySet()));
        }

        @Override
        public boolean hasPrivateFullKnowledge(UUID player) {
            if (player == null || player.equals(Util.NIL_UUID)) {
                return fullKnowledge;
            }
            return privateFullKnowledge.contains(player);
        }

        @Override
        public Set<ItemInfo> getTeamKnowledge() {
            return Set.copyOf(knowledge);
        }

        @Override
        public int getMemberCount(ItemInfo info) {
            return privateMemberCounts.getOrDefault(info, 0);
        }

        @Override
        public void setFullKnowledge(boolean fullKnowledge, UUID player) {
            if (player != null && player.equals(Util.NIL_UUID)) {
                this.fullKnowledge = fullKnowledge;
                return;
            }
            if (fullKnowledge) {
                privateFullKnowledge.add(player);
            } else {
                privateFullKnowledge.remove(player);
            }
            this.fullKnowledge = !privateFullKnowledge.isEmpty();
        }

        @Override
        public boolean hasFullKnowledge(UUID player) {
            if (player == null || player.equals(Util.NIL_UUID)) {
                return fullKnowledge;
            }
            return fullKnowledge || privateFullKnowledge.contains(player);
        }

        @Override
        public void removeMember(UUID player) {
            // Retain private knowledge for the leaving player so rejoin
            // restores what they personally added (plan: detached, not deleted).
        }

        @Override
        public KnowledgeData convert(UUID owner) {
            KnowledgeData data = new NotSharing();
            // Per-player private sets carry over as their personal sets.
            privateKnowledge.forEach((uuid, set) -> set.forEach(info -> data.addKnowledge(info, uuid)));
            // Shared-only items (legacy, no private record) go to the owner.
            Set<ItemInfo> covered = privateKnowledge.values().stream().flatMap(Collection::stream).collect(Collectors.toSet());
            knowledge.forEach(info -> {
                if (!covered.contains(info)) {
                    data.addKnowledge(info, owner);
                }
            });
            if (fullKnowledge) {
                // Team shared full knowledge transfers to owner if no private flag covers it.
                boolean anyPrivate = !privateFullKnowledge.isEmpty();
                if (!anyPrivate) {
                    data.setFullKnowledge(true, owner);
                }
            }
            privateFullKnowledge.forEach(uuid -> data.setFullKnowledge(true, uuid));
            return data;
        }

        @Override
        public KnowledgeSnapshot snapshot() {
            Map<UUID, Set<ItemKey>> privateKeys = new HashMap<>();
            privateKnowledge.forEach((uuid, set) -> {
                Set<ItemKey> keys = new HashSet<>();
                for (ItemInfo info : set) {
                    ItemKey key = ItemKey.from(info);
                    if (key != null) keys.add(key);
                }
                privateKeys.put(uuid, keys);
            });
            Set<ItemKey> sharedKeys = new HashSet<>();
            for (ItemInfo info : knowledge) {
                ItemKey key = ItemKey.from(info);
                if (key != null) sharedKeys.add(key);
            }
            return new KnowledgeSnapshot(true, fullKnowledge, sharedKeys, privateKeys,
                    Set.copyOf(privateFullKnowledge));
        }

        @Override
        public void restoreFrom(KnowledgeSnapshot snap) {
            knowledge.clear();
            privateKnowledge.clear();
            privateMemberCounts.clear();
            privateFullKnowledge.clear();
            fullKnowledge = false;
            // Private contributor sets first (addKnowledge maintains counts).
            snap.privateKnowledge().forEach((player, keys) -> {
                for (ItemKey key : keys) {
                    ItemInfo info = key.toItemInfo();
                    if (info != null) addKnowledge(info, player);
                }
            });
            // Shared-only items (no private contributor) seed the team view.
            Set<ItemKey> covered = snap.privateKnowledge().values().stream()
                    .flatMap(Collection::stream).collect(Collectors.toSet());
            for (ItemKey key : snap.sharedKnowledge()) {
                if (!covered.contains(key)) {
                    ItemInfo info = key.toItemInfo();
                    if (info != null) addKnowledge(info, Util.NIL_UUID);
                }
            }
            if (snap.sharedFull()) setFullKnowledge(true, Util.NIL_UUID);
            snap.privateFull().forEach(player -> setFullKnowledge(true, player));
        }

        public static String getType() {
            return "sharing";
        }
    }

    /**
     * Private knowledge with reference counting.
     *
     * <p>Per-player sets are retained so {@link #getKnowledge(UUID)} keeps its
     * privacy semantics (a member only sees their own items), while
     * {@code itemMemberCounts} and the explicit {@code teamKnowledge} set
     * implement the invariant: an item is in team knowledge iff at least one
     * member holds it. Unknowledging an item decrements its count and only
     * prunes it from team knowledge when the count reaches zero, so one
     * member forgetting an item never removes it for members who still know it.
     */
    class NotSharing implements KnowledgeData {
        private final Map<UUID, Set<ItemInfo>> knowledge = new HashMap<>();
        private final Map<ItemInfo, Integer> itemMemberCounts = new HashMap<>();
        private final Set<ItemInfo> teamKnowledge = new HashSet<>();
        private final Set<UUID> fullKnowledge = new HashSet<>();

        private void incrementCount(ItemInfo info) {
            int count = itemMemberCounts.getOrDefault(info, 0) + 1;
            itemMemberCounts.put(info, count);
            if (count == 1)
                teamKnowledge.add(info);
        }

        private void decrementCount(ItemInfo info) {
            int count = itemMemberCounts.getOrDefault(info, 0) - 1;
            if (count <= 0) {
                itemMemberCounts.remove(info);
                teamKnowledge.remove(info);
            } else {
                itemMemberCounts.put(info, count);
            }
        }

        private void rebuildCounts() {
            itemMemberCounts.clear();
            teamKnowledge.clear();
            for (Set<ItemInfo> playerItems : knowledge.values()) {
                if (playerItems == null)
                    continue;
                for (ItemInfo info : playerItems) {
                    if (info == null)
                        continue;
                    itemMemberCounts.merge(info, 1, Integer::sum);
                }
            }
            itemMemberCounts.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .forEach(e -> teamKnowledge.add(e.getKey()));
        }

        @Override
        public boolean addKnowledge(ItemInfo info, UUID player) {
            Set<ItemInfo> playerSet = knowledge.computeIfAbsent(player, k -> new HashSet<>());
            if (playerSet.add(info)) {
                incrementCount(info);
                return true;
            }
            return false;
        }

        @Override
        public boolean removeKnowledge(ItemInfo info, UUID player) {
            Set<ItemInfo> playerSet = knowledge.get(player);
            if (playerSet == null)
                return false;
            if (playerSet.remove(info)) {
                decrementCount(info);
                if (playerSet.isEmpty())
                    knowledge.remove(player);
                return true;
            }
            return false;
        }

        @Override
        public void clearKnowledge(UUID player) {
            Set<ItemInfo> playerSet = knowledge.remove(player);
            if (playerSet == null)
                return;
            for (ItemInfo info : playerSet)
                decrementCount(info);
        }

        @Override
        public Set<ItemInfo> getKnowledge(UUID player) {
            return Set.copyOf(knowledge.getOrDefault(player, Collections.emptySet()));
        }

        @Override
        public Set<ItemInfo> getTeamKnowledge() {
            return Set.copyOf(teamKnowledge);
        }

        @Override
        public int getMemberCount(ItemInfo info) {
            return itemMemberCounts.getOrDefault(info, 0);
        }

        @Override
        public void setFullKnowledge(boolean fullKnowledge, UUID player) {
            if (fullKnowledge)
                this.fullKnowledge.add(player);
            else
                this.fullKnowledge.remove(player);
        }

        @Override
        public boolean hasFullKnowledge(UUID player) {
            return fullKnowledge.contains(player);
        }

        @Override
        public void removeMember(UUID player) {
            // Retain private knowledge for rejoin restore (detached, not deleted).
        }

        @Override
        public KnowledgeData convert(UUID owner) {
            KnowledgeData data = new Sharing();
            teamKnowledge.forEach(info -> data.addKnowledge(info, Util.NIL_UUID));
            // Preserve per-player private sets in the new Sharing mode.
            knowledge.forEach((uuid, set) -> {
                if (uuid == null || uuid.equals(Util.NIL_UUID)) return;
                set.forEach(info -> data.addKnowledge(info, uuid));
            });
            // Full knowledge: any private full becomes shared full.
            data.setFullKnowledge(!fullKnowledge.isEmpty(), Util.NIL_UUID);
            fullKnowledge.forEach(uuid -> data.setFullKnowledge(true, uuid));
            return data;
        }

        @Override
        public KnowledgeSnapshot snapshot() {
            Map<UUID, Set<ItemKey>> privateKeys = new HashMap<>();
            knowledge.forEach((uuid, set) -> {
                Set<ItemKey> keys = new HashSet<>();
                for (ItemInfo info : set) {
                    ItemKey key = ItemKey.from(info);
                    if (key != null) keys.add(key);
                }
                privateKeys.put(uuid, keys);
            });
            Set<ItemKey> sharedKeys = new HashSet<>();
            for (ItemInfo info : teamKnowledge) {
                ItemKey key = ItemKey.from(info);
                if (key != null) sharedKeys.add(key);
            }
            return new KnowledgeSnapshot(false, false, sharedKeys, privateKeys, Set.copyOf(fullKnowledge));
        }

        @Override
        public void restoreFrom(KnowledgeSnapshot snap) {
            knowledge.clear();
            itemMemberCounts.clear();
            teamKnowledge.clear();
            fullKnowledge.clear();
            snap.privateKnowledge().forEach((player, keys) -> {
                for (ItemKey key : keys) {
                    ItemInfo info = key.toItemInfo();
                    if (info != null) addKnowledge(info, player);
                }
            });
            // Counts-only fallback: shared items with no private contributor
            // are kept in the team view (counts derived from per-player sets
            // remain authoritative otherwise).
            Set<ItemKey> covered = snap.privateKnowledge().values().stream()
                    .flatMap(Collection::stream).collect(Collectors.toSet());
            for (ItemKey key : snap.sharedKnowledge()) {
                if (!covered.contains(key)) {
                    ItemInfo info = key.toItemInfo();
                    if (info != null) {
                        itemMemberCounts.putIfAbsent(info, 1);
                        teamKnowledge.add(info);
                    }
                }
            }
            snap.privateFull().forEach(player -> setFullKnowledge(true, player));
        }

        public static String getType() {
            return "not_sharing";
        }
    }
}
