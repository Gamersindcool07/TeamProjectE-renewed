package cn.leomc.teamprojecte;

import cn.leomc.teamprojecte.data.EmcSnapshot;
import net.minecraft.Util;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public interface EMCData {
    BigInteger getEMC(UUID player);

    void setEMC(BigInteger emc, UUID player);

    void removeMember(UUID player);

    EMCData convert(UUID owner);

    /** Plain persistence snapshot (no NBT). */
    EmcSnapshot snapshot();

    /** Build runtime data from a persistence snapshot (no NBT). */
    void restoreFrom(EmcSnapshot snap);

    static EMCData restore(EmcSnapshot snap) {
        EMCData data = snap.sharing() ? new Sharing() : new NotSharing();
        data.restoreFrom(snap);
        return data;
    }


    class Sharing implements EMCData {
        private BigInteger emc = BigInteger.ZERO;

        @Override
        public BigInteger getEMC(UUID player) {
            return emc;
        }

        @Override
        public void setEMC(BigInteger emc, UUID player) {
            this.emc = emc;
        }

        @Override
        public void removeMember(UUID player) {
        }

        @Override
        public EMCData convert(UUID owner) {
            EMCData data = new NotSharing();
            data.setEMC(emc, owner);
            return data;
        }

        @Override
        public EmcSnapshot snapshot() {
            return new EmcSnapshot(true, emc, Map.of());
        }

        @Override
        public void restoreFrom(EmcSnapshot snap) {
            emc = snap.sharedEmc();
        }

        public static String getType() {
            return "sharing";
        }
    }

    class NotSharing implements EMCData {
        private final Map<UUID, BigInteger> emc = new HashMap<>();

        @Override
        public BigInteger getEMC(UUID player) {
            return emc.getOrDefault(player, BigInteger.ZERO);
        }

        @Override
        public void setEMC(BigInteger emc, UUID player) {
            this.emc.put(player, emc);
        }

        @Override
        public void removeMember(UUID player) {
            emc.remove(player);
        }

        @Override
        public EMCData convert(UUID owner) {
            EMCData data = new Sharing();
            data.setEMC(emc.values().stream().reduce(BigInteger.ZERO, BigInteger::add), Util.NIL_UUID);
            return data;
        }

        @Override
        public EmcSnapshot snapshot() {
            return new EmcSnapshot(false, BigInteger.ZERO, Map.copyOf(emc));
        }

        @Override
        public void restoreFrom(EmcSnapshot snap) {
            emc.clear();
            emc.putAll(snap.byPlayer());
        }

        public static String getType() {
            return "not_sharing";
        }
    }
}
