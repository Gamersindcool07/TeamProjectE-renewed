package cn.leomc.teamprojecte.persistence;

import cn.leomc.teamprojecte.TeamProjectE;
import cn.leomc.teamprojecte.TPTeam;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * JDBC-backed team storage over normalized tables (see {@link EntitySql}).
 * Short-lived connections via {@link DriverManager}; every save is a single
 * transaction (delete + insert) so multi-table writes are atomic.
 */
public abstract class JdbcTeamRepository implements TeamDataStore {

    protected final String jdbcUrl;
    protected final String username;
    protected final String password;
    protected final int connectionTimeoutMs;

    public JdbcTeamRepository(String jdbcUrl, String username, String password, int connectionTimeoutMs)
            throws SQLException {
        if (jdbcUrl == null || jdbcUrl.isBlank()) throw new SQLException("jdbcUrl is empty");
        this.jdbcUrl = jdbcUrl;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.connectionTimeoutMs = connectionTimeoutMs <= 0 ? 30000 : connectionTimeoutMs;
        loadDriver();
        ensureSchema();
    }

    private void loadDriver() throws SQLException {
        String driver = null;
        if (jdbcUrl.startsWith("jdbc:sqlite:")) driver = "org.sqlite.JDBC";
        else if (jdbcUrl.startsWith("jdbc:mysql:")) driver = "com.mysql.cj.jdbc.Driver";
        else if (jdbcUrl.startsWith("jdbc:postgresql:")) driver = "org.postgresql.Driver";
        if (driver != null) {
            try {
                Class.forName(driver);
            } catch (ClassNotFoundException e) {
                throw new SQLException("JDBC driver not on classpath: " + driver
                        + " (add it to the server mods/libraries path)", e);
            }
        }
    }

    protected Connection open() throws SQLException {
        Properties props = new Properties();
        if (!username.isEmpty()) props.setProperty("user", username);
        if (!password.isEmpty()) props.setProperty("password", password);
        props.setProperty("connectTimeout", String.valueOf(connectionTimeoutMs));
        DriverManager.setLoginTimeout(Math.max(1, connectionTimeoutMs / 1000));
        return DriverManager.getConnection(jdbcUrl, props);
    }

    private void ensureSchema() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS teams (" +
                    "uuid TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL, " +
                    "free_join INTEGER NOT NULL DEFAULT 1, vanished TEXT NOT NULL DEFAULT '', " +
                    "knowledge_sharing INTEGER NOT NULL DEFAULT 1, emc_sharing INTEGER NOT NULL DEFAULT 1, " +
                    "version INTEGER NOT NULL DEFAULT 2)");
            st.execute("CREATE TABLE IF NOT EXISTS team_members (" +
                    "team_uuid TEXT NOT NULL, player_uuid TEXT NOT NULL, " +
                    "PRIMARY KEY (team_uuid, player_uuid))");
            st.execute("CREATE TABLE IF NOT EXISTS team_knowledge (" +
                    "team_uuid TEXT NOT NULL, player_uuid TEXT NOT NULL, item_id TEXT NOT NULL, " +
                    "PRIMARY KEY (team_uuid, player_uuid, item_id))");
            st.execute("CREATE TABLE IF NOT EXISTS team_full_knowledge (" +
                    "team_uuid TEXT NOT NULL, player_uuid TEXT NOT NULL, " +
                    "PRIMARY KEY (team_uuid, player_uuid))");
            st.execute("CREATE TABLE IF NOT EXISTS team_emc (" +
                    "team_uuid TEXT NOT NULL, player_uuid TEXT NOT NULL, emc TEXT NOT NULL, " +
                    "PRIMARY KEY (team_uuid, player_uuid))");
        }
    }

    @Override
    public Map<UUID, TPTeam> loadAll() {
        Map<UUID, TPTeam> out = new HashMap<>();
        try (Connection c = open()) {
            out.putAll(EntitySql.loadAll(c));
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to load teams from SQL backend", e);
        }
        return out;
    }

    @Override
    public void save(TPTeam team) {
        if (team == null) return;
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                EntitySql.deleteTeamRows(c, team.getUUID().toString());
                EntitySql.writeTeam(c, team.toEntity());
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to save team {} to SQL backend", team.getUUID(), e);
        }
    }

    @Override
    public void saveAll(Map<UUID, TPTeam> teams) {
        if (teams == null) return;
        for (TPTeam t : teams.values()) save(t);
    }

    @Override
    public void deleteTeam(UUID teamUuid) {
        if (teamUuid == null) return;
        try (Connection c = open()) {
            EntitySql.deleteTeamRows(c, teamUuid.toString());
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to delete team {} from SQL backend", teamUuid, e);
        }
    }

    @Override
    public boolean exists(UUID teamUuid) {
        if (teamUuid == null) return false;
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM teams WHERE uuid = ?")) {
            ps.setString(1, teamUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to check team existence in SQL backend", e);
            return false;
        }
    }

    @Override
    public UUID getPlayerTeam(UUID playerUuid) {
        if (playerUuid == null) return null;
        String needle = playerUuid.toString();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT team_uuid FROM team_members WHERE player_uuid = ?")) {
            ps.setString(1, needle);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString("team_uuid"));
            }
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to query player team from SQL backend", e);
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM teams WHERE owner = ?")) {
            ps.setString(1, needle);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString("uuid"));
            }
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to query player team (owner) from SQL backend", e);
        }
        return null;
    }

    /** Row count of teams; used by migration to decide whether the table is empty. */
    public int count() {
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM teams")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            TeamProjectE.LOGGER.error("Failed to count SQL teams", e);
            return 0;
        }
    }

    @Override
    public void close() {
        // Short-lived connections; nothing to close.
    }
}
