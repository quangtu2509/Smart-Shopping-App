package com.toked.smartshopping.backend

/** Test-only reset. Includes every owner FK, and refuses any development database even if called accidentally. */
internal fun resetAuthTestData(db: Database) = db.transaction { c ->
    query(c, "SELECT current_database(),inet_server_port()").use { s -> s.executeQuery().use { r ->
        check(r.next() && r.getString(1) == "auth00_test" && r.getInt(2) == 55432) { "Refusing reset outside isolated PostgreSQL" }
    } }
    execute(c, """TRUNCATE profile_requests,auth_challenges,auth_identities,auth_rate_limits,refresh_tokens,auth_sessions,profiles,app_users""")
}
