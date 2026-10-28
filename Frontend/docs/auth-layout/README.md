# Bố cục đăng nhập và đăng ký

SC-01/02 hiện dùng thiết kế từ Stitch: đăng nhập đặt CTA trước Google, đăng ký đặt CTA trong thẻ form khi đủ chỗ. Trên màn ngắn, chữ lớn hoặc mở bàn phím, CTA chuyển sang footer cố định cùng nút chuyển tài khoản để dễ thao tác; form vẫn cuộn tới trường/lỗi. Các nhóm đăng nhập được phân bố theo chiều cao để tránh khối trống lớn. Vùng chạm tối thiểu48dp, không giới hạn font scale.

`AuthFormLayout` quản lý footer thích ứng; `AuthScrollContent` dùng chiều cao tối thiểu bằng viewport và cách chia nhóm do từng màn chọn. `AuthBrandHeader`, AuthTopBar/AuthDivider/AuthSubmitButton dùng lại kiểu chữ/màu của hệ thống. OTP/reset/link dùng thẻ trắng; Quên mật khẩu bỏ logo hero lớn và gom header/card/footer gần giữa theo phản hồi trước. Insets vẫn do `AuthContent` cung cấp. API, cooldown/pending và dữ liệu không thay đổi; ViewModel thêm cờ UI `passwordResetComplete` sau khi reset thành công, nút Đăng nhập ngay xóa cờ để mở form đăng nhập.

## Nguồn thiết kế Stitch

Đọc trực tiếp [Smart Shopping App Design](https://stitch.withgoogle.com/projects/11930636729725860020) ngày27/10/2026, không sửa project trên Stitch. Bảy HTML/ảnh gốc cùng [manifest màn](design/screens.json) lưu để đối chiếu; HTML chỉ là tham chiếu, không dùng JavaScript giả lập của prototype trong app.

| Màn | Screen ID | Ảnh thiết kế |
|---|---|---|
| Đăng nhập | 9db38bd691e047e79f8b6b184dc5d023 | [Stitch](design/login.jpg) |
| Đăng ký | b86d05bbf8884a5e9fc5ef7730c78e75 | [Stitch](design/register.png) |
| Quên mật khẩu | d6e9df68b8ae4cb79e0bdaffb6670674 | [Stitch](design/forgot.jpg) |
| OTP | 98731f220ef0445f8c81dbe0f5a063a5 | [Stitch](design/otp.jpg) |
| Đặt mật khẩu | a9c1bb794f7e4a08b4025f3b3f6539d0 | [Stitch](design/reset.jpg) |
| Đăng ký thành công | 225d1d2330434a6388165cc1acbc4789 | [Stitch](design/register-success.png) |
| Đổi mật khẩu thành công | 3f9d2900c14d4fbab3c4515f2e56b34e | [Stitch](design/reset-success.jpg) |

Giữ bố cục, thẻ, logo, màu và thứ tự hành động. Nội dung runtime dùng email/state thật; không sao chép email mẫu, đếm ngược1:59, quy tắc8ký tự hoặc40ưu đãi mẫu. Mật khẩu giữ quy tắc10–128 và bốn điều kiện đúng backend; OTP nêu thời hạn tối đa10phút và cooldown resend thật. Không tạo link hỗ trợ chưa có đích. Đăng ký đã có session nên CTA thành công là Tiếp tục tới onboarding; thẻ đổi mật khẩu thành công đưa về đăng nhập. Liên kết Google dùng cùng phong cách thẻ vì Stitch chưa có màn riêng.

## Ảnh triển khai theo Stitch trên Pixel9a

| Màn | Ảnh app |
|---|---|
| Đăng nhập | [Native](evidence/stitch/pixel-9a/login-native.png), [720dp](evidence/stitch/pixel-9a/login-phone.png), [bàn phím](evidence/stitch/pixel-9a/login-keyboard.png), [200%/tối](evidence/stitch/pixel-9a/login-large-dark.png) |
| Đăng ký | [Native](evidence/stitch/pixel-9a/register-native.png), [720dp](evidence/stitch/pixel-9a/register-phone.png), [bàn phím](evidence/stitch/pixel-9a/register-keyboard.png), [200%](evidence/stitch/pixel-9a/register-large.png) |
| Quên mật khẩu | [Căn giữa](evidence/stitch/pixel-9a/forgot-centered-native.png), [lỗi/IME](evidence/stitch/pixel-9a/forgot-centered-keyboard.png) |
| OTP | [Email](evidence/stitch/pixel-9a/verify-email-native.png), [khôi phục](evidence/stitch/pixel-9a/verify-reset-native.png) |
| Mật khẩu/Google | [Đặt mật khẩu](evidence/stitch/pixel-9a/reset-native.png), [200%/tối](evidence/stitch/pixel-9a/reset-large-dark.png), [liên kết](evidence/stitch/pixel-9a/link-native.png) |
| Thành công | [Modal đăng ký](evidence/stitch/pixel-9a/success-native.png), [200%/tối](evidence/stitch/pixel-9a/register-success-large-dark.png), [đổi mật khẩu](evidence/stitch/pixel-9a/reset-success-native.png) |

Lượt triển khai Stitch:97 unit tests đạt,0skip; build app/test APK và lint0lỗi/27cảnh báo cũ. 32/32 UI tests đạt trên Pixel_9a có sẵn, Android15/API35,1080×2424/density420, gồm15 AuthLayout +4 Login +6 Register +4 PendingSession +3 AuthStep. Fixture thuần không HTTP/session storage/database; ảnh dùng dữ liệu synthetic, MainActivity vẫn FLAG_SECURE. Không chạy Google/email/provider thật trong task UI. [Plan Stitch](../../../../plans/completed/2026-10-27-auth-stitch-design.md) ghi kết quả và ranh giới.

Sau kiểm tra ảnh, Dialog thành công được truyền LocalDensity để nhận đúng chữ200% của bên gọi. Ba ca xác nhận cuối đạt3/3,0skip, gồm assert cỡ chữ thực tế và CTA vẫn cuộn tới được. Đã cài/mở lại APK localhost8080 trên Pixel9a; giữ emulator và reverse8080.

## Lượt cân đối trước khi đối chiếu lại Stitch

Logo đăng nhập176×132dp, đăng ký120×90dp; lời chào28sp, đậm. Các nhóm header/form/tùy chọn dùng phần dư chiều cao để giãn đều; màn ngắn/chữ lớn vẫn cuộn tự nhiên, giữ footer riêng. Đăng ký trên khung720dp có thể cuộn phần trường cuối; nút chính và chuyển đăng nhập luôn hiện.

Đã cài/mở bản localhost8080 trên **Pixel_9a có sẵn, Android15/API35, 1080×2424/density420 mặc định**, giữ reverse8080 và emulator chạy. Không cài điện thoại. **27/27 UI tests đạt,0skip**:10 AuthLayoutUiTest +4 Login +6 Register +4 PendingSession, rồi3 AuthStepUiTest kiểm tra OTP/resend, success và reset. Build hai APK/lint đạt,0lỗi/27cảnh báo cũ. Không đổi logic/API hoặc chạy đăng nhập/provider/database thật.

| Thiết lập | Đăng nhập | Đăng ký |
|---|---|---|
| Toàn màn Pixel9a | [Ảnh mới](evidence/balanced/login-native.png) | [Ảnh mới](evidence/balanced/register-native.png) |
| Khung360×720dp | [Ảnh](evidence/balanced/login-phone.png) | [Ảnh](evidence/balanced/register-phone.png) |
| Mở bàn phím | [Ảnh](evidence/balanced/login-keyboard.png) | [Ảnh](evidence/balanced/register-keyboard.png) |
| Chữ200%/cao560dp | [Tối](evidence/balanced/login-large-dark.png) | [Sáng](evidence/balanced/register-large.png) |

Các bước auth cùng bố cục: [Quên mật khẩu mới](evidence/balanced/forgot-centered-native.png), [OTP email](evidence/balanced/verify-email-native.png), [OTP khôi phục](evidence/balanced/verify-reset-native.png), [Đặt mật khẩu](evidence/balanced/reset-native.png), [Liên kết Google](evidence/balanced/link-native.png), [Thành công](evidence/balanced/success-native.png), [Reset tối/chữ200%](evidence/balanced/reset-large-dark.png). Ảnh fixture không chứa tài khoản thật; giữ FLAG_SECURE ở MainActivity. [Plan cân đối](../../../../plans/completed/2026-10-27-auth-balanced-layout.md) ghi bằng chứng/lệnh và giới hạn.

Theo phản hồi tiếp theo, **riêng Quên mật khẩu bỏ logo**, giữ biểu tượng email và gom tiêu đề/form thành cụm căn giữa với khoảng cách24dp. Các màn auth khác giữ cách chia nhóm trước đó. Xem [bản mới toàn màn](evidence/balanced/forgot-centered-native.png) và [lỗi email/chữ150%/IME](evidence/balanced/forgot-centered-keyboard.png). Lượt xác nhận trên Pixel9a:15/15 UI tests đạt,0skip (12 AuthLayoutUiTest +3 AuthStepUiTest), build hai APK và lint0lỗi/27cảnh báo cũ. Đã cài/mở lại app URL8080; không đổi xác thực hoặc dữ liệu.

## Lượt thu gọn ban đầu ngày 27/10/2026

- Pixel_9a/API35, cấu hình 1080×2376/density480 giống điện thoại: 20/20 fixture UI đạt, không skip. Gồm 6 AuthLayoutUiTest mới, 4 LoginUiTest, 6 RegisterUiTest và 4 PendingSessionUiTest.
- OnePlus Ace 5 Pro/Android16 qua USB: 6/6 AuthLayoutUiTest đạt, không skip. Cài bằng `adb install -r`, giữ dữ liệu app và mở lại MainActivity sau test.
- Khung 360×720dp/font1: trường, Google, nút chính và chuyển form hiện ngay. Khung cao 560dp/font2: hành động nằm trọn vùng nhìn, cuộn đến lỗi và Google được. Hai ca khác mở bàn phím thật, kiểm tra hành động và submit IME.
- 76 unit tests đạt; lint không lỗi, còn 27 cảnh báo có sẵn. Không chạy đăng nhập/OTP/Google thật hoặc kiểm thử ghi database trong task bố cục.

Ảnh là **fixture giao diện**, không chứa tài khoản của người dùng; MainActivity vẫn giữ bảo vệ ảnh chụp.

| Thiết lập | Đăng nhập | Đăng ký |
|---|---|---|
| Trước sửa, emulator | [Ảnh](evidence/login-before.png) | [Ảnh](evidence/register-before.png) |
| Sau sửa, điện thoại USB | [Ảnh](evidence/phone/login.png) | [Ảnh](evidence/phone/register.png) |
| Sau sửa, emulator | [Ảnh](evidence/after/login-phone.png) | [Ảnh](evidence/after/register-phone.png) |
| Mở bàn phím | [Ảnh](evidence/after/login-keyboard.png) | [Ảnh](evidence/after/register-keyboard.png) |
| Chữ 200%, cao 560dp | [Ảnh tối](evidence/after/login-large-dark.png) | [Ảnh sáng](evidence/after/register-large.png) |

## Chạy lại fixture trên một thiết bị

Build `:app:assembleDebug :app:assembleDebugAndroidTest` với property API phù hợp; agent dùng skill gradle-run. Từ Frontend, chỉ định serial và cài giữ dữ liệu:

```powershell
$adb = 'C:\Users\toked\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$serial = 'SERIAL_THIET_BI'
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adb -s $serial shell am instrument -w -r -e class com.toked.smartshopping.auth.AuthLayoutUiTest com.toked.smartshopping.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $serial shell am start -W -n com.toked.smartshopping/.MainActivity
```

Fixture không cần backend và không truy cập session store. Khi quay lại app thật với bản debug localhost, reverse 8080 trên từng thiết bị theo [hướng dẫn thiết bị](../local-backend-devices.md). [Plan kiểm chứng](../../../../plans/completed/2026-10-27-auth-screen-fit.md) ghi kết quả và giới hạn.
