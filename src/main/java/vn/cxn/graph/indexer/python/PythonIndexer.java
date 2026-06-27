package vn.cxn.graph.indexer.python;

import com.arcadedb.database.Database;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.cxn.graph.indexer.SourceCodeIndexer;
import vn.cxn.graph.indexer.MethodCallInfo;
import vn.cxn.graph.indexer.antlr.python.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Bộ quét mã nguồn dành riêng cho Python sử dụng ANTLR Python3Parser.
 */
@Component
public class PythonIndexer implements SourceCodeIndexer {
    private static final Logger log = LoggerFactory.getLogger(PythonIndexer.class);

    @Override
    public boolean supports(String fileExtension) {
        return "py".equalsIgnoreCase(fileExtension);
    }

    @Override
    public void index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls) throws Exception {
        String targetFilepath = file.toAbsolutePath().toString();
        log.info("Starting Python scan: {}", targetFilepath);

        try {
            Python3Lexer lexer = new Python3Lexer(CharStreams.fromPath(file, StandardCharsets.UTF_8));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            Python3Parser parser = new Python3Parser(tokens);
            parser.removeErrorListeners(); // Avoid polluting console logs
            ParseTree tree = parser.file_input();

            ParseTreeWalker walker = new ParseTreeWalker();
            PythonParseListener listener = new PythonParseListener();
            walker.walk(listener, tree);

            importToDatabase(listener, targetFilepath, db, pendingMethodCalls);
        } catch (Exception e) {
            log.error("Error parsing Python file {}: {}", targetFilepath, e.getMessage(), e);
        }
    }

    private void importToDatabase(PythonParseListener listener, String filepath, Database db,
            List<MethodCallInfo> pendingMethodCalls) {
        // A. Import Classes
        for (PythonClassInfo clazz : listener.classes) {
            db.command("cypher",
                    "MERGE (c:Class {name: $name}) " +
                            "SET c.package = $pkg, c.filepath = $filepath, c.description = $desc",
                    Map.of("name", clazz.name, "pkg", "python_module", "filepath", filepath, "desc",
                            clazz.description));

            // EXTENDS (Kế thừa lớp)
            for (String baseName : clazz.bases) {
                db.command("cypher",
                        "MERGE (parent:Class {name: $superName}) " +
                                "WITH parent " +
                                "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                                "MERGE (c)-[:EXTENDS]->(parent)",
                        Map.of("superName", baseName, "className", clazz.name, "filepath", filepath));
            }
        }

        // B. Import Methods / Functions
        for (PythonMethodInfo method : listener.methods) {
            String parentClass = method.className;
            String fqName = method.fqName;
            String desc = method.description;

            if (parentClass != null && !parentClass.isEmpty()) {
                // Phương thức thuộc một Class
                db.command("cypher",
                        "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                                "MERGE (m:Method {name: $fqMethodName}) " +
                                "SET m.shortName = $shortName, m.className = $className, m.description = $desc, m.filepath = $filepath " +
                                "MERGE (c)-[:CONTAINS]->(m)",
                        Map.of("className", parentClass, "filepath", filepath, "fqMethodName", fqName, "shortName",
                                method.name, "desc", desc));
            } else {
                // Hàm tự do cấp module -> Coi như thuộc Module Class ảo mang tên file
                String filename = new File(filepath).getName();
                String moduleClassName = filename.substring(0, filename.lastIndexOf('.'));

                db.command("cypher",
                        "MERGE (c:Class {name: $moduleClassName}) " +
                                "SET c.package = 'python_module', c.filepath = $filepath, c.description = 'Python module container' "
                                +
                                "WITH c " +
                                "MERGE (m:Method {name: $fqMethodName}) " +
                                "SET m.shortName = $shortName, m.className = $moduleClassName, m.description = $desc, m.filepath = $filepath " +
                                "MERGE (c)-[:CONTAINS]->(m)",
                        Map.of("moduleClassName", moduleClassName, "filepath", filepath, "fqMethodName", fqName,
                                "shortName", method.name, "desc", desc));
            }

            // Thu thập các liên kết gọi hàm
            for (String calledName : method.calls) {
                if (!calledName.equals(method.name)) {
                    pendingMethodCalls.add(new MethodCallInfo(fqName, calledName));
                }
            }
        }

        // C. Import Thư viện phụ thuộc (Imports -> DEPENDS)
        for (PythonImportInfo imp : listener.imports) {
            String importName = imp.name;
            String depName = importName.contains(".") ? importName.substring(importName.lastIndexOf('.') + 1)
                    : importName;

            db.command("cypher",
                    "MERGE (dep:Class {name: $depName}) " +
                            "WITH dep " +
                            "MATCH (c:Class {filepath: $filepath}) " +
                            "MERGE (c)-[:INJECTS {fieldName: $fieldName, via: 'import'}]->(dep)",
                    Map.of("depName", depName, "fieldName", imp.asname != null ? imp.asname : depName, "filepath",
                            filepath));
        }
    }

    public static class PythonParseListener extends Python3ParserBaseListener {
        public final List<PythonClassInfo> classes = new ArrayList<>();
        public final List<PythonMethodInfo> methods = new ArrayList<>();
        public final List<PythonImportInfo> imports = new ArrayList<>();

        private String currentClass;
        private PythonMethodInfo currentMethodInfo;

        @Override
        public void enterClassdef(Python3Parser.ClassdefContext ctx) {
            if (ctx.name() == null)
                return;
            String className = ctx.name().getText();
            currentClass = className;

            PythonClassInfo info = new PythonClassInfo();
            info.name = className;
            info.description = "Python Class";

            if (ctx.arglist() != null) {
                for (Python3Parser.ArgumentContext arg : ctx.arglist().argument()) {
                    info.bases.add(arg.getText());
                }
            }
            classes.add(info);
        }

        @Override
        public void exitClassdef(Python3Parser.ClassdefContext ctx) {
            currentClass = null;
        }

        @Override
        public void enterFuncdef(Python3Parser.FuncdefContext ctx) {
            if (ctx.name() == null)
                return;
            String name = ctx.name().getText();
            String fqName = (currentClass != null ? currentClass + "." : "") + name;

            PythonMethodInfo info = new PythonMethodInfo();
            info.name = name;
            info.className = currentClass;
            info.fqName = fqName;
            info.description = "Python Function";

            methods.add(info);
            currentMethodInfo = info;
        }

        @Override
        public void exitFuncdef(Python3Parser.FuncdefContext ctx) {
            currentMethodInfo = null;
        }

        @Override
        public void enterAtom_expr(Python3Parser.Atom_exprContext ctx) {
            if (currentMethodInfo == null)
                return;
            String calledName = getCalledName(ctx);
            if (calledName != null) {
                currentMethodInfo.calls.add(calledName);
            }
        }

        @Override
        public void enterDotted_as_name(Python3Parser.Dotted_as_nameContext ctx) {
            if (ctx.dotted_name() == null)
                return;
            PythonImportInfo info = new PythonImportInfo();
            info.name = ctx.dotted_name().getText();
            info.asname = ctx.name() != null ? ctx.name().getText() : null;
            imports.add(info);
        }

        @Override
        public void enterImport_as_name(Python3Parser.Import_as_nameContext ctx) {
            // Xác định parent Import_from
            RuleContext parent = ctx.parent;
            while (parent != null && !(parent instanceof Python3Parser.Import_fromContext)) {
                parent = parent.parent;
            }

            if (parent instanceof Python3Parser.Import_fromContext fromCtx) {
                if (fromCtx.dotted_name() != null && ctx.name() != null && !ctx.name().isEmpty()) {
                    String fromModule = fromCtx.dotted_name().getText();
                    String importName = ctx.name(0).getText();

                    PythonImportInfo info = new PythonImportInfo();
                    info.name = fromModule + "." + importName;
                    info.asname = (ctx.AS() != null && ctx.name().size() > 1) ? ctx.name(1).getText() : null;
                    imports.add(info);
                }
            }
        }

        private String getCalledName(Python3Parser.Atom_exprContext ctx) {
            if (ctx.trailer() == null || ctx.trailer().isEmpty()) {
                return null;
            }

            boolean hasCallTrailer = false;
            for (Python3Parser.TrailerContext t : ctx.trailer()) {
                if (t.getText().startsWith("(")) {
                    hasCallTrailer = true;
                    break;
                }
            }
            if (!hasCallTrailer) {
                return null;
            }

            StringBuilder sb = new StringBuilder(ctx.atom().getText());
            for (Python3Parser.TrailerContext t : ctx.trailer()) {
                String text = t.getText();
                if (text.startsWith("(")) {
                    break;
                }
                sb.append(text);
            }

            String callPath = sb.toString();
            int lastDot = callPath.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < callPath.length() - 1) {
                return callPath.substring(lastDot + 1);
            }
            return callPath;
        }
    }

    public static class PythonClassInfo {
        public String name;
        public List<String> bases = new ArrayList<>();
        public String description;
    }

    public static class PythonMethodInfo {
        public String name;
        public String className;
        public String fqName;
        public String description;
        public List<String> calls = new ArrayList<>();
    }

    public static class PythonImportInfo {
        public String name;
        public String asname;
    }
}
