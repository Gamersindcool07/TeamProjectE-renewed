package cn.leomc.teamprojecte.persistence;

import cn.leomc.teamprojecte.TeamProjectE;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.data.TeamEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JSON file backend (replaces the old NBT/SNBT file approach). Serializes
 * {@link TeamEntity} lists via Gson to {@code world/teamprojecte.json}.
 */
public class FileTeamRepository implements TeamDataStore {

    private static final Type LIST_TYPE = new TypeToken<List<TeamEntity>>() { }.getType();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private final Path file;

    public FileTeamRepository(Path file) {
        this.file = file;
    }

    private Map<UUID, TPTeam> readAll() {
        Map<UUID, TPTeam> out = new HashMap<>();
        if (!Files.exists(file)) return out;
        try {
            String json = Files.readString(file);
            if (json == null || json.isBlank()) return out;
            List<TeamEntity> entities = gson.fromJson(json, LIST_TYPE);
            if (entities == null) return out;
            for (TeamEntity e : entities) {
                TPTeam team = TPTeam.fromEntity(e);
                if (team != null) out.put(team.getUUID(), team);
            }
        } catch (Exception e) {
            TeamProjectE.LOGGER.error("Failed to read team file {}", file, e);
        }
        return out;
    }

    private void writeAll(Map<UUID, TPTeam> teams) {
        try {
            Files.createDirectories(file.getParent());
            List<TeamEntity> entities = new ArrayList<>();
            for (TPTeam t : teams.values()) entities.add(t.toEntity());
            Files.writeString(file, gson.toJson(entities, LIST_TYPE));
        } catch (IOException e) {
            TeamProjectE.LOGGER.error("Failed to write team file {}", file, e);
        }
    }

    @Override
    public Map<UUID, TPTeam> loadAll() {
        return readAll();
    }

    @Override
    public void save(TPTeam team) {
        if (team == null) return;
        Map<UUID, TPTeam> all = readAll();
        all.put(team.getUUID(), team);
        writeAll(all);
    }

    @Override
    public void saveAll(Map<UUID, TPTeam> teams) {
        if (teams == null) return;
        writeAll(teams);
    }

    @Override
    public void deleteTeam(UUID teamUuid) {
        if (teamUuid == null) return;
        Map<UUID, TPTeam> all = readAll();
        all.remove(teamUuid);
        writeAll(all);
    }

    @Override
    public boolean exists(UUID teamUuid) {
        return teamUuid != null && readAll().containsKey(teamUuid);
    }

    @Override
    public int count() {
        return readAll().size();
    }

    @Override
    public UUID getPlayerTeam(UUID playerUuid) {
        if (playerUuid == null) return null;
        for (Map.Entry<UUID, TPTeam> e : readAll().entrySet()) {
            if (e.getValue().getAll().contains(playerUuid)) return e.getKey();
        }
        return null;
    }

    @Override
    public void close() {
        // Nothing to release for the file backend.
    }
}
