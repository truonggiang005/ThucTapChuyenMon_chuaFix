<div align="center">
  <h1>🗓️ FlexiShift AI - Smart Shift Scheduling & AI Staffing</h1>
  <p>
    <b>Hệ thống quản lý ca làm việc thông minh, tích hợp Trí tuệ Nhân tạo (AI) chấm điểm & phân bổ nhân sự.</b>
  </p>

<!-- Tech Stack Badges -->
![Java](https://img.shields.io/badge/java-17-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/spring--boot-3.3-%236DB33F.svg?style=for-the-badge&logo=springboot&logoColor=white)
![MySQL](https://img.shields.io/badge/mysql-8.0-%2300f.svg?style=for-the-badge&logo=mysql&logoColor=white)
![Python](https://img.shields.io/badge/python-3.9+-%233776AB.svg?style=for-the-badge&logo=python&logoColor=white)
![FastAPI](https://img.shields.io/badge/FastAPI-AI%20Service-%23009688.svg?style=for-the-badge&logo=fastapi&logoColor=white)
![Gemini](https://img.shields.io/badge/Google%20Gemini-AI-%238E75B2.svg?style=for-the-badge&logo=googlegemini&logoColor=white)

</div>

---

## 📖 Giới thiệu Dự án

Khác với các công cụ xếp lịch truyền thống, **FlexiShift AI (SmartSchedule)** không chỉ giúp tạo và quản lý ca làm việc mà còn sử dụng AI (Google Gemini) để đóng vai trò như một *"trưởng ca thông minh"*. Khi có ca trống hoặc nhân viên nhả ca, hệ thống sẽ tự động lọc ứng viên phù hợp, chấm điểm **Match Score (0-100%)** dựa trên độ tin cậy, kỹ năng, khung giờ ưa thích và khối lượng công việc, rồi đề xuất (hoặc tự động gán) người phù hợp nhất.

---

## 🚀 Tính năng nổi bật

### 👷 Dành cho Nhân viên
* 📅 **Lịch cá nhân tổng hợp:** Xem toàn bộ ca làm việc và lịch bận của bản thân theo tuần trực quan.
* 🛒 **Chợ Ca Trực (Shift Marketplace):** Xem danh sách các ca đang trống, lọc theo chi nhánh/kỹ năng và nhận ca nhanh chóng.
* 🔁 **Nhả ca linh hoạt:** Trả lại ca không thể làm để hệ thống đưa lên chợ ca cho người khác nhận.
* ⛔ **Khai báo lịch bận:** Đăng ký khung giờ bận cá nhân để hệ thống không xếp ca trùng lịch.

### 💻 Dành cho Quản trị viên (Admin)
* ➕ **Quản lý ca làm việc:** Tạo, sửa ca theo chi nhánh, yêu cầu kỹ năng và cấp độ chuyên môn.
* 🤖 **AI Matchmaking:** Nhận gợi ý danh sách ứng viên phù hợp nhất do Google Gemini phân tích và xếp hạng kèm lý do chi tiết.
* ⏰ **Tự động gán ca (Cron Job):** Mỗi 5 phút, hệ thống tự động quét các ca `OPEN` sắp diễn ra trong 2 giờ tới và tự động gán cho ứng viên có điểm số cao nhất (`FORCE_ASSIGNED`).
* 🛡️ **Xử lý tương tranh (Optimistic Locking):** Cơ chế chống xung đột khi 2 người cùng nhận 1 ca tại cùng một thời điểm qua `@Version`.

---

## 🛠️ Sơ đồ Kiến trúc & Công nghệ (Tech Stack)

### 1. Công nghệ lõi
* **Backend:** `Java 17`, `Spring Boot 3`, `Spring Data JPA`, `Spring Validation`, `Spring Scheduling`.
* **AI Microservice:** `Python 3.9+`, `FastAPI`, `Pydantic`, `Uvicorn`, `google-genai`.
* **Database:** `MySQL 8.0`.
* **Frontend:** `HTML5`, `CSS3 (Glassmorphism & Dark Theme)`, `Vanilla JS (ES modules, mô hình MVVM)`, `Thymeleaf`.
* **Bảo mật:** `Spring Security` (session + CSRF, BCrypt, phân quyền ADMIN/EMPLOYEE ở server).

### 2. Luồng hoạt động kiến trúc
```text
[Trình duyệt Web] ──HTTP (8080)──▶ [Spring Boot Backend] ──REST (8001, kèm X-Internal-Key)──▶ [Python AI Service] ──▶ [Google Gemini API]
                                         │
                                         ▼
                                   [MySQL Database :3306]
```

### 3. Cấu trúc thư mục dự án
```text
ThucTapChuyenMon/
├── src/
│   ├── main/
│   │   ├── java/com/smartschedule/       # Mã nguồn Java Spring Boot
│   │   │   ├── config/                   # SecurityConfig, Seeder, RestTemplate, Exception handler
│   │   │   ├── security/                 # AppUserDetails, AppUserDetailsService (đăng nhập)
│   │   │   ├── controller/               # REST API Endpoints & WebController điều hướng
│   │   │   ├── dto/                      # Data Transfer Objects
│   │   │   ├── entity/                   # JPA Entities (Shift, Employee, Branch, Skill...)
│   │   │   ├── repository/               # Spring Data JPA Repositories
│   │   │   ├── service/                  # Business Logic & Scheduled Cron Jobs
│   │   │   ├── exception/, util/         # Ngoại lệ 400/429, RateLimiter, LogSanitizer
│   │   └── resources/
│   │       ├── application.properties    # Cấu hình mặc định (đọc DB/khóa từ biến môi trường)
│   │       ├── application-prod.properties # Profile chạy thật (SPRING_PROFILES_ACTIVE=prod)
│   │       ├── static/                   # css/style.css và js/ theo MVVM:
│   │       │   └── js/{model,viewmodel,view}/, main.js, login.js
│   │       └── templates/                # Giao diện Thymeleaf (index.html, dashboard.html)
│   │
│   └── ai-service/                       # Microservice AI Python (FastAPI)
│       ├── .env.example                  # Mẫu biến môi trường (sao chép thành .env, KHÔNG commit)
│       ├── requirements.txt              # Danh sách thư viện Python
│       ├── schemas.py                    # Khai báo schema Pydantic
│       ├── model.py                      # Prompt & logic chấm điểm Google Gemini
│       └── main.py                       # FastAPI entrypoint (Port 8001)
│
├── frontend-tests/                       # Test ViewModel/View bằng Node: node --test frontend-tests/*.test.mjs
├── .mvn/                                 # Maven Wrapper
├── mvnw & mvnw.cmd                       # Script chạy Maven không cần cài đặt
├── pom.xml                               # Quản lý dependencies dự án Spring Boot
├── .gitignore                            # Cấu hình file bỏ qua của Git
└── README.md                             # Tài liệu toàn diện về dự án
```

---

## ⚙️ Hướng dẫn Cài đặt & Khởi động Hệ thống

### 1. Yêu cầu hệ thống
* **Java JDK**: 17 trở lên ([Tải JDK 17](https://adoptium.net/))
* **Python**: 3.9 trở lên ([Tải Python](https://www.python.org/downloads/))
* **MySQL Server**: 8.0 trở lên ([Tải MySQL](https://dev.mysql.com/downloads/installer/))

---

### 2. Các bước triển khai

#### Bước 1: Cấu hình Database (MySQL)
1. Đảm bảo dịch vụ MySQL đang chạy trên máy (port mặc định `3306`).
2. Thông tin kết nối đọc từ **biến môi trường** (không viết cứng trong mã). Nếu MySQL của bạn dùng tài khoản/mật khẩu khác mặc định (`root`, mật khẩu rỗng), đặt trước khi chạy:
```bash
# Linux/macOS                      # Windows (PowerShell)
export DB_USER=smartschedule        $env:DB_USER="smartschedule"
export DB_PASSWORD=mat_khau_cua_ban $env:DB_PASSWORD="mat_khau_cua_ban"
```
   Khuyến nghị tạo user MySQL riêng với quyền tối thiểu thay vì dùng `root`.
3. **Nếu DB `smart_schedule` đã tồn tại từ phiên bản cũ:** hãy xóa DB (`DROP DATABASE smart_schedule;`) rồi chạy lại để seeder tạo tài khoản có mật khẩu băm BCrypt và cột `role` mới.

*(Ở chế độ phát triển, Hibernate tự tạo bảng và `DataSeeder.java` nạp dữ liệu mẫu. Khi chạy thật dùng `SPRING_PROFILES_ACTIVE=prod`: schema không tự đổi (`ddl-auto=validate`), không seed, cookie phiên chỉ qua HTTPS, và BẮT BUỘC đặt `DB_URL`, `DB_USER`, `DB_PASSWORD`, `AI_INTERNAL_KEY`.)*

#### Bước 2: Cấu hình khóa cho AI Service
```bash
cd src/ai-service
cp .env.example .env        # Windows: copy .env.example .env
```
Mở file `.env` (đã nằm trong `.gitignore`, không bao giờ commit) và điền:
```env
GEMINI_API_KEY=khoa_gemini_that_cua_ban
INTERNAL_KEY=chuoi_ngau_nhien_dai     # python -c "import secrets; print(secrets.token_urlsafe(32))"
```
Đặt **cùng giá trị** `INTERNAL_KEY` cho Spring Boot bằng biến môi trường `AI_INTERNAL_KEY` trước khi chạy ở Bước 4. Nếu chưa có `GEMINI_API_KEY` hợp lệ, hệ thống tự dùng bộ chấm điểm quy tắc dự phòng.
> Nếu bạn từng dán khóa Gemini thật vào một file đã commit: **thu hồi và tạo khóa mới** trong Google AI Studio.

#### Bước 3: Khởi động Python AI Service (Port 8001)
Mở Terminal 1 tại thư mục dự án:
```bash
cd src/ai-service
pip install -r requirements.txt
python main.py
```
> Khi khởi động thành công, console sẽ báo: `Uvicorn running on http://127.0.0.1:8001`. AI service chỉ nghe trên localhost, không có `/docs` (bật khi phát triển bằng `AI_DEBUG=1 python main.py`).

#### Bước 4: Khởi động Spring Boot Backend & Giao diện (Port 8080)
Mở Terminal 2 tại thư mục gốc `ThucTapChuyenMon`:
```bash
# nhớ đặt AI_INTERNAL_KEY (cùng giá trị INTERNAL_KEY ở Bước 2), ví dụ Linux/macOS: export AI_INTERNAL_KEY=...
# Windows
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```
> Spring Boot sẽ phục vụ toàn bộ REST API và giao diện Web tại cổng `8080`.

---

### 3. Truy cập hệ thống

Mở trình duyệt web và truy cập:
* 🌐 **Giao diện Web:** [http://localhost:8080/](http://localhost:8080/)
* 📊 **Dashboard:** [http://localhost:8080/dashboard](http://localhost:8080/dashboard)
* 📑 **Swagger AI Service** (chỉ khi chạy `AI_DEBUG=1`): [http://localhost:8001/docs](http://localhost:8001/docs)

**Tài khoản đăng nhập mẫu** (chỉ có khi seed dữ liệu ở môi trường phát triển; mật khẩu mẫu `123456` đặt ở `app.seed.password`, được băm BCrypt; vai trò lưu trong DB, không còn suy ra từ id):
| Email | Họ và tên | Vai trò |
| :--- | :--- | :--- |
| `bach@gmail.com` | Trần Xuân Bách | **Quản lý (Admin)** |
| `giang@gmail.com` | Lưu Trường Giang | **Nhân viên** |
| `ly@gmail.com` | Phạm Khánh Ly | **Nhân viên** |
| `vu@gmail.com` | Nguyễn Sơn Vũ | **Nhân viên** |
| `nga@gmail.com` | Đặng Thị Phương Nga | **Nhân viên** |

---

## 🧪 Kịch bản Kiểm thử các Chức năng Chính (Testing Guide)

### Kịch bản 1: Đổi ca & Xử lý Tương tranh (Optimistic Locking)
1. Đăng nhập tài khoản `bach@gmail.com`.
2. Vào trang **Lịch Cá Nhân**, tìm ca có trạng thái `ASSIGNED` và bấm nút **Nhả Ca**.
3. Ca này sẽ chuyển sang trạng thái `OPEN` và lập tức xuất hiện trên mục **Chợ Ca Trực**.
4. **Kiểm tra tương tranh:**
   - Mở 2 tab trình duyệt ẩn danh khác nhau.
   - Tab A đăng nhập `binh@smartschedule.com`, Tab B đăng nhập `cuong@smartschedule.com`.
   - Cả 2 cùng vào **Chợ Ca Trực** và bấm **Nhận Ca** trên cùng 1 ca trống.
   - **Kết quả:** Người bấm trước sẽ nhận ca thành công; người bấm sau sẽ nhận thông báo lỗi an toàn *"Ca này vừa bị người khác nhận trước bạn"*, không bị ghi đè dữ liệu.

### Kịch bản 2: AI Matchmaking chấm điểm ứng viên
1. Vào tab **Chợ Ca Trực**, bấm nút **🤖 AI Gợi Ý** trên một ca bất kỳ.
2. Hệ thống gửi yêu cầu sang AI Service; Google Gemini phân tích 4 tiêu chí (Độ tin cậy, Kỹ năng, Khung giờ ưa thích, Khối lượng giờ làm) và trả về bảng xếp hạng Top ứng viên kèm điểm số và giải thích chi tiết.
3. Quản lý có thể bấm **Gán Ca** trực tiếp cho ứng viên điểm cao nhất.

### Kịch bản 3: Cron Job Tự động gán ca khẩn cấp
1. Hệ thống có sẵn Cron Job chạy nền mỗi 5 phút (`@Scheduled(fixedRate = 300000)` trong `ShiftService.java`).
2. Tác vụ sẽ tự động quét các ca `OPEN` sắp diễn ra trong vòng 2 giờ tới, gửi sang AI và gán cho nhân viên có điểm Match Score cao nhất (đã kiểm tra lại kỹ năng, trùng lịch, lịch bận, giờ/tuần) chuyển thành trạng thái `FORCE_ASSIGNED`. Khi Gemini hết quota/lỗi, cron vẫn tự gán theo điểm công thức dự phòng (trọng số: tin cậy 30%, kỹ năng 25%, khung giờ 20%, khối lượng việc 15%, chi nhánh 10%) và ghi rõ trong thông báo cho admin; chỉ khi dịch vụ AI không trả điểm nào (chưa chạy/không kết nối) cron mới bỏ qua. Đặt `app.scheduler.allow-formula-fallback=false` để chỉ gán khi điểm do Gemini chấm. Khi chạy nhiều instance, đặt `app.scheduler.enabled=false` ở các instance phụ.

---

## 🔌 Danh mục API Endpoints

| HTTP Method | Endpoint | Mô tả chức năng | Service |
| :--- | :--- | :--- | :--- |
| `GET` | `/` | Trang đăng nhập giao diện | `WebController` |
| `GET` | `/dashboard` | Trang quản lý chính | `WebController` |
| `POST` | `/api/auth/login` | Đăng nhập (cấp cookie phiên HttpOnly) | `Spring Boot` |
| `GET` | `/api/auth/me` | Người dùng đang đăng nhập (id, họ tên, vai trò) | `Spring Boot` |
| `POST` | `/api/auth/logout` | Đăng xuất | `Spring Boot` |
| `GET` | `/api/employees` | Danh sách nhân viên (**ADMIN**) | `Spring Boot` |
| `GET` | `/api/employees/{id}` | Hồ sơ nhân viên (ADMIN hoặc chính chủ) | `Spring Boot` |
| `GET` | `/api/shifts` | Toàn bộ ca làm việc (**ADMIN**) | `Spring Boot` |
| `GET` | `/api/shifts/open` | Danh sách các ca đang mở (Chợ ca) | `Spring Boot` |
| `GET` | `/api/shifts/employee/{id}` | Ca làm việc của một nhân viên (ADMIN hoặc chính chủ) | `Spring Boot` |
| `POST` | `/api/shifts` | Tạo ca làm việc mới (**ADMIN**) | `Spring Boot` |
| `PUT` | `/api/shifts/{id}` | Cập nhật ca chưa bắt đầu (**ADMIN**) | `Spring Boot` |
| `POST` | `/api/shifts/{id}/release` | Nhả ca **của chính mình** | `Spring Boot` |
| `POST` | `/api/shifts/{id}/take` | **Mình** nhận ca trống | `Spring Boot` |
| `POST` | `/api/shifts/{id}/assign` | Admin gán ca cho NV (body `{employeeId}`) (**ADMIN**) | `Spring Boot` |
| `GET` | `/api/shifts/{id}/candidates` | Ứng viên do AI chấm điểm (**ADMIN**) | `Spring Boot → AI` |
| `GET` | `/api/shifts/{id}/my-score` | Điểm AI của chính mình cho ca | `Spring Boot → AI` |
| `GET` | `/api/ai/health` | Trạng thái AI service (giao diện chỉ gọi Spring) | `Spring Boot` |
| `POST` | `/api/busy-schedules` | Đăng ký lịch bận cho chính mình | `Spring Boot` |
| `GET` | `/api/busy-schedules/employee/{id}` | Lịch bận (ADMIN hoặc chính chủ) | `Spring Boot` |
| `DELETE` | `/api/busy-schedules/{id}` | Xóa lịch bận của mình | `Spring Boot` |
| `POST` | `/api/v1/match-score` | API AI tính Match Score (cần header `X-Internal-Key`) | `FastAPI AI` |
| `GET` | `/api/v1/health` | Kiểm tra tình trạng hoạt động AI Service | `FastAPI AI` |

---

## 🛠️ Xử lý lỗi thường gặp (Troubleshooting)

* **Lỗi `Communications link failure` (Không kết nối được MySQL):**
  - Kiểm tra xem dịch vụ MySQL đã bật chưa trong `services.msc`.
  - Kiểm tra biến môi trường `DB_USER` / `DB_PASSWORD` (hoặc `DB_URL`) đã đúng chưa.
* **Lỗi `Port 8080 already in use`:**
  - Cổng 8080 đang bị ứng dụng khác chiếm. Tìm và tắt process đang chạy cổng 8080: `netstat -ano | findstr :8080` rồi `taskkill /PID <PID> /F`.
* **Lỗi `AI Status: Offline` trên giao diện:**
  - Đảm bảo terminal của AI Service đang chạy (`python main.py`).
  - Kiểm tra [http://localhost:8001/api/v1/health](http://localhost:8001/api/v1/health) và chắc chắn `AI_INTERNAL_KEY` (Spring) trùng `INTERNAL_KEY` (file `.env` của AI service).

---

## 👥 Đội ngũ Phát triển

Dự án được xây dựng và phát triển với sự đóng góp của:
* 👨‍💻 **Trường Giang** - Fullstack & AI Developer *(Spring Boot, AI Integration, Frontend)*

<div align="center">
  <p><i>Phát triển trong khuôn khổ Thực tập Chuyên môn - 2026</i></p>
</div>
