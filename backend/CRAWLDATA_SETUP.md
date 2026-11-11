# Nguồn catalog CrawlData (tự host)

Backend hỗ trợ hai nguồn catalog, chọn bằng biến môi trường `CATALOG_PROVIDER`:

| Giá trị | Nguồn | Khóa | Cách đồng bộ |
|---|---|---|---|
| bỏ trống hoặc `catalogflow` | CatalogFlow (mặc định, như trước) | `CATALOGFLOW_API_KEY` (DPAPI) | Tăng dần: snapshot + changes |
| `crawldata` | Dịch vụ CrawlData tự chạy | Không cần | Nhập toàn bộ theo trang |

Giá trị khác `catalogflow`/`crawldata` bị từ chối ngay khi khởi động.

## Cấu hình

- `CRAWLDATA_BASE_URL`: origin của CrawlData, mặc định `http://127.0.0.1:8000`.
- Chỉ cho phép HTTP với `localhost`, `127.0.0.1` hoặc `[::1]`. Host khác phải dùng HTTPS. Không chấp nhận path, query hay thông tin đăng nhập trong URL.
- Khi chọn `crawldata`, launcher không nạp và không yêu cầu khóa CatalogFlow.

```powershell
$env:CATALOG_PROVIDER = 'crawldata'
.\scripts\run-local.ps1 -Action CheckCatalog   # đọc một mẫu, không cần database
.\scripts\run-local.ps1 -Action SyncCatalog    # nhập vào PostgreSQL development (hỏi DB password)
.\scripts\run-local.ps1 -Action Run            # chạy backend với nguồn CrawlData
```

## Ánh xạ dữ liệu

Adapter `catalog/CrawlDataClient.kt` gọi các endpoint sau:

- `GET /api/v1/stores` và `GET /api/v1/categories?store_id=` để lấy tên category. Dữ liệu này được tải lại ở đầu mỗi lượt phân trang. Store bị tắt hoặc chưa sẵn sàng bị bỏ qua.
- `GET /api/v1/products?limit=&offset=` (tối đa 100 mỗi trang), với các tham số lọc `retailer`, `q`, `category_id`.
- Sau khi tải sản phẩm, Sync đọc lại `/api/v1/stores` và nạp store có `pricing_scope=branch` đang bật/sẵn sàng vào `shopping_stores` (PUBLIC, `source=CRAWLDATA`, `location`/`address` null vì nguồn không có). Hiện chỉ Emart có chi nhánh: Sala, Phan Văn Trị, Phan Huy Ích. Store `chain` (giá mặc định website) không thành chi nhánh. Chỉ nạp chi nhánh của retailer có trong lượt Sync; chi nhánh bị gỡ upstream chưa bị ẩn.

Mỗi **offer** thành một `ProviderProduct`:

- `id` có dạng `offer-<offer id>`. `website` là mã retailer (`bhx`, `emart`, `lottemart`, `winmart`, `coopmart`).
- `price` lấy từ giá offer. `originalPrice` lấy từ `reference_price`. Thời điểm quan sát lấy từ `updated_at` của offer.
- Đơn vị lấy từ `details.variant_label`. Nếu thiếu thì dùng "sản phẩm", tức một đơn vị niêm yết.
- Category gồm `category_id` (đứng đầu) và `category_ids`. Category cha được suy từ `source_details.parents`.

Các quy tắc giá vẫn giữ nguyên:

- Offer có `conditions` hoặc `requires_detail` không có giá. Cờ cảnh báo là `PRICE_CONDITIONS_REQUIRE_REVIEW`.
- `pricing_scope` khác `chain` (ví dụ giá theo từng chi nhánh Emart) được coi là giá theo cửa hàng chưa xác minh. Normalizer bỏ giá và gắn cờ `STORE_PRICE_SCOPE_UNVERIFIED`.
- Giá thiếu hoặc sai kiểu giữ null, không bao giờ coi là 0.
- Không đưa `source_price`/`pricing_context` thô vào hệ thống.

Phân trang đếm theo **sản phẩm** (`total` của CrawlData), không đếm theo offer. Nhờ đó một sản phẩm có nhiều offer không làm dừng trang sớm. SyncCatalog với CrawlData cho phép tối đa 1000 trang (100.000 sản phẩm). CatalogFlow vẫn giữ giới hạn 200 trang.

## Giới hạn hiện tại

- CrawlData chưa có giao thức đồng bộ tăng dần, nên mỗi lần Sync tải lại toàn bộ (khoảng 670 trang cho khoảng 66.800 sản phẩm).
- Lượt làm mới theo retailer từ API Plan (`catalog-refresh`) vẫn giới hạn 60 giây và 200 trang. Retailer lớn như Emart có thể hết thời gian, khi đó nguồn được ghi lỗi. Hãy dùng SyncCatalog để nạp đầy đủ.
- Sync đầy đủ thành công (không partial) sẽ tắt các mục catalog cũ cùng mã nguồn, gồm cả mục do CatalogFlow nạp trước đó. Nếu quay lại CatalogFlow sau khi đã có cursor tăng dần, các mục đã bị tắt chỉ bật lại khi upstream có thay đổi.
- Ba offer Emart (theo chi nhánh) của cùng một sản phẩm hiện là ba listing không có giá, cho tới khi xử lý giá theo cửa hàng (STORE-02).
