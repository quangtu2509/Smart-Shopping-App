# PROFILE-01 — Hồ sơ mua sắm

Sau đăng ký, nút Tiếp tục mở SC-03. Người dùng đã đăng nhập mở Cá nhân → Hồ sơ mua sắm. Form đọc hồ sơ trên server, sửa số người/chu kỳ tùy chọn, nhóm hàng và thương hiệu ưu tiên/cần tránh, số cửa hàng 1–3 và chế độ tối ưu. Lưu thành công hoặc bỏ qua đưa về Home. Bản nhập được giữ khi app vào nền hoặc Activity dựng lại; khi tiến trình bị hệ điều hành đóng, mở lại sẽ đọc bản đã lưu trên server.

Các trường tùy chọn có thể để trống; default là tối đa 3 cửa hàng/BALANCED. Giao diện nhận chu kỳ như 2 tuần hoặc 1 tháng 3 ngày và chuyển sang ISO 8601 cho API; số người là integer dương. Thay đổi hồ sơ không sửa các Plan đã có. Chọn ưu tiên tự bỏ trạng thái cần tránh của cùng mục và ngược lại. Khi rời form có thay đổi chưa lưu, app yêu cầu xác nhận bỏ bản nhập.

## API và dữ liệu tham chiếu

API-PROFILE-01 GET /me/shopping-profile trả Profile; API-PROFILE-02 PATCH cùng path gửi expected_version và Idempotency-Key. Tất cả endpoint yêu cầu bearer; owner lấy từ phiên, không nhận owner từ client. Optional field vắng giữ nguyên, null chỉ xóa household_size/default_period. PATCH không thay đổi dữ liệu giữ nguyên version; thay đổi dữ liệu tăng một version.

Lỗi 422 có field_errors. VERSION_CONFLICT 409 giữ bản nhập và cho tải bản mới sau xác nhận, không tự ghi đè. Khi kết quả lưu chưa rõ vì lỗi mạng/server, app khóa chỉnh input và thử lại đúng key/body cũ; người dùng cũng có thể tải lại bản trên server sau xác nhận. Repository giữ quy tắc refresh một lần và gửi lại cùng key/body. Bản lưu idempotency có phạm vi owner + PATCH /me/shopping-profile, giữ tối thiểu 24 giờ và không chứa thông tin xác thực.

API-REF-02 GET /categories và API-REF-03 GET /brands cung cấp Page<Reference>, có tìm kiếm/phân trang. Migration V4 tạo shopping_categories, shopping_brands và profile_requests. Hai bảng tham chiếu ban đầu trống; DATA-00 cung cấp schema; dữ liệu nội bộ thật được nạp qua quy trình PROVIDER-00/CATALOG-01. Form cho lưu những tùy chọn khác khi danh sách trống và thông báo thiếu dữ liệu. Test fixture seed riêng, không đưa nhãn thử nghiệm vào migration hoặc database development. Giá trị cũ ngừng hoạt động vẫn hiển thị để người dùng giữ hoặc bỏ; không cho thêm mới ID không hợp lệ.

## Kiểm chứng

Backend: ShoppingProfileTest kiểm tra persistence, null/partial patch, field validation, owner, conflict, concurrency, idempotency và rollback trên PostgreSQL tạm 55432. ShoppingProfilePostmanTest chạy postman/profile01.postman_collection.json qua Netty thật; token test chỉ nằm trong environment tạm, bị xóa sau chạy. Collection chia sẻ để trống token; nhập token của tài khoản test riêng vào biến local nếu chạy thủ công, không export environment có token.

Android: ShoppingProfileTest kiểm tra logic/form, retry cùng yêu cầu, refresh, conflict và HTTP consumer. ShoppingProfileUiTest có fixture UI sáng/tối/font lớn, loading/error/saving/confirm và luồng Android → Netty → PostgreSQL tạm. Live test yêu cầu authIntegrationBaseUrl và smartShoppingDebugApiBaseUrl cùng http://127.0.0.1:18080/api/v1/, cùng adb reverse 18080. Không dùng URL backend development để chạy test tạo tài khoản.

Chạy Gradle qua [gradle-run](../../../../.agents/skills/gradle-run/SKILL.md). Backend task test và prepareAuthFixture; Android :app:testDebugUnitTest, :app:connectedDebugAndroidTest và :app:lintDebug. Ảnh được ghi vào additionalTestOutputDir và thu về Frontend/app/build/outputs/connected_android_test_additional_output; kiểm tra file và xem ảnh trước khi coi là bằng chứng hiển thị. Xem [backend guide](../../../backend/README.md) và [frontend guide](../../README.md) để chuẩn bị môi trường tạm.
