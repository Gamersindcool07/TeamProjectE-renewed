package cn.leomc.teamprojecte.persistence;

import cn.leomc.teamprojecte.TPTeam;

import java.util.Map;
import java.util.UUID;

/**
 * Data-access layer for team entities. Implementations map between
 * {@link cn.leomc.teamprojecte.data.TeamEntity} and the backing store;
 * serialization lives here, never in entities.
 */
public interface TeamRepository {

    /** Load all teams from the backend. */
    Map<UUID, TPTeam> loadAll();

    /** Persist a single team (insert or update). */
    void save(TPTeam team);

    /** Persist all given teams. */
    void saveAll(Map<UUID, TPTeam> teams);

    /** Delete a team by UUID. */
    void deleteTeam(UUID teamUuid);

    /** Whether a team row exists. */
    boolean exists(UUID teamUuid);

    /** Number of stored teams. */
    int count();

    /** Find the UUID of the team a player belongs to, or null. */
    UUID getPlayerTeam(UUID playerUuid);

    /** Release any resources (connections, files). */
    void close();
}
