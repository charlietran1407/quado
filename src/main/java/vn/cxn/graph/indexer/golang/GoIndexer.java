package vn.cxn.graph.indexer.golang;

import com.arcadedb.database.Database;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.cxn.graph.indexer.MethodCallInfo;
import vn.cxn.graph.indexer.SourceCodeIndexer;
import vn.cxn.graph.indexer.antlr.golang.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Bộ quét mã nguồn dành riêng cho Go sử dụng ANTLR GoParser.
 */
@Component
public class GoIndexer implements SourceCodeIndexer {
    private static final Logger log = LoggerFactory.getLogger(GoIndexer.class);

    @Override
    public boolean supports(String fileExtension) {
        return "go".equalsIgnoreCase(fileExtension);
    }

    @Override
    public void index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls) throws Exception {
        String targetFilepath = file.toAbsolutePath().toString();
        log.info("Bắt đầu quét file Go bằng ANTLR4: {}", targetFilepath);

        try {
            GoLexer lexer = new GoLexer(CharStreams.fromPath(file, StandardCharsets.UTF_8));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            GoParser parser = new GoParser(tokens);
            ParseTree tree = parser.sourceFile();

            ParseTreeWalker walker = new ParseTreeWalker();
            GoParseListener listener = new GoParseListener();
            walker.walk(listener, tree);

            importToDatabase(listener, targetFilepath, db, pendingMethodCalls);
        } catch (Exception e) {
            log.error("Lỗi khi phân tích cú pháp file Go {}: {}", targetFilepath, e.getMessage(), e);
        }
    }

    private void importToDatabase(GoParseListener listener, String filepath, Database db, List<MethodCallInfo> pendingMethodCalls) {
        String packageName = listener.getPackageName() != null ? listener.getPackageName() : "main";

        // MERGE Package Class node
        db.command("cypher",
                "MERGE (c:Class {name: $name}) " +
                "SET c.package = $pkg, c.filepath = $filepath, c.description = 'Go Package container'",
                Map.of("name", packageName, "pkg", packageName, "filepath", filepath)
        );

        // Import Functions / Methods
        for (GoMethodInfo method : listener.getMethods()) {
            String className = method.className != null ? method.className : packageName;
            String fqName = method.fqName;

            if (method.className != null) {
                // Struct method: MERGE Struct Class first
                db.command("cypher",
                        "MERGE (c:Class {name: $className}) " +
                        "SET c.package = $pkg, c.filepath = $filepath, c.description = 'Go Struct' " +
                        "WITH c " +
                        "MERGE (m:Method {name: $fqName}) " +
                        "SET m.shortName = $shortName, m.className = $className, m.description = 'Go Method' " +
                        "MERGE (c)-[:CONTAINS]->(m)",
                        Map.of("className", className, "pkg", packageName, "filepath", filepath, "fqName", fqName, "shortName", method.name)
                );
            } else {
                // Global package function
                db.command("cypher",
                        "MATCH (c:Class {name: $packageName, filepath: $filepath}) " +
                        "MERGE (m:Method {name: $fqName}) " +
                        "SET m.shortName = $shortName, m.className = $packageName, m.description = 'Go Function' " +
                        "MERGE (c)-[:CONTAINS]->(m)",
                        Map.of("packageName", packageName, "filepath", filepath, "fqName", fqName, "shortName", method.name)
                );
            }

            // Pending calls
            for (String call : method.calls) {
                pendingMethodCalls.add(new MethodCallInfo(fqName, call));
            }
        }
    }

    public static class GoParseListener extends GoParserBaseListener {
        private String packageName;
        private final List<GoMethodInfo> methods = new ArrayList<>();
        private GoMethodInfo currentMethod;

        public String getPackageName() {
            return packageName;
        }

        public List<GoMethodInfo> getMethods() {
            return methods;
        }

        @Override
        public void enterPackageClause(GoParser.PackageClauseContext ctx) {
            if (ctx.packageName() != null) {
                packageName = ctx.packageName().getText();
            }
        }

        @Override
        public void enterFunctionDecl(GoParser.FunctionDeclContext ctx) {
            if (ctx.IDENTIFIER() == null) return;
            String name = ctx.IDENTIFIER().getText();
            String fqName = (packageName != null ? packageName : "main") + "." + name;

            GoMethodInfo info = new GoMethodInfo();
            info.name = name;
            info.fqName = fqName;
            methods.add(info);
            currentMethod = info;
        }

        @Override
        public void exitFunctionDecl(GoParser.FunctionDeclContext ctx) {
            currentMethod = null;
        }

        @Override
        public void enterMethodDecl(GoParser.MethodDeclContext ctx) {
            if (ctx.IDENTIFIER() == null) return;
            String name = ctx.IDENTIFIER().getText();
            String receiverType = getReceiverType(ctx.receiver());

            String fqName = (packageName != null ? packageName : "main") + ".";
            if (receiverType != null) {
                fqName += receiverType + ".";
            }
            fqName += name;

            GoMethodInfo info = new GoMethodInfo();
            info.name = name;
            info.className = receiverType;
            info.fqName = fqName;
            methods.add(info);
            currentMethod = info;
        }

        @Override
        public void exitMethodDecl(GoParser.MethodDeclContext ctx) {
            currentMethod = null;
        }

        @Override
        public void enterPrimaryExpr(GoParser.PrimaryExprContext ctx) {
            if (currentMethod == null) return;
            String calledName = getCalledName(ctx);
            if (calledName != null && !calledName.equals(currentMethod.name)) {
                currentMethod.calls.add(calledName);
            }
        }

        private String getReceiverType(GoParser.ReceiverContext receiver) {
            if (receiver == null) return null;
            String text = receiver.getText();
            text = text.replace("(", "").replace(")", "").trim();
            String[] parts = text.split("[\\s\\*]+");
            if (parts.length > 0) {
                return parts[parts.length - 1];
            }
            return null;
        }

        private String getCalledName(GoParser.PrimaryExprContext ctx) {
            if (ctx.arguments() == null || ctx.arguments().isEmpty()) {
                return null;
            }
            String fullText = ctx.getText();
            int parenIndex = fullText.indexOf('(');
            if (parenIndex > 0) {
                String callPath = fullText.substring(0, parenIndex);
                int lastDot = callPath.lastIndexOf('.');
                if (lastDot >= 0 && lastDot < callPath.length() - 1) {
                    return callPath.substring(lastDot + 1);
                }
                return callPath;
            }
            return null;
        }
    }

    public static class GoMethodInfo {
        public String name;
        public String className;
        public String fqName;
        public List<String> calls = new ArrayList<>();
    }
}
