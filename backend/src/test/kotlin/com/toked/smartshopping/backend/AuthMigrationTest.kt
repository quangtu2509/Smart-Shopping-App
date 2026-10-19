package com.toked.smartshopping.backend

import org.flywaydb.core.Flyway
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class AuthMigrationTest {
    @Test fun upgradingLegacySchemaPreservesAccountProfileAndRevokesSession() {
        val url = "jdbc:postgresql://127.0.0.1:55432/auth00_test"
        val schema = "auth_migration_" + UUID.randomUUID().toString().replace("-","")
        val owner = UUID.randomUUID()
        val session = UUID.randomUUID()
        fun migrate(target: String) = Flyway.configure().dataSource(url,"auth00_test","")
            .schemas(schema).defaultSchema(schema).target(target).load().migrate()
        try {
            migrate("2")
            DriverManager.getConnection(url,"auth00_test","").use { c ->
                c.createStatement().use { it.execute("SET search_path TO $schema") }
                execute(c,"INSERT INTO app_users(id,email,password_hash,created_at) VALUES (?,?,?,now())",owner,"legacy@example.com","legacy-hash")
                execute(c,"INSERT INTO profiles(user_id,household_size) VALUES (?,4)",owner)
                execute(c,"INSERT INTO auth_sessions(id,user_id,access_hash,access_expires_at,current_refresh_hash,created_at,last_used_at,expires_at) VALUES (?,?,?,now()+interval '15 minutes',?,now(),now(),now()+interval '30 days')",
                    session,owner,"a".repeat(64),"b".repeat(64))
            }
            migrate("3")
            DriverManager.getConnection(url,"auth00_test","").use { c ->
                c.createStatement().use { it.execute("SET search_path TO $schema") }
                query(c,"SELECT u.password_hash,u.email_verified_at,p.household_size,s.revoked_at FROM app_users u JOIN profiles p ON p.user_id=u.id JOIN auth_sessions s ON s.user_id=u.id WHERE u.id=?",owner).use { q -> q.executeQuery().use { r ->
                    assertTrue(r.next()); assertEquals("legacy-hash",r.getString(1)); assertNull(r.getTimestamp(2)); assertEquals(4,r.getInt(3)); assertNotNull(r.getTimestamp(4))
                } }
            }
        } finally {
            // Constant isolated connection and generated schema, never a development database.
            DriverManager.getConnection(url,"auth00_test","").use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}
