# CATALOG-01 — danh sách sản phẩm theo Plan

Sau khi lưu Plan, app mở catalog. Mở lại Plan từ Home có nút **Xem sản phẩm**. Catalog đọc dữ liệu qua API-CATALOG-01–03 của backend; không giữ API key CatalogFlow trong APK.

## Trải nghiệm

- Ngân sách/đã chọn/còn lại lấy từ Plan/BudgetSummary của server; không tính một tổng khác ở client.
- Tìm kiếm theo tên, lọc nơi mua/nhóm hàng/thương hiệu, sắp xếp tăng/giảm giá và tải thêm trang. Nhóm hàng thật được giới hạn theo retailer_ids và có tìm kiếm/tải thêm khi số lượng lớn.
- Danh sách theo nơi mua, ảnh có cache bằng Coil, tên/brand/quy cách/giá và nhãn độ mới. Thiếu giá hiện “Chưa xác nhận giá”; giá chuỗi không được gọi là giá của một chi nhánh.
- Chi tiết có nguồn, observed_at, scope, stock unknown và warning. Dữ liệu cửa hàng chưa xác minh phạm vi giá có thông báo riêng và giữ giá thiếu. Không mở nút thêm món/Compare chưa có service; các hành vi đó thuộc LIST-01/COMPARE-01.
- Loading/empty/partial/failed có thông báo và retry; lỗi trang tiếp theo giữ trang đã tải, cursor lỗi cần tải lại từ đầu. Refresh mutation giữ nguyên key/body khi retry/rotation token. Retry-After có countdown. Back/logout/đổi owner hủy job và không đưa kết quả cũ vào Plan mới.

## Thiết kế

Nguồn Stitch: project `11930636729725860020`, màn **Chọn sản phẩm - Smart Shopping** `b2044f0c24f841e1ac9807a32962e5c0`. Đã tải trực tiếp qua Stitch tools, giữ [HTML](design/stitch-catalog.html) và [ảnh gốc](design/stitch-catalog.png). Giữ màu/theme, card ngân sách, tìm/lọc ngang, nhóm theo nơi mua và product card; dùng dữ liệu thực tế thay số/sản phẩm mẫu. CTA thêm List/so sánh trong prototype chưa được giả lập ở task catalog.

State do CatalogController trong scope RegisterViewModel/PlanController sở hữu; CatalogContent nhận state/callback và image slot thuần cho kiểm thử. AuthApi/AuthSessionRepository dùng lại bảo vệ phiên/refresh/mutex; không có một session manager thứ hai. Controller cập nhật Plan version từ server sau refresh. Về Plan vẫn sửa/mở lại được; không tự thêm ShoppingItem.

## Chạy

Backend cần [đồng bộ catalog và chạy bản mới](../../../backend/CATALOG_API.md). Cài app debug theo [hướng dẫn thiết bị](../local-backend-devices.md); dùng Pixel_9a. Source mặc định 10.0.2.2:8080; property ghi đè khi dùng USB/reverse. Không dùng fixture 18080 để sử dụng hằng ngày.

Kiểm thử live dùng duy nhất PG55432 + AuthFixtureServer test-only ở 18080, fake mail/provider và hai property URL trùng nhau. CatalogUiTest có fixture thuần cho sáng/tối/font200%, partial/empty/error/detail/filter/callback và live Plan/catalog/search/pagination/detail. Ảnh fixture không được coi là dữ liệu provider thật. Build/test/lint Android trước device:108 unit đạt,0 skip; lint0error/29warning (27 có sẵn,2 thông báo phiên bản Coil mới hơn),1hint. Coil3.3.0 đã kiểm tra với Kotlin2.2.10/Compose của project; không tự nâng toàn toolchain.

Kết quả device và đường dẫn ảnh được cập nhật sau khi chạy trong [plan](../../../../plans/completed/2026-10-28-product-catalog.md). Việc provider probe/SyncCatalog thật của người dùng được ghi riêng; không suy rằng mọi sản phẩm/retailer hoặc quyền/quota đã được nghiệm thu chỉ từ test fixture.

## Ảnh kiểm chứng

[Danh sách sáng](evidence/pixel-9a/catalog-light.png), [tối/font 200%](evidence/pixel-9a/catalog-dark-font200.png), [chi tiết](evidence/pixel-9a/catalog-detail.png), [giá thiếu/partial](evidence/pixel-9a/catalog-partial-missing-price.png), [lỗi và thử lại](evidence/pixel-9a/catalog-failed.png), [chi tiết qua API fixture](evidence/pixel-9a/catalog-live-detail.png), [kết quả rỗng qua API fixture](evidence/pixel-9a/catalog-live-empty-outside-retailer.png). Tất cả là ảnh runtime Pixel_9a với dữ liệu kiểm thử.

Thu lại ảnh là tùy chọn: chạy `scripts/capture-catalog-evidence.ps1` trong một terminal riêng trước lượt CatalogUiTest có runner argument `catalogEvidence=true` (giới hạn mặc định 180 giây). Script chỉ dùng ADB screencap và marker/token do test tạo, lưu PNG đã kiểm tra vào thư mục evidence; không dùng UiAutomation screenshot trên API35. Kiểm thử chức năng bình thường không cần collector hoặc argument này. Live test vẫn cần fixture 18080 và hai URL kiểm thử trùng nhau; không chạy vào backend development 8080.
