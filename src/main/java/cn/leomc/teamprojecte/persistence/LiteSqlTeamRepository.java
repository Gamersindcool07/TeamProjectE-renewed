package cn.leomc.teamprojecte.persistence;

import java.nio.file.Path;
import java.sql.SQLException;

/**
 * LiteSQL team repository: SQLite file {@code teamprojecte_team.db} in the
 * world directory. See {@link JdbcTeamRepository} for the normalized schema.
 */
public class LiteSqlTeamRepository extends JdbcTeamRepository {

    public LiteSqlTeamRepository(Path dbFile) throws SQLException {
        super("jdbc:sqlite:" + dbFile.toAbsolutePath(), "", "", 30000);
    }
}
