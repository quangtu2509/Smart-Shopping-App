package com.toked.smartshopping.backend

import org.flywaydb.core.Flyway
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class ShoppingMigrationTest {
    @Test fun v4ToV5KeepsAccountsProfileAndSessionsAndIsRepeatable() {
        val url="jdbc:postgresql://127.0.0.1:55432/auth00_test"
        val schema="data_migration_"+UUID.randomUUID().toString().replace("-","")
        fun migrate(target: String)=Flyway.configure().dataSource(url,"auth00_test","").schemas(schema).defaultSchema(schema).target(target).load().migrate()
        val owner=UUID.randomUUID(); val session=UUID.randomUUID()
        try {
            migrate("4")
            DriverManager.getConnection(url,"auth00_test","").use { c ->
                c.createStatement().use { it.execute("SET search_path TO $schema") }
                execute(c,"INSERT INTO app_users(id,email,password_hash,email_verified_at,created_at) VALUES (?,?,'fixture-hash',now(),now())",owner,"migration@example.com")
                execute(c,"INSERT INTO profiles(user_id,household_size,max_stores,version) VALUES (?,4,2,3)",owner)
                execute(c,"INSERT INTO auth_sessions(id,user_id,access_hash,access_expires_at,current_refresh_hash,created_at,last_used_at,expires_at) VALUES (?,?,?,now()+interval '15 minutes',?,now(),now(),now()+interval '30 days')",session,owner,"a".repeat(64),"b".repeat(64))
            }
            migrate("5");assertEquals(0,migrate("5").migrationsExecuted)
            DriverManager.getConnection(url,"auth00_test","").use { c ->
                c.createStatement().use { it.execute("SET search_path TO $schema") }
                query(c,"SELECT u.email,p.household_size,p.max_stores,p.version,s.revoked_at FROM app_users u JOIN profiles p ON p.user_id=u.id JOIN auth_sessions s ON s.user_id=u.id WHERE u.id=?",owner).use { s -> s.executeQuery().use { r ->
                    assertTrue(r.next());assertEquals("migration@example.com",r.getString(1));assertEquals(4,r.getInt(2));assertEquals(2,r.getInt(3));assertEquals(3,r.getInt(4));assertNull(r.getTimestamp(5))
                } }
                query(c,"SELECT shopping_valid_amount(1.1,'VND'),shopping_valid_amount(0,'VND'),(SELECT count(*) FROM shopping_plans)").use { s -> s.executeQuery().use { r ->
                    r.next();assertFalse(r.getBoolean(1));assertTrue(r.getBoolean(2));assertEquals(0,r.getInt(3))
                } }
            }
        } finally { DriverManager.getConnection(url,"auth00_test","").use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }
    }
}
