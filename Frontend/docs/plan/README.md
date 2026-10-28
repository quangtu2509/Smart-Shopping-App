# PLAN-01 — Tạo và mở lại kế hoạch nháp

Từ Home, chọn **Bắt đầu ngay**. Ba bước bám thiết kế Stitch: nhập ngân sách → chọn nơi mua → chọn nhóm hàng. Tiến trình và nút cuối nằm ngoài vùng cuộn; thông tin đã nhập được giữ khi Back hoặc xoay màn hình. Lỗi input nằm cạnh CTA; lỗi giới hạn chi nhánh nằm cạnh nút Xong của hộp chọn, nên không bị khuất khi cuộn.

1. Nhập ngân sách VND lớn hơn 0, không dùng dấu phân cách hàng nghìn, ví dụ `500000`; hoặc chọn mức tiền nhanh. Vượt ngân sách chỉ là cảnh báo trong nghiệp vụ về sau, không có lời hứa tự tối ưu.
2. Chọn 1–`max_stores` nơi mua, tối đa 3 theo Profile. Nhãn **Cả chuỗi** khác **Chi nhánh cụ thể**. Sau khi chọn chuỗi, có thể mở Chọn chi nhánh, tìm theo tên/khu vực đã lưu. Chi nhánh thay cho lựa chọn cả chuỗi tương ứng; cửa hàng riêng chỉ owner thấy. Không cần GPS để chọn chuỗi.
3. Chọn nhiều nhóm hàng, kiểm tra tóm tắt rồi **Lưu kế hoạch**. Server lưu Draft thật vào PostgreSQL, version1/revision0. Home hiển thị kế hoạch của owner, mới nhất trước, có phân trang và tìm trong các kế hoạch đã tải. Mở lại để xem hoặc chỉnh cùng Plan ID.

Lỗi mạng khi lưu giữ nguyên request/key và khóa sửa input cho đến khi Thử lưu lại, tránh tạo trùng. Version conflict giữ bản đang nhập, chỉ thay bằng bản mới khi người dùng xác nhận. Rời form chưa xác nhận kết quả lưu có cảnh báo riêng. Draft chưa lưu và ý định chờ chỉ nằm trong RAM, không tự khôi phục qua process death; kế hoạch server đã commit vẫn xuất hiện khi tải lại Home.

## Thiết kế và ảnh

Nguồn: [Stitch project](https://stitch.withgoogle.com/projects/11930636729725860020), screen IDs `6ba84b164eab4cd5b97b8e9ce20bdbc3`, `72f5d405802d46fba25a512af241ca59`, `98ee9fa676774e2db55fad9edefba426`. Bản tham chiếu đã lưu: [ngân sách](design/step-1.png), [nơi mua](design/step-2.png), [nhóm hàng](design/step-3.png); HTML tương ứng trong cùng thư mục.

Giữ màu xanh, Inter/Plus Jakarta Sans, thẻ bo góc, lựa chọn nhiều, tóm tắt và CTA cố định. Tóm tắt chuyển sang cột khi chữ lớn. Dữ liệu thương hiệu và hình minh họa mẫu của Stitch không được đưa thành dữ liệu provider thật trong app.

| Trạng thái trên Pixel 9a | Ảnh fixture |
|---|---|
| Ngân sách | [Mặc định](evidence/pixel-9a/final/budget-light.png), [bàn phím](evidence/pixel-9a/final/budget-keyboard.png), [lỗi khi nhập](evidence/pixel-9a/final/budget-error-keyboard.png) |
| Nơi mua | [Chuỗi](evidence/pixel-9a/final/stores-light.png), [chi nhánh qua API](evidence/pixel-9a/final/branch-live.png), [chưa có dữ liệu](evidence/pixel-9a/final/stores-empty.png), [lỗi giới hạn](evidence/pixel-9a/final/branch-limit-error.png) |
| Nhóm hàng | [Sáng](evidence/pixel-9a/final/categories-light.png), [tối/chữ 200%](evidence/pixel-9a/final/categories-large-dark.png) |
| Lưu và chỉnh | [Thử lưu lại](evidence/pixel-9a/final/save-retry.png), [xung đột](evidence/pixel-9a/final/version-conflict.png), [đã lưu](evidence/pixel-9a/final/draft-live-saved.png), [đã chỉnh](evidence/pixel-9a/final/draft-live-edited.png) |

Ảnh sử dụng dữ liệu synthetic trong test, không chứa tài khoản thật. MainActivity vẫn giữ FLAG_SECURE. Fixture layout được giới hạn 360dp; luồng live dùng toàn màn hình Pixel 9a mặc định 1080×2424/density420, Android15/API35. Lượt khảo sát đầu còn có khung hẹp 1080×2376/density480 trong `evidence/pixel-9a/first-pass/`.

## Phạm vi còn lại

PLAN-01 hoàn tất phần Draft riêng theo 17.8. **UC-04 chưa hoàn tất toàn luồng catalog**: CATALOG-01/PRICE-01/PROVIDER-00 và SC-08 còn phải nối. Sau lưu hiện thông báo catalog chưa khả dụng, không tạo sản phẩm/giá giả hoặc tự chạy Smart Suggest. Dữ liệu retailer/category runtime bắt đầu trống theo DATA-00; app hiển thị empty/retry khi chưa có nguồn. GPS, Places và nearest-store còn thuộc Map/provider; phần chọn chi nhánh hiện chỉ đọc Store nội bộ, có cảnh báo phạm vi dữ liệu.

## Chạy bản phát triển

Backend cần khởi động lại từ source mới để có API Plan. Ở lượt kiểm chứng trước khi gián đoạn, GET `/me` không bearer trên 8080 trả 401, còn GET `/plans` trả 404: tiến trình development chạy bản cũ. Sau lần gián đoạn, backend8080 đã dừng; app source cuối được cài/mở lại trên đúng Pixel 9a và policy HTTP đạt 1/1, chưa probe lại backend. Agent không đọc secrets hoặc sửa/reset database development. Chạy lại backend theo [hướng dẫn backend](../../../backend/README.md); Flyway áp dụng migration còn thiếu lúc khởi động.

Giữ cấu hình dùng chung `smartShoppingDebugApiBaseUrl=http://127.0.0.1:8080/api/v1/` và reverse8080 cho Pixel 9a theo [hướng dẫn thiết bị](../local-backend-devices.md). Default source của nút Run vẫn là10.0.2.2. Người dùng yêu cầu chỉ kiểm tra emulator; không cài/thay app trên điện thoại trong PLAN-01.

## Kiểm chứng

97 Android unit tests đạt: 16 PlanController, 5 PlanApi và 76 hồi quy; lint 0 lỗi/27 cảnh báo cũ. Pixel 9a: 32/32 UI tests, 0 skip, gồm 8 Plan, 4 Home và 20 auth. Ca live tạo account fixture, chọn chuỗi/chi nhánh, xoay Activity giữ ViewModel/input, POST/readback/list/open, conflict do lần sửa ngoài UI và PATCH thành công cùng ID. Bản URL8080 cài/mở trên Pixel 9a, 2/2 DebugNetworkTest không bearer đạt; ca live cuối sau chỉnh tương phản cũng đạt. Backend có 90 tests full-suite đạt và 8 ca PLAN-01 xác nhận cuối; Postman17 requests/51 assertions. Chi tiết ở [plan](../../../../plans/completed/2026-10-27-create-shopping-plan.md) và [API](../../../backend/PLAN_API.md).

Test live chỉ nhận API fixture `http://127.0.0.1:18080/api/v1/`, PostgreSQL tạm55432 và fake mail; yêu cầu **cả hai** property `smartShoppingDebugApiBaseUrl`/`authIntegrationBaseUrl` cùng URL này. Dùng quy trình fixture ở [Profile guide](../profile/README.md), chọn class `com.toked.smartshopping.shopping.PlanUiTest`. Không chạy class live với backend development. Agent chạy Gradle qua skill gradle-run. Sau test phải build/cài lại app URL8080 trong lượt riêng.
