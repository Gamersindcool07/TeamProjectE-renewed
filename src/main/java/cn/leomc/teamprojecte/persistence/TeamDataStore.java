package cn.leomc.teamprojecte.persistence;

/**
 * Pluggable persistence backend for team data. The default implementation is
 * {@link LiteSqlTeamStore} (SQLite, separate team/private DB files).
 * {@link SqlTeamDataStore} provides generic JDBC storage and
 * {@link FileTeamRepository} a JSON file store.
 */
public interface TeamDataStore extends TeamRepository {
}
