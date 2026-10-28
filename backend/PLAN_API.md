# PLAN-01 — API kế hoạch nháp

API-PLAN-01–04 sử dụng schema V5 và transaction ShoppingData đã có; không cần migration mới. Plan, selections, category links, audit và replay nằm trong PostgreSQL. HTTP service không gọi provider hoặc giữ transaction trong lúc chờ mạng. Backend local đang chạy cần restart để nạp route mới.

| API | Thành công | Hành vi |
|---|---|---|
| POST `/api/v1/plans` | 201 PlanView, Location | Decimal string budget>0/currency được hỗ trợ, 1–3 selections trong giới hạn Profile, ít nhất 1 category ACTIVE; tạo DRAFT/version1/revision0. |
| GET `/api/v1/plans` | 200 Page<Plan> | Chỉ owner, filter status, limit1–100/cursor; mới nhất trước, keyset created_at+id. Cursor gắn owner/filter. |
| GET `/api/v1/plans/{id}` | 200 PlanView | Items và tổng lấy từ nền DATA-00. ID không tồn tại/không thuộc owner cùng404. |
| PATCH `/api/v1/plans/{id}` | 200 PlanView | expected_version bắt buộc; chỉ budget/selections/category_ids được sửa. Một transaction tăng version một lần, no-op giữ version. |
| GET `/api/v1/retailers` | 200 Page<Reference> | API-REF-01, q/cursor/limit; code/logo_url nullable. Không seed retailer thật bằng dữ liệu mẫu. |
| GET `/api/v1/map/stores` | 200 map+next_cursor | Phần Store nội bộ của API-MAP-03: bbox bắt buộc, q/retailer_ids/cursor/limit, public và private của owner. |
| GET `/api/v1/stores/{id}` | 200 Store | API-STORE-01, can_edit đúng owner; private của người khác404. |

Các route đều cần bearer. Mọi POST/PATCH Plan cần Idempotency-Key, owner lấy từ auth session. Retry cùng key/body/query trả kết quả trước khi kiểm version; key/payload khác409. Request không nhận owner/status/total/snapshot tùy ý. Sửa Plan đã FINALIZED hoặc trạng thái không cho phép trả INVALID_STATE; reopen/finalize thuộc task sau.

Selection cả chuỗi không được lặp cùng các chi nhánh của chuỗi đó. Store public chuỗi phải đúng retailer; private chỉ owner và có thể retailer_id null. Đổi context đánh dấu catalog FAILED và invalidate kết quả/revision phụ thuộc; response PLAN_CONTEXT_CHANGED nhắc review các món. Item không bị xóa. Budget/tổng dùng BigDecimal và MoneyRules, không qua float.

Map lookup hiện chỉ truy vấn Store đã lưu, không gọi Geoapify/Places/nearest-store. Có dữ liệu trả PARTIAL, không có trả EMPTY, luôn có warning PLACES_NOT_CONNECTED. API-MAP-04, geocoding, tile/GPS và phần provider của MAP-03 chưa triển khai. Không nhận provider Place ID như Store nội bộ. Không thay trạng thái triển khai toàn bộ task Map vì phần đọc Store này.

## Request mẫu

ID dưới đây chỉ thuộc fixture test:

```json
{
  "budget": "500000",
  "currency": "VND",
  "selections": [{"retailer_id": "plan-retailer-a", "store_id": null}],
  "category_ids": ["plan-food"]
}
```

PATCH thêm expected_version và bỏ currency. Null không được dùng để xóa các nhóm input bắt buộc. Response giữ success/error envelope, field_errors/details và request_id theo15.6. Nền tiền/currency/V5 xem [DATA_FOUNDATION](DATA_FOUNDATION.md).

## Test và giới hạn

ShoppingPlansTest kiểm persistence, default Profile snapshot, strict input, chain/branch/private owner, no-op/version/replay, rollback, concurrent writer/create, state guard, pagination mới nhất trước, HTTP và cursor scope. Full backend90 tests đã đạt; 8 tests PLAN-01 xác nhận lại sau chỉnh thứ tự phân trang. Không skip database test.

ShoppingPlansPostmanTest chạy [collection](postman/plan01.postman_collection.json) qua Netty port tạm và PostgreSQL55432: **17 requests/51 assertions** đạt. Account/token/environment chỉ tạo tạm trong test rồi xóa, không export credentials. Collection có IDs synthetic, chỉ dùng cluster test; không chạy bằng account/database development. PlanTestFixtures và AuthFixtureServer nằm trong src/test, không đi vào runtime distribution. Android live dùng fixture18080 với fake mail.

Retailer/category/Store runtime chưa được nạp từ provider. API Draft không đồng nghĩa catalog đã tải hoặc UC-04 hoàn tất; CATALOG-01/PRICE-01/PROVIDER-00 còn phải nối SC-08. Agent không migrate/reset development5432, không restart tiến trình chứa secrets và không xác nhận provider thật. [Giao diện và ảnh](../Frontend/docs/plan/README.md).
