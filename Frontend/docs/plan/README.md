# PLAN-01 — Tạo và mở lại kế hoạch

Từ Home chọn **Bắt đầu ngay**. Ba bước theo [Stitch](https://stitch.withgoogle.com/projects/11930636729725860020):

1. Nhập ngân sách VND lớn hơn 0, ví dụ `500000`, hoặc chọn mức tiền nhanh.
2. Chọn từ 1 đến `max_stores` nơi mua, tối đa 3 theo Profile. **Cả chuỗi** và **Chi nhánh cụ thể** là hai lựa chọn khác nhau; chọn chi nhánh thay cho chuỗi tương ứng. Không cần GPS để chọn chuỗi.
3. Chọn nhóm hàng, xem tóm tắt rồi **Lưu kế hoạch**. Backend lưu Plan và app mở catalog. Từ Home có thể mở lại cùng Plan để **Xem sản phẩm** hoặc **Chỉnh sửa kế hoạch**.

Source Stitch: ngân sách `6ba84b164eab4cd5b97b8e9ce20bdbc3`, nơi mua `72f5d405802d46fba25a512af241ca59`, nhóm hàng `98ee9fa676774e2db55fad9edefba426`. Ảnh kiểm chứng và bản xuất thiết kế được giữ cục bộ.

## Lưu và lỗi

Back hoặc xoay Activity giữ bản nhập. Ý định lưu chưa rõ kết quả được thử lại với cùng key/body; input bị khóa để tránh tạo nháp trùng. Version conflict giữ bản nhập và chỉ tải bản mới khi người dùng xác nhận. Bản nhập chưa lưu nằm trong RAM; sau process death, Home đọc lại những Plan server đã lưu.

Category/retailer lấy từ reference thật. Khi chưa có dữ liệu, UI hiển thị rỗng/thử lại. Chọn chi nhánh hiện đọc Store nội bộ, có cảnh báo phạm vi dữ liệu; GPS/Places/nearest-store còn thuộc nhóm Map/provider. Catalog không tự thêm món, tối ưu hoặc so sánh giá.

## Chạy và kiểm thử

Khởi động backend bản mới và đồng bộ nguồn theo [hướng dẫn catalog](../../../backend/CATALOG_API.md). Cấu hình emulator/USB ở [hướng dẫn thiết bị](../local-backend-devices.md). Chi tiết HTTP/ownership/version/idempotency ở [PLAN_API](../../../backend/PLAN_API.md).

PlanControllerTest/PlanApiTest kiểm tra validation, retry intent, pagination, owner clearing và version conflict. PlanUiTest kiểm tra ba bước, lỗi input, branch selection, chữ lớn và luồng lưu/mở/chỉnh sửa. Các ca live chỉ dùng fixture `http://127.0.0.1:18080/api/v1/`, PostgreSQL tạm 55432 và fake mail; hai property `smartShoppingDebugApiBaseUrl`/`authIntegrationBaseUrl` phải trùng URL này. Xem [Profile guide](../profile/README.md) để chuẩn bị fixture. Sau test, cài lại app với URL backend phát triển.
