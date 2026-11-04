package cn.leomc.teamprojecte.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Backwards-compatible alias for {@link FileTeamRepository} (JSON file backend).
 * @deprecated use {@link FileTeamRepository} directly.
 */
@Deprecated
public class FileTeamDataStore extends FileTeamRepository {

    public FileTeamDataStore() {
        super(Paths.get("world", "teamprojecte.json"));
    }

    public FileTeamDataStore(Path file) {
        super(file);
    }
}
