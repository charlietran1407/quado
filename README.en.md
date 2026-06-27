# Quado

> **A multi-language source code relationship graph analysis and visualization tool**, combining a JavaFX Desktop UI, an embedded ArcadeDB graph database, and a built-in MCP Server for AI Agents.

---

## ✨ Key Features

- **Multi-language source scanning**: Java, Python, TypeScript, Go (Golang), Delphi (Pascal), SQL
- **Graph relationship building** with node and edge types:
  - `Class`, `Interface`, `Method`
  - `EXTENDS`, `IMPLEMENTS`, `INJECTS` (DI), `CONTAINS`, `CALLS`
- **Native in-JVM AST parsing** using ANTLR4 parsers (for Python, TypeScript, Go, Delphi, SQL) and JavaParser (for Java). No external runtimes or environments (like Python or Node.js) are required to run the scans.
- **JavaFX Desktop UI** — intuitive and easy to use
- **Built-in MCP Server** (Streamable HTTP) — allows AI Agents (Claude, Cursor, etc.) to query the graph directly

---

## 🛠️ System Requirements

### Required

| Tool | Minimum Version | Notes |
|------|----------------|-------|
| **JDK** | 21+ | Required to build and run the application |
| **Maven** | 3.8+ | Required to build the project |

> ℹ️ **Note:** Unlike previous versions, you **do not need** Python or Node.js installed on your machine. All source files are parsed directly using Java and ANTLR4.

**Quick check in terminal:**
```bash
# Check Java
java -version

# Check Maven
mvn -version
```

---

## 🚀 Installation & Running

### 1. Clone the repository

```bash
git clone <repository-url>
cd Quado
```

### 2. Build the project

```bash
mvn clean package -DskipTests
```

The JAR file will be created at: `target/quado-1.0.0-SNAPSHOT.jar`

### 3. Run the application

```bash
java -jar target/quado-1.0.0-SNAPSHOT.jar
```

The application will open a JavaFX Desktop window and start the MCP Server at:
```
http://localhost:3000/mcp
```

---

## 📖 Usage Guide

### Step 1: Index source code

1. In the Desktop UI, click **"Select Directory"**
2. Choose the root directory of the project to analyze (e.g., `src/main/java`)
3. Click **"Start Indexing"**
4. Monitor progress in the log window

### Step 2: Connect an AI Agent (MCP)

Add the MCP configuration to your AI Client config file (Claude Desktop, Cursor, etc.):

```json
{
  "mcpServers": {
    "quado": {
      "url": "http://localhost:3000/mcp"
    }
  }
}
```

---

## 🔌 MCP Tools — API for AI Agents

Once connected, AI Agents can use the following 5 tools:

| Tool | Parameter | Description |
|------|-----------|-------------|
| `getGraphSummary` | _(none)_ | Graph overview: total Classes, Interfaces, Methods |
| `findClassDependencies` | `className` | EXTENDS, IMPLEMENTS, INJECTS relationships for a Class |
| `traceMethodCalls` | `methodFqName` | Call chain (CALLS) originating from a Method |
| `getClassInfo` | `className` | Class/Interface details including description, package, filepath |
| `getMethodInfo` | `methodFqName` | Method details including description, className, filepath |

**Query examples:**
```
getClassInfo("Canvas")
getMethodInfo("Canvas.render")
findClassDependencies("CodeIndexerService")
traceMethodCalls("Canvas.render")
```

---

## 🏗️ System Architecture

```
+-----------------------------------------------------+
|              JavaFX Desktop UI                      |
|         (MainController + FXML)                     |
+------------------------+----------------------------+
                         |
             +-----------v-----------+
             |    CodeIndexerService |
             |  (Async index coord.) |
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

## 🧱 Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Primary Language | Java | 21 |
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

## 📁 Project Structure

```
Quado/
├── src/main/antlr4/vn/cxn/graph/indexer/antlr/
│   ├── delphi/
│   │   └── pascal.g4                # Delphi grammar
│   ├── golang/
│   │   ├── GoLexer.g4               # Go Lexer
│   │   └── GoParser.g4              # Go Parser
│   ├── python/
│   │   ├── Python3Lexer.g4          # Python Lexer
│   │   └── Python3Parser.g4         # Python Parser
│   ├── sql/
│   │   ├── TSqlLexer.g4             # SQL Server Lexer
│   │   └── TSqlParser.g4            # SQL Server Parser
│   └── typescript/
│       ├── TypeScriptLexer.g4       # TypeScript Lexer
│       └── TypeScriptParser.g4      # TypeScript Parser
├── src/main/java/vn/cxn/graph/
│   ├── App.java                     # Entry point (JavaFX)
│   ├── SpringApp.java               # Spring Boot bootstrap
│   ├── controller/
│   │   └── MainController.java      # JavaFX UI Controller
│   ├── indexer/
│   │   ├── SourceCodeIndexer.java   # Common interface for all Indexers
│   │   ├── JavaIndexer.java         # Java scanner using JavaParser
│   │   ├── PythonIndexer.java       # Python scanner using ANTLR4
│   │   ├── TypeScriptIndexer.java   # TypeScript scanner using ANTLR4
│   │   ├── MethodCallInfo.java      # Model storing method call links
│   │   ├── delphi/
│   │   │   └── DelphiIndexer.java   # Delphi scanner using ANTLR4
│   │   ├── golang/
│   │   │   └── GoIndexer.java       # Go scanner using ANTLR4
│   │   └── sql/
│   │       └── SqlIndexer.java      # SQL scanner using ANTLR4
│   ├── mcp/
│   │   └── McpGraphTools.java       # 5 MCP Tools for AI Agents
│   └── service/
│       ├── ArcadeDbService.java     # ArcadeDB connection manager
│       └── CodeIndexerService.java  # Scan & graph persistence orchestrator
├── src/main/resources/
│   ├── vn/cxn/graph/ui/
│   │   ├── main.fxml                # UI FXML file
│   │   └── style.css                # UI CSS stylesheet
│   └── application.properties
└── pom.xml
```

---

## ⚙️ Configuration

File `src/main/resources/application.properties`:

```properties
# MCP Server port (default: 3000)
server.port=3000

# ArcadeDB data directory
arcadedb.path=./arcadedb_graph
```

### 🔧 Changing the MCP Server Port

No rebuild required — Spring Boot supports port override with the following priority order (**highest to lowest**):

**Option 1: Runtime argument (recommended)**
```bash
java -jar target/quado-1.0.0-SNAPSHOT.jar --server.port=8080
```

**Option 2: Environment variable**
```powershell
# Windows PowerShell
$env:SERVER_PORT=8080; java -jar target/quado-1.0.0-SNAPSHOT.jar
```
```bash
# Linux / macOS
SERVER_PORT=8080 java -jar target/quado-1.0.0-SNAPSHOT.jar
```

**Option 3: Edit `application.properties` (permanent default, requires rebuild)**
```properties
server.port=8080
```

After changing the port, update the MCP Client URL accordingly:
```json
{
  "mcpServers": {
    "quado": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

### 📂 Changing the Database Directory or Location (ArcadeDB)

Similar to the server port, the graph database storage directory (default is `./arcadedb_graph`) can be overridden or changed dynamically via the `arcadedb.path` property without rebuilding:

**Option 1: Runtime argument (recommended)**
```bash
java -jar target/quado-1.0.0-SNAPSHOT.jar --arcadedb.path=D:/GraphData/my_new_graph
```

**Option 2: Environment variable**
```powershell
# Windows PowerShell
$env:ARCADEDB_PATH="D:/GraphData/my_new_graph"; java -jar target/quado-1.0.0-SNAPSHOT.jar
```
```bash
# Linux / macOS
ARCADEDB_PATH="D:/GraphData/my_new_graph" java -jar target/quado-1.0.0-SNAPSHOT.jar
```

**Option 3: Edit `application.properties` (requires rebuild)**
```properties
arcadedb.path=./my_custom_graph_folder
```

---

## 🐛 Troubleshooting

### Build error: JAR file is in use
```
ERROR: Failed to delete .jar: The process cannot access the file because it is being used by another process
```
**Solution:** Close the running application before rebuilding.

## 🛠️ How to Add a New Parser/Indexer

Quado is designed with an extensible modular architecture (Plugin/Strategy pattern). To add support for scanning a new programming language (e.g., `Go`, `SQL`, `Delphi`, or any other language), follow these three steps:

### Step 1: Define ANTLR4 Grammars (if using ANTLR4)
1. Create a subfolder under `src/main/antlr4/vn/cxn/graph/indexer/antlr/<language>/`.
2. Define the `.g4` lexer and parser grammar files.
3. Generate the Java parser classes by compiling:
   ```bash
   mvn antlr4:antlr4
   # Or compile the whole project
   mvn clean compile
   ```

### Step 2: Implement the Indexer Class
1. Create a new Java class implementing the `SourceCodeIndexer` interface in the package `vn.cxn.graph.indexer.<language>/`.
2. Annotate the class with `@Component` to enable automatic Spring Boot dependency injection and discovery.
3. Implement the two required methods:
   - `supports(String fileExtension)`: Return the file extension this indexer processes (e.g., `"go"`, `"sql"`, etc.).
   - `index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls)`:
     - Read the file content and traverse the AST using the generated ANTLR4 parser and custom listeners.
     - Extract entities (`Class`, `Interface`, `Method`) and their structural relationships.
     - Persist the graph nodes and relations into ArcadeDB using Cypher queries via the `Database` reference:
       ```java
       db.command("cypher", "MERGE (c:Class {name: $name}) ...", Map.of(...));
       ```
     - Collect any method invocations into the `pendingMethodCalls` list as `MethodCallInfo` objects so the orchestrator can resolve `CALLS` relations globally.

### Step 3: Verify and Build
- Run the application and test parsing code in the newly supported language.
- Run the build verification command to ensure compilation succeeds:
  ```bash
  mvn clean compile
  ```

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.
