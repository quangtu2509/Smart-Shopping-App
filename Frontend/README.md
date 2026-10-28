# Android — AUTH-01/02

SC-04 Home đã có giao diện Compose theo Stitch: header, tìm kiếm, tạo kế hoạch, các khu vực kế hoạch/giá/lịch sử/mẫu và năm mục điều hướng. Cá nhân mở hồ sơ mặc định, quản lý phiên và xác nhận đăng xuất. Dữ liệu nghiệp vụ chưa được tích hợp; Home dùng trạng thái rỗng, các đích chưa triển khai có thông báo rõ, không hiển thị số liệu mẫu như dữ liệu thật. Source: `app/src/main/java/com/toked/smartshopping/home/HomeContent.kt`; UI tests: `app/src/androidTest/java/com/toked/smartshopping/home/HomeUiTest.kt`.

Mở project Frontend trong Android Studio. Compile/target SDK 36, min SDK 26. App có đăng ký/đăng nhập Compose theo Stitch, validation, API-AUTH-01–16, encrypted session, refresh và quản lý phiên. Đăng ký qua OTP/Google → màn hình thành công → onboarding/default Profile; đăng nhập và khôi phục phiên vào Home đúng User. Chỉnh Profile và Home nghiệp vụ đầy đủ vẫn thuộc các task riêng.

Người dùng xác nhận hoàn tất AUTH ngày 19/10/2026. Xem [phạm vi, tương thích migration, kết quả kiểm chứng và ảnh fixture](docs/auth/README.md); xác nhận thực tế của người dùng được ghi riêng với test tự động.

## Chạy đăng ký/đăng nhập với backend local

1. Chạy PostgreSQL và backend theo [backend guide](../backend/README.md). Backend dùng localhost:8080/api/v1, database smart_shopping trên cổng 5432.
2. Mở emulator trong Android Studio, chọn thiết bị rồi nhấn Run cho Frontend. Bản debug mặc định gọi `http://10.0.2.2:8080/api/v1/`: `10.0.2.2` là địa chỉ host Windows nhìn từ Android Emulator. Backend vẫn bind `127.0.0.1:8080` trên Windows; không cần `adb reverse` khi dùng emulator mặc định. Quy trình mỗi lần: chạy backend → mở emulator → Run Frontend. Sau lần đổi URL này, cần Run để cài bản debug mới.

Google cần máy ảo có Google Play Services, tài khoản Google và Internet/DNS hoạt động. Đổi URL backend không tự sửa lỗi mạng tới Google.

Để dùng **cùng một bản app trên emulator, điện thoại USB và Wireless debugging qua Wi-Fi**, xem [hướng dẫn chạy chung thiết bị](docs/local-backend-devices.md). Build với `smartShoppingDebugApiBaseUrl=http://127.0.0.1:8080/api/v1/` và reverse cổng 8080 trên từng thiết bị. Giữ property này trong mỗi lệnh build; nút Run mặc định vẫn dùng `10.0.2.2`. HTTP debug chỉ cho phép đúng `127.0.0.1`, `localhost`, `10.0.2.2`; HTTPS vẫn được hỗ trợ.

3. Nhập Gmail chưa dùng, mật khẩu 10–128 ký tự gồm hoa/thường/ký tự đặc biệt và xác nhận khớp. Password không trim; email được backend normalize. Nhập mã trong email, xem “Đăng ký thành công!” rồi “Tiếp tục” vào “Thiết lập mua sắm”, cho phép sửa đầy đủ hồ sơ mua sắm (PROFILE-01), mặc định tối đa 3 cửa hàng/BALANCED. “Lưu hồ sơ” hoặc “Bỏ qua và tiếp tục” đi tới Home; xem [hướng dẫn Profile](docs/profile/README.md).

App chưa có phiên mở màn Đăng nhập. Nhấn “Đăng ký” để tạo tài khoản; tài khoản đã có dùng email/mật khẩu → Home có email của User. “Phiên đăng nhập” hiển thị các thiết bị còn hiệu lực, có tải thêm, thu hồi một phiên và xác nhận đăng xuất tất cả. “Đăng xuất” ở Home thu hồi phiên thiết bị này; dữ liệu mua sắm không bị xóa. Nếu mạng lỗi, app báo chưa xác nhận thu hồi server và vẫn xóa phiên trên thiết bị.

AUTH-02 đã kiểm chứng với 37 unit/17 device tests, không skip; backend có 13 tests và Postman 66 assertions đạt trên PostgreSQL tạm riêng. Chi tiết và giới hạn: [plan AUTH-02](../../plans/completed/2026-10-16-login-session.md).

Debug chỉ cho HTTP tới 127.0.0.1/localhost/10.0.2.2; release cấm cleartext. Khi chọn hosting, cấu hình Gradle property `smartShoppingReleaseApiBaseUrl` bằng HTTPS API base URL có tiền tố /api/v1/. Hiện chưa có hostname production; không phát hành release với cấu hình URL rỗng.

## Giao diện theo Stitch

Màn đăng ký dùng [thiết kế Tạo tài khoản đã lưu](../../stitch-audit/b86d05bbf8884a5e9fc5ef7730c78e75.html): logo gốc, xanh lá, Inter/Plus Jakarta Sans, thẻ form bo góc và icon trong ô nhập. Asset nằm trong app nên không cần tải mạng khi mở màn hình; giấy phép font nằm trong [licenses/](licenses/). Theme mặc định giữ màu thương hiệu, hỗ trợ sáng/tối và cuộn khi chữ lớn hoặc bàn phím mở.

Đăng ký Gmail và reset dùng mật khẩu 10–128 ký tự với hoa/thường/ký tự đặc biệt, có nút hiện/ẩn riêng. Google có ở hai form, xác minh qua backend; email trùng yêu cầu mật khẩu cũ trước khi liên kết. Badge chỉ mô tả mã hóa phiên trên thiết bị; kết nối HTTP debug local vẫn theo cấu hình ở trên. Xác minh xong tự lưu phiên, hiển thị thành công rồi Tiếp tục đến onboarding.

Bằng chứng cập nhật UI: [plan giao diện AUTH-01](../../plans/completed/2026-10-13-auth01-stitch-ui.md).

## State và credentials

RegisterViewModel giữ draft/password trong bộ nhớ qua Activity recreation, không lưu password vào SavedStateHandle hoặc backup. Thành công lưu session trước điều hướng và xóa password/confirmation. SecureSessionStore dùng AES-256-GCM với key Android Keystore, AtomicFile trong noBackupFilesDir. App đặt FLAG_SECURE để bảo vệ form/session khỏi screenshot thông thường; ảnh kiểm chứng chỉ chụp UI fixture không có credentials.

OkHttp tắt retry/redirect, request có timeout và cancellation theo viewModelScope. Không tự retry register/login/refresh sau mất response. Lỗi lưu sau đăng ký/đăng nhập cho retry lưu cục bộ, không gửi mutation lần hai. Email trùng/validation hiển thị tại field; 429 và ACCOUNT_LOCKED chờ Retry-After, đổi qua lại form không bỏ qua thời gian chờ. Login chỉ yêu cầu mật khẩu không rỗng/tối đa 128 ký tự; quy tắc tối thiểu 10 cùng hoa/thường/ký tự đặc biệt áp dụng khi tạo/reset mật khẩu, không dùng để từ chối credential đăng nhập cũ.

Khi đã xác thực nhưng chưa lưu được phiên, app giữ phiên trong RAM và vô hiệu hóa Đăng ký/Đăng nhập/Quên mật khẩu, nút quay lại và bắt đầu lại liên quan. Bấm “Lưu lại phiên đăng nhập” chỉ thử ghi phiên đó, không gọi lại login, OTP, Google hoặc liên kết. Sau lưu thành công, tài khoản mới đến màn hình đăng ký thành công; tài khoản cũ đến Home. Phiên chờ không được lưu vào SavedStateHandle; nếu tiến trình bị kết thúc trước khi lưu thành công, người dùng cần bắt đầu lại xác thực.

AuthSessionRepository kiểm tra GET /me trước khi mở dữ liệu owner lúc khởi động/resume; không mở Home từ cached account khi offline. Access hết hạn/invalid dùng một refresh có mutex và retry request bảo vệ tối đa một lần. Bản token cũ bị bỏ khỏi lưu trữ trước khi rotate; lưu cặp mới trước khi dùng. Nếu mất response refresh, đổi sai session/owner hoặc lưu thất bại, yêu cầu đăng nhập lại, không replay refresh token cũ. Phiên bị revoke/locked được xóa mà không refresh. Marker đăng xuất trong noBackupFilesDir ngăn ciphertext cũ được dùng lại nếu app dừng giữa lúc xóa phiên.

## Kiểm chứng

Unit test: `app/src/test/java/com/toked/smartshopping/auth/` với MockWebServer và fake SessionStore. Device test: `app/src/androidTest/java/com/toked/smartshopping/auth/`, gồm semantics, hiện/ẩn mật khẩu độc lập, quay lại/đăng nhập, cooldown/IME, fixture tối/chữ lớn, Keystore roundtrip/tamper, Activity recreation và đăng ký thật. Fake HTTP/state tests không thay database/device evidence.

Người dùng có thể chạy từ thư mục Frontend:

```powershell
.\gradlew.bat :app:testDebugUnitTest
# Khởi động emulator trước lệnh sau; bốn test live sẽ skip khi thiếu backend test riêng.
.\gradlew.bat :app:connectedDebugAndroidTest
```

Live device tests cần backend/PostgreSQL tạm riêng, instrumentation argument `authIntegrationBaseUrl=http://127.0.0.1:18080/api/v1/` và Gradle property `smartShoppingDebugApiBaseUrl` cùng giá trị. Gradle từ chối thiếu URL ghi đè hoặc URL không khớp khi có argument integration; MainActivity phải gọi cùng fixture với test. Chỉ reverse cổng 18080 tới backend test 18080, không reverse 8080. Không trỏ test tới database development. Thiếu argument bốn live auth tests sẽ skip, vì vậy phải báo skipped khi bàn giao. LoginUiTest tạo tài khoản test riêng rồi kiểm tra đăng nhập, restore/401-refresh, thu hồi phiên khác, logout-all, đổi owner và revoke phiên hiện tại. Unit tests chứng minh mutex, timeout/cancellation, tối đa một retry, lỗi lưu và xóa phiên.

Ví dụ sau khi đã khởi động fixture riêng và reverse 18080, chạy từ Frontend:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-PsmartShoppingDebugApiBaseUrl=http://127.0.0.1:18080/api/v1/" "-Pandroid.testInstrumentationRunnerArguments.authIntegrationBaseUrl=http://127.0.0.1:18080/api/v1/"
```

DebugNetworkTest kiểm tra network policy trên Android; bước GET `/me` không bearer chỉ chạy khi truyền `-Pandroid.testInstrumentationRunnerArguments.localBackendProbe=true`. Probe chấp nhận địa chỉ local đã cho phép trên cổng 8080: debug mặc định qua `10.0.2.2` hoặc build ghi đè `127.0.0.1` qua adb reverse cho emulator/USB/Wi-Fi; mong đợi 401 AUTH_REQUIRED, không tạo tài khoản/phiên hoặc truy cập dữ liệu database. Không truyền localBackendProbe thì probe skip; kiểm tra policy vẫn chạy. Sau test với URL fixture, Run/build lại không có property ghi đè trước khi dùng app thường ngày.

Agent chạy task qua [gradle-run](../../.agents/skills/gradle-run/SKILL.md). Cần chuẩn bị backend bằng task installDist và PostgreSQL tạm theo backend guide, chạy Netty riêng cổng 18080. Sau test dừng đúng server test, dọn PostgreSQL test và adb reverse; giữ credentials development trên máy người dùng.

Ảnh fixture đăng ký/onboarding được ghi vào `additionalTestOutputDir` trên device và Gradle thu vào `app/build/outputs/connected_android_test_additional_output/`. Kết quả test đạt không tự chứng minh ảnh đã được thu; kiểm tra file PNG và xem ảnh trước khi báo bằng chứng. Test runner có thể dọn adb reverse sau mỗi lượt; thiết lập lại các đường nối trước khi chạy lại live test. Trạng thái kiểm chứng và phạm vi còn lại: [plan AUTH-01](../../plans/completed/2026-10-12-register-account.md).

## Mở rộng auth ngày 18/10/2026

AuthStepContent nhận state/callback thuần cho OTP 6 ô, forgot, reset, Google link và success. Nguồn Stitch: login 9db38bd691e047e79f8b6b184dc5d023; OTP 98731f220ef0445f8c81dbe0f5a063a5; forgot d6e9df68b8ae4cb79e0bdaffb6670674; reset a9c1bb794f7e4a08b4025f3b3f6539d0; success 225d1d2330434a6388165cc1acbc4789. Google icon lấy từ SVG bốn màu trong thiết kế đã lưu. Giữ palette/font/logo, safe insets, cuộn và IME.

GoogleSignIn dùng Credential Manager, nonce từ backend, Web Client ID trong BuildConfig; không có SMTP password trong APK. Logout xóa trạng thái Google cục bộ. ViewModel giữ challenge/reset grant trong RAM, không SavedStateHandle; pending save retry không gọi lại API single-use. AuthUiEvent Channel một collector theo lifecycle chuyển toast thành công sau khi lưu phiên, không phát khi restore.

Đọc [AUTH_SETUP](../backend/AUTH_SETUP.md) để nhập SMTP secret trên máy và kiểm thử Google thật. Tài khoản cũ chưa verified phải qua OTP; dữ liệu cũ giữ nguyên. Bản chỉ dùng Google không có local password.

## DATA-00 — Hợp đồng nhận dữ liệu

`shopping/ShoppingContracts.kt` có PlanView/Offer/item/BudgetSummary, Money BigDecimal và Quantity, giữ decimal string/null từ server. Tổng budget lấy từ server; chưa nối endpoint hoặc giao diện Plan/List/Session. ShoppingContractsTest dùng JSON fixture chung với backend. [Nền dữ liệu](../backend/DATA_FOUNDATION.md) ghi schema và phạm vi tích hợp còn lại. Cấu hình emulator/USB/Wi-Fi hiện tại không đổi.
