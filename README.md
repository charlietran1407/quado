# Quado

> **Công cụ phân tích và trực quan hoá đồ thị quan hệ mã nguồn đa ngôn ngữ**, kết hợp giao diện Desktop JavaFX, cơ sở dữ liệu đồ thị nhúng ArcadeDB, và MCP Server tích hợp cho AI Agent.

---

## ✨ Tính năng nổi bật

- **Quét mã nguồn đa ngôn ngữ**: Java, Python, TypeScript, Go (Golang), Delphi (Pascal), SQL
- **Xây dựng đồ thị quan hệ** với các loại node và cạnh:
  - `Class`, `Interface`, `Method`
  - `EXTENDS`, `IMPLEMENTS`, `INJECTS` (DI), `CONTAINS`, `CALLS`
- **Tự động phân tích cú pháp (AST) ngay trên JVM** thông qua các parser ANTLR4 (đối với Python, TypeScript, Go, Delphi, SQL) và JavaParser (đối với Java). Không cần cài đặt bất kỳ runtime/môi trường bên thứ ba nào khác (như Python hay Node.js).
- **Giao diện Desktop JavaFX** trực quan, dễ sử dụng
- **MCP Server tích hợp** (Streamable HTTP) — cho phép AI Agent (Claude, Cursor, v.v.) truy vấn đồ thị trực tiếp

---

## 🛠️ Yêu cầu hệ thống

### Bắt buộc

| Công cụ | Phiên bản tối thiểu | Ghi chú |
|---------|-------------------|---------|
| **JDK** | 21+ | Cần thiết để build & chạy ứng dụng |
| **Maven** | 3.8+ | Dùng để build project |

**Kiểm tra nhanh trên terminal:**
```bash
# Kiểm tra Java
java -version

# Kiểm tra Maven
mvn -version
```

---

## 🚀 Hướng dẫn cài đặt & chạy

### 1. Clone repository

```bash
git clone <repository-url>
cd Quado
```

### 2. Build project

```bash
mvn clean package -DskipTests
```

File JAR sẽ được tạo tại: `target/quado-1.0.0-SNAPSHOT.jar`

### 3. Chạy ứng dụng

```bash
java -jar target/quado-1.0.0-SNAPSHOT.jar
```

Ứng dụng sẽ mở cửa sổ Desktop JavaFX và khởi động MCP Server tại:
```
http://localhost:3000/mcp
```

---

## 📖 Hướng dẫn sử dụng

### Bước 1: Index mã nguồn

1. Trong giao diện Desktop, nhấn nút **"Chọn Thư Mục"**
2. Chọn thư mục gốc của project cần phân tích (ví dụ: `src/main/java`)
3. Nhấn **"Bắt đầu Index"**
4. Theo dõi tiến trình trong cửa sổ log

### Bước 2: Kết nối AI Agent (MCP)

Thêm cấu hình MCP vào file config của AI Client (Claude Desktop, Cursor, v.v.):

```json
{
  "mcpServers": {
    "quado": {
      "url": "http://localhost:3000/mcp"
    }
  }
}
```

### Bước 3: Phân tích nghiệp vụ bằng AI cục bộ (Enrichment)

1. Đảm bảo đã khởi chạy Ollama trên máy trạm cục bộ (`http://localhost:11434`) và kéo về model tương ứng (ví dụ: `ollama pull qwen2.5-coder:3b`).
2. Sau khi index mã nguồn ở Bước 1, nhấp nút **"Start AI Analysis"** trong phần **AI KNOWLEDGE RICHNESS** trên giao diện Desktop.
3. Tiến trình phân tích AI chạy bất đồng bộ để làm giàu tri thức của các đỉnh `Method` bằng thuộc tính `ai_summary` và chuyển trạng thái `is_analyzed = true`.
4. Có thể nhấn **"Stop"** để dừng bất kỳ lúc nào.

---

## 🔌 MCP Tools — API cho AI Agent

Sau khi kết nối, AI Agent có thể sử dụng các công cụ sau:

| Tool | Tham số | Mô tả |
|------|---------|-------|
| `getGraphSummary` | _(không có)_ | Tổng quan đồ thị: số Class, Interface, Method |
| `findClassDependencies` | `className` | Các quan hệ EXTENDS, IMPLEMENTS, INJECTS của một Class |
| `traceMethodCalls` | `methodFqName` | Chuỗi gọi hàm (CALLS) từ một Method |
| `getClassInfo` | `className` | Chi tiết Class/Interface kèm Javadoc/docstring, package, filepath |
| `getMethodInfo` | `methodFqName` | Chi tiết Method kèm Javadoc/docstring, className, filepath |
| `analyzeMethodsWithAi` | _(không có)_ | Kích hoạt bất đồng bộ tiến trình phân tích AI cho các Method chưa xử lý |
| `getAiAnalysisStatus` | _(không có)_ | Lấy thông tin trạng thái & tiến độ của tiến trình phân tích AI |

**Ví dụ truy vấn:**
```
getClassInfo("Canvas")
getMethodInfo("Canvas.render")
findClassDependencies("CodeIndexerService")
traceMethodCalls("Canvas.render")
```

---

## 🏗️ Kiến trúc hệ thống

```
+-----------------------------------------------------+
|              JavaFX Desktop UI                      |
|         (MainController + FXML)                     |
+------------------------+----------------------------+
                         |
             +-----------v-----------+
             |    CodeIndexerService |
             |  (Điều phối index)    |
             +----+------+-------+---+
                  |      |       |
         +--------v-+  +-v-------v---+
         |JavaIndexer|  |ANTLR4       |
         |(JavaParser|  |Indexers     |
         +----------+  +----+--------+
                             |
                 +-----------+-----------+
                 |  (Python, TypeScript, |
                 |   Go, Delphi, SQL)    |
                 +-----------+-----------+
                             |
                  +----------v-----------+
                  |  ArcadeDB (Embedded) |
                  |   Graph Database     |
                  |  (Cypher Query)      |
                  +----------+-----------+
                             |
                  +----------v-----------+
                  |   MCP Server         |
                  | (Spring AI 2.0)      |
                  | Streamable HTTP      |
                  | http://localhost:3000/mcp |
                  +---------------------+
```

---

## 🧱 Stack công nghệ

| Thành phần | Công nghệ | Phiên bản |
|-----------|-----------|-----------|
| Ngôn ngữ chính | Java | 21 |
| UI Framework | JavaFX | 21 |
| Backend Framework | Spring Boot | 4.0.0 |
| MCP Server | Spring AI MCP | 2.0.0 |
| Graph Database | ArcadeDB (Embedded) | 26.6.1 |
| Java AST Parser | JavaParser | 3.28.2 |
| Python AST Parser | ANTLR4 Python3Parser | 4.13.2 |
| TypeScript AST Parser | ANTLR4 TypeScriptParser | 4.13.2 |
| Go AST Parser | ANTLR4 GoParser | 4.13.2 |
| Delphi AST Parser | ANTLR4 PascalParser | 4.13.2 |
| SQL AST Parser | ANTLR4 TSqlParser | 4.13.2 |

---

## 📁 Cấu trúc project

```
Quado/
├── src/main/antlr4/vn/cxn/graph/indexer/antlr/
│   ├── delphi/
│   │   └── pascal.g4                # Grammar Delphi
│   ├── golang/
│   │   ├── GoLexer.g4               # Lexer Go
│   │   └── GoParser.g4              # Parser Go
│   ├── python/
│   │   ├── Python3Lexer.g4          # Lexer Python
│   │   └── Python3Parser.g4         # Parser Python
│   ├── sql/
│   │   ├── TSqlLexer.g4             # Lexer SQL Server
│   │   └── TSqlParser.g4            # Parser SQL Server
│   └── typescript/
│       ├── TypeScriptLexer.g4       # Lexer TypeScript
│       └── TypeScriptParser.g4      # Parser TypeScript
├── src/main/java/vn/cxn/graph/
│   ├── App.java                     # Entry point (JavaFX)
│   ├── SpringApp.java               # Spring Boot bootstrap
│   ├── controller/
│   │   └── MainController.java      # JavaFX UI Controller
│   ├── indexer/
│   │   ├── SourceCodeIndexer.java   # Interface chung cho các Indexer
│   │   ├── MethodCallInfo.java      # Model lưu trữ liên kết gọi hàm
│   │   ├── delphi/
│   │   │   └── DelphiIndexer.java   # Quét Delphi bằng ANTLR4
│   │   ├── golang/
│   │   │   └── GoIndexer.java       # Quét Go bằng ANTLR4
│   │   ├── java/
│   │   │   └── JavaIndexer.java     # Quét Java bằng JavaParser
│   │   ├── python/
│   │   │   └── PythonIndexer.java   # Quét Python bằng ANTLR4
│   │   ├── sql/
│   │   │   └── SqlIndexer.java      # Quét SQL bằng ANTLR4
│   │   └── typescript/
│   │       └── TypeScriptIndexer.java # Quét TypeScript bằng ANTLR4
│   ├── mcp/
│   │   └── McpGraphTools.java       # 5 MCP Tools cho AI Agent
│   └── service/
│       ├── ArcadeDbService.java     # Quản lý kết nối ArcadeDB
│       └── CodeIndexerService.java  # Orchestrator quét & lưu đồ thị
├── src/main/resources/
│   ├── vn/cxn/graph/ui/
│   │   ├── main.fxml                # Giao diện FXML
│   │   └── style.css                # CSS style cho UI
│   └── application.properties
└── pom.xml
```

---

## ⚙️ Cấu hình

File `src/main/resources/application.properties`:

```properties
# Cổng MCP Server (mặc định: 3000)
server.port=3000

# Thư mục lưu dữ liệu ArcadeDB
arcadedb.path=./arcadedb_graph
```

### 🔧 Đổi port MCP Server

Không cần build lại — Spring Boot hỗ trợ override port theo thứ tự ưu tiên **từ cao xuống thấp**:

**Cách 1: Runtime argument (khuyên dùng)**
```bash
java -jar target/quado-1.0.0-SNAPSHOT.jar --server.port=8080
```

**Cách 2: Biến môi trường**
```powershell
# Windows PowerShell
$env:SERVER_PORT=8080; java -jar target/quado-1.0.0-SNAPSHOT.jar
```
```bash
# Linux / macOS
SERVER_PORT=8080 java -jar target/quado-1.0.0-SNAPSHOT.jar
```

**Cách 3: Sửa `application.properties` (thay đổi default vĩnh viễn, cần build lại)**
```properties
server.port=8080
```

Sau khi đổi port, cập nhật URL trong cấu hình MCP Client tương ứng:
```json
{
  "mcpServers": {
    "quado": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

### 📂 Đổi tên thư mục hoặc vị trí lưu đồ thị (ArcadeDB)

Tương tự như cổng port, thư mục lưu trữ đồ thị (mặc định là `./arcadedb_graph`) cũng có thể được ghi đè hoặc thay đổi qua thuộc tính `arcadedb.path` mà không cần biên dịch lại:

**Cách 1: Runtime argument (khuyên dùng)**
```bash
java -jar target/quado-1.0.0-SNAPSHOT.jar --arcadedb.path=D:/GraphData/my_new_graph
```

**Cách 2: Biến môi trường**
```powershell
# Windows PowerShell
$env:ARCADEDB_PATH="D:/GraphData/my_new_graph"; java -jar target/quado-1.0.0-SNAPSHOT.jar
```
```bash
# Linux / macOS
ARCADEDB_PATH="D:/GraphData/my_new_graph" java -jar target/quado-1.0.0-SNAPSHOT.jar
```

**Cách 3: Sửa `application.properties` (cần build lại)**
```properties
arcadedb.path=./my_custom_graph_folder
```

---

## 🛠️ Hướng dẫn bổ sung Parser mới cho ngôn ngữ khác

Dự án Quado được thiết kế theo dạng plugin/strategy pattern rất dễ mở rộng. Để bổ sung hỗ trợ quét một ngôn ngữ mới (ví dụ: `Go`, `SQL`, `Delphi` hoặc ngôn ngữ bất kỳ), thực hiện theo quy trình 3 bước sau:

### Bước 1: Định nghĩa Grammar ANTLR4 (nếu sử dụng ANTLR4)
1. Tạo thư mục tương ứng trong `src/main/antlr4/vn/cxn/graph/indexer/antlr/<ngon_ngu>/`.
2. Định nghĩa các file `.g4` (Lexer và Parser) cho ngôn ngữ đó.
3. Chạy lệnh biên dịch để tự động sinh mã nguồn Parser Java:
   ```bash
   mvn antlr4:antlr4
   # Hoặc biên dịch toàn bộ dự án
   mvn clean compile
   ```

### Bước 2: Tạo lớp Indexer mới
1. Tạo một class mới kế thừa từ `SourceCodeIndexer` tại package `vn.cxn.graph.indexer.<ngon_ngu>/` (hoặc package con tương ứng).
2. Đánh dấu class bằng annotation `@Component` để Spring tự động phát hiện và nạp (Auto-wiring).
3. Triển khai 2 phương thức bắt buộc:
   - `supports(String fileExtension)`: Trả về phần mở rộng của file mà indexer hỗ trợ (ví dụ: `"go"`, `"pas"`, v.v.).
   - `index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls)`:
     - Đọc file và sử dụng Parser sinh ra từ ANTLR4 để phân tích cú pháp AST.
     - Sử dụng `ParseTreeWalker` và Listener để duyệt cây cú pháp nhằm trích xuất các Node (`Class`, `Interface`, `Method`) và mối quan hệ giữa chúng.
     - Sử dụng Cypher của ArcadeDB qua đối tượng `Database` để MERGE dữ liệu đồ thị:
       ```java
       db.command("cypher", "MERGE (c:Class {name: $name}) ...", Map.of(...));
       ```
     - Thu thập thông tin các cuộc gọi hàm và add vào danh sách `pendingMethodCalls` dưới dạng `MethodCallInfo` để hệ thống tự động thiết lập liên kết `CALLS` sau đó.

### Bước 3: Kiểm tra và biên dịch dự án
- Tiến hành chạy kiểm thử để chắc chắn tiến trình index của ngôn ngữ mới hoạt động ổn định.
- Chạy lệnh kiểm tra biên dịch bắt buộc trước khi bàn giao:
  ```bash
  mvn clean compile
  ```


---
## ⚠️ Quan trọng: Vấn đề tương thích với Eclipse JDT Language Server

### 🐛 Vấn đề:
Khi sử dụng ANTLR4 để tạo parser từ grammar viết bằng chữ thường (ví dụ: `grammar pascal;`), ANTLR4 sẽ sinh ra các file Java có tên bắt đầu bằng chữ thường như `pascalLexer.java`, `pascalParser.java`.

**Tuy nhiên**, Eclipse JDT Language Server (sử dụng trong Eclipse IDE, Spring Tool Suite, và VS Code với Java Extensions) có **cơ chế phân tích cú pháp "thông minh" quá mức**:
- Nó **giả định** các tên bắt đầu bằng chữ thường **không phải là class**
- Thay vào đó, nó coi đó là **biến, method, hoặc package reference**
- Kết quả: JDT LS **tự tạo** các file `.class` lỗi với nội dung: `java.lang.Error: Unresolved compilation problems`

### 💥 Hậu quả:
- File `.class` lỗi được tạo trong `target/classes/`
- Maven đóng gói file lỗi này vào JAR cuối cùng
- Ứng dụng **crash runtime** khi khởi tạo bean sử dụng các class này

### ✅ Giải pháp:

#### **Phương án 1: Đổi tên grammar thành PascalCase (Khuyến nghị)**

#### Thay vì: 
  - grammar pascal;

#### Nên dùng:
  - grammar Pascal;

#### **Phương án 2: Cấu hình Eclipse không auto-build**

Project → Properties → Builders → Bỏ check "Java Builder"

Hoặc:

Window → Preferences → General → Workspace → Bỏ check "Build automatically"

---

## 📄 License
MIT License — xem file [LICENSE](LICENSE) để biết chi tiết.

---

> 🇬🇧 **English version**: [README.en.md](README.en.md)
