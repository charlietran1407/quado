package vn.cxn.graph.indexer.typescript;

import com.arcadedb.database.Database;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.cxn.graph.indexer.SourceCodeIndexer;
import vn.cxn.graph.indexer.MethodCallInfo;
import vn.cxn.graph.indexer.antlr.typescript.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bộ quét mã nguồn dành cho ngôn ngữ TypeScript/JavaScript sử dụng bộ Parser
 * ANTLR4 thuần Java.
 */
@Component
public class TypeScriptIndexer implements SourceCodeIndexer {

    private static final Logger log = LoggerFactory.getLogger(TypeScriptIndexer.class);

    @Override
    public boolean supports(String fileExtension) {
        return "ts".equalsIgnoreCase(fileExtension) ||
                "js".equalsIgnoreCase(fileExtension) ||
                "tsx".equalsIgnoreCase(fileExtension) ||
                "jsx".equalsIgnoreCase(fileExtension);
    }

    @Override
    public void index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls) throws Exception {
        String targetFilepath = file.toAbsolutePath().toString();
        log.info("Bắt đầu quét file TS/JS bằng ANTLR4: {}", targetFilepath);

        TypeScriptParseResult result = new TypeScriptParseResult();
        result.classes = new ArrayList<>();
        result.methods = new ArrayList<>();
        result.imports = new ArrayList<>();

        try {
            TypeScriptLexer lexer = new TypeScriptLexer(CharStreams.fromPath(file, StandardCharsets.UTF_8));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            TypeScriptParser parser = new TypeScriptParser(tokens);
            ParseTree tree = parser.program();

            ParseTreeWalker walker = new ParseTreeWalker();
            TypeScriptParserBaseListener listener = createListener(result);
            walker.walk(listener, tree);
        } catch (Exception e) {
            log.error("Lỗi khi phân tích cú pháp file {}: {}", targetFilepath, e.getMessage(), e);
            result.error = e.getMessage();
            return;
        }

        // Import dữ liệu vào ArcadeDB
        importToDatabase(result, targetFilepath, db, pendingMethodCalls);
    }

    /*
     * -------------------------------------------------------------
     * Helper: extract a callable name from an expression (or a list of them)
     * -------------------------------------------------------------
     */
    /**
     * Returns a simple identifier (e.g. foo, obj.prop) from a
     * SingleExpressionContext.
     */
    private String getCalledNameSingle(TypeScriptParser.SingleExpressionContext ctx) {
        if (ctx instanceof TypeScriptParser.IdentifierExpressionContext ident) {
            return ident.identifierName().getText();
        } else if (ctx instanceof TypeScriptParser.MemberDotExpressionContext member) {
            return member.identifierName().getText();
        } else if (ctx instanceof TypeScriptParser.OptionalChainExpressionContext opt) {
            // opt.singleExpression() may be a single context or a list – delegate to the
            // overloaded method
            Object inner = opt.singleExpression();
            String name = getCalledName(inner);
            return name != null ? name : null;
        }
        return null; // not a simple call we can track
    }

    /**
     * Accepts either a single {@link SingleExpressionContext} or a {@link List}
     * (as returned by some ANTLR rules) and returns the first recognizable
     * identifier found.
     */
    private String getCalledName(Object exprOrList) {
        if (exprOrList == null) {
            return null;
        }
        if (exprOrList instanceof List) {
            for (Object o : (List<?>) exprOrList) {
                if (o instanceof TypeScriptParser.SingleExpressionContext singleExpressionContext) {
                    String name = getCalledNameSingle(singleExpressionContext);
                    if (name != null) {
                        return name;
                    }
                }
            }
            return null;
        } else if (exprOrList instanceof TypeScriptParser.SingleExpressionContext singleExpressionContext) {
            return getCalledNameSingle(singleExpressionContext);
        }
        return null;
    }

    /*
     * -------------------------------------------------------------
     * Listener implementation
     * -------------------------------------------------------------
     */
    private TypeScriptParserBaseListener createListener(TypeScriptParseResult result) {
        return new TypeScriptParserBaseListener() {
            private String currentClass = null;
            private TypeScriptMethodInfo currentMethod = null;

            @Override
            public void enterClassDeclaration(TypeScriptParser.ClassDeclarationContext ctx) {
                if (ctx.identifier() == null) {
                    return;
                }
                String className = ctx.identifier().getText();
                List<String> bases = new ArrayList<>();
                if (ctx.classHeritage() != null && ctx.classHeritage().classExtendsClause() != null) {
                    bases.add(ctx.classHeritage().classExtendsClause().typeReference().getText());
                }
                TypeScriptClassInfo classInfo = new TypeScriptClassInfo();
                classInfo.name = className;
                classInfo.bases = bases;
                classInfo.description = ""; // could be filled from JSDoc if needed
                result.classes.add(classInfo);
                currentClass = className;
            }

            @Override
            public void exitClassDeclaration(TypeScriptParser.ClassDeclarationContext ctx) {
                currentClass = null;
            }

            @Override
            public void enterMethodDeclarationExpression(TypeScriptParser.MethodDeclarationExpressionContext ctx) {
                if (ctx.propertyName() == null) {
                    return;
                }
                String methodName = ctx.propertyName().getText();
                String fqName = (currentClass != null ? currentClass + "." : "") + methodName;

                TypeScriptMethodInfo methodInfo = new TypeScriptMethodInfo();
                methodInfo.name = methodName;
                methodInfo.className = currentClass;
                methodInfo.fqName = fqName;
                methodInfo.description = "";
                methodInfo.calls = new ArrayList<>();
                result.methods.add(methodInfo);
                currentMethod = methodInfo;
            }

            @Override
            public void exitMethodDeclarationExpression(TypeScriptParser.MethodDeclarationExpressionContext ctx) {
                currentMethod = null;
            }

            @Override
            public void enterFunctionDeclaration(TypeScriptParser.FunctionDeclarationContext ctx) {
                if (ctx.identifier() == null) {
                    return;
                }
                String funcName = ctx.identifier().getText();
                String fqName = (currentClass != null ? currentClass + "." : "") + funcName;

                TypeScriptMethodInfo methodInfo = new TypeScriptMethodInfo();
                methodInfo.name = funcName;
                methodInfo.className = currentClass;
                methodInfo.fqName = fqName;
                methodInfo.description = "";
                methodInfo.calls = new ArrayList<>();
                result.methods.add(methodInfo);
                currentMethod = methodInfo;
            }

            @Override
            public void exitFunctionDeclaration(TypeScriptParser.FunctionDeclarationContext ctx) {
                currentMethod = null;
            }

            @Override
            public void enterArgumentsExpression(TypeScriptParser.ArgumentsExpressionContext ctx) {
                if (currentMethod == null) {
                    return;
                }
                // ctx.singleExpression() returns the list of expressions inside the call
                String called = getCalledName(ctx.singleExpression());
                if (called != null && !called.equals(currentMethod.name)) {
                    currentMethod.calls.add(called);
                }
            }

            @Override
            public void enterNewExpression(TypeScriptParser.NewExpressionContext ctx) {
                if (currentMethod == null) {
                    return;
                }
                // NewExpression also contains a list of expressions (the constructed type)
                String called = getCalledName(ctx.singleExpression());
                if (called != null && !called.equals(currentMethod.name)) {
                    currentMethod.calls.add(called);
                }
            }

            @Override
            public void enterImportStatement(TypeScriptParser.ImportStatementContext ctx) {
                TypeScriptParser.ImportFromBlockContext fromBlock = ctx.importFromBlock();
                if (fromBlock == null) {
                    return;
                }

                String moduleSpecifier = "";
                if (fromBlock.importFrom() != null && fromBlock.importFrom().StringLiteral() != null) {
                    moduleSpecifier = fromBlock.importFrom().StringLiteral().getText();
                } else if (fromBlock.StringLiteral() != null) {
                    moduleSpecifier = fromBlock.StringLiteral().getText();
                }
                if (moduleSpecifier.isEmpty()) {
                    return;
                }
                if (moduleSpecifier.startsWith("'") || moduleSpecifier.startsWith("\"")) {
                    moduleSpecifier = moduleSpecifier.substring(1, moduleSpecifier.length() - 1);
                }

                // Default import
                if (fromBlock.importDefault() != null && fromBlock.importDefault().aliasName() != null) {
                    String name = fromBlock.importDefault().aliasName().getText();
                    TypeScriptImportInfo imp = new TypeScriptImportInfo();
                    imp.name = moduleSpecifier + "." + name;
                    imp.asname = name;
                    result.imports.add(imp);
                }

                // Namespace import
                if (fromBlock.importNamespace() != null) {
                    TypeScriptParser.ImportNamespaceContext ns = fromBlock.importNamespace();
                    String name = "*";
                    if (ns.identifierName() != null && !ns.identifierName().isEmpty()) {
                        name = ns.identifierName(0).getText();
                    }
                    String asname = name;
                    if (ns.identifierName() != null && ns.identifierName().size() > 1) {
                        asname = ns.identifierName(1).getText();
                    }
                    TypeScriptImportInfo imp = new TypeScriptImportInfo();
                    imp.name = moduleSpecifier + "." + name;
                    imp.asname = asname;
                    result.imports.add(imp);
                }

                // Named imports
                if (fromBlock.importModuleItems() != null) {
                    TypeScriptParser.ImportModuleItemsContext items = fromBlock.importModuleItems();
                    if (items.importAliasName() != null) {
                        for (TypeScriptParser.ImportAliasNameContext item : items.importAliasName()) {
                            if (item.moduleExportName() == null) {
                                continue;
                            }
                            String origName = item.moduleExportName().getText();
                            String aliasName = origName;
                            if (item.importedBinding() != null) {
                                aliasName = item.importedBinding().getText();
                            }
                            TypeScriptImportInfo imp = new TypeScriptImportInfo();
                            imp.name = moduleSpecifier + "." + origName;
                            imp.asname = aliasName;
                            result.imports.add(imp);
                        }
                    }
                }
            }
        };
    }

    /*
     * -------------------------------------------------------------
     * Import parsed data into ArcadeDB
     * -------------------------------------------------------------
     */
    private void importToDatabase(TypeScriptParseResult result, String filepath, Database db,
            List<MethodCallInfo> pendingMethodCalls) {
        // ---- A. Import Classes -------------------------------------------------
        if (result.classes != null) {
            for (TypeScriptClassInfo clazz : result.classes) {
                db.command("cypher",
                        "MERGE (c:Class {name: $name}) " +
                                "SET c.package = $pkg, c.filepath = $filepath, c.description = $desc",
                        Map.of("name", clazz.name,
                                "pkg", "js_module",
                                "filepath", filepath,
                                "desc", clazz.description != null ? clazz.description : ""));

                // EXTENDS (inheritance)
                if (clazz.bases != null) {
                    for (String baseName : clazz.bases) {
                        db.command("cypher",
                                "MERGE (parent:Class {name: $superName}) " +
                                        "WITH parent " +
                                        "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                                        "MERGE (c)-[:EXTENDS]->(parent)",
                                Map.of("superName", baseName,
                                        "className", clazz.name,
                                        "filepath", filepath));
                    }
                }
            }
        }

        // ---- B. Import Methods / Functions ------------------------------------
        if (result.methods != null) {
            for (TypeScriptMethodInfo method : result.methods) {
                String parentClass = method.className;
                String fqName = method.fqName;
                String desc = method.description != null ? method.description : "";

                if (parentClass != null && !parentClass.isEmpty()) {
                    // Method belonging to a class
                    db.command("cypher",
                            "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                                    "MERGE (m:Method {name: $fqMethodName}) " +
                                    "SET m.shortName = $shortName, m.className = $className, m.description = $desc " +
                                    "MERGE (c)-[:CONTAINS]->(m)",
                            Map.of("className", parentClass,
                                    "filepath", filepath,
                                    "fqMethodName", fqName,
                                    "shortName", method.name,
                                    "desc", desc));
                } else {
                    // Stand‑alone function → treat as belonging to a synthetic “module” class
                    String filename = new File(filepath).getName();
                    String moduleClassName = filename.substring(0, filename.lastIndexOf('.'));

                    db.command("cypher",
                            "MERGE (c:Class {name: $moduleClassName}) " +
                                    "SET c.package = 'js_module', c.filepath = $filepath, c.description = 'JS module container' "
                                    +
                                    "WITH c " +
                                    "MERGE (m:Method {name: $fqMethodName}) " +
                                    "SET m.shortName = $shortName, m.className = $moduleClassName, m.description = $desc "
                                    +
                                    "MERGE (c)-[:CONTAINS]->(m)",
                            Map.of("moduleClassName", moduleClassName,
                                    "filepath", filepath,
                                    "fqMethodName", fqName,
                                    "shortName", method.name,
                                    "desc", desc));
                }

                // Collect method calls
                if (method.calls != null) {
                    for (String calledName : method.calls) {
                        if (!calledName.equals(method.name)) {
                            pendingMethodCalls.add(new MethodCallInfo(fqName, calledName));
                        }
                    }
                }
            }
        }

        // ---- C. Import Dependencies (imports → INJECTS/DEPENDS) ---------------
        if (result.imports != null) {
            for (TypeScriptImportInfo imp : result.imports) {
                String importName = imp.name;
                String depName = importName.contains(".")
                        ? importName.substring(importName.lastIndexOf('.') + 1)
                        : importName;

                db.command("cypher",
                        "MERGE (dep:Class {name: $depName}) " +
                                "WITH dep " +
                                "MATCH (c:Class {filepath: $filepath}) " +
                                "MERGE (c)-[:INJECTS {fieldName: $fieldName, via: 'import'}]->(dep)",
                        Map.of("depName", depName,
                                "fieldName", imp.asname != null ? imp.asname : depName,
                                "filepath", filepath));
            }
        }
    }

    /*
     * -------------------------------------------------------------
     * JSON Mapping POJOs
     * -------------------------------------------------------------
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TypeScriptParseResult {
        public List<TypeScriptClassInfo> classes;
        public List<TypeScriptMethodInfo> methods;
        public List<TypeScriptImportInfo> imports;
        public String error;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TypeScriptClassInfo {
        public String name;
        public List<String> bases;
        public String description;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TypeScriptMethodInfo {
        public String name;
        public String className;
        public String fqName;
        public String description;
        public List<String> calls;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TypeScriptImportInfo {
        public String name;
        public String asname;
    }
}