package cn.leomc.teamprojecte.persistence;

import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.data.EmcSnapshot;
import cn.leomc.teamprojecte.data.ItemKey;
import cn.leomc.teamprojecte.data.KnowledgeSnapshot;
import cn.leomc.teamprojecte.data.TeamEntity;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared JDBC mapping between {@link TeamEntity} and normalized SQL tables.
 * No NBT/SNBT blobs: collections are normalized rows, EMC is a single
 * BigInteger string per slot, vanished is a comma-separated UUID list.
 * Shared (team-level) rows use {@code player_uuid = ''}.
 */
final class EntitySql {

    static final String SHARED = "";

    private EntitySql() {
    }

    static void writeTeam(java.sql.Connection c, TeamEntity e) throws SQLException {
        String team = e.getUuid().toString();
        try (var ps = c.prepareStatement(
                "INSERT INTO teams (uuid, owner, name, free_join, vanished, knowledge_sharing, emc_sharing, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, team);
            ps.setString(2, e.getOwner().toString());
            ps.setString(3, e.getName());
            ps.setInt(4, e.getSettings().freeJoin() ? 1 : 0);
            ps.setString(5, joinUuids(e.getVanished()));
            ps.setInt(6, e.getSettings().shareKnowledge() ? 1 : 0);
            ps.setInt(7, e.getSettings().shareEmc() ? 1 : 0);
            ps.setInt(8, e.getVersion());
            ps.executeUpdate();
        }
        try (var ps = c.prepareStatement("INSERT INTO team_members (team_uuid, player_uuid) VALUES (?, ?)")) {
            for (UUID m : e.getMembers()) {
                ps.setString(1, team);
                ps.setString(2, m.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        KnowledgeSnapshot k = e.getKnowledge();
        try (var ps = c.prepareStatement("INSERT INTO team_knowledge (team_uuid, player_uuid, item_id) VALUES (?, ?, ?)")) {
            Set<ItemKey> covered = new HashSet<>();
            for (var entry : k.privateKnowledge().entrySet()) {
                for (ItemKey key : entry.getValue()) {
                    ps.setString(1, team);
                    ps.setString(2, entry.getKey().toString());
                    ps.setString(3, key.toString());
                    ps.addBatch();
                    covered.add(key);
                }
            }
            if (k.sharing()) {
                for (ItemKey key : k.sharedKnowledge()) {
                    if (covered.contains(key)) continue;
                    ps.setString(1, team);
                    ps.setString(2, SHARED);
                    ps.setString(3, key.toString());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        try (var ps = c.prepareStatement("INSERT INTO team_full_knowledge (team_uuid, player_uuid) VALUES (?, ?)")) {
            if (k.sharedFull()) {
                ps.setString(1, team);
                ps.setString(2, SHARED);
                ps.addBatch();
            }
            for (UUID p : k.privateFull()) {
                ps.setString(1, team);
                ps.setString(2, p.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        EmcSnapshot emc = e.getEmc();
        try (var ps = c.prepareStatement("INSERT INTO team_emc (team_uuid, player_uuid, emc) VALUES (?, ?, ?)")) {
            if (emc.sharing()) {
                ps.setString(1, team);
                ps.setString(2, SHARED);
                ps.setString(3, emc.sharedEmc().toString());
                ps.addBatch();
            } else {
                for (var entry : emc.byPlayer().entrySet()) {
                    ps.setString(1, team);
                    ps.setString(2, entry.getKey().toString());
                    ps.setString(3, entry.getValue().toString());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    static void deleteTeamRows(java.sql.Connection c, String team) throws SQLException {
        for (String table : new String[]{"team_members", "team_knowledge", "team_full_knowledge", "team_emc"}) {
            try (var ps = c.prepareStatement("DELETE FROM " + table + " WHERE team_uuid = ?")) {
                ps.setString(1, team);
                ps.executeUpdate();
            }
        }
        try (var ps = c.prepareStatement("DELETE FROM teams WHERE uuid = ?")) {
            ps.setString(1, team);
            ps.executeUpdate();
        }
    }

    static Map<UUID, TPTeam> loadAll(java.sql.Connection c) throws SQLException {
        Map<UUID, TeamEntity> entities = new HashMap<>();
        Map<String, TeamEntity> byString = new HashMap<>();
        try (var st = c.createStatement();
             var rs = st.executeQuery(
                     "SELECT uuid, owner, name, free_join, vanished, knowledge_sharing, emc_sharing, version FROM teams")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                TeamEntity e = new TeamEntity(uuid,
                        UUID.fromString(rs.getString("owner")),
                        rs.getString("name"));
                e.setSettings(new cn.leomc.teamprojecte.data.TeamSettings(
                        rs.getInt("knowledge_sharing") != 0,
                        rs.getInt("emc_sharing") != 0,
                        rs.getInt("free_join") != 0));
                e.addAllVanished(splitUuids(rs.getString("vanished")));
                e.setVersion(rs.getInt("version"));
                entities.put(uuid, e);
                byString.put(rs.getString("uuid"), e);
            }
        }
        // Members
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT team_uuid, player_uuid FROM team_members")) {
            while (rs.next()) {
                TeamEntity e = byString.get(rs.getString("team_uuid"));
                if (e != null) e.addMember(UUID.fromString(rs.getString("player_uuid")));
            }
        }
        // Knowledge
        Map<UUID, Map<UUID, Set<ItemKey>>> privateKnowledge = new HashMap<>();
        Map<UUID, Set<ItemKey>> sharedKnowledge = new HashMap<>();
        Set<UUID> sharedFull = new HashSet<>();
        Map<UUID, Set<UUID>> privateFull = new HashMap<>();
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT team_uuid, player_uuid, item_id FROM team_knowledge")) {
            while (rs.next()) {
                TeamEntity e = byString.get(rs.getString("team_uuid"));
                if (e == null) continue;
                ItemKey key = ItemKey.parse(rs.getString("item_id"));
                if (key == null) continue;
                String player = rs.getString("player_uuid");
                if (player == null || player.isEmpty()) {
                    sharedKnowledge.computeIfAbsent(e.getUuid(), x -> new HashSet<>()).add(key);
                } else {
                    privateKnowledge.computeIfAbsent(e.getUuid(), x -> new HashMap<>())
                            .computeIfAbsent(UUID.fromString(player), x -> new HashSet<>()).add(key);
                }
            }
        }
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT team_uuid, player_uuid FROM team_full_knowledge")) {
            while (rs.next()) {
                TeamEntity e = byString.get(rs.getString("team_uuid"));
                if (e == null) continue;
                String player = rs.getString("player_uuid");
                if (player == null || player.isEmpty()) sharedFull.add(e.getUuid());
                else privateFull.computeIfAbsent(e.getUuid(), x -> new HashSet<>()).add(UUID.fromString(player));
            }
        }
        // Attach knowledge snapshots: need per-team privateFull sets, so re-query grouping is kept simple:
        // (privateFull/sharedFull collected per-team below via second pass is merged here.)
        // EMC
        Map<UUID, java.math.BigInteger> sharedEmc = new HashMap<>();
        Map<UUID, Map<UUID, java.math.BigInteger>> byPlayer = new HashMap<>();
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT team_uuid, player_uuid, emc FROM team_emc")) {
            while (rs.next()) {
                TeamEntity e = byString.get(rs.getString("team_uuid"));
                if (e == null) continue;
                String player = rs.getString("player_uuid");
                java.math.BigInteger value = new java.math.BigInteger(rs.getString("emc"));
                if (player == null || player.isEmpty()) sharedEmc.put(e.getUuid(), value);
                else byPlayer.computeIfAbsent(e.getUuid(), x -> new HashMap<>()).put(UUID.fromString(player), value);
            }
        }
        // Finalise snapshots per team
        for (TeamEntity e : entities.values()) {
            UUID id = e.getUuid();
            boolean kSharing = e.getSettings().shareKnowledge();
            boolean eSharing = e.getSettings().shareEmc();
            e.setKnowledge(new KnowledgeSnapshot(
                    kSharing,
                    sharedFull.contains(id),
                    sharedKnowledge.getOrDefault(id, Set.of()),
                    privateKnowledge.getOrDefault(id, Map.of()),
                    privateFull.getOrDefault(id, Set.of())));
            e.setEmc(new EmcSnapshot(
                    eSharing,
                    sharedEmc.getOrDefault(id, java.math.BigInteger.ZERO),
                    byPlayer.getOrDefault(id, Map.of())));
        }
        Map<UUID, TPTeam> out = new HashMap<>();
        for (TeamEntity e : entities.values()) {
            TPTeam team = TPTeam.fromEntity(e);
            if (team != null) out.put(team.getUUID(), team);
        }
        return out;
    }

    static String joinUuids(Set<UUID> uuids) {
        StringBuilder sb = new StringBuilder();
        for (UUID u : uuids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(u);
        }
        return sb.toString();
    }

    static Set<UUID> splitUuids(String s) {
        Set<UUID> out = new HashSet<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                try { out.add(UUID.fromString(t)); } catch (IllegalArgumentException ignored) { }
            }
        }
        return out;
    }
}
