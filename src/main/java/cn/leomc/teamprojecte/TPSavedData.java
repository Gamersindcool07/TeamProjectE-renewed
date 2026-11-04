package cn.leomc.teamprojecte;

import cn.leomc.teamprojecte.persistence.MigrationUtil;
import cn.leomc.teamprojecte.persistence.TeamDataStore;
import cn.leomc.teamprojecte.persistence.TeamPersistenceFactory;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory team registry (no longer Minecraft {@code SavedData}/NBT).
 * Persistence is owned by the DAL store (LiteSQL by default, separate
 * team/private SQLite files); this class only holds the loaded maps and
 * the active store. {@link #playerTeamCache} is a runtime-only cache,
 * never persisted.
 */
public class TPSavedData {

    private static TPSavedData DATA;
    private static TeamDataStore STORE;

    public static TPSavedData getData() {
        if (DATA == null) {
            if (ServerLifecycleHooks.getCurrentServer() == null) return null;
            DATA = new TPSavedData();
            if (STORE == null) initStore();
        }
        return DATA;
    }

    static void onServerStopped() {
        if (STORE != null) {
            try { STORE.close(); } catch (Exception ignored) {}
            STORE = null;
        }
        DATA = null;
    }

    /** Initialise the persistence backend (called on ServerStarted). Idempotent. */
    public static TeamDataStore initStore() {
        if (STORE != null) return STORE;
        if (DATA == null) DATA = new TPSavedData();
        STORE = TeamPersistenceFactory.fromConfig();
        // One-time, idempotent NBT/JSON -> SQL migration runs BEFORE load so
        // legacy teams land in SQL first (plan: nbt-json-to-sql-migration).
        MigrationUtil.migrateIfNeeded(STORE);
        Map<UUID, TPTeam> loaded = STORE.loadAll();
        DATA.teams.clear();
        DATA.teams.putAll(loaded);
        DATA.playerTeamCache.clear();
        if (TeamPersistenceFactory.isLiteSql(STORE)) {
            TeamProjectE.LOGGER.info("Team persistence backend: LiteSQL (teamprojecte_team.db + teamprojecte_private.db)");
        } else if (TeamPersistenceFactory.isSql(STORE)) {
            TeamProjectE.LOGGER.info("Team persistence backend: SQL");
        } else {
            TeamProjectE.LOGGER.info("Team persistence backend: file (JSON)");
        }
        TeamProjectE.LOGGER.info("Loaded {} team(s)", loaded.size());
        return STORE;
    }

    public static TeamDataStore getStore() {
        return STORE;
    }

    public final Map<UUID, TPTeam> teams = new HashMap<>();
    public final Map<UUID, UUID> playerTeamCache = new HashMap<>();

    public Map<UUID, TPTeam> getTeams() {
        return Collections.unmodifiableMap(teams);
    }

    void invalidateCache(UUID uuid) {
        playerTeamCache.remove(uuid);
    }

    /** SQL/JSON writes are immediate (via the store); nothing to flush. */
    public void setDirty() {
    }

    TPSavedData() {
    }
}
