package vn.cxn.graph.indexer.delphi;

import com.arcadedb.database.Database;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.cxn.graph.indexer.MethodCallInfo;
import vn.cxn.graph.indexer.SourceCodeIndexer;
import vn.cxn.graph.indexer.antlr.delphi.*;
import vn.cxn.graph.indexer.antlr.sql.*;
import vn.cxn.graph.indexer.sql.SqlIndexer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bộ quét mã nguồn dành riêng cho Delphi / Object Pascal.
 * Sử dụng kiến trúc lai (Hybrid): kết hợp bộ phân tích cú pháp ANTLR4 (cho
 * Pascal tiêu chuẩn)
 * và phân tích Regex (để xử lý mở rộng Object Pascal và trích xuất SQL nhúng).
 */
@Component
public class DelphiIndexer implements SourceCodeIndexer {
    private static final Logger log = LoggerFactory.getLogger(DelphiIndexer.class);

    @Override
    public boolean supports(String fileExtension) {
        return "pas".equalsIgnoreCase(fileExtension) || "dpr".equalsIgnoreCase(fileExtension);
    }

    @Override
    public void index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls) throws Exception {
        String targetFilepath = file.toAbsolutePath().toString();
        log.info("Bắt đầu quét file Delphi: {}", targetFilepath);

        String content = Files.readString(file, StandardCharsets.UTF_8);

        // 1. Phân tích bằng Regex (Chắc chắn chạy được và trích xuất được SQL nhúng)
        DelphiMetadata metadata = analyzeWithRegex(content);

        // 2. Phân tích bằng ANTLR (Thử sức nếu cú pháp chuẩn Pascal)
        try {
            PascalLexer lexer = new PascalLexer(CharStreams.fromPath(file, StandardCharsets.UTF_8));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            PascalParser parser = new PascalParser(tokens);
            // Tắt báo lỗi console mặc định để đỡ trôi log nếu parse lỗi
            parser.removeErrorListeners();
            ParseTree tree = parser.program();

            ParseTreeWalker walker = new ParseTreeWalker();
            DelphiParseListener listener = new DelphiParseListener();
            walker.walk(listener, tree);

            // Bổ sung dữ liệu quét từ ANTLR vào metadata
            if (listener.unitName != null) {
                metadata.unitName = listener.unitName;
            }
            metadata.methods.addAll(listener.methods);
        } catch (Exception e) {
            log.debug(
                    "Bộ parse ANTLR4 Pascal bỏ qua hoặc lỗi cú pháp (do tính năng Object Pascal), chuyển sang chế độ phân tích Regex thuần túy cho file: {}",
                    file.getFileName());
        }

        // 3. Import kết quả vào ArcadeDB
        importToDatabase(metadata, targetFilepath, db, pendingMethodCalls);
    }

    private DelphiMetadata analyzeWithRegex(String content) {
        DelphiMetadata metadata = new DelphiMetadata();

        // A. Trích xuất Unit Name
        Pattern unitPattern = Pattern.compile("(?i)\\b(unit|program)\\s+([a-zA-Z0-9_]+)\\b");
        Matcher unitMatcher = unitPattern.matcher(content);
        if (unitMatcher.find()) {
            metadata.unitName = unitMatcher.group(2);
        } else {
            metadata.unitName = "UnknownUnit";
        }

        // B. Trích xuất Các hàm / Procedure trong phần implementation
        // Ví dụ: procedure TForm1.Button1Click(Sender: TObject);
        Pattern methodPattern = Pattern.compile("(?i)\\b(procedure|function)\\s+([a-zA-Z0-9_\\.]+)\\s*(\\([^)]*\\))?");
        Matcher methodMatcher = methodPattern.matcher(content);
        while (methodMatcher.find()) {
            String fullMethodName = methodMatcher.group(2); // e.g. TForm1.Button1Click
            metadata.methods.add(fullMethodName);
        }

        // C. Trích xuất và phân tích câu lệnh SQL nhúng
        // Pascal string literal regex: '([^']*(?:''[^']*)*)'
        Pattern stringPattern = Pattern.compile("'([^']*(?:''[^']*)*)'");
        Matcher stringMatcher = stringPattern.matcher(content);

        // Ta gom các chuỗi SQL tìm thấy theo từng dòng hoặc ngữ cảnh
        while (stringMatcher.find()) {
            String rawString = stringMatcher.group(1);
            String cleanString = rawString.replace("''", "'").trim();

            if (isSqlQuery(cleanString)) {
                Set<String> tables = parseSqlTables(cleanString);
                if (!tables.isEmpty()) {
                    // Liên kết các bảng tìm thấy vào các method đang quét
                    metadata.embeddedQueries.add(new SqlQueryInfo(cleanString, tables));
                }
            }
        }

        return metadata;
    }

    private boolean isSqlQuery(String text) {
        if (text == null || text.length() < 10)
            return false;
        String upper = text.toUpperCase();
        return upper.startsWith("SELECT ") || upper.startsWith("INSERT ") ||
                upper.startsWith("UPDATE ") || upper.startsWith("DELETE ") ||
                upper.contains("SELECT ") && upper.contains(" FROM ");
    }

    private Set<String> parseSqlTables(String sql) {
        Set<String> tables = new HashSet<>();
        // 1. Thử phân tích bằng TSqlParser của ANTLR4
        try {
            CharStream input = CharStreams.fromString(sql);
            TSqlLexer lexer = new TSqlLexer(input);
            lexer.removeErrorListeners();
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            TSqlParser parser = new TSqlParser(tokens);
            parser.removeErrorListeners();
            ParseTree tree = parser.tsql_file();

            ParseTreeWalker walker = new ParseTreeWalker();
            SqlIndexer.SqlParseListener sqlListener = new SqlIndexer.SqlParseListener();
            walker.walk(sqlListener, tree);
            tables.addAll(sqlListener.getTables());
        } catch (Exception e) {
            // 2. Nếu parse SQL lỗi (do chuỗi query bị cắt khúc), dùng regex fallback
            Pattern p = Pattern.compile("(?i)\\b(from|join|into|update)\\s+([a-zA-Z0-9_\\[\\]\\.]+)");
            Matcher m = p.matcher(sql);
            while (m.find()) {
                String table = m.group(2).replace("[", "").replace("]", "").replace("`", "").trim();
                if (!table.toUpperCase()
                        .matches("SELECT|INSERT|UPDATE|DELETE|WHERE|AND|OR|ON|LEFT|RIGHT|INNER|CROSS|OUTER|JOIN")) {
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    private void importToDatabase(DelphiMetadata metadata, String filepath, Database db,
            List<MethodCallInfo> pendingMethodCalls) {
        String unitName = metadata.unitName;

        // MERGE Unit Class node
        db.command("cypher",
                "MERGE (c:Class {name: $name}) " +
                        "SET c.package = 'delphi_unit', c.filepath = $filepath, c.description = 'Delphi Unit Container'",
                Map.of("name", unitName, "filepath", filepath));

        // Import Methods
        for (String methodFqName : metadata.methods) {
            String shortName = methodFqName;
            String className = unitName;

            if (methodFqName.contains(".")) {
                int dotIdx = methodFqName.lastIndexOf('.');
                className = methodFqName.substring(0, dotIdx);
                shortName = methodFqName.substring(dotIdx + 1);

                // MERGE Class/Form node
                db.command("cypher",
                        "MERGE (c:Class {name: $className}) " +
                                "SET c.package = 'delphi_class', c.filepath = $filepath, c.description = 'Delphi Object/Form Class' "
                                +
                                "WITH c " +
                                "MERGE (m:Method {name: $fqName}) " +
                                "SET m.shortName = $shortName, m.className = $className, m.description = 'Delphi Method' "
                                +
                                "MERGE (c)-[:CONTAINS]->(m)",
                        Map.of("className", className, "filepath", filepath, "fqName", methodFqName, "shortName",
                                shortName));
            } else {
                // Global unit function/procedure
                db.command("cypher",
                        "MATCH (c:Class {name: $unitName, filepath: $filepath}) " +
                                "MERGE (m:Method {name: $fqName}) " +
                                "SET m.shortName = $shortName, m.className = $unitName, m.description = 'Delphi Procedure/Function' "
                                +
                                "MERGE (c)-[:CONTAINS]->(m)",
                        Map.of("unitName", unitName, "filepath", filepath, "fqName", methodFqName, "shortName",
                                shortName));
            }

            // Liên kết SQL Queries vào method hiện tại (Best-effort mapping)
            for (SqlQueryInfo query : metadata.embeddedQueries) {
                // Giả định đơn giản: Các query trong file liên kết tới các method được khai báo
                // Để chính xác hơn, ta MERGE câu lệnh SQL và link sang Table
                for (String table : query.tables) {
                    db.command("cypher",
                            "MERGE (t:Table {name: $tableName}) " +
                                    "SET t.filepath = $filepath " +
                                    "WITH t " +
                                    "MATCH (m:Method {name: $fqName}) " +
                                    "MERGE (m)-[:QUERIES {sql: $sql}]->(t)",
                            Map.of("tableName", table, "filepath", filepath, "fqName", methodFqName, "sql", query.sql));
                }
            }
        }
    }

    public static class DelphiParseListener extends PascalBaseListener {
        public String unitName;
        public final Set<String> methods = new HashSet<>();

        @Override
        public void enterProgramHeading(PascalParser.ProgramHeadingContext ctx) {
            if (ctx.identifier() != null) {
                unitName = ctx.identifier().getText();
            }
        }

        @Override
        public void enterProcedureDeclaration(PascalParser.ProcedureDeclarationContext ctx) {
            if (ctx.identifier() != null) {
                methods.add(ctx.identifier().getText());
            }
        }

        @Override
        public void enterFunctionDeclaration(PascalParser.FunctionDeclarationContext ctx) {
            if (ctx.identifier() != null) {
                methods.add(ctx.identifier().getText());
            }
        }
    }

    private static class DelphiMetadata {
        public String unitName;
        public final Set<String> methods = new LinkedHashSet<>();
        public final List<SqlQueryInfo> embeddedQueries = new ArrayList<>();
    }

    private static class SqlQueryInfo {
        public String sql;
        public Set<String> tables;

        public SqlQueryInfo(String sql, Set<String> tables) {
            this.sql = sql;
            this.tables = tables;
        }
    }
}
