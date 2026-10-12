# Backend local — AUTH-00

Ktor + PostgreSQL 18, JDBC/HikariCP và Flyway. Backend là Gradle project riêng, Android giữ compile/target SDK 36. JDK daemon/toolchain 25, Java/Kotlin bytecode target 17. Migration nằm ở `src/main/resources/db/migration/`; Flyway tự áp dụng khi backend khởi động. Không tạo bảng thủ công bằng pgAdmin.

Gốc repository Git là `Smart Shopping App/`, chỉ có hai project `Frontend/` và `backend/` cùng metadata Git. Workspace root là thư mục cha `Smart Shopping/`, chứa `.agents/`, `docs/`, `plans/` và `tests/` bên ngoài repository. Các lệnh ghi workspace root chạy từ thư mục cha này. Build/chạy backend bằng wrapper trong `backend/`; khi deploy, chọn thư mục này làm project build. Hosting/production chưa được cấu hình.

## Chạy với database đã tạo trong pgAdmin

Database: `smart_shopping`; owner/login: `smart_shopping_app`; host: `localhost`; port: `5432`. Đây là cấu hình development, không phải thông tin hosting production. Đặt biến môi trường trong PowerShell và chạy từ `backend/`:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/smart_shopping'
$env:DB_USER = 'smart_shopping_app'
$localPassword = Read-Host 'Database password' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($localPassword)
try {
    $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
try {
    .\gradlew.bat run
} finally {
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
    $localPassword.Dispose()
}
```

Ví dụ trên dành cho người dùng chạy local. Agent chạy mọi task Gradle qua skill gradle-run. `.env.example` chỉ mô tả tên biến; app không tự đọc file `.env`. Password chỉ nằm trong environment của tiến trình; không gửi qua chat, command argument hoặc collection chia sẻ.

API base URL: `http://localhost:8080/api/v1`. Server mặc định bind `127.0.0.1`; cấu hình `HTTP_HOST`/`HTTP_PORT` khi cần. `/me` không có bearer trả 401 theo hợp đồng, không phải health check public. Chưa cấu hình HTTPS/hosting và chưa nối Android.

## Hợp đồng và cấu hình auth

Thực hiện API-AUTH-01–08 tại [contracts 15.6](../../docs/03-architecture/system-and-integrations.md#156-api-specification--contracts). JSON snake_case, envelope có request_id, credentials response no-store, không nhận owner từ client. User/default Profile/auth session tạo trong một transaction. Email trim/lowercase; password 15–128 Unicode code points, không trim; confirm không lưu.

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

## Postman thủ công

Import `postman/auth00.postman_collection.json`, đặt `base_url` vào backend local và Run Collection. Collection có 18 request, bao phủ 8 endpoint cùng lỗi duplicate/replay/revoke/unknown field; tự tạo email/password test và giữ token trong collection variables khi chạy. Không export biến đã có credentials. Các request này tạo tài khoản test trong database được chọn; dùng instance test khi kiểm chứng lặp.
