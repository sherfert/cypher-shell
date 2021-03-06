package org.neo4j.shell.state;

import org.neo4j.driver.AccessMode;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionParametersTemplate;
import org.neo4j.driver.Statement;
import org.neo4j.driver.StatementResult;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.exceptions.SessionExpiredException;
import org.neo4j.driver.summary.DatabaseInfo;
import org.neo4j.shell.ConnectionConfig;
import org.neo4j.shell.Connector;
import org.neo4j.shell.DatabaseManager;
import org.neo4j.shell.TransactionHandler;
import org.neo4j.shell.TriFunction;
import org.neo4j.shell.exception.CommandException;
import org.neo4j.shell.log.NullLogging;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Handles interactions with the driver
 */
public class BoltStateHandler implements TransactionHandler, Connector, DatabaseManager {
    private final TriFunction<String, AuthToken, Config, Driver> driverProvider;
    protected Driver driver;
    Session session;
    private String version;
    private List<Statement> transactionStatements;
    private String activeDatabaseNameAsSetByUser;
    private String actualDatabaseNameAsReportedByServer;

    public BoltStateHandler() {
        this(GraphDatabase::driver);
    }

    BoltStateHandler(TriFunction<String, AuthToken, Config, Driver> driverProvider) {
        this.driverProvider = driverProvider;
        activeDatabaseNameAsSetByUser = ABSENT_DB_NAME;
    }

    @Override
    public void setActiveDatabase(String databaseName) throws CommandException
    {
        if (isTransactionOpen()) {
            throw new CommandException("There is an open transaction. You need to close it before you can switch database.");
        }
        activeDatabaseNameAsSetByUser = databaseName;
        if (isConnected()) {
            reconnect(false);
        }
    }

    @Override
    public String getActiveDatabaseAsSetByUser()
    {
        return activeDatabaseNameAsSetByUser;
    }

    @Override
    public String getActualDatabaseAsReportedByServer()
    {
        return actualDatabaseNameAsReportedByServer;
    }

    @Override
    public void beginTransaction() throws CommandException {
        if (!isConnected()) {
            throw new CommandException("Not connected to Neo4j");
        }
        if (isTransactionOpen()) {
            throw new CommandException("There is already an open transaction");
        }
        transactionStatements = new ArrayList<>();
    }

    @Override
    public Optional<List<BoltResult>> commitTransaction() throws CommandException {
        if (!isConnected()) {
            throw new CommandException("Not connected to Neo4j");
        }
        if (!isTransactionOpen()) {
            throw new CommandException("There is no open transaction to commit");
        }
        return captureResults(transactionStatements);

    }

    @Override
    public void rollbackTransaction() throws CommandException {
        if (!isConnected()) {
            throw new CommandException("Not connected to Neo4j");
        }
        if (!isTransactionOpen()) {
            throw new CommandException("There is no open transaction to rollback");
        }
        clearTransactionStatements();
    }

    @Override
    public boolean isTransactionOpen() {
        return transactionStatements != null;
    }

    @Override
    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    @Override
    public void connect(@Nonnull ConnectionConfig connectionConfig) throws CommandException {
        if (isConnected()) {
            throw new CommandException("Already connected");
        }

        final AuthToken authToken = AuthTokens.basic(connectionConfig.username(), connectionConfig.password());

        try {
            setActiveDatabase(connectionConfig.database());
            driver = getDriver(connectionConfig, authToken);
            reconnect();
        } catch (Throwable t) {
            try {
                silentDisconnect();
            } catch (Exception e) {
                t.addSuppressed(e);
            }
            throw t;
        }
    }

    private void reconnect() {
        reconnect(true);
    }

    private void reconnect(boolean keepBookmark) {
        Consumer<SessionParametersTemplate> sessionOptionalArgs = t -> {};
        if (session != null && keepBookmark) {
            // Save the last bookmark and close the session
            final String bookmark = session.lastBookmark();
            session.close();
            sessionOptionalArgs = t -> t.withBookmarks(bookmark);
        }
        Consumer<SessionParametersTemplate> sessionArgs = t -> {
            t.withDefaultAccessMode(AccessMode.WRITE);
            if (!ABSENT_DB_NAME.equals(activeDatabaseNameAsSetByUser)) {
                t.withDatabase(activeDatabaseNameAsSetByUser);
            }
        };
        session = driver.session(sessionArgs.andThen(sessionOptionalArgs));

        String query = activeDatabaseNameAsSetByUser.compareToIgnoreCase(SYSTEM_DB_NAME) == 0 ? "SHOW DATABASES" : "RETURN 1";

        resetActualDbName(); // Set this to null first in case run throws an exception
        StatementResult run = session.run(query);

        this.version = run.summary().server().version();
        updateActualDbName(run);
    }

    @Nonnull
    @Override
    public String getServerVersion() {
        if (isConnected()) {
            if (version == null) {
                // On versions before 3.1.0-M09
                version = "";
            }
            if (version.startsWith("Neo4j/")) {
                // Want to return '3.1.0' and not 'Neo4j/3.1.0'
                version = version.substring(6);
            }
            return version;
        }
        return "";
    }

    @Nonnull
    public Optional<BoltResult> runCypher(@Nonnull String cypher,
                                          @Nonnull Map<String, Object> queryParams) throws CommandException {
        if (!isConnected()) {
            throw new CommandException("Not connected to Neo4j");
        }
        if (this.transactionStatements != null) {
            transactionStatements.add(new Statement(cypher, queryParams));
            return Optional.empty();
        } else {
            try {
                // Note that PERIODIC COMMIT can't execute in a transaction, so if the user has not typed BEGIN, then
                // the statement should NOT be executed in a transaction.
                return getBoltResult(cypher, queryParams);
            } catch (SessionExpiredException e) {
                // Server is no longer accepting writes, reconnect and try again.
                // If it still fails, leave it up to the user
                reconnect();
                return getBoltResult(cypher, queryParams);
            }
        }
    }

    /**
     * @throws SessionExpiredException when server no longer serves writes anymore
     */
    @Nonnull
    private Optional<BoltResult> getBoltResult(@Nonnull String cypher, @Nonnull Map<String, Object> queryParams) throws SessionExpiredException {
        StatementResult statementResult = session.run(new Statement(cypher, queryParams));

        if (statementResult == null) {
            return Optional.empty();
        }

        updateActualDbName(statementResult);

        return Optional.of(new StatementBoltResult(statementResult));
    }

    private String getActualDbName(@Nonnull StatementResult statementResult) {
        DatabaseInfo dbInfo = statementResult.summary().database();
        return dbInfo.name() == null ? ABSENT_DB_NAME : dbInfo.name();
    }

    private void updateActualDbName(@Nonnull StatementResult statementResult) {
        actualDatabaseNameAsReportedByServer = getActualDbName(statementResult);
    }

    private void resetActualDbName() {
        actualDatabaseNameAsReportedByServer = null;
    }

    /**
     * Disconnect from Neo4j, clearing up any session resources, but don't give any output.
     * Intended only to be used if connect fails.
     */
    void silentDisconnect() {
        try {
            if (session != null) {
                session.close();
            }
            if (driver != null) {
                driver.close();
            }
        } finally {
            session = null;
            driver = null;
            resetActualDbName();
        }
    }

    /**
     * Reset the current session. This rolls back any open transactions.
     */
    public void reset() {
        if (isConnected()) {
            session.reset();

            // Clear current state
            if (isTransactionOpen()) {
                // Bolt has already rolled back the transaction but it doesn't close it properly
                clearTransactionStatements();
            }
        }
    }

    List<Statement> getTransactionStatements() {
        return this.transactionStatements;
    }

    private void clearTransactionStatements() {
        this.transactionStatements = null;
    }

    private Driver getDriver(@Nonnull ConnectionConfig connectionConfig, @Nullable AuthToken authToken) {
        Config.ConfigBuilder configBuilder = Config.build().withLogging(NullLogging.NULL_LOGGING);
        if (connectionConfig.encryption()) {
            configBuilder = configBuilder.withEncryption();
        } else {
            configBuilder = configBuilder.withoutEncryption();
        }
        return driverProvider.apply(connectionConfig.driverUrl(), authToken, configBuilder.toConfig());
    }

    private Optional<List<BoltResult>> captureResults(@Nonnull List<Statement> transactionStatements) {
        List<BoltResult> results = executeWithRetry(transactionStatements, (statement, transaction) -> {
            // calling list() is what actually executes cypher on the server
            StatementResult sr = transaction.run(statement);
            BoltResult singleResult = new ListBoltResult(sr.list(), sr.consume(), sr.keys());
            updateActualDbName(sr);
            return singleResult;
        });

        clearTransactionStatements();
        if (results == null || results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(results);
    }

    private List<BoltResult> executeWithRetry(List<Statement> transactionStatements, BiFunction<Statement, Transaction, BoltResult> biFunction) {
        return session.writeTransaction(tx ->
                transactionStatements.stream()
                        .map(transactionStatement -> biFunction.apply(transactionStatement, tx))
                        .collect(Collectors.toList()));

    }
}
