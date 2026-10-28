# Bố cục auth theo Stitch

Đăng nhập, đăng ký, quên mật khẩu, OTP, đặt mật khẩu, liên kết Google và các trạng thái thành công dùng chung theme của app. Logo/font/icon dùng asset cục bộ. Màn quên mật khẩu gom tiêu đề và form ở giữa, không có logo.

Form đăng nhập/đăng ký bố trí nút chính và chuyển tài khoản trong vùng nhìn thấy khi đủ chỗ. Màn thấp, bàn phím mở hoặc chữ lớn vẫn cho cuộn form; các nút không bị khuất bởi bàn phím. OTP và reset hiển thị trạng thái API thật; điều kiện mật khẩu theo backend.

Nguồn thiết kế: [Stitch project](https://stitch.withgoogle.com/projects/11930636729725860020).

| Màn | Screen ID |
|---|---|
| Đăng nhập | 9db38bd691e047e79f8b6b184dc5d023 |
| Đăng ký | b86d05bbf8884a5e9fc5ef7730c78e75 |
| Quên mật khẩu | d6e9df68b8ae4cb79e0bdaffb6670674 |
| OTP | 98731f220ef0445f8c81dbe0f5a063a5 |
| Đặt mật khẩu | a9c1bb794f7e4a08b4025f3b3f6539d0 |
| Đăng ký thành công | 225d1d2330434a6388165cc1acbc4789 |
| Đổi mật khẩu thành công | 3f9d2900c14d4fbab3c4515f2e56b34e |

## Kiểm thử

AuthLayoutUiTest kiểm tra các kích thước màn hình, bàn phím, chữ 200%, theme sáng/tối và hành động trên form. LoginUiTest, RegisterUiTest, PendingSessionUiTest và AuthStepUiTest kiểm tra tương tác, trạng thái chờ/lỗi và điều hướng. Fixture thuần không cần backend; các ca live cần backend kiểm thử riêng theo [hướng dẫn Frontend](../../README.md).

MainActivity giữ FLAG_SECURE. Ảnh kiểm chứng và bản xuất HTML/ảnh từ Stitch được giữ cục bộ, không phải đầu vào build/test và không được theo dõi trong Git.
