# CATALOG-01 — catalog theo Plan

Product Service dùng cache PostgreSQL và adapter CatalogFlow; Android chỉ gọi Smart Shopping API bằng phiên đăng nhập. CatalogFlow key giữ ở backend. Không thêm item, tự ghép sản phẩm giữa retailer, thay List/snapshot hoặc giả tổng ngân sách trong task này.

## Chuẩn bị dữ liệu thật

Đã Configure CatalogFlow thì giữ khóa hiện tại. Từ thư mục bất kỳ, người dùng chạy:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\Android Project\My project\Smart Shopping\Smart Shopping App\backend\scripts\run-local.ps1" -Action SyncCatalog
```

SyncCatalog hỏi DB password qua input ẩn nếu environment chưa có, nạp riêng file DPAPI provider và áp dụng migration V6, sau đó nhập reference/Product/Offer bằng dữ liệu đã duyệt. Không nạp Gmail/Google, không khởi động HTTP server, không reset tài khoản/hồ sơ/Plan. Đọc tối đa 200 trang × 100 sản phẩm/lần gọi; partial báo rõ khi chạm giới hạn, metadata sai hoặc nguồn thay đổi giữa các trang. Chỉ commit cache sau khi đọc xong; provider timeout/401/429 không ghi snapshot thiếu vào database. Số EXECUTING của Gradle không phải tiến độ sản phẩm; chờ dòng `Catalog sync: received=...; imported=...; rejected=...; sources=...; partial=...` và BUILD SUCCESSFUL.

Thao tác này đọc toàn catalog bằng khóa đã duyệt của tài khoản; không tạo job crawl hoặc thay quyền duyệt trên website. Không tự chạy lúc backend startup. Agent chỉ dùng HTTP fixture/PG55432; người dùng tự chạy lệnh với development database. Khóa và DB password không có trong command line, JSON report hoặc Postman collection.

Sau đồng bộ, khởi động lại backend cũ trong terminal của người dùng bằng Ctrl+C rồi:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\Android Project\My project\Smart Shopping\Smart Shopping App\backend\scripts\run-local.ps1" -Action Run
```

App có thể chọn retailer/nhóm hàng vừa nhập khi tạo Plan. Không đổi Android base URL sang CatalogFlow; xem [cấu hình thiết bị](../Frontend/docs/local-backend-devices.md). Flyway/PG warning không tự chứng minh một task thất bại; phải xem exit status/dòng kết quả cuối cùng.

## API

Tất cả path dưới `/api/v1`, Bearer access token Smart Shopping bắt buộc, envelope `data/meta`, snake_case, tiền decimal string và Cache-Control no-store. Ownership kiểm tra trước khi đọc cache/replay. Source refresh lỗi không được chuyển thành 401 auth của Android.

| API | Request | Kết quả |
|---|---|---|
| API-CATALOG-01 | GET `/plans/{plan_id}/catalog`; retailer_id/store_id/category_id/q/brand_id/sort/limit/cursor | CatalogPage: items Offer[], next_cursor, status, retailer_results, warnings |
| API-CATALOG-02 | POST `/plans/{plan_id}/catalog-refresh`; Idempotency-Key; `{expected_version}` | 202 `{plan,catalog_status:LOADING}`; nhận công việc, chưa phải hoàn tất |
| API-CATALOG-03 | GET `/products/{product_id}?plan_id=...` | Product và offers trong đúng Plan; ngoài ngữ cảnh 404 |

limit 1–100 (mặc định 20), q tối đa 200 ký tự. sort RELEVANCE/PRICE_ASC/PRICE_DESC; null price đứng cuối cả hai chiều giá. Cursor gắn owner/Plan/version/cache generation/toàn bộ filter/limit; đổi context trả 400 INVALID_CURSOR và client tải lại từ đầu. Retailer/store/category ngoài Plan trả 422; product/Plan của owner khác trả 404. Brand filter vẫn nằm trong selections/category của Plan. Category có nhiều nhánh của provider được lưu qua catalog_entries.category_ids; không coi cùng tên là cùng Category/Product giữa retailer.

retailer_results có status/error_code riêng và retry_after_seconds nullable. Giữ dữ liệu cache nếu một source hỏng, trả PARTIAL khi có phần dùng được, FAILED khi không có dữ liệu khả dụng, EMPTY khi tìm/lọc không có kết quả. Cache quá 15 phút hoặc source lỗi được trình bày stale; observed_at không thay bằng thời điểm fetch. Cache mới được dùng lại khi mở/refresh Plan; không tự retry provider. Retry-After được lưu và tôn trọng ở backend, kể cả khi client cố gọi sớm.

Refresh tạo job bền vững cùng version/owner trong transaction nhận intent; CAS/lease chống chạy trùng qua replay/poll. Application scope quản lý coroutine và cancellation; network chạy ngoài transaction. Lease 5 phút, timeout mỗi retailer 60 giây, tối đa 200 trang/source. GET có thể tiếp tục job đang chờ/lease hết hạn sau gián đoạn; sau process crash có thể phải đợi lease cũ. Kết quả chỉ áp đúng input version, còn request đổi Plan trong khi tải được giữ nguyên và không mắc kẹt LOADING. Version/state/audit/invalidation dùng primitive DATA-00. Finalized/session/history không bị refresh thay snapshot.

## Normalize và cache

V6 thêm catalog_sources, catalog_entries, catalog_cache_version và catalog_refresh_jobs; dùng lại reference/Product/Offer của V4/V5. Không đổi/xóa migration cũ. Các cache writer commit dưới generation lock; cursor không trộn hai thế hệ dữ liệu. Full import hoàn chỉnh đánh inactive entries cũ không còn trong snapshot, giữ danh tính/snapshot đã chọn; import partial giữ phần cache cũ. API-REF-02 bổ sung retailer_ids cho nhóm hàng, client hạn chế danh sách theo nơi mua đã chọn và cho phép tìm kiếm/tải thêm nhóm hàng.

ID ổn định theo namespace website/provider ID, không tự ghép theo tên hay SKU. Brand/category là reference thật; brand_name/package_label bổ sung nhãn hiển thị, không giả barcode/package_size. Product unit phải có từ nguồn; quantity_step=1 là chính sách đếm đơn vị niêm yết, kèm LISTING_QUANTITY_STEP, chưa xác minh bước chia nhỏ cân nặng. Chưa có quantity/priority/add-item UI trong CATALOG-01.

Chỉ nhận VND và số tiền nguyên không âm, đọc BigDecimal không qua Double. Zero thực sự khác thiếu giá. Thiếu source/observed_at, giá lẻ không hợp lệ hoặc nhiều phương án/biến thể chưa xác minh thì price=null có warning; không lấy giá thấp nhất tùy tiện. Timestamp nhận Unix seconds trong khoảng hợp lệ, không đoán milliseconds. Provider stale hoặc observation hơn 24 giờ được nhãn STALE, thiếu metadata là UNKNOWN. Stock UNKNOWN, scope RETAILER; khi chọn chi nhánh vẫn ghi “chưa xác nhận riêng tại chi nhánh”. Không suy unit_price, loyalty/combo discount hoặc tồn kho. PRICE-01 còn policy/validation sâu hơn; Compare/Store-private CRUD/List nằm ở task sau. API-STORE-04/SC-17 sẽ dùng lại Product Service khi triển khai STORE-02, không mở endpoint private store giả.

## Kiểm chứng

Backend CatalogServiceTest dùng PG55432/provider giả cho normalize/context/owner/cursor/null sorting, cache/refresh/replay/Retry-After, concurrency thay Budget trong lúc network và giữ snapshot. CatalogPostmanTest chạy [collection](postman/catalog01.postman_collection.json) qua Netty/PG tạm: 14 requests/36 assertions. Fixture/tokens không vào app production; environment Newman tạm được xóa.

Hồi quy backend và kiểm tra phạm vi giá được ghi trong [plan](../../plans/completed/2026-10-28-product-catalog.md). Cách kiểm chứng Android/ảnh và giới hạn dữ liệu thật ở [hướng dẫn giao diện](../Frontend/docs/catalog/README.md).

Nguồn có metadata cửa hàng chưa xác minh trả `price=null` và `STORE_PRICE_SCOPE_UNVERIFIED`, UI ghi rõ phạm vi chưa xác minh. Giá của cache từ normalizer cũ cũng bị ẩn cho tới khi refresh xác minh lại; cache có version `cf_price2_…` đánh dấu chính sách hiện hành. Refresh không dùng lại cache cũ dù vừa sync trong 15 phút. Không thay giá snapshot của List/History. Sau khi cập nhật bản backend có chính sách này, cần khởi động lại server; không bắt buộc đồng bộ lại toàn bộ catalog vì refresh có thể xác minh từng retailer.
