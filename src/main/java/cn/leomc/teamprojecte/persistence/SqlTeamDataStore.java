package cn.leomc.teamprojecte.persistence;

import java.sql.SQLException;

/**
 * JDBC-backed team storage (sqlite / mysql / postgresql) over the normalized
 * schema in {@link JdbcTeamRepository}. Kept as the generic SQL backend;
 * the default LiteSQL backend is {@link LiteSqlTeamStore} (separate
 * team/private SQLite files).
 */
public class SqlTeamDataStore extends JdbcTeamRepository {

    public SqlTeamDataStore(String jdbcUrl, String username, String password, int connectionTimeoutMs)
            throws SQLException {
        super(jdbcUrl, username, password, connectionTimeoutMs);
    }
}
