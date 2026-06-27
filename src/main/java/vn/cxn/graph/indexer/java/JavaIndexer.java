package vn.cxn.graph.indexer.java;

import com.arcadedb.database.Database;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.cxn.graph.indexer.SourceCodeIndexer;
import vn.cxn.graph.indexer.MethodCallInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Bộ quét mã nguồn dành riêng cho ngôn ngữ Java sử dụng thư viện JavaParser.
 */
@Component
public class JavaIndexer implements SourceCodeIndexer {
    private static final Logger log = LoggerFactory.getLogger(JavaIndexer.class);

    @Override
    public boolean supports(String fileExtension) {
        return "java".equalsIgnoreCase(fileExtension);
    }

    @Override
    public void index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(file.toFile());
        String filepath = file.toAbsolutePath().toString();
        analyzeCompilationUnit(cu, filepath, db, pendingMethodCalls);
    }

    private void analyzeCompilationUnit(CompilationUnit cu, String filepath, Database db, List<MethodCallInfo> pendingMethodCalls) {
        String packageName = cu.getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .orElse("");

        // A. Phân tích Interfaces
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(ClassOrInterfaceDeclaration::isInterface)
                .forEach(iface -> {
                    String name = iface.getNameAsString();
                    String desc = cleanComment(iface.getComment().orElse(null));

                    db.command("cypher",
                            "MERGE (i:Interface {name: $name}) " +
                            "SET i.package = $pkg, i.filepath = $filepath, i.description = $desc",
                            Map.of("name", name, "pkg", packageName, "filepath", filepath, "desc", desc)
                    );
                });

        // B. Phân tích Classes
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> !c.isInterface())
                .forEach(clazz -> {
                    String className = clazz.getNameAsString();
                    String classDesc = cleanComment(clazz.getComment().orElse(null));

                    // MERGE Class node
                    db.command("cypher",
                            "MERGE (c:Class {name: $name}) " +
                            "SET c.package = $pkg, c.filepath = $filepath, c.description = $desc",
                            Map.of("name", className, "pkg", packageName, "filepath", filepath, "desc", classDesc)
                    );

                    // EXTENDS
                    clazz.getExtendedTypes().forEach(superType -> {
                        String superName = superType.getNameAsString();
                        db.command("cypher",
                                "MERGE (parent:Class {name: $superName}) " +
                                "WITH parent " +
                                "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                                "MERGE (c)-[:EXTENDS]->(parent)",
                                Map.of("superName", superName, "className", className, "filepath", filepath)
                        );
                    });

                    // IMPLEMENTS
                    clazz.getImplementedTypes().forEach(ifaceType -> {
                        String ifaceName = ifaceType.getNameAsString();
                        db.command("cypher",
                                "MERGE (i:Interface {name: $ifaceName}) " +
                                "WITH i " +
                                "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                                "MERGE (c)-[:IMPLEMENTS]->(i)",
                                Map.of("ifaceName", ifaceName, "className", className, "filepath", filepath)
                        );
                    });

                    // INJECTS (Field Injection Autowired/Inject)
                    clazz.findAll(FieldDeclaration.class).forEach(field -> {
                        boolean isInjected = field.isAnnotationPresent("Autowired") || field.isAnnotationPresent("Inject");
                        if (isInjected) {
                            String typeName = field.getElementType().asString();
                            field.getVariables().forEach(v -> {
                                String fieldName = v.getNameAsString();
                                db.command("cypher",
                                        "MERGE (dep:Class {name: $typeName}) " +
                                        "WITH dep " +
                                        "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                                        "MERGE (c)-[:INJECTS {fieldName: $fieldName, via: 'field'}]->(dep)",
                                        Map.of("typeName", typeName, "fieldName", fieldName, "className", className, "filepath", filepath)
                                );
                            });
                        }
                    });

                    // INJECTS (Constructor Injection)
                    clazz.findAll(ConstructorDeclaration.class).forEach(ctor -> {
                        ctor.getParameters().forEach(param -> {
                            String typeName = param.getType().asString();
                            String paramName = param.getNameAsString();
                            if (!typeName.isEmpty() && Character.isUpperCase(typeName.charAt(0))) {
                                db.command("cypher",
                                        "MERGE (dep:Class {name: $typeName}) " +
                                        "WITH dep " +
                                        "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                                        "MERGE (c)-[:INJECTS {fieldName: $paramName, via: 'constructor'}]->(dep)",
                                        Map.of("typeName", typeName, "paramName", paramName, "className", className, "filepath", filepath)
                                );
                            }
                        });
                    });

                    // CONTAINS Methods
                    clazz.findAll(MethodDeclaration.class).forEach(method -> {
                        String methodShortName = method.getNameAsString();
                        String fqMethodName = className + "." + methodShortName;
                        String methodDesc = cleanComment(method.getComment().orElse(null));

                        db.command("cypher",
                                "MATCH (c:Class {name: $className, filepath: $filepath}) " +
                                "MERGE (m:Method {name: $fqMethodName}) " +
                                "SET m.shortName = $shortName, m.className = $className, m.description = $desc, m.filepath = $filepath " +
                                "MERGE (c)-[:CONTAINS]->(m)",
                                Map.of("className", className, "filepath", filepath, "fqMethodName", fqMethodName, "shortName", methodShortName, "desc", methodDesc)
                        );

                        // Thu thập lời gọi hàm để giải quyết sau khi quét xong toàn bộ dự án
                        method.findAll(MethodCallExpr.class).forEach(call -> {
                            String calledName = call.getNameAsString();
                            if (!calledName.equals(methodShortName)) {
                                pendingMethodCalls.add(new MethodCallInfo(fqMethodName, calledName));
                            }
                        });
                    });
                });
    }

    private String cleanComment(Comment comment) {
        if (comment == null) return "";
        return comment.getContent()
                .replaceAll("(?m)^\\s*\\*\\s?", "")
                .trim();
    }
}
