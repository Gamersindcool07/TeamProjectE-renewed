package cn.leomc.teamprojecte.persistence;

import cn.leomc.teamprojecte.data.PlayerEntity;

import java.util.List;
import java.util.UUID;

/** Data-access layer for per-player private data (private DB). */
public interface PlayerRepository {

    PlayerEntity load(UUID playerUuid);

    void save(PlayerEntity entity);

    void deleteTeamMapping(UUID teamUuid);

    List<PlayerEntity> loadAll();

    void close();
}
