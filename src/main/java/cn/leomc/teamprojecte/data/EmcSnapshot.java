package cn.leomc.teamprojecte.data;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

/**
 * Plain persistence snapshot of team EMC.
 *
 * @param sharing   sharing mode (single shared pool) vs per-player slots
 * @param sharedEmc pool value in sharing mode
 * @param byPlayer  per-player EMC in non-sharing mode
 */
public record EmcSnapshot(
        boolean sharing,
        BigInteger sharedEmc,
        Map<UUID, BigInteger> byPlayer) {

    public EmcSnapshot {
        sharedEmc = sharedEmc == null ? BigInteger.ZERO : sharedEmc;
        byPlayer = byPlayer == null ? Map.of() : Map.copyOf(byPlayer);
    }

    public static EmcSnapshot emptySharing() {
        return new EmcSnapshot(true, BigInteger.ZERO, Map.of());
    }
}
