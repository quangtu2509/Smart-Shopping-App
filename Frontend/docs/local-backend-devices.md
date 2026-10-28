# Một bản debug cho emulator, USB và Wi-Fi

Dùng API base URL `http://127.0.0.1:8080/api/v1/` trong bản debug, rồi tạo `adb reverse` trên từng thiết bị. Backend giữ cấu hình hiện tại tại máy tính; không cần mở cổng 8080 ra LAN hoặc đổi PostgreSQL. Điện thoại Wi-Fi dùng Wireless debugging, nên cần duy trì kết nối ADB với máy tính.

Source vẫn mặc định `http://10.0.2.2:8080/api/v1/` cho emulator chạy trực tiếp. Cách dùng chung bên dưới ghi đè URL bằng Gradle property cho mỗi lần build, không thay mặc định này.

## 1. Bật backend và kết nối thiết bị

Giữ backend đang chạy như trong [backend guide](../../backend/README.md). Kiểm tra cổng API 8080; cổng PostgreSQL 5432 không phải địa chỉ để app kết nối.

- Emulator: mở máy ảo từ Android Studio.
- USB: bật Developer options → USB debugging, mở khóa điện thoại, cắm cáp dữ liệu và chấp nhận hộp thoại cho phép máy tính gỡ lỗi. Windows thấy điện thoại ở chế độ truyền file chưa có nghĩa ADB đã được cấp quyền.
- Wi-Fi (Android 11+): điện thoại và máy tính cùng mạng Wi-Fi; bật Developer options → Wireless debugging. Trong Android Studio, mở danh sách thiết bị → **Pair Devices Using Wi-Fi**, rồi dùng điện thoại quét QR để ghép đôi. Xem [hướng dẫn Android](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi).

Trong PowerShell trên máy này:

```powershell
$adb = 'C:\Users\toked\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
```

Thiết bị cần có trạng thái `device`. Với `unauthorized`, mở khóa và chấp nhận hộp thoại trên điện thoại. Nếu chỉ hiện emulator, kiểm tra USB debugging/cáp/driver trước khi cài app.

Sau khi ghép đôi Wi-Fi, rút cáp USB rồi chạy lại `devices -l`: điện thoại phải vẫn xuất hiện. Serial Wi-Fi có thể là địa chỉ IP:cổng hoặc tên dịch vụ; dùng đúng serial mà ADB đang liệt kê.

Nếu QR không dùng được, trên điện thoại chọn ghép đôi bằng mã và chạy:

```powershell
& $adb pair 'IP_DIEN_THOAI:CONG_GHEP_DOI'
```

Nhập mã trực tiếp khi ADB hỏi. Nếu ghép đôi xong nhưng chưa kết nối tự động, lấy **địa chỉ/cổng kết nối** ở trang Wireless debugging chính rồi chạy:

```powershell
& $adb connect 'IP_DIEN_THOAI:CONG_KET_NOI'
& $adb devices -l
```

Cổng ghép đôi và cổng kết nối có thể khác nhau; các giá trị trên là chỗ giữ chỗ, cần thay bằng thông tin đang hiển thị trên điện thoại. Không lưu mã ghép đôi vào repository.

## 2. Nối cổng cho từng thiết bị

Thay `SERIAL_THIET_BI` bằng serial thật từ `devices -l`, chạy một lần cho mỗi emulator/điện thoại muốn dùng:

```powershell
& $adb -s 'SERIAL_THIET_BI' reverse tcp:8080 tcp:8080
& $adb -s 'SERIAL_THIET_BI' reverse --list
```

Lệnh tạo đường nối cổng 8080 trên thiết bị về cổng 8080 của máy tính. Nó dùng được khi điện thoại kết nối qua USB hoặc Wireless debugging. Khi nhiều thiết bị cùng xuất hiện, luôn chọn thiết bị bằng `-s`. Xem [tài liệu ADB](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md).

## 3. Build và cài cùng bản app

Từ PowerShell:

```powershell
Set-Location 'D:\Android Project\My project\Smart Shopping\Smart Shopping App\Frontend'

.\gradlew.bat :app:installDebug `
  '-PsmartShoppingDebugApiBaseUrl=http://127.0.0.1:8080/api/v1/'
```

Task cài bản debug lên các thiết bị tương thích đang kết nối. Mở biểu tượng Smart Shopping trên thiết bị để dùng app. Những lệnh Gradle trong tài liệu này dành cho người dùng; agent chạy qua [gradle-run](../../../.agents/skills/gradle-run/SKILL.md).

Mỗi lần build lại phải tiếp tục truyền property trên. Nếu dùng nút Run mặc định của Android Studio, app sẽ được build lại với `10.0.2.2` và chỉ còn phù hợp với emulator. Chuyển USB sang Wi-Fi không cần build lại khi vẫn dùng cùng APK `127.0.0.1`.

## 4. Kiểm tra kết nối và dùng lại

Trên trình duyệt của thiết bị, mở `http://127.0.0.1:8080/api/v1/me`. Phản hồi **401 AUTH_REQUIRED** xác nhận đường nối đến backend hoạt động. Endpoint này yêu cầu đăng nhập nên không dùng trang thành công 200 làm tiêu chí kiểm tra mạng.

Probe tự động có sẵn chỉ gửi GET /me không bearer; không tạo tài khoản hoặc sửa dữ liệu. Từ Frontend, sau khi reverse đã có:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-PsmartShoppingDebugApiBaseUrl=http://127.0.0.1:8080/api/v1/' `
  '-Pandroid.testInstrumentationRunnerArguments.localBackendProbe=true' `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.toked.smartshopping.auth.DebugNetworkTest'
```

Chỉ chạy class trên khi kiểm tra backend development; không truyền argument `authIntegrationBaseUrl` của bộ test tạo tài khoản. Probe là opt-in: thiếu `localBackendProbe=true` thì ca kiểm tra đường mạng sẽ skip. Test runner có thể dọn reverse và gỡ APK sau lượt chạy. Sau khi test xong, chạy riêng lệnh `installDebug` ở bước 3 để cài lại, rồi tạo lại mapping trước khi mở app; không gộp install và connected test trong một lượt vì thứ tự task có thể khiến APK bị dọn sau khi cài.

Khi rút/cắm USB, đổi sang Wireless debugging, mất Wi-Fi hoặc khởi động lại thiết bị, chạy lại `devices -l` và `reverse` cho serial hiện tại. Backend phải tiếp tục chạy trên máy tính. Điện thoại không có kết nối ADB sẽ không dùng được URL local này, kể cả cùng Wi-Fi.

Muốn gỡ riêng đường nối khi không dùng nữa:

```powershell
& $adb -s 'SERIAL_THIET_BI' reverse --remove tcp:8080
```
