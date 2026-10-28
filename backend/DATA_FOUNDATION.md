# DATA-00 — Nền dữ liệu mua sắm

DATA-00 cung cấp schema, DTO và transaction để các service Plan/List/Finalize/Session dùng tiếp; bản thân nền này không đăng ký endpoint. PLAN-01 nay đã triển khai API-PLAN-01–04 và UI Draft dùng V5; xem [PLAN_API](PLAN_API.md). API-LIST-01–04, API-BUDGET-01, API-FINAL-01–03 và API Session/History vẫn thuộc task tương ứng. Kiểm chứng nền/Draft không thay nghiệm thu toàn bộ UC-04 hoặc FR-15.

## Schema và snapshot

Migration `V5__shopping_data_foundation.sql` giữ V1–V4 và auth/Profile, thêm các nhóm sau:

| Nhóm | Trách nhiệm |
|---|---|
| shopping_currencies | Currency được hỗ trợ và precision; mặc định chỉ VND/0, không tự quy đổi tiền. |
| shopping_retailers/stores/products/store_products/offers | Reference nội bộ tối thiểu. Offer có khóa ghép `(kind,id)` để phân biệt CATALOG/PRIVATE. Store private có owner; không ép retailer giả. |
| shopping_plans/plan_selections/plan_categories/items | Plan thuộc owner, version hiện hành, revision counter, input và snapshot item đã chọn. Tổng tiền được tính từ item, không có cache tổng dễ lệch. |
| shopping_plan_revisions/revision_validity | Payload revision bất biến và trạng thái CURRENT/INVALIDATED riêng. Invalidate không sửa/xóa nội dung bản chốt cũ. |
| shopping_artifacts | Marker phụ thuộc input_version cho Suggest/Optimization/Route; không chạy engine hoặc chứa tuyến giả. |
| shopping_mutations/audit | Replay request và audit cùng transaction với mutation. Audit không bị sửa/xóa. |
| shopping_sessions/session_items/history | Snapshot ban đầu bất biến, quan hệ thay thế cùng Session, tối đa một History/Session. COMPLETED và History phải commit cùng nhau nhờ constraint trigger deferred. |

Revision và History có trigger chặn UPDATE/DELETE. Session item giữ mô tả ban đầu, chỉ outcome có thể thay đổi; không xóa cứng item. JSON snapshot lưu tên/quy cách/quantity/price/store, không hydrate từ catalog khi đọc lại. Các ID nguồn trên ShoppingItem là provenance, không phải FK bắt buộc giữ một offer/product hiện hành mãi mãi; xóa catalog không làm mất snapshot. Những FK còn lại giữ parent/owner và liên kết reference hiện hành. API xóa nguồn về sau cần tôn trọng chính sách ngừng cung cấp/retention của từng entity.

Reference tables bắt đầu trống; không seed retailer/thương hiệu/giá provider vào development. V4 đã có Category/Brand; DATA-00 bổ sung cấu trúc và kiểm tra liên kết, còn dữ liệu thật cần quy trình nạp của PROVIDER-00/CATALOG-01. Fixture chỉ trong src/test.

## Tiền và quantity

`MoneyRules` dùng BigDecimal; JSON giữ decimal string. Input chỉ nhận số thập phân thông thường, không nhận exponent, dấu âm, NaN hoặc Infinity. Giá bằng 0 được xác nhận khác giá null. Phần thập phân không biểu diễn được theo currency bị từ chối, không làm tròn âm thầm; zero thừa như `10.00` với VND được chuẩn hóa thành `10`.

Mỗi subtotal làm tròn HALF_UP theo precision currency, sau đó mới cộng. BudgetSummary trả tổng phần có giá, remaining, over_budget_amount, missing IDs và is_complete. Vượt Budget là warning. Tổng active Session chỉ gồm PENDING/PURCHASED; tổng cuối chỉ PURCHASED, loại các món bỏ/thay/skip. Android nhận tổng server, không triển khai một phép tính budget khác.

Quantity dương và đúng quantity_step. Product và private catalog có step; custom item chưa ghép dùng step 1 mặc định, hoặc policy step theo unit do server truyền vào ShoppingData. Client không tự cấp step hoặc tổng tiền. Thêm currency khác phải cấu hình shopping_currencies với precision tương ứng; không đổi precision của currency đã có dữ liệu bằng một thao tác cấu hình tùy tiện.

## Ranh giới transaction cho service sau

`ShoppingData.read(access) { ... }` và `mutate(access, request) { ... }` lấy owner từ auth trong cùng transaction. Không truyền owner từ request client. Session/account được khóa theo thứ tự auth hiện có; Plan/item phải thuộc đúng parent. Người khác và ID không tồn tại cùng trả RESOURCE_NOT_FOUND.

Một service backend có thể ghép nhiều thao tác trong cùng block mutate. Block phải ngắn, đồng bộ, không giữ lại ShoppingRead/ShoppingWrite hoặc gọi provider/chờ người dùng bên trong. Ví dụ primitive lưu Draft (HTTP wrapper hiện có ở ShoppingPlans):

```kotlin
val request = MutationRequest("POST", "/plans", key, shoppingJson.encodeToJsonElement(input))
val result = data.mutate(accessToken, request) {
    val view = createDraft(input)
    MutationResult(201, shoppingJson.encodeToJsonElement(view), "/api/v1/plans/${view.plan.id}")
}
```

MutationRequest phải chứa method/path và body/query đúng ý định HTTP đã validate; canonical JSON sắp xếp object keys, giữ thứ tự array. Cache có phạm vi owner + method + path + key; chỉ lưu SHA-256 request và kết quả nghiệp vụ/status/Location, giữ ít nhất 24 giờ. Replay kiểm tra auth/owner trước, trả kết quả cũ trước kiểm version. Key cũ/body-query khác trả IDEMPOTENCY_KEY_REUSED. Credential results bị chặn, và lỗi làm rollback cả Plan/item/audit/replay key.

Draft mới version=1/revision=0/status DRAFT, lấy max_stores/mode từ Profile một lần. Đổi Profile không sửa Plan đã có. Catalog state adapter là primitive nội bộ; provider thực chưa được gọi. Mọi thay đổi item/budget trả PlanView với tổng mới trong cùng version. No-op giữ version. Version cũ trả VERSION_CONFLICT với details.current_version; item trùng không tự gộp, Essential cần xác nhận khi xóa.

`appendRevision` là primitive ghi snapshot, **không phải implementation API-FINAL-02**. Finalize service sau này phải kiểm review, thời hạn, version giá/offer, allocation và consent trong cùng transaction trước khi gọi primitive này. Ingestion phải cập nhật offer.version khi dữ liệu ảnh hưởng review thay đổi. Không lấy primitive làm đường tắt bỏ qua review/confirm. Reopen chỉ từ FINALIZED, giữ revision counter, xóa con trỏ current và invalidate markers; lần chốt sau tăng revision. Chưa triển khai Start Session, routing, Complete hoặc Template service.

## Android và kiểm chứng

Android `shopping/ShoppingContracts.kt` có kiểu Money(BigDecimal), Quantity và decoder PlanView. Decoder kiểm tra decimal string, version/state, currency, null/thiếu giá và completeness; giữ metadata của Offer. JSON đầu vào là phần `data` của success envelope. Không có request client hoặc UI Plan mới trong DATA-00.

Backend và Android dùng chung fixture `Frontend/app/src/test/resources/shopping/plan-view.json` để kiểm serialization/decoder. Backend test khai báo file này là input Gradle; nó chỉ là test dependency trong cùng repository, không đi vào backend runtime.

Các bộ kiểm tra backend: MoneyRulesTest, ShoppingDataTest và ShoppingMigrationTest. Chúng dùng PostgreSQL tạm riêng 55432 qua script test-local, không skip khi thiếu database; kiểm rollback/concurrency/replay, precision/null, owner, snapshot và nâng V4→V5. Auth/Profile reset helper chỉ chạy khi current_database=auth00_test và port=55432, liệt kê đầy đủ FK mới. Kiểm thử endpoint nền bằng Postman là không áp dụng vì không có route DATA-00 riêng; hồi quy Postman auth/Profile vẫn chạy qua task test.

Android ShoppingContractsTest và unit regressions/build kiểm consumer. Không cần screenshot/device test mới vì không đổi giao diện hoặc đường mạng. Khi chạy lại build debug cho thiết bị USB, giữ `smartShoppingDebugApiBaseUrl=http://127.0.0.1:8080/api/v1/` theo [hướng dẫn thiết bị](../Frontend/docs/local-backend-devices.md).

Agent chạy Gradle qua [gradle-run](../../.agents/skills/gradle-run/SKILL.md). Database development chưa được agent migrate; V5 tự áp dụng khi người dùng khởi động lại backend. Backup/restore, retention/cleanup, chi phí, hosting/TLS và vận hành production còn cần kiểm chứng theo môi trường release; DATA-00 local không xác nhận production đã sẵn sàng.
