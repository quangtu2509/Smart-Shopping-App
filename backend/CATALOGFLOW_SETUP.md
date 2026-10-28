# CatalogFlow — cấu hình và kiểm tra API

Nguồn được người dùng chọn ngày 27/10/2026: [CatalogFlow](https://crawl-data-rosy.vercel.app/), website crawl do người dùng tự triển khai. Dicho.site không còn được dùng theo xác nhận của người dùng.

Đường kết nối cần triển khai: **Android → API Smart Shopping → Product Service/adapter → CatalogFlow**. Khóa CatalogFlow chỉ nằm trên máy backend. Access token đăng nhập Smart Shopping và khóa provider là hai loại khác nhau.

Backend đã có adapter Kotlin và nạp cấu hình local. CATALOG-01 đã nối Product Service/API catalog và giao diện Android; cần [SyncCatalog rồi chạy backend bản mới](CATALOG_API.md) để app đọc reference và sản phẩm đã nhập. Chỉ cấu hình khóa chưa làm sản phẩm tự xuất hiện trên app.

## Nhập khóa trên máy tính

Dùng **Windows PowerShell** với tài khoản Windows chạy backend. Phải có ít nhất một sản phẩm đã được duyệt trên CatalogFlow để kiểm tra cấu trúc item.

```powershell
Set-Location 'D:\Android Project\My project\Smart Shopping\Smart Shopping App\backend'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\catalogflow-local.ps1 -Action Configure
```

Dán khóa vào lời nhắc `CatalogFlow API key`: ký tự được ẩn, khóa không nằm trong lịch sử lệnh. Công cụ gửi đúng một GET, không tạo job crawl hoặc thay dữ liệu. Khi HTTP 200 và envelope hợp lệ, khóa được lưu bằng Windows DPAPI trong `.env.catalogflow.local.xml` đã được Git ignore. File auth `.env.local.xml` giữ nguyên. Không gửi khóa hoặc hai file bí mật này vào chat/Git.

Đọc lại để kiểm tra sau này:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\catalogflow-local.ps1 -Action Probe
```

Nếu đã tạo/thay khóa trên website, dùng `-Action Configure -Replace` để cập nhật bản lưu local; công cụ chỉ thay sau khi probe hợp lệ. Khóa bị thu hồi trả HTTP 401; 403 là thiếu quyền, 429 là giới hạn gọi. Lỗi mạng/TLS/timeout được báo chung, không in body/header/khóa. Công cụ chỉ gửi khóa qua HTTPS, tắt chuyển hướng và không tự retry. Tổng số sản phẩm 0 vẫn là kết nối hợp lệ nhưng cần duyệt sản phẩm rồi Probe lại.

## Bằng chứng và giới hạn hiện tại

- Endpoint theo giao diện website: `GET /api/v1/live-products?page=1&limit=1`, header `Authorization: Bearer <key>`; envelope `items`, `total`, `page`, `limit`. `limit` tối đa 100 theo mô tả website.
- Probe chỉ lưu tên trường và kiểu dữ liệu của một item trong `build/provider/catalogflow-probe.json`, không lưu giá trị sản phẩm/khóa. Đây là bằng chứng kết nối và cấu trúc, chưa chứng minh ý nghĩa giá/độ mới.
- Ngày 27/10/2026, Configure có khóa ban đầu trả HTTP 200/total=0. Probe sau đó đã trả HTTP 200, total=13481, sample_count=1; agent đối chiếu báo cáo tên/kiểu trường và không đọc kho bí mật. Đã đọc được một mẫu thật, chưa xác minh ngữ nghĩa giá hoặc tính đầy đủ của toàn catalog trước bước adapter.
- Backend đã có `catalog/CatalogFlowClient.kt`, `CatalogFlowDecoder.kt` và `CatalogFlowConfig.kt`, đăng ký `CatalogProvider` tại application khi được cấu hình. Người dùng đã xác nhận `CheckCatalog` hiện connected với nguồn thật. PROVIDER-00 còn cần xác minh metadata, quota, phạm vi retailer/store, quyền phân phối và Map; CATALOG-01 nối API-CATALOG-01–03 và giao diện, PRICE-01 chuẩn hóa metadata giá.
- Không thay base URL Android bằng địa chỉ CatalogFlow. Android tiếp tục gọi backend local theo [hướng dẫn thiết bị](../Frontend/docs/local-backend-devices.md). API key không được đóng gói trong APK.

Một mẫu có `price/api_price` kiểu number, `currency/source/website/unit` kiểu string, `images` là mảng string, `categories` là mảng object, `observed_at/last_updated_at/last_success_at` kiểu number, `stale` kiểu boolean; `stores` rỗng. Chưa biết giá trị/ngữ nghĩa từ báo cáo chỉ kiểu, chưa kiểm thử bộ lọc page/limit/search/site/collection/category. Không coi timestamp crawl là cam kết giá/tồn kho tại một chi nhánh. Không thêm giá 0 hoặc timestamp giả khi thiếu.

## Kiểm tra bằng adapter Kotlin và chạy backend

Đã Configure thành công thì không cần nhập lại khóa. Chạy từ thư mục bất kỳ:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\Android Project\My project\Smart Shopping\Smart Shopping App\backend\scripts\run-local.ps1" -Action CheckCatalog
```

Lệnh này nạp riêng khóa provider đã lưu, gọi `gradlew.bat run --args=--check-catalog`, GET page 1/limit 1 qua adapter Kotlin rồi thoát; không khởi động HTTP server, nạp auth secrets hoặc kết nối/migrate database. Kết quả chỉ có total/decoded/rejected/partial, không in nội dung sản phẩm hay khóa. `partial=true` có thể do thiếu metadata của mẫu, không tự đồng nghĩa mất kết nối. Khi bị lỗi, mã TIMEOUT/NETWORK/RATE_LIMITED/AUTHENTICATION/FORBIDDEN/REDIRECT/INVALID_RESPONSE/RESPONSE_TOO_LARGE mô tả nguyên nhân; chỉ RATE_LIMITED/UNAVAILABLE có thể kèm Retry-After upstream, không tự retry.

Để chạy backend với provider đã nạp, dừng backend cũ bằng Ctrl+C tại terminal của bạn rồi chạy lại lệnh Run quen thuộc:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\Android Project\My project\Smart Shopping\Smart Shopping App\backend\scripts\run-local.ps1" -Action Run
```

Run vẫn dùng cấu hình auth/PostgreSQL hiện có và sẽ tự nạp `.env.catalogflow.local.xml` nếu environment chưa có `CATALOGFLOW_API_KEY`. Biến `CATALOGFLOW_BASE_URL` và `CATALOGFLOW_API_KEY` được giữ trong process rồi khôi phục khi script thoát. Nếu truyền khóa bằng environment thì cặp cấu hình này được ưu tiên; không ghép khóa trong file với origin environment khác. Nếu không có cả hai biến/file provider, auth/Profile/Plan vẫn chạy như trước. Startup không tự crawl, tải toàn bộ catalog hoặc ghi reference/database từ provider.

## Hợp đồng adapter backend

`CatalogProvider.products(CatalogQuery)` là API suspend, caller quản lý hủy và vòng đời; không gọi HTTP trong transaction database. Hỗ trợ page/limit/search/site/collection/category, UTF-8 query encoding, HTTPS ở runtime, Bearer trong header, không theo redirect và không tự retry. Timeout toàn yêu cầu 20 giây, body giới hạn 4 MiB trong lúc nhận. HttpClient sống cùng application và được đóng khi server dừng. Không thêm dependency HTTP mới; backend dùng JDK 25.

`ProviderPage` gồm items/total/page/limit/rejectedItems/warnings/partial. Item sai identity/name/website, chưa duyệt hoặc không phải object bị loại và báo partial; duplicate cùng website/id được loại, không ghép giữa retailer chỉ vì cùng tên. Không cache hoặc trả raw/evidence qua hợp đồng này. Query/filter chưa kiểm chứng với toàn bộ retailer thật; kiểm thử fixture xác nhận encoding và truyền tham số.

`ProviderProduct` giữ giá và timestamp dạng BigDecimal, không đi qua Double. Giá null/không hợp lệ khác giá 0. Giữ riêng price/apiPrice/originalPrice/salePrice/priceOptions; không chọn giá biến thể hoặc quy cách thay người dùng. Currency/source/observedAt/stale thiếu sinh warning. Lớp adapter chỉ giữ dữ liệu nguồn; [CatalogNormalizer của CATALOG-01](CATALOG_API.md#normalize-và-cache) chuyển sang Product/Offer theo chính sách VND, Unix seconds và đếm đơn vị niêm yết có cảnh báo. Không giả barcode, tồn kho hoặc thời gian hiện tại. `hasStoreMetadata` chỉ báo có dữ liệu store, không xác nhận chi nhánh; khi có metadata này nhưng chưa xác minh phạm vi áp dụng thì giá bị giữ null. Mẫu probe thật trước đây có stores rỗng, không suy toàn bộ catalog đều như vậy. PRICE-01 còn policy và điều kiện giá nâng cao.

Kiểm thử script dùng khóa/sản phẩm giả, không gọi provider hay database:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\src\test\powershell\catalogflow-local.tests.ps1
```
