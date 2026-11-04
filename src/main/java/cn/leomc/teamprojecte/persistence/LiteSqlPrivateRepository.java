package cn.leomc.teamprojecte.persistence;

import cn.leomc.teamprojecte.TeamProjectE;
import cn.leomc.teamprojecte.data.ItemKey;
import cn.leomc.teamprojecte.data.PlayerEntity;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * LiteSQL private (per-player) repository: SQLite file
 * {@code teamprojecte_private.db}. Holds only source-of-truth data:
 * personal knowledge rows, one EMC string per player, team mapping.
 */
public class LiteSqlPrivateRepository implements PlayerRepository {

    private final String jdbcUrl;

    public LiteSqlPrivateRepository(Path dbFile) throws SQLException {
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not on classpath", e);
        }
        ensureSchema();
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void ensureSchema() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS player_knowledge (" +
                    "player_uuid TEXT NOT NULL, item_id TEXT NOT NULL, " +
                    "PRIMARY KEY (player_uuid, item_id))");
            st.execute("CREATE TABLE IF NOT EXISTS player_emc (" +
                    "player_uuid TEXT PRIMARY KEY, emc TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS player_team (" +
                    "player_uuid TEXT PRIMARY KEY, team_uuid TEXT)");
        }
    }

    @Override
    public PlayerEntity load(UUID playerUuid) {
        if (playerUuid == null) return null;
        Set<ItemKey> knowledge = new HashSet<>();
        UUID team = null;
        java.math.BigInteger emc = java.math.BigInteger.ZERO;
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_id FROM player_knowledge WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ItemKey key = ItemKey.parse(rs.getString("item_id"));
                        if (key != null) knowledge.add(key);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT emc FROM player_emc WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) emc = new java.math.BigInteger(rs.getString("emc"));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT team_uuid FROM player_team WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String t = rs.getString("team_uuid");
                        if (t != null && !t.isBlank()) team = UUID.fromString(t);
                    }
                }
            }
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to load private data for {}", playerUuid, e);
        }
        return new PlayerEntity(playerUuid, team, knowledge, emc);
    }

    @Override
    public void save(PlayerEntity entity) {
        if (entity == null || entity.playerUuid() == null) return;
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM player_knowledge WHERE player_uuid = ?")) {
                    del.setString(1, entity.playerUuid().toString());
                    del.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO player_knowledge (player_uuid, item_id) VALUES (?, ?)")) {
                    for (ItemKey key : entity.knowledge()) {
                        ins.setString(1, entity.playerUuid().toString());
                        ins.setString(2, key.toString());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                try (PreparedStatement up = c.prepareStatement(
                        "INSERT INTO player_emc (player_uuid, emc) VALUES (?, ?) " +
                                "ON CONFLICT(player_uuid) DO UPDATE SET emc = excluded.emc")) {
                    up.setString(1, entity.playerUuid().toString());
                    up.setString(2, entity.emc().toString());
                    up.executeUpdate();
                }
                try (PreparedStatement up = c.prepareStatement(
                        "INSERT INTO player_team (player_uuid, team_uuid) VALUES (?, ?) " +
                                "ON CONFLICT(player_uuid) DO UPDATE SET team_uuid = excluded.team_uuid")) {
                    up.setString(1, entity.playerUuid().toString());
                    up.setString(2, entity.teamUuid() == null ? null : entity.teamUuid().toString());
                    up.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to save private data for {}", entity.playerUuid(), e);
        }
    }

    @Override
    public void deleteTeamMapping(UUID teamUuid) {
        if (teamUuid == null) return;
        try (Connection c = open();
             PreparedStatement del = c.prepareStatement(
                     "DELETE FROM player_team WHERE team_uuid = ?")) {
            del.setString(1, teamUuid.toString());
            del.executeUpdate();
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to delete private team mapping for {}", teamUuid, e);
        }
    }

    @Override
    public List<PlayerEntity> loadAll() {
        List<PlayerEntity> out = new ArrayList<>();
        Set<UUID> players = new HashSet<>();
        try (Connection c = open(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT player_uuid FROM player_knowledge")) {
                while (rs.next()) players.add(UUID.fromString(rs.getString("player_uuid")));
            }
            try (ResultSet rs = st.executeQuery("SELECT player_uuid FROM player_emc")) {
                while (rs.next()) players.add(UUID.fromString(rs.getString("player_uuid")));
            }
            try (ResultSet rs = st.executeQuery("SELECT player_uuid FROM player_team")) {
                while (rs.next()) players.add(UUID.fromString(rs.getString("player_uuid")));
            }
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to list private players", e);
        }
        for (UUID p : players) out.add(load(p));
        return out;
    }

    @Override
    public void close() {
        // Short-lived connections; nothing to close.
    }
}
