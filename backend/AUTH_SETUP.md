# Google, xác minh Gmail và khôi phục mật khẩu trên local

Backend và Android dùng API-AUTH-01–16. Android giữ SDK 36. Gmail SMTP dùng phát triển, không phải cấu hình gửi thư production.

## 1. Nhập thông tin bí mật tại máy

Từ thư mục backend, chạy trong PowerShell dưới tài khoản Windows của bạn:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-local.ps1 -Action Configure
```

Script đã có Web Client ID và Gmail bạn cung cấp. Nhập mật khẩu ứng dụng Gmail khi được hỏi; ký tự bị ẩn, không dùng mật khẩu đăng nhập Google. Script tạo `.env.local.xml` được Git ignore; mật khẩu và khóa HMAC được Windows DPAPI mã hóa cho user/máy hiện tại. Không chia sẻ file. `.env.example` chỉ mô tả biến, không tự được Gradle nạp.

Không chạy Configure lại để đổi khóa khi challenge đang hoạt động. Để thay credentials có chủ đích, dừng backend, tự xóa file private rồi Configure lại; mọi challenge chưa hoàn tất sẽ hết hiệu lực khi đổi khóa, dữ liệu tài khoản không bị xóa.

## 2. Khởi động backend

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-local.ps1 -Action Run
```

Nếu backend cũ đang chạy ở cổng 8080, dừng bằng Ctrl+C trong terminal cũ trước. Nhập mật khẩu database khi được hỏi. Mặc định kết nối `localhost:5432/smart_shopping`, user `smart_shopping_app`; DB_URL/DB_USER có thể đặt qua environment như trước. Flyway chạy V3 khi khởi động: giữ tài khoản/Profile, thêm bảng/cột auth, thu hồi session cũ để yêu cầu xác minh email. Không tự đánh dấu email cũ verified. Agent không chạy migration trên development trong quá trình triển khai.

Thư gửi qua `smtp.gmail.com:587`, STARTTLS bắt buộc và kiểm tra hostname. Hãy kiểm tra cả Spam; lỗi cấu hình gửi thư hiển thị lỗi có thể thử lại, không tạo tài khoản ACTIVE trước proof. Quên mật khẩu luôn dùng thông báo chung kể cả không tồn tại tài khoản hoặc gửi thư lỗi.

## 3. Tự test trong Postman Desktop

Import `postman/auth00.postman_collection.json`. Đặt `base_url=http://127.0.0.1:8080/api/v1`. Đặt `run_email` bằng Gmail do bạn kiểm soát, `run_password` bằng mật khẩu test đạt policy. Trước khi reset, tự đặt `reset_password` thành mật khẩu mới riêng của bạn trong biến local; cả reset và replay dùng cùng biến, không có mật khẩu mặc định. Mật khẩu mới theo policy 10–128 ký tự, có hoa/thường/ký tự đặc biệt không tính khoảng trắng. Không cấu hình `fixture_url` khi dùng server thật: endpoint fixture chỉ tồn tại trong server test tự động.

1. Send **API-AUTH-01 Register**: nhận 202, challenge_id, chưa có token. Kiểm tra database chưa tạo User/Profile cho đăng ký chờ.
2. Nhận mã trong Gmail. Nhập `otp_code` vào biến local của Postman, Send **API-AUTH-09 Verify registration**: nhận 200, is_new_user=true và tokens.
3. Test login, me, refresh, session list, revoke và logout như collection. OTP sai/hết hạn/đã dùng trả 400; resend dưới 60 giây trả 429 + Retry-After. Nếu không muốn gửi lại, bỏ qua **API-AUTH-10 Resend cooldown**. Nếu gửi sau cooldown và còn hạn, nhận 202 cùng email mới; collection xóa otp_code cũ, bạn chờ rồi nhập mã mới nhất trước Verify registration. Mã cũ không còn dùng được; gửi lại giữ hạn và số lần thử của challenge, không cấp thêm 10 phút. Sau khi hết hạn hoặc dùng hết số lần, bắt đầu lại đăng ký/login/forgot.
4. Send **API-AUTH-14 Forgot password**, nhập mã mới vào otp_code, Send **API-AUTH-15 Verify recovery**. Tự nhập `reset_password` local rồi Send **API-AUTH-16 Reset password**; thiếu biến thì [skipRequest](https://learning.postman.com/v11/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-execution#pmexecutionskiprequest) bỏ qua request trước khi gửi, Console nhắc nhập biến, không có response/test cho request bị bỏ qua. Nếu kiểm tra **Reset grant replay rejected**, giữ nguyên biến đó để xác nhận grant chỉ dùng một lần. Sau reset thành công, cập nhật `run_password` bằng mật khẩu mới nếu muốn dùng request Login của collection; các phiên trước bị thu hồi.
5. Không dùng Runner để tự động chạy qua bước OTP thật. Gửi từng request để có thời gian nhập mã. Không export giá trị token, mật khẩu hoặc mã OTP lên Git; xóa các biến nhạy cảm khi test xong.

Collection tự động chỉ dùng Gmail tổng hợp khi có fixture_url trên server PostgreSQL tạm; không gửi tới hộp thư thật. Fixture tự sinh reset_password riêng mỗi lượt và vẫn bắt buộc resend trả 429; nhánh thủ công 202/429 được kiểm chứng riêng bằng test script.

## 4. Test Google trong Android

Google Cloud phải có Android client cho package `com.toked.smartshopping` + SHA-1 debug đúng máy, và Web client cùng project. Web Client ID đã khai báo trong Android BuildConfig và cấu hình backend local. Nếu project ở Testing, thêm tài khoản kiểm thử theo cấu hình Audience.

Chạy backend → mở Android Emulator → Run Frontend trong Android Studio. Debug mặc định dùng `http://10.0.2.2:8080/api/v1/`, không cần adb reverse. Máy ảo cần Google Play Services, tài khoản Google và Internet/DNS hoạt động. Điện thoại USB dùng URL debug ghi đè và reverse theo [frontend guide](../Frontend/README.md). Bấm Tiếp tục với Google ở một trong hai form. Google mới có Gmail verified → success → onboarding; Google trùng email local → nhập mật khẩu hiện tại → cùng User/Profile. Tài khoản Google bị khóa ở backend không được vào Home.

Postman không tạo được Google ID token hợp lệ bằng cách tùy ý nhập email: token phải lấy từ luồng Google thật, có nonce từ API-AUTH-11 và đúng audience. Kiểm thử tự động dùng verifier giả để kiểm tra nghiệp vụ; không coi đó là bằng chứng đăng nhập Google thật.

Verifier cho phép lệch đồng hồ tối đa 30 giây khi kiểm tra iat/exp theo [cơ chế time skew của Google](https://docs.cloud.google.com/java/docs/reference/google-oauth-client/latest/com.google.api.client.auth.openidconnect.IdTokenVerifier). Token ngoài biên hoặc sai chữ ký/audience/issuer/nonce vẫn bị từ chối; challenge vẫn chỉ dùng một lần. Kiểm thử RSA/certificate và clock cố định kiểm tra đúng biên 30/31 giây, không dùng provider thật.

## 5. Kiểm thử tự động

Dùng `scripts/test-local.ps1 -Action Start`, backend `test`, rồi `-Action Stop` theo backend README. Test dùng PostgreSQL 55432 riêng, fake mail và fake Google identity; không đọc file `.env.local.xml`, không chạm database 5432 hoặc gửi email thật. Các bộ kiểm thử Android kiểm tra API, trạng thái, UI và luồng live với server fixture riêng. Thực hiện kiểm thử Gmail/Google thật sau khi nhập cấu hình trên máy; không cần cung cấp mật khẩu cho agent.
