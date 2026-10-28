# Backend local — Auth

CatalogFlow đã có adapter Kotlin và kho khóa DPAPI riêng; xem [hướng dẫn](CATALOGFLOW_SETUP.md). CheckCatalog kiểm tra độc lập trước auth/database; Product Service/API/UI thuộc CATALOG-01.

Ktor + PostgreSQL 18, JDBC/HikariCP và Flyway. Backend là Gradle project riêng, Android giữ compile/target SDK 36. JDK daemon/toolchain 25, Java/Kotlin bytecode target 17. Migration nằm ở `src/main/resources/db/migration/`; Flyway tự áp dụng khi backend khởi động. Không tạo bảng thủ công bằng pgAdmin.

PLAN-01 đã có API Draft create/list/get/patch, retailer reference và đọc Store nội bộ phục vụ chọn chi nhánh. Xem [PLAN_API](PLAN_API.md) và [giao diện Android](../Frontend/docs/plan/README.md). Dùng lại schema V5; catalog/provider/GPS/nearest-store vẫn thuộc task sau. Backend development đang chạy bản cũ cần khởi động lại để nạp API mới.

AUTH-01/02 đã có OTP Gmail, Google/link và password recovery trong API-AUTH-01–16; người dùng xác nhận hoàn tất ngày 19/10/2026. Xem [phạm vi và bằng chứng AUTH](../Frontend/docs/auth/README.md) để phân biệt test tự động với xác nhận thực tế, cùng tác động migration V3 lên phiên cũ.

Gốc repository Git là `Smart Shopping App/`, chỉ có hai project `Frontend/` và `backend/` cùng metadata Git. Workspace root là thư mục cha `Smart Shopping/`, chứa `.agents/`, `docs/`, `plans/` và `tests/` bên ngoài repository. Các lệnh ghi workspace root chạy từ thư mục cha này. Build/chạy backend bằng wrapper trong `backend/`; khi deploy, chọn thư mục này làm project build. Hosting/production chưa được cấu hình.

## Chạy với database đã tạo trong pgAdmin

Database: `smart_shopping`; owner/login: `smart_shopping_app`; host: `localhost`; port: `5432`. Đọc [AUTH_SETUP](AUTH_SETUP.md) để cấu hình Google và Gmail SMTP. Từ backend:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-local.ps1 -Action Configure
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-local.ps1 -Action Run
```

Configure hỏi mật khẩu ứng dụng Gmail qua input ẩn và tạo khóa HMAC ngẫu nhiên, lưu bằng Windows DPAPI trong ignored `.env.local.xml`. Run hỏi mật khẩu PostgreSQL nếu environment chưa có; nạp cấu hình chỉ vào tiến trình rồi chạy backend. Agent không chạy script này hoặc đọc file bí mật. `.env.example` chỉ mô tả tên biến, không tự được nạp. Người dùng chạy Gradle local trực tiếp; agent dùng skill gradle-run.

V3 giữ tài khoản/Profile nhưng thu hồi session cũ để bắt buộc xác minh email. Không tự tạo bảng thủ công hoặc xóa/reset database development.

Tài khoản chỉ dùng Google không bị tăng bộ đếm sai mật khẩu/khóa tạm khi có yêu cầu password login; phản hồi vẫn là lỗi chung. Recovery bằng OTP/reset grant hợp lệ được đi qua khóa tạm do nhập sai mật khẩu, nhưng status LOCKED do quản trị vẫn chặn cả hai bước. Reset thành công xóa khóa tạm và thu hồi phiên như trước. Email tài khoản cũ được normalize và tra cứu theo quy tắc legacy cho login/forgot, kể cả TLD punycode; giới hạn Gmail chỉ áp dụng cho đăng ký mới.

API base URL: `http://localhost:8080/api/v1`. Server mặc định bind `127.0.0.1`; cấu hình `HTTP_HOST`/`HTTP_PORT` khi cần. `/me` không có bearer trả 401 theo hợp đồng, không phải health check public. Chưa cấu hình HTTPS/hosting; Android AUTH-01/02 đã có consumer API-AUTH-01–16, gồm đăng ký/đăng nhập, refresh, logout và quản lý phiên.

## Hợp đồng và cấu hình auth

Thực hiện API-AUTH-01–16 tại [contracts 15.6](../../docs/03-architecture/system-and-integrations.md#156-api-specification--contracts). JSON snake_case, envelope có request_id, credentials response no-store, không nhận owner từ client. Đăng ký trả 202 ChallengeDto; chỉ sau OTP mới tạo User/default Profile/auth session nguyên tử. Email trim/lowercase; đăng ký mới chỉ gmail.com; password tạo/reset 10–128 Unicode code points với hoa/thường/ký tự đặc biệt không tính khoảng trắng, không bắt buộc số, không trim; confirm không lưu.

Password dùng Argon2id với salt 16 byte, memory 19 MiB, iterations 2, parallelism 1. Token ngẫu nhiên 32 byte; database chỉ giữ SHA-256 hash. Refresh rotate nguyên tử, vô hiệu access cũ; replay refresh đã dùng revoke phiên, kể cả access mới. Lock tài khoản revoke các phiên và unlock không hồi sinh phiên cũ. Session là auth session, không phải ShoppingSession.

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| ACCESS_TTL_SECONDS | 900 | Access token 15 phút |
| SESSION_TTL_SECONDS | 2592000 | Hạn tuyệt đối 30 ngày; refresh không kéo dài |
| AUTH_RATE_LIMIT | 20 | Register/login/refresh mỗi route và peer address |
| AUTH_RATE_WINDOW_SECONDS | 60 | Cửa sổ rate limit, trả 429 và Retry-After |
| LOGIN_LOCK_THRESHOLD | 10 | Số lần password sai gây khóa tạm |
| LOGIN_LOCK_SECONDS | 900 | Khóa tạm 15 phút; credential sai luôn 401 |

Rate limit lưu trong PostgreSQL để áp dụng giữa các backend process. Các giá trị này là cấu hình local ban đầu, cần đánh giá tải/production trước phát hành. Không tin forwarded headers từ proxy chưa cấu hình. Argon2 chạy cùng JDBC trên Dispatchers.IO do request quản lý; không tạo coroutine scope tách rời. Chưa tích hợp nguồn password rò rỉ, hosting/TLS, cleanup định kỳ lịch sử token/rate bucket hoặc chính sách vận hành production; những phần này phải hoàn thiện trước release.

## Kiểm chứng trên PostgreSQL tạm

Script chỉ tạo instance tạm trong Windows temp, bind loopback `127.0.0.1:55432`, database/role `auth00_test`. Instance dùng trust để kiểm thử local; không dùng cấu hình này cho database development/production. Cần PostgreSQL 18 ở `C:\Program Files\PostgreSQL\18\bin`, cổng 55432 trống, Python `python3`, JDK 25 và Node/npm cho Newman. Chạy `npx --yes newman@6.2.1 --version` một lần để tải Newman vào cache; test dùng `--offline` để không chờ mạng. State/output tạm nằm ở `.agent-output/` trong workspace cha, ngoài repository ứng dụng. Test bắt buộc kết nối database này, không skip nếu thiếu; không dùng URL development để reset dữ liệu.

Từ workspace root, Start; chạy task `test` từ backend qua workflow gradle-run; luôn Stop kể cả test thất bại:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "Smart Shopping App/backend/scripts/test-local.ps1" -Action Start
```

Tạo workflow từ workspace root:

```powershell
python3 .agents/skills/gradle-run/scripts/gradle_run.py create
```

Giữ workflow ID trả về, vào `Smart Shopping App/backend/` và chạy:

```powershell
python3 ../../.agents/skills/gradle-run/scripts/gradle_run.py run --workflow WORKFLOW_ID --scope targeted --question "Do AUTH-00 integration and HTTP tests pass on isolated PostgreSQL 18?" -- gradlew.bat test
```

Quay lại workspace root để dọn instance và log wrapper:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "Smart Shopping App/backend/scripts/test-local.ps1" -Action Stop
python3 .agents/skills/gradle-run/scripts/gradle_run.py finish --workflow WORKFLOW_ID
```

`AuthIntegrationTest` kiểm tra transaction rollback, email trùng/concurrent register, password/hash, expiry, refresh replay/concurrency, owner, pagination, lock/rate limit và HTTP/schema. `AuthPostmanTest` chạy collection bằng Newman 6.2.1 qua Netty HTTP thật + PostgreSQL tạm, không mock database. Báo cáo cục bộ ở ignored `build/postman-local.log`, không xuất populated collection/environment.

`GoogleTokenVerifierTest` dùng RSA/certificate giả lập và clock cố định để kiểm tra iat/exp ở biên 30/31 giây, giữ kiểm tra chữ ký/audience/issuer. `PostmanScriptsTest` chạy 10 Node script tests cho nhánh thủ công/fixture và Newman qua HTTP sink để xác nhận thiếu reset_password không gửi cả hai request reset/replay. Gradle theo dõi collection và thư mục src/test/javascript để thay script không bị bỏ qua do up-to-date.

## Postman thủ công

AUTH-02 bổ sung Retry-After cho 403 ACCOUNT_LOCKED khi có thời điểm hết khóa tạm, chỉ sau khi mật khẩu hợp lệ. Khóa quản trị không có thời điểm mở khóa không trả header này; client cần hiển thị hướng liên hệ hỗ trợ thay vì tự đặt thời gian mở khóa. Không đổi JSON/schema/migration.

Import `postman/auth00.postman_collection.json`, đặt base_url, run_email bằng Gmail bạn kiểm soát và run_password đạt policy; trước reset tự nhập reset_password riêng trong biến local. Collection có 27 request, gồm OTP/recovery và các API session, Google negative cases. Hai request reset/replay không có mật khẩu mặc định; thiếu reset_password ở chế độ thủ công sẽ dừng trước khi gửi. Với Gmail thật, gửi từng request và nhập otp_code từ email; không Run Collection qua bước chờ OTP. Resend thủ công nhận 429 trong cooldown hoặc 202 sau cooldown; khi 202 phải nhập OTP mới vì mã cũ bị vô hiệu hóa. Fixture tự sinh mật khẩu mỗi lượt và kiểm tra resend đúng 429. Chỉ server test tự động có fixture_url để tự lấy mã. Không export biến có credentials. Xem [hướng dẫn từng bước](AUTH_SETUP.md).

## Email, Google và recovery

AuthFlows xử lý OTP/HMAC, resend, Google nonce/link và reset; AuthProviders tích hợp SMTP STARTTLS + Google ID token verifier. Mail/Google gọi ngoài transaction. AuthFixtureServer chỉ nằm ở src/test, bind 127.0.0.1:18080 với PostgreSQL tạm 55432, fake mail và không nhận Google thật; fixture không có trong application distribution. API-AUTH-09–16 và cách test Desktop xem [AUTH_SETUP](AUTH_SETUP.md). Token Google/password/OTP không được ghi log.

## PROFILE-01

API-PROFILE-01/02: GET/PATCH /api/v1/me/shopping-profile; API-REF-02/03: GET /api/v1/categories, /brands. Tất cả yêu cầu bearer. PATCH nhận expected_version và Idempotency-Key; null xóa household_size/default_period, field vắng giữ nguyên. Input không hợp lệ trả 422 field_errors, version cũ trả 409 VERSION_CONFLICT với details.current_version, replay key/payload trả bản kết quả cũ, key cũ/payload khác trả 409 IDEMPOTENCY_KEY_REUSED. Key có phạm vi owner + endpoint và giữ ít nhất 24 giờ. Không lưu auth token trong replay record.

Migration V4 giữ tài khoản/Profile/session, tạo shopping_categories, shopping_brands và profile_requests. Hai bảng reference khởi tạo trống, DATA-00 đã có schema; dữ liệu thật chờ quy trình PROVIDER-00/CATALOG-01; test seed chỉ trong src/test. Form vẫn lưu được các trường khác khi chưa có reference. ID tham chiếu mới phải ACTIVE/tồn tại, sở thích và né tránh không giao nhau. Reference có q/cursor/limit 1–100; categories có parent_id, cursor gắn owner và bộ lọc.

ShoppingProfileTest chạy PostgreSQL tạm qua cùng script test-local; ShoppingProfilePostmanTest dùng collection profile01.postman_collection.json với 14 request/42 assertions. File environment Newman có token chỉ tồn tại tạm trong ignored build, bị xóa sau chạy; không export token. Xem [Profile guide](../Frontend/docs/profile/README.md) cho consumer Android, trạng thái UI và giới hạn dữ liệu thật.

## DATA-00 — Storage primitives

V5 bổ sung Plan/item/offer, decimal currency rules, snapshot/revision, idempotency/audit và nền Session/History. Không đăng ký endpoint Plan/List/Finalize hoặc gọi catalog provider; xem [DATA_FOUNDATION](DATA_FOUNDATION.md) cho schema, transaction API, primitive snapshot và điều kiện tích hợp phía sau. API auth/Profile không đổi. Database development chỉ nhận V5 khi bạn khởi động lại backend; agent kiểm chứng riêng trên PostgreSQL tạm 55432.
