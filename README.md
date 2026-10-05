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
* **Frontend:** `HTML5`, `CSS3 (Glassmorphism & Dark Theme)`, `Vanilla JS`, `Thymeleaf`.

### 2. Luồng hoạt động kiến trúc
```text
[Trình duyệt Web] ──HTTP (8080)──▶ [Spring Boot Backend] ──REST (8081)──▶ [Python AI Service] ──▶ [Google Gemini API]
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
│   │   │   ├── config/                   # Cấu hình CORS, Seeder, RestTemplate, Exception
│   │   │   ├── controller/               # REST API Endpoints & WebController điều hướng
│   │   │   ├── dto/                      # Data Transfer Objects
│   │   │   ├── entity/                   # JPA Entities (Shift, Employee, Branch, Skill...)
│   │   │   ├── repository/               # Spring Data JPA Repositories
│   │   │   └── service/                  # Business Logic & Scheduled Cron Jobs
│   │   └── resources/
│   │       ├── application.properties    # Cấu hình Database & URL AI Service
│   │       ├── static/                   # Static assets (css/style.css, js/app.js)
│   │       └── templates/                # Giao diện Thymeleaf (index.html, dashboard.html)
│   │
│   └── ai-service/                       # Microservice AI Python (FastAPI)
│       ├── ApiGeminiKey.env              # File cấu hình biến môi trường GEMINI_API_KEY
│       ├── requirements.txt              # Danh sách thư viện Python
│       ├── schemas.py                    # Khai báo schema Pydantic
│       ├── model.py                      # Prompt & logic chấm điểm Google Gemini
│       └── main.py                       # FastAPI entrypoint (Port 8001)
│
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
2. Mở file `src/main/resources/application.properties` và điều chỉnh lại username/password nếu máy bạn dùng mật khẩu khác:
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/smart_schedule?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=123456
```
*(Database `smart_schedule` và các bảng sẽ được Hibernate tự động tạo khi chạy, đồng thời `DataSeeder.java` sẽ tự nạp dữ liệu mẫu ban đầu)*.

#### Bước 2: Cấu hình API Key cho AI Service
Mở file `src/ai-service/ApiGeminiKey.env` và điền key của bạn:
```env
GEMINI_API_KEY=your_gemini_api_key_here
```

#### Bước 3: Khởi động Python AI Service (Port 8001)
Mở Terminal 1 tại thư mục dự án:
```bash
cd src/ai-service
pip install -r requirements.txt
python main.py
```
> Khi khởi động thành công, console sẽ báo: `Uvicorn running on http://0.0.0.0:8001`.

#### Bước 4: Khởi động Spring Boot Backend & Giao diện (Port 8080)
Mở Terminal 2 tại thư mục gốc `ThucTapChuyenMon`:
```bash
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
* 📑 **Tài liệu Swagger AI Service:** [http://localhost:8001/docs](http://localhost:8001/docs)

**Tài khoản đăng nhập mẫu** (Mật khẩu chung: `123456`):
| Email | Họ và tên | Vai trò |
| :--- | :--- | :--- |
| `an@smartschedule.com` | Nguyễn Văn An | **Quản lý (Admin)** |
| `binh@smartschedule.com` | Trần Thị Bình | **Nhân viên** |
| `cuong@smartschedule.com` | Lê Hoàng Cường | **Nhân viên** |
| `duc@smartschedule.com` | Phạm Minh Đức | **Nhân viên** |
| `ema@smartschedule.com` | Hoàng Thị Ema | **Nhân viên** |

---

## 🧪 Kịch bản Kiểm thử các Chức năng Chính (Testing Guide)

### Kịch bản 1: Đổi ca & Xử lý Tương tranh (Optimistic Locking)
1. Đăng nhập tài khoản `an@smartschedule.com`.
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
2. Tác vụ sẽ tự động quét các ca `OPEN` sắp diễn ra trong vòng 2 giờ tới, tự động gửi sang AI và gán thẳng cho nhân viên có điểm Match Score cao nhất chuyển thành trạng thái `FORCE_ASSIGNED`.

---

## 🔌 Danh mục API Endpoints

| HTTP Method | Endpoint | Mô tả chức năng | Service |
| :--- | :--- | :--- | :--- |
| `GET` | `/` | Trang đăng nhập giao diện | `WebController` |
| `GET` | `/dashboard` | Trang quản lý chính | `WebController` |
| `GET` | `/api/employees` | Lấy danh sách toàn bộ nhân viên | `Spring Boot` |
| `GET` | `/api/shifts` | Lấy danh sách toàn bộ ca làm việc | `Spring Boot` |
| `GET` | `/api/shifts/open` | Lấy danh sách các ca đang mở (Chợ ca) | `Spring Boot` |
| `GET` | `/api/shifts/employee/{id}` | Lấy ca làm việc của một nhân viên | `Spring Boot` |
| `POST` | `/api/shifts` | Tạo ca làm việc mới | `Spring Boot` |
| `PUT` | `/api/shifts/{id}` | Cập nhật thông tin ca | `Spring Boot` |
| `POST` | `/api/shifts/{id}/release?employeeId=` | Nhân viên nhả ca | `Spring Boot` |
| `POST` | `/api/shifts/{id}/take?employeeId=` | Nhân viên nhận ca trống | `Spring Boot` |
| `GET` | `/api/shifts/{id}/candidates` | Lấy danh sách ứng viên do AI chấm điểm | `Spring Boot → AI` |
| `POST` | `/api/busy-schedules` | Đăng ký khung giờ bận cá nhân | `Spring Boot` |
| `DELETE` | `/api/busy-schedules/{id}?employeeId=` | Xóa lịch bận | `Spring Boot` |
| `POST` | `/api/v1/match-score` | API AI tính Match Score theo JSON | `FastAPI AI` |
| `GET` | `/api/v1/health` | Kiểm tra tình trạng hoạt động AI Service | `FastAPI AI` |

---

## 🛠️ Xử lý lỗi thường gặp (Troubleshooting)

* **Lỗi `Communications link failure` (Không kết nối được MySQL):**
  - Kiểm tra xem dịch vụ MySQL đã bật chưa trong `services.msc`.
  - Kiểm tra đúng `username` và `password` trong `src/main/resources/application.properties`.
* **Lỗi `Port 8080 already in use`:**
  - Cổng 8080 đang bị ứng dụng khác chiếm. Tìm và tắt process đang chạy cổng 8080: `netstat -ano | findstr :8080` rồi `taskkill /PID <PID> /F`.
* **Lỗi `AI Status: Offline` trên giao diện:**
  - Đảm bảo terminal của AI Service đang chạy (`python main.py`).
  - Kiểm tra kết nối tại [http://localhost:8081/api/v1/health](http://localhost:8081/api/v1/health).

---

## 👥 Đội ngũ Phát triển

Dự án được xây dựng và phát triển với sự đóng góp của:

* 👨‍💻 **Trường Giang** - Fullstack & AI Developer *(Spring Boot, AI Integration, Frontend)*

<div align="center">
  <p><i>Phát triển trong khuôn khổ Thực tập Chuyên môn - 2026</i></p>
</div>
