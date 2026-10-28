# CATALOG-01 — danh sách sản phẩm theo Plan

Sau khi lưu Plan, app mở catalog. Mở lại Plan từ Home có nút **Xem sản phẩm**. Android gọi API-CATALOG-01–03 bằng phiên Smart Shopping; API key CatalogFlow chỉ nằm ở backend.

## Trải nghiệm

- Tìm theo tên, lọc retailer/nhóm hàng/thương hiệu, sắp xếp giá và tải thêm trang.
- Ngân sách/đã chọn/còn lại lấy từ BudgetSummary của server. Giá thiếu hiện **Chưa xác nhận giá**.
- Chi tiết hiển thị brand/quy cách, nguồn, thời điểm ghi nhận, độ mới, phạm vi giá và cảnh báo. Metadata cửa hàng chưa xác minh giữ giá thiếu, không khẳng định giá toàn chuỗi.
- Loading/empty/partial/error có thông báo và thử lại. Lỗi tải trang sau giữ sản phẩm đã tải. Retry-After có đếm ngược; retry mutation giữ cùng key/body. Back/logout/đổi owner hủy tác vụ cũ.

Thêm món và Compare thuộc task sau. Thiết kế dựa trên [Stitch project](https://stitch.withgoogle.com/projects/11930636729725860020), màn `b2044f0c24f841e1ac9807a32962e5c0`. Bản xuất thiết kế và ảnh kiểm chứng giữ cục bộ, không phải đầu vào build/test.

## Chạy

Người dùng cần [SyncCatalog và chạy backend bản mới](../../../backend/CATALOG_API.md). Dùng [hướng dẫn thiết bị](../local-backend-devices.md) để cài app. Debug mặc định dùng `10.0.2.2:8080`; USB/reverse dùng property ghi đè localhost. URL fixture 18080 chỉ dành cho kiểm thử.

## Kiểm thử và source

CatalogController sở hữu state và tác vụ trong scope của RegisterViewModel/PlanController. CatalogContent nhận state/callback và image slot; AuthApi/AuthSessionRepository dùng lại cơ chế phiên và refresh hiện có. Ảnh sản phẩm runtime dùng Coil.

CatalogTest kiểm tra decoder, request/context, retry, pagination và cancellation. CatalogUiTest kiểm tra tìm/lọc, partial/missing price, empty/error/retry, chi tiết và chữ 200%/theme tối. Ca live dùng fake provider/mail, fixture 18080 và PostgreSQL tạm 55432; hai property URL phải trùng nhau. Chạy kiểm thử chức năng bình thường không cần bật `catalogEvidence` hoặc script thu ảnh cục bộ. Không chạy ca tạo tài khoản fixture vào backend development 8080.
