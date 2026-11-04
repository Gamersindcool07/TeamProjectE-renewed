package cn.leomc.teamprojecte.data;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plain persistence snapshot of team knowledge.
 *
 * @param sharing         sharing mode (shared union view) vs per-player sets
 * @param sharedFull      shared full-knowledge flag (sharing mode)
 * @param sharedKnowledge team/shared item set (items with no private contributor
 *                        are shared-only entries)
 * @param privateKnowledge per-player private item sets (bare minimum: counts are
 *                        derived from these, never persisted)
 * @param privateFull     players with personal full knowledge
 */
public record KnowledgeSnapshot(
        boolean sharing,
        boolean sharedFull,
        Set<ItemKey> sharedKnowledge,
        Map<UUID, Set<ItemKey>> privateKnowledge,
        Set<UUID> privateFull) {

    public KnowledgeSnapshot {
        sharedKnowledge = sharedKnowledge == null ? Set.of() : Set.copyOf(sharedKnowledge);
        privateKnowledge = privateKnowledge == null ? Map.of() : Map.copyOf(privateKnowledge);
        privateFull = privateFull == null ? Set.of() : Set.copyOf(privateFull);
    }

    public static KnowledgeSnapshot emptySharing() {
        return new KnowledgeSnapshot(true, false, Set.of(), Map.of(), Set.of());
    }
}
