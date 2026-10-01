# 📘 HƯỚNG DẪN CÀI ĐẶT, CẤU HÌNH VÀ THỰC THI DỰ ÁN

## Hệ thống Quản lý Ca làm việc tích hợp AI Chấm điểm & Phân bổ Nhân sự (SmartSchedule)

---

## 📋 MỤC LỤC

1. [Yêu cầu hệ thống](#1-yêu-cầu-hệ-thống)
2. [Cấu trúc dự án](#2-cấu-trúc-dự-án)
3. [Cài đặt môi trường](#3-cài-đặt-môi-trường)
4. [Cấu hình Database (MySQL)](#4-cấu-hình-database-mysql)
5. [Cấu hình & Chạy AI Microservice (Python)](#5-cấu-hình--chạy-ai-microservice-python)
6. [Cấu hình & Chạy Backend (Spring Boot)](#6-cấu-hình--chạy-backend-spring-boot)
7. [Chạy Frontend](#7-chạy-frontend)
8. [Thứ tự khởi động hệ thống](#8-thứ-tự-khởi-động-hệ-thống)
9. [Kiểm tra hệ thống hoạt động](#9-kiểm-tra-hệ-thống-hoạt-động)
10. [Test các chức năng chính](#10-test-các-chức-năng-chính)
11. [Các file cấu hình quan trọng](#11-các-file-cấu-hình-quan-trọng)
12. [Xử lý lỗi thường gặp](#12-xử-lý-lỗi-thường-gặp)
13. [Ghi chú kỹ thuật](#13-ghi-chú-kỹ-thuật)

---

## 1. YÊU CẦU HỆ THỐNG

### Phần mềm bắt buộc

| Phần mềm         | Phiên bản tối thiểu | Mục đích                        | Link tải                                           |
|-------------------|----------------------|---------------------------------|----------------------------------------------------|
| **Java JDK**      | 17+                  | Chạy Spring Boot Backend        | https://adoptium.net/                              |
| **Maven**         | 3.8+ (hoặc dùng mvnw) | Build project Java            | https://maven.apache.org/download.cgi              |
| **Python**        | 3.9+                 | Chạy AI Microservice (FastAPI)  | https://www.python.org/downloads/                  |
| **MySQL Server**  | 8.0+                 | Cơ sở dữ liệu                  | https://dev.mysql.com/downloads/mysql/             |
| **Trình duyệt**   | Chrome / Firefox     | Chạy Frontend                   | —                                                  |

### Phần mềm khuyến nghị (không bắt buộc)

| Phần mềm         | Mục đích                                       |
|-------------------|-------------------------------------------------|
| **VS Code**       | IDE viết code, có Live Server extension         |
| **IntelliJ IDEA** | IDE chuyên dụng cho Java/Spring Boot            |
| **MySQL Workbench** | Quản lý database trực quan                    |
| **Postman**       | Test API thủ công                               |
| **Git**           | Quản lý phiên bản mã nguồn                     |

### Kiểm tra cài đặt

Mở Terminal / CMD và chạy các lệnh sau để xác nhận:

```bash
# Kiểm tra Java
java -version
# Kết quả mong đợi: openjdk version "17.x.x" hoặc cao hơn

# Kiểm tra Maven (nếu cài global)
mvn -version
# Hoặc dùng Maven Wrapper trong project: ./mvnw -version

# Kiểm tra Python
python --version
# Kết quả mong đợi: Python 3.9.x hoặc cao hơn

# Kiểm tra pip
pip --version

# Kiểm tra MySQL
mysql --version
```

---

## 2. CẤU TRÚC DỰ ÁN

```
ThucTapChuyenMon/
│
├── backend/                              ← Spring Boot (Java 17)
│   ├── pom.xml                           ← Maven dependencies
│   └── src/main/
│       ├── java/com/smartschedule/
│       │   ├── SmartScheduleApplication.java    ← Entry point + @EnableScheduling
│       │   ├── entity/                          ← JPA Entities (7 files)
│       │   ├── repository/                      ← Spring Data JPA (5 files)
│       │   ├── dto/                             ← Data Transfer Objects (7 files)
│       │   ├── service/                         ← Business Logic (3 files)
│       │   ├── controller/                      ← REST API Endpoints (2 files)
│       │   └── config/                          ← Cấu hình (4 files)
│       └── resources/
│           └── application.properties           ← ★ Cấu hình chính
│
├── ai-service/                           ← Python FastAPI
│   ├── requirements.txt                  ← Python dependencies
│   ├── schemas.py                        ← Pydantic models
│   ├── model.py                          ← ★ AI Scoring Model
│   └── main.py                           ← FastAPI Entry point
│
├── frontend/                             ← HTML/CSS/JS thuần
│   ├── index.html                        ← Giao diện chính
│   ├── style.css                         ← Dark theme + Glassmorphism
│   └── app.js                            ← ★ Fetch API logic
│
└── HUONG_DAN_THUC_THI.md                ← File này
```

---

## 3. CÀI ĐẶT MÔI TRƯỜNG

### 3.1. Cài đặt Java JDK 17

**Windows:**
1. Tải JDK 17 từ https://adoptium.net/
2. Chạy installer, chọn "Set JAVA_HOME variable"
3. Kiểm tra: `java -version`

**Thiết lập biến môi trường (nếu chưa có):**
```
Biến: JAVA_HOME
Giá trị: C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot  (thay bằng đường dẫn thực tế)

Thêm vào PATH: %JAVA_HOME%\bin
```

### 3.2. Cài đặt Python 3.9+

**Windows:**
1. Tải từ https://www.python.org/downloads/
2. ⚠️ **QUAN TRỌNG**: Tick ✅ "Add Python to PATH" khi cài đặt
3. Kiểm tra: `python --version`

### 3.3. Cài đặt MySQL 8.0

**Windows:**
1. Tải MySQL Installer từ https://dev.mysql.com/downloads/installer/
2. Chọn "Developer Default" hoặc "Server Only"
3. Trong quá trình cài đặt:
   - **Root Password**: Đặt là `root` (hoặc bất kỳ, nhưng phải cập nhật lại trong `application.properties`)
   - **Port**: Giữ mặc định `3306`
4. Kiểm tra: `mysql -u root -p`

---

## 4. CẤU HÌNH DATABASE (MySQL)

### 4.1. Tạo Database

Đăng nhập MySQL và chạy lệnh:

```sql
-- Tạo database
CREATE DATABASE IF NOT EXISTS smart_schedule 
    CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

-- Kiểm tra
SHOW DATABASES;

-- (Tùy chọn) Tạo user riêng thay vì dùng root
CREATE USER 'smartschedule'@'localhost' IDENTIFIED BY 'smartschedule123';
GRANT ALL PRIVILEGES ON smart_schedule.* TO 'smartschedule'@'localhost';
FLUSH PRIVILEGES;
```

### 4.2. Cấu hình kết nối trong Spring Boot

Mở file `backend/src/main/resources/application.properties`:

```properties
# ── Thay đổi các giá trị sau cho phù hợp với máy bạn ──

# URL kết nối MySQL
spring.datasource.url=jdbc:mysql://localhost:3306/smart_schedule?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true

# Tài khoản MySQL (thay đổi nếu bạn dùng user/password khác)
spring.datasource.username=root
spring.datasource.password=root
```

> ⚠️ **LƯU Ý**: Nếu MySQL của bạn dùng password khác `root`, hãy sửa lại `spring.datasource.password`.

### 4.3. Tự động tạo bảng

Cấu hình `spring.jpa.hibernate.ddl-auto=update` trong `application.properties` sẽ khiến Hibernate **tự động tạo/cập nhật** các bảng khi Spring Boot khởi động. **Không cần chạy SQL tạo bảng thủ công**.

Các bảng sẽ được tạo tự động:
- `branches` — Chi nhánh
- `employees` — Nhân viên
- `skills` — Kỹ năng
- `employee_skills` — Bảng trung gian Employee-Skill
- `shifts` — Ca làm việc

### 4.4. Dữ liệu mẫu (DataSeeder)

Class `DataSeeder.java` sẽ tự động chèn dữ liệu mẫu khi ứng dụng khởi động lần đầu:
- 2 chi nhánh (HCM, Hà Nội)
- 3 kỹ năng (Barista, Cashier, Kitchen)
- 5 nhân viên (với lịch sử AI khác nhau)
- 11 bản ghi employee_skills
- 5 ca mẫu (2 ASSIGNED + 3 OPEN)

> DataSeeder chỉ chạy khi bảng `branches` trống. Nếu muốn reset data, xóa tất cả bảng trong MySQL rồi restart Spring Boot.

---

## 5. CẤU HÌNH & CHẠY AI MICROSERVICE (Python)

### 5.1. Cài đặt dependencies

```bash
# Di chuyển vào thư mục ai-service
cd ai-service

# (Khuyến nghị) Tạo virtual environment
python -m venv venv
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser

# Kích hoạt virtual environment
# Windows CMD:
venv\Scripts\activate
# Windows PowerShell:
venv\Scripts\Activate.ps1

# Cài đặt dependencies
pip install -r requirements.txt
```

### 5.2. Chạy AI Service

```bash
# Cách 1: Chạy trực tiếp
python main.py
\ 0
# Cách 2: Chạy bằng uvicorn (có hot-reload)
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

### 5.3. Kiểm tra AI Service

Khi chạy thành công, bạn sẽ thấy:
```
INFO:     Uvicorn running on http://0.0.0.0:8000
INFO:     Started server process
```

Truy cập trình duyệt:
- **Swagger UI**: http://localhost:8000/docs — Giao diện test API tương tác
- **Health Check**: http://localhost:8000/api/v1/health — Phải trả về `{"status": "healthy"}`

### 5.4. Cấu hình Port (nếu cần thay đổi)

Nếu port 8000 đã bị chiếm, sửa trong `main.py`:

```python
uvicorn.run("main:app", host="0.0.0.0", port=8001, ...)  # Đổi port
```

Và cập nhật trong `application.properties` của Spring Boot:

```properties
ai.service.url=http://localhost:8001
```

---

## 6. CẤU HÌNH & CHẠY BACKEND (Spring Boot)

### 6.1. Build project

```bash
# Di chuyển vào thư mục backend
cd backend

# Build project (lần đầu sẽ tải dependencies, có thể mất 3-5 phút)
.\mvnw.cmd clean install -DskipTests
> ⚠️ Nếu không có file `mvnw` (Maven Wrapper), dùng Maven global: `mvn clean install -DskipTests`

### 6.3. Chạy Spring Boot

```bash
# Windows:
.\mvnw.cmd spring-boot:run



```

### 6.4. Kiểm tra Backend

Khi chạy thành công, bạn sẽ thấy log:
```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

[SEEDER] 🌱 Bắt đầu seed dữ liệu mẫu...
[SEEDER] ✅ Seed hoàn tất: 2 branches, 3 skills, 5 employees, 5 shifts
...
Started SmartScheduleApplication in X.XXX seconds
```

Kiểm tra API:
- http://localhost:8080/api/employees — Danh sách nhân viên
- http://localhost:8080/api/shifts — Danh sách ca
- http://localhost:8080/api/shifts/open — Ca đang mở

### 6.5. File cấu hình `application.properties` — Giải thích chi tiết

```properties
# ── SERVER ──
server.port=8080                    # Port Spring Boot (đổi nếu bị trùng)

# ── MYSQL ──
spring.datasource.url=jdbc:mysql://localhost:3306/smart_schedule
                                    # Tên DB: smart_schedule
                                    # createDatabaseIfNotExist=true: tự tạo DB nếu chưa có
spring.datasource.username=root     # User MySQL
spring.datasource.password=root     # Password MySQL

# ── JPA/HIBERNATE ──
spring.jpa.hibernate.ddl-auto=update  # update: tự tạo/cập nhật bảng
                                       # create-drop: xóa và tạo lại mỗi lần chạy (dùng khi dev)
                                       # none: không tự động (dùng khi production)
spring.jpa.show-sql=true              # Hiện SQL trong console (tắt khi production)

# ── AI SERVICE ──
ai.service.url=http://localhost:8000  # URL Python FastAPI (đổi nếu dùng port khác)

# ── SCHEDULING ──
spring.task.scheduling.pool.size=5    # Số thread cho Cron Job
```

---

## 7. CHẠY FRONTEND

### 7.1. Cách 1: Mở trực tiếp trong trình duyệt

```
Double-click vào file: frontend/index.html
```

> ⚠️ Cách này có thể gặp lỗi CORS khi gọi API. Nên dùng Cách 2.

### 7.2. Cách 2: Dùng VS Code Live Server (KHUYẾN NGHỊ)

1. Mở thư mục `frontend/` trong VS Code
2. Cài extension **Live Server** (của Ritwick Dey)
3. Click chuột phải vào `index.html` → **Open with Live Server**
4. Trình duyệt sẽ mở tại `http://127.0.0.1:5500/index.html`

### 7.3. Cách 3: Dùng Python HTTP Server

```bash
cd frontend
python -m http.server 5500
# Truy cập: http://localhost:5500
```

### 7.4. Cấu hình URL API trong Frontend

Nếu Spring Boot chạy ở port khác 8080, sửa trong `frontend/app.js`:

```javascript
// Dòng 16-17
const API_BASE = 'http://localhost:8080/api';       // Đổi port nếu cần
const AI_HEALTH_URL = 'http://localhost:8000/api/v1/health';  // Đổi port nếu cần
```

---

## 8. THỨ TỰ KHỞI ĐỘNG HỆ THỐNG

```
┌─────────────────────────────────────────────────────┐
│  Bước 1: MySQL Server phải đang chạy                │
│  ↓                                                   │
│  Bước 2: Chạy Python AI Service (port 8000)          │
│  ↓                                                   │
│  Bước 3: Chạy Spring Boot Backend (port 8080)        │
│  ↓                                                   │
│  Bước 4: Mở Frontend (index.html)                    │
└─────────────────────────────────────────────────────┘
```

> **Ghi chú**: AI Service (Bước 2) có thể khởi động sau Spring Boot. Hệ thống có cơ chế fallback — nếu AI service không khả dụng, Match Score mặc định = 50% cho tất cả nhân viên.

**Tóm tắt lệnh chạy nhanh (3 terminal riêng biệt):**

```bash
# Terminal 1: AI Service
cd ai-service && python main.py

# Terminal 2: Spring Boot
cd backend && .\mvnw.cmd spring-boot:run

# Terminal 3: Frontend (tùy chọn)
cd frontend && python -m http.server 5500
```

---

## 9. KIỂM TRA HỆ THỐNG HOẠT ĐỘNG

### 9.1. Checklist kiểm tra

| # | Kiểm tra | URL / Lệnh | Kết quả mong đợi |
|---|----------|-------------|-------------------|
| 1 | MySQL đang chạy | `mysql -u root -p -e "SHOW DATABASES;"` | Thấy `smart_schedule` |
| 2 | AI Service sống | http://localhost:8000/api/v1/health | `{"status": "healthy"}` |
| 3 | Spring Boot sống | http://localhost:8080/api/employees | JSON 5 nhân viên |
| 4 | API ca hoạt động | http://localhost:8080/api/shifts/open | JSON 3 ca OPEN |
| 5 | Frontend hiển thị | http://localhost:5500 | Giao diện dark theme |
| 6 | AI Status trên UI | Góc phải header | "AI: Online" (chấm xanh) |

### 9.2. Test nhanh bằng trình duyệt

Mở trình duyệt và nhập từng URL:

```
http://localhost:8080/api/employees
→ Phải trả về JSON danh sách 5 nhân viên

http://localhost:8080/api/shifts
→ Phải trả về JSON danh sách 5 ca làm việc

http://localhost:8080/api/shifts/open
→ Phải trả về JSON 3 ca có status "OPEN"

http://localhost:8000/docs
→ Phải hiện giao diện Swagger UI của Python
```

---

## 10. TEST CÁC CHỨC NĂNG CHÍNH

### 10.1. Module 1 — Đổi Ca (Tương tranh)

**Test nhả ca (ASSIGNED → OPEN):**
```
Trên giao diện:
1. Chọn nhân viên "Nguyễn Văn An" từ dropdown
2. Vào tab "Lịch Của Tôi"
3. Bấm "Nhả ca" trên ca đang ASSIGNED
4. Ca chuyển sang OPEN, xuất hiện ở tab "Ca Đang Mở"
```

**Test nhận ca (OPEN → TAKEN):**
```
1. Chọn nhân viên "Trần Thị Bình"
2. Vào tab "Ca Đang Mở"
3. Bấm "Nhận Ca" trên 1 ca OPEN
4. Nếu thành công: toast xanh "Nhận ca thành công!"
5. Ca chuyển sang TAKEN trong lịch của Bình
```

**Test tương tranh (Optimistic Locking):**
```
1. Mở 2 tab trình duyệt
2. Tab 1: Chọn NV "Bình" → Bấm "Nhận Ca" trên ca #2
3. Tab 2: Chọn NV "Cường" → Bấm "Nhận Ca" trên ca #2 (cùng ca)
4. Kết quả: 1 người thành công, 1 người nhận toast đỏ:
   "Ca này vừa bị người khác nhận trước bạn"
```

### 10.2. Module 2 — AI Matchmaking

```
1. Vào tab "Ca Đang Mở"
2. Bấm "AI Gợi Ý" trên bất kỳ ca OPEN nào
3. Panel AI hiện ra với danh sách ứng viên:
   - Xếp hạng 1, 2, 3 (huy chương vàng/bạc/đồng)
   - Match Score (0-100%)
   - Breakdown: Tin cậy / Kỹ năng / Giờ phù hợp / Khối lượng
4. Bấm "Gán ca" để gán cho ứng viên
```

### 10.3. Module 3 — Cron Job (Auto-assign)

```
Module này chạy tự động mỗi 5 phút:
- Quét ca OPEN có start_time trong 2 giờ tới
- Gọi AI chấm điểm
- Gán cho người có score cao nhất → FORCE_ASSIGNED

Để test:
1. Tạo ca OPEN có start_time trong 2 giờ tới (thông qua DB)
2. Chờ 5 phút (hoặc restart Spring Boot)
3. Kiểm tra ca đã chuyển sang FORCE_ASSIGNED

Xem log Cron Job trong console Spring Boot:
  [MODULE 3] ⏰ CRON JOB bắt đầu quét ca OPEN sắp đến giờ...
  [MODULE 3] ✅ FORCE_ASSIGNED ca #X → Nguyễn Văn An (Score: 84.2%)
```

---

## 11. CÁC FILE CẤU HÌNH QUAN TRỌNG

| File | Đường dẫn | Nội dung cần chú ý |
|------|-----------|---------------------|
| **application.properties** | `backend/src/main/resources/` | DB connection, AI service URL, scheduling |
| **pom.xml** | `backend/` | Java version, Spring Boot version, dependencies |
| **requirements.txt** | `ai-service/` | Python packages version |
| **app.js** | `frontend/` | `API_BASE` URL (dòng 16) |
| **main.py** | `ai-service/` | AI service port (dòng cuối) |
| **model.py** | `ai-service/` | Trọng số 4 tiêu chí AI (dòng 28-31) |

### Tùy chỉnh trọng số AI (model.py)

```python
# Thay đổi trọng số để ưu tiên tiêu chí khác nhau
WEIGHT_RELIABILITY = 0.35   # Độ tin cậy (tổng = 1.0)
WEIGHT_SKILL_FIT = 0.25     # Kỹ năng
WEIGHT_TIME_FIT = 0.20      # Khung giờ
WEIGHT_WORKLOAD_FIT = 0.20  # Khối lượng
```

### Tùy chỉnh Cron Job (ShiftService.java)

```java
// Thay đổi tần suất chạy (mili giây)
@Scheduled(fixedRate = 300000)  // 300000ms = 5 phút
                                 // 60000ms  = 1 phút
                                 // 600000ms = 10 phút

// Thay đổi ngưỡng thời gian quét
LocalDateTime threshold = now.plusHours(2);  // Quét ca trong 2 giờ tới
                                              // Đổi thành plusHours(4) để quét 4 giờ tới
```

---

## 12. XỬ LÝ LỖI THƯỜNG GẶP

### Lỗi 1: `Communications link failure` (Không kết nối được MySQL)

```
Nguyên nhân: MySQL Server chưa chạy hoặc sai cấu hình.
Cách sửa:
  1. Kiểm tra MySQL đang chạy: services.msc → MySQL80 → Start
  2. Kiểm tra port: mysql -u root -p -e "SHOW VARIABLES LIKE 'port';"
  3. Kiểm tra username/password trong application.properties
```

### Lỗi 2: `Port 8080 already in use`

```
Nguyên nhân: Port 8080 đã bị ứng dụng khác chiếm.
Cách sửa:
  1. Tìm và kill process: netstat -ano | findstr :8080
  2. Hoặc đổi port trong application.properties:
     server.port=8081
  3. Cập nhật API_BASE trong frontend/app.js
```

### Lỗi 3: `Port 8000 already in use` (Python)

```
Cách sửa:
  1. Kill process: netstat -ano | findstr :8000
  2. Hoặc đổi port trong main.py và application.properties
```

### Lỗi 4: `ModuleNotFoundError: No module named 'fastapi'`

```
Nguyên nhân: Chưa cài đặt dependencies Python.
Cách sửa:
  cd ai-service
  pip install -r requirements.txt
```

### Lỗi 5: Frontend hiện "Không thể kết nối đến server"

```
Nguyên nhân: CORS hoặc Spring Boot chưa chạy.
Cách sửa:
  1. Kiểm tra Spring Boot đang chạy (port 8080)
  2. Mở frontend qua HTTP server (không mở bằng file://)
  3. Kiểm tra WebConfig.java đã cho phép CORS
```

### Lỗi 6: `mvnw is not recognized` / `Permission denied`

```
Windows:
  Dùng: .\mvnw.cmd spring-boot:run
  Hoặc: mvn spring-boot:run (nếu cài Maven global)

Linux/macOS:
  chmod +x mvnw
  ./mvnw spring-boot:run
```

### Lỗi 7: `JAVA_HOME is not set`

```
Windows:
  1. Tìm đường dẫn JDK: where java
  2. System Properties → Environment Variables
  3. Tạo biến JAVA_HOME = C:\Program Files\Eclipse Adoptium\jdk-17...
  4. Thêm %JAVA_HOME%\bin vào PATH
  5. Restart terminal
```

### Lỗi 8: AI Status hiện "Offline" trên Frontend

```
Nguyên nhân: Python AI Service chưa chạy.
Giải pháp:
  - Hệ thống vẫn hoạt động bình thường (fallback score = 50%)
  - Khởi động AI service: cd ai-service && python main.py
  - Sau 30 giây, trạng thái tự cập nhật thành "Online"
```

---

## 13. GHI CHÚ KỸ THUẬT

### Kiến trúc 3 Module

```
Module 1 (Đổi ca):     Employee → releaseShift() → OPEN → takeShift() → TAKEN
                        Optimistic Locking: @Version trên Shift entity

Module 2 (AI Match):    Repository lọc 3 tầng → RestTemplate POST → Python scoring → ranked list
                        Fallback: Nếu AI down → score mặc định 50%

Module 3 (Cron Job):    @Scheduled(5 phút) → quét OPEN trong 2h tới → AI scoring → FORCE_ASSIGNED
                        Bonus: Ca quá hạn → UNFILLED
```

### Database Indexes (Tối ưu hiệu suất)

```sql
-- Tự động tạo bởi Hibernate từ @Index annotation trên Shift entity
CREATE INDEX idx_shift_status ON shifts(status);
CREATE INDEX idx_shift_start_time ON shifts(start_time);
CREATE INDEX idx_shift_status_start ON shifts(status, start_time);
```

### Về bảo mật (Production)

Dự án hiện tại chưa có authentication/authorization. Để triển khai production:
1. Thêm Spring Security + JWT Token
2. Đổi `spring.jpa.hibernate.ddl-auto` từ `update` sang `none`
3. Tắt `spring.jpa.show-sql`
4. Giới hạn CORS origins (không dùng `*`)
5. Mã hóa password bằng BCryptPasswordEncoder
6. Cấu hình HTTPS

---

> 📌 **Tài liệu này được tạo cho dự án Thực tập chuyên môn.**
> Mọi thắc mắc vui lòng liên hệ hướng dẫn viên hoặc tham khảo source code.
