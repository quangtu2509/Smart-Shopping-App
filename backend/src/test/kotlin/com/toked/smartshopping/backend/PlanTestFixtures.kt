package com.toked.smartshopping.backend

import com.toked.smartshopping.backend.shopping.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Synthetic reference data, compiled exclusively into tests and the isolated fixture executable. */
internal fun seedPlanReferences(db: Database, privateOwner: String? = null) = db.transaction { c ->
    execute(c, """INSERT INTO shopping_categories(id,name,status) VALUES
        ('plan-food','Thực phẩm cho kế hoạch','ACTIVE'),('plan-home','Đồ dùng cho kế hoạch','ACTIVE'),('plan-old','Nhóm ngừng dùng','INACTIVE')
        ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,status=EXCLUDED.status""")
    for (letter in listOf("a", "b", "c", "d")) execute(c,
        "INSERT INTO shopping_retailers(id,name,status,code) VALUES (?,?, 'ACTIVE',?) ON CONFLICT DO NOTHING",
        "plan-retailer-$letter", "Cửa hàng thử nghiệm ${letter.uppercase()}", letter.uppercase())
    val public = StoreSnapshot("plan-branch-a", 1, "plan-retailer-a", "Chi nhánh thử nghiệm A", "Khu vực thử nghiệm",
        Coordinate(10.0, 106.0), "USER", "PUBLIC")
    execute(c, "INSERT INTO shopping_stores(id,retailer_id,visibility,payload) VALUES (?,?,'PUBLIC',?::jsonb) ON CONFLICT DO NOTHING",
        public.id, public.retailer_id, Json.encodeToString(public))
    if (privateOwner != null) {
        val private = public.copy(id = "plan-private-$privateOwner", retailer_id = null, name = "Cửa hàng riêng thử nghiệm", visibility = "PRIVATE")
        execute(c, "INSERT INTO shopping_stores(id,owner_id,visibility,payload) VALUES (?,?,'PRIVATE',?::jsonb) ON CONFLICT DO NOTHING",
            private.id, UUID.fromString(privateOwner), Json.encodeToString(private))
    }
}
