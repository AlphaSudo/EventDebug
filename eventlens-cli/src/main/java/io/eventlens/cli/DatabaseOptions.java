package io.eventlens.cli;

import picocli.CommandLine.Option;
import io.eventlens.pg.PgConfig;

/**
 * Shared database options mixin — included in all commands that need DB access.
 */
public class DatabaseOptions {

    @Option(names = "--db-url", required = true, description = "PostgreSQL JDBC URL")
    String dbUrl;

    @Option(names = "--db-user", defaultValue = "postgres", description = "Database username")
    String dbUser;

    @Option(names = "--db-password", defaultValue = "", description = "Database password")
    String dbPassword;

    @Option(names = "--table", description = "Event store table name (auto-detected if omitted)")
    String tableName;

    public PgConfig toPgConfig() {
        return new PgConfig(dbUrl, dbUser, dbPassword, tableName);
    }
}
