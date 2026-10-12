package com.toked.smartshopping.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class Database(url: String, username: String, password: String) : AutoCloseable {
    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; this.username = username; this.password = password
        maximumPoolSize = 8; connectionTimeout = 5000; poolName = "auth-db"
    })

    fun migrate() { Flyway.configure().dataSource(pool).load().migrate() }

    fun <T> transaction(action: (Connection) -> T): T = pool.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = action(connection)
            connection.commit()
            result
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        }
    }

    override fun close() = pool.close()
}

internal fun query(connection: Connection, sql: String, vararg values: Any?): PreparedStatement =
    connection.prepareStatement(sql).apply {
        values.forEachIndexed { index, value ->
            when (value) {
                is Instant -> setTimestamp(index + 1, Timestamp.from(value))
                else -> setObject(index + 1, value)
            }
        }
    }

internal fun execute(connection: Connection, sql: String, vararg values: Any?): Int =
    query(connection, sql, *values).use { it.executeUpdate() }

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
internal fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()
