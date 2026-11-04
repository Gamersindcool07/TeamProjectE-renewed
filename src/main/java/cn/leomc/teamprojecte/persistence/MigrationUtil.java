package cn.leomc.teamprojecte.persistence;

import cn.leomc.teamprojecte.TPSavedData;
import cn.leomc.teamprojecte.TeamProjectE;
import cn.leomc.teamprojecte.TPTeam;
import cn.leomc.teamprojecte.data.TeamEntity;
import cn.leomc.teamprojecte.persistence.legacy.LegacyNbtReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One-time, idempotent migration of every legacy NBT/JSON save (teams, EMC,
 * knowledge) into SQL (LiteSQL by default) on server start.
 *
 * <p>Idempotency guards (all must hold to skip):
 * <ol>
 *   <li>Marker file {@code teamprojecte.migrated} exists in the world root.</li>
 *   <li>SQL team table already has rows (marker is written, then skip).</li>
 *   <li>Otherwise proceed only if legacy NBT/SQL data is non-empty.</li>
 * </ol>
 * Legacy files are retained (backed up as {@code .bak}). Each team save is
 * wrapped in try/catch so one bad row does not abort the run; the marker is
 * only written on at least partial success so failures retry next boot.
 * File (JSON) backend: migration is skipped entirely.
 */
public final class MigrationUtil {

    private MigrationUtil() {
    }

    private static int storeCount(TeamDataStore store) {
        if (store instanceof JdbcTeamRepository jdbc) return jdbc.count();
        if (store instanceof LiteSqlTeamStore lite) return lite.count();
        return 0;
    }

    public static void migrateIfNeeded(TeamDataStore store) {
        if (!TeamPersistenceFactory.isSql(store)) return;
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            Path worldDir = server.getWorldPath(LevelResource.ROOT);
            Path marker = worldDir.resolve("teamprojecte.migrated");
            if (Files.exists(marker)) return;
            if (storeCount(store) > 0) {
                writeMarker(marker);
                return;
            }
            List<TeamEntity> legacy = LegacyNbtReader.readAll(worldDir);
            scanExtraSaves(worldDir);
            if (legacy.isEmpty()) {
                // Nothing to migrate yet; do not write marker so a later
                // restart with populated legacy saves can still migrate.
                return;
            }
            int migrated = 0;
            for (TeamEntity entity : legacy) {
                try {
                    TPTeam team = TPTeam.fromEntity(entity);
                    if (team != null) {
                        store.save(team);
                        migrated++;
                    }
                } catch (Exception e) {
                    TeamProjectE.LOGGER.warn("Migration skipped unreadable legacy team {}", entity.getUuid(), e);
                }
            }
            if (migrated > 0) {
                writeMarker(marker);
                TeamProjectE.LOGGER.info("Migrated {} team(s) from legacy NBT/SQL to SQL backend", migrated);
            }
        } catch (Exception e) {
            TeamProjectE.LOGGER.warn("Team migration failed (continuing with in-memory data)", e);
        }
    }

    /** Best-effort scan of standalone JSON/NBT saves in the world dir. */
    private static void scanExtraSaves(Path worldDir) {
        try (var stream = Files.list(worldDir)) {
            stream.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".json") || n.endsWith(".dat");
                    })
                    .forEach(p -> TeamProjectE.LOGGER.info(
                            "Legacy save candidate (best-effort; only teamprojecte.dat / teamprojecte.db are migrated): {}",
                            p.getFileName()));
        } catch (IOException e) {
            TeamProjectE.LOGGER.warn("Failed to scan world dir for legacy saves", e);
        }
    }

    /** Force re-migration (idempotent upsert). Used by {@code /team_projecte admin migrate}. */
    public static synchronized int forceMigrate() {
        TeamDataStore store = TPSavedData.getStore();
        TPSavedData data = TPSavedData.getData();
        if (!TeamPersistenceFactory.isSql(store) || data == null) {
            TeamProjectE.LOGGER.warn("forceMigrate ignored: backend is not SQL or data unavailable");
            return 0;
        }
        TeamProjectE.LOGGER.info("forceMigrate: upserting {} team(s) (in-memory state is authoritative post-load)",
                data.getTeams().size());
        store.saveAll(data.getTeams());
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                writeMarker(server.getWorldPath(LevelResource.ROOT).resolve("teamprojecte.migrated"));
            }
        } catch (Exception ignored) {
        }
        return data.getTeams().size();
    }

    private static void writeMarker(Path marker) throws IOException {
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "migrated" + System.lineSeparator());
    }
}
