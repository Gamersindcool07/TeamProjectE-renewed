package cn.leomc.teamprojecte.persistence;

import cn.leomc.teamprojecte.TeamProjectE;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Selects a {@link TeamDataStore} from config. Default (and fallback) is
 * LiteSQL: two SQLite files in the world directory
 * ({@code teamprojecte_team.db} / {@code teamprojecte_private.db}).
 */
public final class TeamPersistenceFactory {

    public static final String TEAM_DB = "teamprojecte_team.db";
    public static final String PRIVATE_DB = "teamprojecte_private.db";
    public static final String JSON_FILE = "teamprojecte.json";

    private TeamPersistenceFactory() {
    }

    public static Path worldDir() {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) return server.getWorldPath(LevelResource.ROOT);
        } catch (Exception ignored) { }
        return Paths.get("world");
    }

    public static LiteSqlTeamStore liteSql() {
        Path dir = worldDir();
        try {
            return new LiteSqlTeamStore(dir.resolve(TEAM_DB), dir.resolve(PRIVATE_DB));
        } catch (Exception e) {
            TeamProjectE.LOGGER.warn("LiteSQL backend unavailable ({}), falling back to JSON file backend",
                    e.getMessage());
            return null;
        }
    }

    public static TeamDataStore fromConfig() {
        String backend = "litesql";
        String jdbcUrl = "jdbc:sqlite:./world/teamprojecte.db";
        String username = "";
        String password = "";
        int timeoutMs = 30000;
        try {
            backend = TeamConfig.BACKEND.get();
            jdbcUrl = TeamConfig.JDBC_URL.get();
            username = TeamConfig.USERNAME.get();
            password = TeamConfig.PASSWORD.get();
            timeoutMs = TeamConfig.CONNECTION_TIMEOUT_MS.get();
        } catch (Exception e) {
            TeamProjectE.LOGGER.warn("Could not read persistence config, using LiteSQL backend", e);
            LiteSqlTeamStore lite = liteSql();
            return lite != null ? lite : new FileTeamRepository(worldDir().resolve(JSON_FILE));
        }
        if (backend == null) backend = "litesql";
        switch (backend.trim().toLowerCase()) {
            case "file" -> {
                return new FileTeamRepository(worldDir().resolve(JSON_FILE));
            }
            case "litesql" -> {
                LiteSqlTeamStore lite = liteSql();
                return lite != null ? lite : new FileTeamRepository(worldDir().resolve(JSON_FILE));
            }
            case "sqlite", "mysql", "postgresql" -> {
                try {
                    return new SqlTeamDataStore(jdbcUrl, username, password, timeoutMs);
                } catch (Exception e) {
                    TeamProjectE.LOGGER.warn("SQL persistence backend '{}' unavailable ({}), falling back to LiteSQL backend",
                            backend, e.getMessage());
                    LiteSqlTeamStore lite = liteSql();
                    return lite != null ? lite : new FileTeamRepository(worldDir().resolve(JSON_FILE));
                }
            }
            default -> {
                TeamProjectE.LOGGER.warn("Unknown persistence backend '{}', falling back to LiteSQL backend", backend);
                LiteSqlTeamStore lite = liteSql();
                return lite != null ? lite : new FileTeamRepository(worldDir().resolve(JSON_FILE));
            }
        }
    }

    public static boolean isSql(TeamDataStore store) {
        return store instanceof JdbcTeamRepository || store instanceof LiteSqlTeamStore;
    }

    public static boolean isLiteSql(TeamDataStore store) {
        return store instanceof LiteSqlTeamStore;
    }
}
