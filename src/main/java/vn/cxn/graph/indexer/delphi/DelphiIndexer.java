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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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

    private static final Pattern UNIT_PATTERN = Pattern.compile("(?i)\\b(unit|program)\\s+([a-zA-Z0-9_]+)\\b");
    private static final Pattern METHOD_HEADER_PATTERN = Pattern.compile("(?i)\\b(procedure|function)\\s+([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)?)\\s*(\\([^)]*\\))?\\s*(?::\\s*[a-zA-Z0-9_\\[\\]\\.]+)?\\s*;");
    private static final Pattern SQL_OPERATION_PATTERN = Pattern.compile("(?i)(?:([a-zA-Z0-9_]+)\\.)?SQL\\.(Add|Text|Strings\\s*\\[\\s*\\d+\\s*\\]|Insert)\\s*(?::=|\\()\\s*('([^']*(?:''[^']*)*)')");
    private static final Pattern RAW_STRING_PATTERN = Pattern.compile("'([^']*(?:''[^']*)*)'");
    private static final Pattern FALLBACK_SQL_PATTERN = Pattern.compile("(?i)\\b(from|join|into|update)\\s+([a-zA-Z0-9_\\[\\]\\.]+)");

    private static final String DEFAULT_QUERY_KEY = "";

    @Override
    public boolean supports(String fileExtension) {
        return "pas".equalsIgnoreCase(fileExtension) || "dpr".equalsIgnoreCase(fileExtension);
    }

    @Override
    public void index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls) throws Exception {
        String targetFilepath = file.toAbsolutePath().toString();
        log.info("Starting Delphi scan: {}", targetFilepath);

        String content = readFileContent(file);

        // 1. Phân tích bằng Regex (Chắc chắn chạy được và trích xuất được SQL nhúng)
        DelphiMetadata metadata = analyzeWithRegex(content);

        // 2. Phân tích bằng ANTLR (Thử sức nếu cú pháp chuẩn Pascal)
        try {
            PascalLexer lexer = new PascalLexer(CharStreams.fromString(content));
            lexer.removeErrorListeners();
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
            for (String method : listener.methods) {
                metadata.methodsMap.putIfAbsent(method, new DelphiMethodInfo(method));
            }
        } catch (Exception e) {
            log.debug(
                    "Bộ parse ANTLR4 Pascal bỏ qua hoặc lỗi cú pháp (do tính năng Object Pascal), chuyển sang chế độ phân tích Regex thuần túy cho file: {}",
                    file.getFileName());
        }

        // 3. Import kết quả vào ArcadeDB
        importToDatabase(metadata, targetFilepath, db, pendingMethodCalls);

        // 4. Nếu là tệp cấu trúc dự án (.dpr), quét mệnh đề uses để thiết lập ánh xạ Unit/Form với tệp pas thực tế
        if (file.getFileName().toString().toLowerCase().endsWith(".dpr")) {
            parseDprUsesClause(file, content, db);
        }
    }

    private String readFileContent(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2) {
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
            }
            if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
            }
        }
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            CharBuffer cb = decoder.decode(buf);
            return cb.toString();
        } catch (Exception e) {
            try {
                return new String(bytes, Charset.forName("windows-1252"));
            } catch (Exception ex) {
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
        }
    }

    DelphiMetadata analyzeWithRegex(String content) {
        DelphiMetadata metadata = new DelphiMetadata();

        // A. Trích xuất Unit Name
        Matcher unitMatcher = UNIT_PATTERN.matcher(content);
        if (unitMatcher.find()) {
            metadata.unitName = unitMatcher.group(2);
        } else {
            metadata.unitName = "UnknownUnit";
        }

        // B. Phân vùng phần implementation (nếu có)
        String implementationContent = content;
        int implIndex = content.toLowerCase().indexOf("implementation");
        int implStartOffset = 0;
        if (implIndex != -1) {
            implementationContent = content.substring(implIndex);
            implStartOffset = implIndex;
        }

        // C. Trích xuất các Method Header trong phần implementation
        List<MethodHeader> headers = new ArrayList<>();
        Matcher headerMatcher = METHOD_HEADER_PATTERN.matcher(implementationContent);
        while (headerMatcher.find()) {
            String fullMethodName = headerMatcher.group(2);
            headers.add(new MethodHeader(fullMethodName, headerMatcher.start(), headerMatcher.end()));
        }

        // D. Phân chia Method Body và trích xuất SQL cho từng Method
        int length = implementationContent.length();
        for (int i = 0; i < headers.size(); i++) {
            MethodHeader currentHeader = headers.get(i);
            int bodyStart = currentHeader.end;
            int bodyEnd = length;

            if (i < headers.size() - 1) {
                bodyEnd = headers.get(i + 1).start;
            } else {
                // Với method cuối cùng, cắt bớt phần initialization/finalization/end. nếu có
                Matcher endMatcher = Pattern.compile("(?i)\\b(initialization|finalization|end\\.)\\b").matcher(implementationContent);
                if (endMatcher.find(bodyStart)) {
                    bodyEnd = endMatcher.start();
                }
            }

            String bodyText = implementationContent.substring(bodyStart, bodyEnd);
            DelphiMethodInfo methodInfo = new DelphiMethodInfo(currentHeader.name);
            extractQueriesFromMethodBody(bodyText, methodInfo);
            metadata.methodsMap.put(currentHeader.name, methodInfo);
        }

        return metadata;
    }

    private void extractQueriesFromMethodBody(String bodyText, DelphiMethodInfo methodInfo) {
        // Lưu trữ các khoảng chỉ số (start, end) của các chuỗi literal đã xử lý để tránh trùng lặp với fallback
        List<int[]> processedRanges = new ArrayList<>();

        // 1. Quét các câu lệnh SQL được gán hoặc thêm trực tiếp vào đối tượng SQL
        Matcher sqlMatcher = SQL_OPERATION_PATTERN.matcher(bodyText);
        Map<String, StringBuilder> activeQueryBuilders = new HashMap<>();
        int lastMatchEnd = 0;

        while (sqlMatcher.find()) {
            String varName = sqlMatcher.group(1);
            if (varName == null) {
                varName = DEFAULT_QUERY_KEY;
            } else {
                varName = varName.toLowerCase();
            }

            String operation = sqlMatcher.group(2);
            int literalStartInMatch = sqlMatcher.start(3);
            int literalEndInMatch = sqlMatcher.end(3);

            // Kiểm tra xem có lệnh SQL.Clear hoặc gán lại Text giữa lần trùng khớp trước và lần này không
            String textBetween = bodyText.substring(lastMatchEnd, sqlMatcher.start());
            if (containsSqlClearOrReset(textBetween, varName) || "Text".equalsIgnoreCase(operation)) {
                saveActiveQuery(activeQueryBuilders, varName, methodInfo);
            }

            // Đọc phần chuỗi literal đầu tiên
            String firstPart = sqlMatcher.group(4);
            String cleanFirstPart = firstPart.replace("''", "'");

            // Đọc tiếp các phần chuỗi literal nối nhau bằng toán tử +
            String concatenatedParts = readConcatenatedString(bodyText, literalEndInMatch, processedRanges);
            String fullSegment = cleanFirstPart + concatenatedParts;

            // Đánh dấu chuỗi đầu tiên đã được xử lý
            processedRanges.add(new int[]{sqlMatcher.start(3), literalEndInMatch});

            // Ghi nhận vào builder hiện tại
            StringBuilder builder = activeQueryBuilders.computeIfAbsent(varName, k -> new StringBuilder());
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(fullSegment);

            lastMatchEnd = sqlMatcher.end();
        }

        // Lưu toàn bộ các câu lệnh SQL còn lại từ các builder hoạt động
        for (String varName : activeQueryBuilders.keySet()) {
            saveActiveQuery(activeQueryBuilders, varName, methodInfo);
        }

        // 2. Fallback: Quét các chuỗi literal tự do thỏa mãn isSqlQuery và chưa được xử lý
        Matcher literalMatcher = RAW_STRING_PATTERN.matcher(bodyText);
        while (literalMatcher.find()) {
            int start = literalMatcher.start();
            int end = literalMatcher.end();

            // Nếu chuỗi này nằm trong vùng đã xử lý trước đó, bỏ qua
            if (isRangeProcessed(start, processedRanges)) {
                continue;
            }

            String rawString = literalMatcher.group(1);
            String cleanString = rawString.replace("''", "'");

            // Đọc tiếp các phần chuỗi literal nối nhau bằng toán tử +
            String concatenatedParts = readConcatenatedString(bodyText, end, processedRanges);
            String fullQuery = (cleanString + concatenatedParts).trim();

            // Đánh dấu các vùng đã xử lý
            processedRanges.add(new int[]{start, end});

            if (isSqlQuery(fullQuery)) {
                Set<String> tables = parseSqlTables(fullQuery);
                if (!tables.isEmpty()) {
                    methodInfo.queries.add(new SqlQueryInfo(fullQuery, tables));
                }
            }
        }
    }

    private boolean containsSqlClearOrReset(String text, String varName) {
        String upperText = text.toUpperCase();
        if (DEFAULT_QUERY_KEY.equals(varName)) {
            return upperText.contains("SQL.CLEAR");
        }
        String varUpper = varName.toUpperCase();
        return upperText.contains(varUpper + ".SQL.CLEAR") || upperText.contains("SQL.CLEAR");
    }

    private void saveActiveQuery(Map<String, StringBuilder> activeQueryBuilders, String varName, DelphiMethodInfo methodInfo) {
        StringBuilder builder = activeQueryBuilders.get(varName);
        if (builder != null && builder.length() > 0) {
            String sql = builder.toString().trim();
            if (isSqlQuery(sql)) {
                Set<String> tables = parseSqlTables(sql);
                if (!tables.isEmpty()) {
                    methodInfo.queries.add(new SqlQueryInfo(sql, tables));
                }
            }
            builder.setLength(0);
        }
    }

    private String readConcatenatedString(String bodyText, int startIndex, List<int[]> processedRanges) {
        StringBuilder sb = new StringBuilder();
        int i = startIndex;
        int len = bodyText.length();

        while (i < len) {
            char c = bodyText.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // Bỏ qua comments
            if (c == '{') {
                while (i < len && bodyText.charAt(i) != '}') {
                    i++;
                }
                if (i < len) i++;
                continue;
            }
            if (c == '/' && i + 1 < len && bodyText.charAt(i + 1) == '/') {
                while (i < len && bodyText.charAt(i) != '\n' && bodyText.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '(' && i + 1 < len && bodyText.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(bodyText.charAt(i) == '*' && bodyText.charAt(i + 1) == ')')) {
                    i++;
                }
                i += 2;
                continue;
            }

            // Toán tử +
            if (c == '+') {
                i++;
                continue;
            }

            // Đọc chuỗi literal tiếp theo
            if (c == '\'') {
                int startQuote = i;
                i++;
                StringBuilder literalContent = new StringBuilder();
                while (i < len) {
                    if (bodyText.charAt(i) == '\'') {
                        if (i + 1 < len && bodyText.charAt(i + 1) == '\'') {
                            literalContent.append('\'');
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        literalContent.append(bodyText.charAt(i));
                        i++;
                    }
                }
                int endQuote = i;
                processedRanges.add(new int[]{startQuote, endQuote});
                sb.append(literalContent.toString().replace("''", "'"));
                continue;
            }

            break;
        }
        return sb.toString();
    }

    private boolean isRangeProcessed(int start, List<int[]> processedRanges) {
        for (int[] range : processedRanges) {
            if (start >= range[0] && start < range[1]) {
                return true;
            }
        }
        return false;
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
        try {
            CharStream input = CharStreams.fromString(sql);
            TSqlLexer lexer = new TSqlLexer(input);
            lexer.removeErrorListeners();
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            TSqlParser parser = new TSqlParser(tokens);
            parser.removeErrorListeners();
            ParseTree tree = parser.tsql_file();

            if (parser.getNumberOfSyntaxErrors() > 0) {
                throw new RuntimeException("TSqlParser syntax errors: " + parser.getNumberOfSyntaxErrors());
            }

            ParseTreeWalker walker = new ParseTreeWalker();
            SqlIndexer.SqlParseListener sqlListener = new SqlIndexer.SqlParseListener();
            walker.walk(sqlListener, tree);
            tables.addAll(sqlListener.getTables());
        } catch (Exception e) {
            Matcher m = FALLBACK_SQL_PATTERN.matcher(sql);
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

        // Import Methods & Queries
        for (DelphiMethodInfo methodInfo : metadata.methodsMap.values()) {
            String methodFqName = methodInfo.fqName;
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
                                "SET m.shortName = $shortName, m.className = $className, m.description = 'Delphi Method', m.filepath = $filepath "
                                +
                                "MERGE (c)-[:CONTAINS]->(m)",
                        Map.of("className", className, "filepath", filepath, "fqName", methodFqName, "shortName",
                                shortName));
            } else {
                // Global unit function/procedure
                db.command("cypher",
                        "MATCH (c:Class {name: $unitName, filepath: $filepath}) " +
                                "MERGE (m:Method {name: $fqName}) " +
                                "SET m.shortName = $shortName, m.className = $unitName, m.description = 'Delphi Procedure/Function', m.filepath = $filepath "
                                +
                                "MERGE (c)-[:CONTAINS]->(m)",
                        Map.of("unitName", unitName, "filepath", filepath, "fqName", methodFqName, "shortName",
                                shortName));
            }

            // Liên kết SQL Queries vào method hiện tại (Chính xác theo Method-scope)
            for (SqlQueryInfo query : methodInfo.queries) {
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

    private void parseDprUsesClause(Path dprFile, String content, Database db) {
        Path projectDir = dprFile.getParent();
        if (projectDir == null) {
            return;
        }
        // Tìm mệnh đề uses
        Matcher usesMatcher = Pattern.compile("(?i)\\buses\\b([\\s\\S]+?);").matcher(content);
        if (usesMatcher.find()) {
            String usesBlock = usesMatcher.group(1);
            // Regex trích xuất: UnitName in 'RelativePath.pas' {FormName}
            Pattern unitPattern = Pattern.compile("(?i)\\b([a-zA-Z0-9_]+)\\s+in\\s+'([^']+)'(?:\\s*\\{([^}]+)\\})?");
            Matcher unitMatcher = unitPattern.matcher(usesBlock);
            
            while (unitMatcher.find()) {
                String unitName = unitMatcher.group(1);
                String relPath = unitMatcher.group(2);
                String formName = unitMatcher.group(3);
                
                String normRelPath = relPath.replace('\\', '/');
                Path pasFilepath = projectDir.resolve(normRelPath).normalize();
                String absPasPath = pasFilepath.toAbsolutePath().toString();
                
                db.transaction(() -> {
                    // Ánh xạ Unit với filepath thực tế
                    db.command("cypher",
                        "MERGE (u:Class {name: $unitName}) " +
                        "SET u.package = 'delphi_unit', u.filepath = $filepath, u.description = 'Delphi Unit'",
                        Map.of("unitName", unitName, "filepath", absPasPath));
                    
                    // Ánh xạ lớp Form (ví dụ TCutProcessDispatch) với filepath thực tế
                    if (formName != null && !formName.trim().isEmpty()) {
                        String formClassName = "T" + formName.trim();
                        db.command("cypher",
                            "MERGE (f:Class {name: $formClassName}) " +
                            "SET f.package = 'delphi_form', f.filepath = $filepath, f.description = 'Delphi Form Class'",
                            Map.of("formClassName", formClassName, "filepath", absPasPath));
                    }
                });
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

    static class DelphiMetadata {
        public String unitName;
        public final Map<String, DelphiMethodInfo> methodsMap = new LinkedHashMap<>();
    }

    static class DelphiMethodInfo {
        public final String fqName;
        public final List<SqlQueryInfo> queries = new ArrayList<>();

        public DelphiMethodInfo(String fqName) {
            this.fqName = fqName;
        }
    }

    static class SqlQueryInfo {
        public final String sql;
        public final Set<String> tables;

        public SqlQueryInfo(String sql, Set<String> tables) {
            this.sql = sql;
            this.tables = tables;
        }
    }

    private static class MethodHeader {
        public final String name;
        public final int start;
        public final int end;

        public MethodHeader(String name, int start, int end) {
            this.name = name;
            this.start = start;
            this.end = end;
        }
    }
}
