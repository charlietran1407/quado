package vn.cxn.graph.indexer.vue;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bộ quét mã nguồn dành cho framework Vue.js (Single File Component - .vue)
 * sử dụng Jsoup/String-Manipulation kết hợp với bộ Parser ANTLR4 TypeScript.
 */
@Component
public class VueIndexer implements SourceCodeIndexer {

    private static final Logger log = LoggerFactory.getLogger(VueIndexer.class);

    private static final Set<String> HTML_TAGS = new HashSet<>(Arrays.asList(
        "html", "body", "head", "link", "meta", "script", "style", "title",
        "div", "span", "p", "a", "img", "button", "input", "label", "ul", "li", "ol",
        "table", "tr", "td", "th", "thead", "tbody", "tfoot", "form", "iframe",
        "h1", "h2", "h3", "h4", "h5", "h6", "section", "header", "footer", "nav", "aside", "main",
        "template", "slot", "transition", "keep-alive", "router-view", "router-link",
        "svg", "path", "circle", "rect", "line", "g", "defs", "use",
        "br", "hr", "select", "option", "textarea", "strong", "em", "i", "b", "u", "small", "pre", "code"
    ));

    private static final Pattern TAG_PATTERN = Pattern.compile("<\\s*([A-Za-z0-9-]+)");

    @Override
    public boolean supports(String fileExtension) {
        return "vue".equalsIgnoreCase(fileExtension);
    }

    @Override
    public void index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls) throws Exception {
        String targetFilepath = file.toAbsolutePath().toString();
        log.info("Starting Vue SFC scan: {}", targetFilepath);

        String content = Files.readString(file, StandardCharsets.UTF_8);
        String filename = file.getFileName().toString();
        String componentName = filename.substring(0, filename.lastIndexOf('.'));

        // ---- 1. Bóc tách Script block và phân tích bằng ANTLR4 TypeScript ----
        String scriptContent = extractScriptContent(content);
        TypeScriptParseResult parseResult = new TypeScriptParseResult();
        parseResult.classes = new ArrayList<>();
        parseResult.methods = new ArrayList<>();
        parseResult.imports = new ArrayList<>();

        if (!scriptContent.trim().isEmpty()) {
            try {
                TypeScriptLexer lexer = new TypeScriptLexer(CharStreams.fromString(scriptContent));
                CommonTokenStream tokens = new CommonTokenStream(lexer);
                TypeScriptParser parser = new TypeScriptParser(tokens);
                ParseTree tree = parser.program();

                ParseTreeWalker walker = new ParseTreeWalker();
                TypeScriptParserBaseListener listener = createListener(parseResult, componentName);
                walker.walk(listener, tree);
            } catch (Exception e) {
                log.error("Error parsing script in file {}: {}", targetFilepath, e.getMessage());
                parseResult.error = e.getMessage();
            }
        }

        // ---- 2. Bóc tách Template block và tìm các Component lồng nhau ----
        String templateContent = extractTemplateContent(content);
        Set<String> renderedComponents = extractRenderedComponents(templateContent);

        // ---- 3. Lưu thông tin vào Database ----
        importToDatabase(parseResult, renderedComponents, componentName, targetFilepath, db, pendingMethodCalls);
    }

    private String extractScriptContent(String content) {
        int scriptStart = content.indexOf("<script");
        if (scriptStart == -1) return "";
        int closeTagEnd = content.indexOf(">", scriptStart);
        if (closeTagEnd == -1) return "";
        int scriptEnd = content.indexOf("</script>", closeTagEnd);
        if (scriptEnd == -1) return "";
        return content.substring(closeTagEnd + 1, scriptEnd);
    }

    private String extractTemplateContent(String content) {
        int templateStart = content.indexOf("<template");
        if (templateStart == -1) return "";
        int closeTagEnd = content.indexOf(">", templateStart);
        if (closeTagEnd == -1) return "";
        int templateEnd = content.lastIndexOf("</template>");
        if (templateEnd == -1 || templateEnd < closeTagEnd) return "";
        return content.substring(closeTagEnd + 1, templateEnd);
    }

    private Set<String> extractRenderedComponents(String templateContent) {
        Set<String> components = new HashSet<>();
        if (templateContent.isEmpty()) return components;

        Matcher matcher = TAG_PATTERN.matcher(templateContent);
        while (matcher.find()) {
            String tagName = matcher.group(1);
            if (HTML_TAGS.contains(tagName.toLowerCase())) {
                continue;
            }
            // Quy đổi kebab-case (ví dụ: my-component) sang PascalCase (MyComponent)
            if (tagName.contains("-")) {
                tagName = kebabToPascal(tagName);
            }
            components.add(tagName);
        }
        return components;
    }

    private String kebabToPascal(String kebab) {
        if (kebab == null || kebab.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char c : kebab.toCharArray()) {
            if (c == '-') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    sb.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private String getCalledNameSingle(TypeScriptParser.SingleExpressionContext ctx) {
        if (ctx instanceof TypeScriptParser.IdentifierExpressionContext ident) {
            return ident.identifierName().getText();
        } else if (ctx instanceof TypeScriptParser.MemberDotExpressionContext member) {
            return member.identifierName().getText();
        } else if (ctx instanceof TypeScriptParser.OptionalChainExpressionContext opt) {
            return getCalledName(opt.singleExpression());
        }
        return null;
    }

    private String getCalledName(Object exprOrList) {
        if (exprOrList == null) return null;
        if (exprOrList instanceof List) {
            for (Object o : (List<?>) exprOrList) {
                if (o instanceof TypeScriptParser.SingleExpressionContext sec) {
                    String name = getCalledNameSingle(sec);
                    if (name != null) return name;
                }
            }
            return null;
        } else if (exprOrList instanceof TypeScriptParser.SingleExpressionContext sec) {
            return getCalledNameSingle(sec);
        }
        return null;
    }

    private TypeScriptParserBaseListener createListener(TypeScriptParseResult result, String componentName) {
        return new TypeScriptParserBaseListener() {
            private String currentClass = componentName;
            private TypeScriptMethodInfo currentMethod = null;

            @Override
            public void enterClassDeclaration(TypeScriptParser.ClassDeclarationContext ctx) {
                if (ctx.identifier() == null) return;
                String className = ctx.identifier().getText();
                List<String> bases = new ArrayList<>();
                if (ctx.classHeritage() != null && ctx.classHeritage().classExtendsClause() != null) {
                    bases.add(ctx.classHeritage().classExtendsClause().typeReference().getText());
                }
                TypeScriptClassInfo classInfo = new TypeScriptClassInfo();
                classInfo.name = className;
                classInfo.bases = bases;
                classInfo.description = "";
                result.classes.add(classInfo);
                currentClass = className;
            }

            @Override
            public void exitClassDeclaration(TypeScriptParser.ClassDeclarationContext ctx) {
                currentClass = componentName;
            }

            @Override
            public void enterMethodDeclarationExpression(TypeScriptParser.MethodDeclarationExpressionContext ctx) {
                if (ctx.propertyName() == null) return;
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
                if (ctx.identifier() == null) return;
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
                if (currentMethod == null) return;
                String called = getCalledName(ctx.singleExpression());
                if (called != null && !called.equals(currentMethod.name)) {
                    currentMethod.calls.add(called);
                }
            }

            @Override
            public void enterNewExpression(TypeScriptParser.NewExpressionContext ctx) {
                if (currentMethod == null) return;
                String called = getCalledName(ctx.singleExpression());
                if (called != null && !called.equals(currentMethod.name)) {
                    currentMethod.calls.add(called);
                }
            }

            @Override
            public void enterImportStatement(TypeScriptParser.ImportStatementContext ctx) {
                TypeScriptParser.ImportFromBlockContext fromBlock = ctx.importFromBlock();
                if (fromBlock == null) return;

                String moduleSpecifier = "";
                if (fromBlock.importFrom() != null && fromBlock.importFrom().StringLiteral() != null) {
                    moduleSpecifier = fromBlock.importFrom().StringLiteral().getText();
                } else if (fromBlock.StringLiteral() != null) {
                    moduleSpecifier = fromBlock.StringLiteral().getText();
                }
                if (moduleSpecifier.isEmpty()) return;
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
                            if (item.moduleExportName() == null) continue;
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

    private void importToDatabase(TypeScriptParseResult result, Set<String> renderedComponents, String componentName,
                                   String filepath, Database db, List<MethodCallInfo> pendingMethodCalls) {
        
        // A. Tạo nút Class đại diện cho Component Vue này
        db.command("cypher",
                "MERGE (c:Class {name: $name}) " +
                "SET c.package = 'vue_component', c.filepath = $filepath, c.description = $desc",
                Map.of("name", componentName,
                        "filepath", filepath,
                        "desc", "Vue Single File Component"));

        // B. Lưu các Class khai báo bên trong (nếu có)
        if (result.classes != null) {
            for (TypeScriptClassInfo clazz : result.classes) {
                if (clazz.name.equals(componentName)) continue; // đã lưu ở trên
                db.command("cypher",
                        "MERGE (c:Class {name: $name}) " +
                        "SET c.package = 'vue_component', c.filepath = $filepath, c.description = $desc",
                        Map.of("name", clazz.name,
                                "filepath", filepath,
                                "desc", clazz.description != null ? clazz.description : ""));

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

        // C. Lưu các Methods / Functions
        if (result.methods != null) {
            for (TypeScriptMethodInfo method : result.methods) {
                String parentClass = method.className != null ? method.className : componentName;
                String fqName = method.fqName.contains(".") ? method.fqName : parentClass + "." + method.name;
                String desc = method.description != null ? method.description : "";

                db.command("cypher",
                        "MERGE (c:Class {name: $className, filepath: $filepath}) " +
                        "WITH c " +
                        "MERGE (m:Method {name: $fqMethodName}) " +
                        "SET m.shortName = $shortName, m.className = $className, m.description = $desc, m.filepath = $filepath " +
                        "MERGE (c)-[:CONTAINS]->(m)",
                        Map.of("className", parentClass,
                                "filepath", filepath,
                                "fqMethodName", fqName,
                                "shortName", method.name,
                                "desc", desc));

                if (method.calls != null) {
                    for (String calledName : method.calls) {
                        if (!calledName.equals(method.name)) {
                            pendingMethodCalls.add(new MethodCallInfo(fqName, calledName));
                        }
                    }
                }
            }
        }

        // D. Lưu Dependencies từ Import block (INJECTS)
        if (result.imports != null) {
            for (TypeScriptImportInfo imp : result.imports) {
                String importName = imp.name;
                String depName = importName.contains(".")
                        ? importName.substring(importName.lastIndexOf('.') + 1)
                        : importName;

                // Bỏ qua các import thư viện node_modules dạng css hoặc json
                if (importName.endsWith(".css") || importName.endsWith(".json")) continue;

                db.command("cypher",
                        "MERGE (dep:Class {name: $depName}) " +
                        "WITH dep " +
                        "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                        "MERGE (c)-[:INJECTS {fieldName: $fieldName, via: 'import'}]->(dep)",
                        Map.of("depName", depName,
                                "className", componentName,
                                "fieldName", imp.asname != null ? imp.asname : depName,
                                "filepath", filepath));
            }
        }

        // E. Lưu các Component được vẽ trong Template (INJECTS via template)
        for (String childComp : renderedComponents) {
            if (childComp.equals(componentName)) continue;
            db.command("cypher",
                    "MERGE (dep:Class {name: $depName}) " +
                    "WITH dep " +
                    "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                    "MERGE (c)-[:INJECTS {fieldName: $depName, via: 'template'}]->(dep)",
                    Map.of("depName", childComp,
                            "className", componentName,
                            "filepath", filepath));
        }
    }

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
