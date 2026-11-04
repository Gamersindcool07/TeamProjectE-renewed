package cn.leomc.teamprojecte.persistence;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Forge config for pluggable persistence.
 * File: {@code config/teamprojecte.toml}
 *
 * <pre>
 * [persistence]
 * backend = "litesql"           # litesql | sqlite | mysql | postgresql | file
 * jdbcUrl = "jdbc:sqlite:./world/teamprojecte.db"
 * username = ""
 * password = ""
 * poolSize = 4
 * connectionTimeoutMs = 30000
 * </pre>
 *
 * LiteSQL (default) uses two SQLite files in the world directory:
 * {@code teamprojecte_team.db} (team data) and
 * {@code teamprojecte_private.db} (per-player private data).
 */
public final class TeamConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> BACKEND;
    public static final ForgeConfigSpec.ConfigValue<String> JDBC_URL;
    public static final ForgeConfigSpec.ConfigValue<String> USERNAME;
    public static final ForgeConfigSpec.ConfigValue<String> PASSWORD;
    public static final ForgeConfigSpec.IntValue POOL_SIZE;
    public static final ForgeConfigSpec.IntValue CONNECTION_TIMEOUT_MS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("persistence");
        BACKEND = b.comment("Persistence backend: litesql (default, two SQLite files) | sqlite | mysql | postgresql | file (JSON).")
                .define("backend", "litesql");
        JDBC_URL = b.comment("JDBC URL for SQL backends (sqlite/mysql/postgresql), e.g. jdbc:sqlite:./world/teamprojecte.db or jdbc:mysql://host/db or jdbc:postgresql://host/db")
                .define("jdbcUrl", "jdbc:sqlite:./world/teamprojecte.db");
        USERNAME = b.comment("DB username (empty for sqlite). Never logged.")
                .define("username", "");
        PASSWORD = b.comment("DB password (empty for sqlite). Never logged. Keep config file permissions restricted.")
                .define("password", "");
        POOL_SIZE = b.comment("Connection pool size (reserved; SQL store uses short-lived connections).")
                .defineInRange("poolSize", 4, 1, 64);
        CONNECTION_TIMEOUT_MS = b.comment("JDBC connection timeout in milliseconds.")
                .defineInRange("connectionTimeoutMs", 30000, 1000, 300000);
        b.pop();
        SPEC = b.build();
    }

    private TeamConfig() {
    }
}
