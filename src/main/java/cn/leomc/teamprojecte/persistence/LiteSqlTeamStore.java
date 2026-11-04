package cn.leomc.teamprojecte.persistence;

import cn.leomc.teamprojecte.TeamProjectE;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.data.ItemKey;
import cn.leomc.teamprojecte.data.PlayerEntity;

import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * LiteSQL store: two separate SQLite files in the world directory —
 * {@code teamprojecte_team.db} (team data) and
 * {@code teamprojecte_private.db} (per-player private data). Every team
 * save also syncs members' private knowledge/EMC/team mapping into the
 * private DB, so both files always agree with in-memory state.
 */
public class LiteSqlTeamStore implements TeamDataStore {

    private final LiteSqlTeamRepository teams;
    private final LiteSqlPrivateRepository privates;

    public LiteSqlTeamStore(Path teamDb, Path privateDb) throws SQLException {
        this.teams = new LiteSqlTeamRepository(teamDb);
        this.privates = new LiteSqlPrivateRepository(privateDb);
    }

    public LiteSqlPrivateRepository getPrivateRepository() {
        return privates;
    }

    public LiteSqlTeamRepository getTeamRepository() {
        return teams;
    }

    @Override
    public Map<UUID, TPTeam> loadAll() {
        return teams.loadAll();
    }

    @Override
    public void save(TPTeam team) {
        if (team == null) return;
        teams.save(team);
        syncPrivate(team);
    }

    @Override
    public void saveAll(Map<UUID, TPTeam> teamsMap) {
        if (teamsMap == null) return;
        for (TPTeam t : teamsMap.values()) save(t);
    }

    @Override
    public void deleteTeam(UUID teamUuid) {
        teams.deleteTeam(teamUuid);
        privates.deleteTeamMapping(teamUuid);
    }

    @Override
    public boolean exists(UUID teamUuid) {
        return teams.exists(teamUuid);
    }

    /** Team row count; used by migration to decide whether the DB is empty. */
    public int count() {
        return teams.count();
    }

    @Override
    public UUID getPlayerTeam(UUID playerUuid) {
        return teams.getPlayerTeam(playerUuid);
    }

    @Override
    public void close() {
        teams.close();
        privates.close();
    }

    private void syncPrivate(TPTeam team) {
        try {
            for (UUID player : team.getAll()) {
                Set<ItemKey> keys = new HashSet<>();
                for (var info : team.getPrivateKnowledge(player)) {
                    ItemKey key = ItemKey.from(info);
                    if (key != null) keys.add(key);
                }
                BigInteger emc = team.getEmc(player);
                privates.save(new PlayerEntity(player, team.getUUID(), keys, emc));
            }
        } catch (Exception e) {
            TeamProjectE.LOGGER.warn("Failed to sync private DB for team {}", team.getUUID(), e);
        }
    }
}
