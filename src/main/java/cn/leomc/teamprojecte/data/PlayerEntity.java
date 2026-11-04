package cn.leomc.teamprojecte.data;

import java.math.BigInteger;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player private data (private DB): personal knowledge, EMC slot, and
 * current team mapping. Derived/cached data is never stored here.
 */
public record PlayerEntity(
        UUID playerUuid,
        UUID teamUuid,
        Set<ItemKey> knowledge,
        BigInteger emc) {

    public PlayerEntity {
        knowledge = knowledge == null ? Set.of() : Set.copyOf(knowledge);
        emc = emc == null ? BigInteger.ZERO : emc;
    }
}
