package vn.cxn.graph.mcp;

import com.arcadedb.database.Database;
import com.arcadedb.query.sql.executor.ResultSet;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import vn.cxn.graph.service.ArcadeDbService;

import java.util.*;

@Component
public class McpGraphTools {

    private final ArcadeDbService arcadeDbService;

    public McpGraphTools(ArcadeDbService arcadeDbService) {
        this.arcadeDbService = arcadeDbService;
    }

    @McpTool(description = "Lấy thông tin tổng quan của đồ thị mã nguồn (số lượng Class, Interface, Method)")
    public Map<String, Object> getGraphSummary() {
        Database db = arcadeDbService.getDatabase();
        Map<String, Object> summary = new HashMap<>();

        db.transaction(() -> {
            try (ResultSet rsClass = db.query("cypher", "MATCH (c:Class) RETURN count(c) as total");
                 ResultSet rsInterface = db.query("cypher", "MATCH (i:Interface) RETURN count(i) as total");
                 ResultSet rsMethod = db.query("cypher", "MATCH (m:Method) RETURN count(m) as total")) {
                
                long totalClass = rsClass.hasNext() ? ((Number) rsClass.next().getProperty("total")).longValue() : 0;
                long totalInterface = rsInterface.hasNext() ? ((Number) rsInterface.next().getProperty("total")).longValue() : 0;
                long totalMethod = rsMethod.hasNext() ? ((Number) rsMethod.next().getProperty("total")).longValue() : 0;

                summary.put("totalClasses", totalClass);
                summary.put("totalInterfaces", totalInterface);
                summary.put("totalMethods", totalMethod);
            }
        });

        return summary;
    }

    @McpTool(description = "Liệt kê toàn bộ danh sách các Class và Interface trong cơ sở dữ liệu")
    public List<Map<String, Object>> getAllClasses() {
        Database db = arcadeDbService.getDatabase();
        List<Map<String, Object>> classes = new ArrayList<>();

        db.transaction(() -> {
            try (ResultSet rs = db.query("cypher", 
                    "MATCH (c:Class) " +
                    "RETURN c.name as name, c.package as package, c.filepath as filepath, 'Class' as type")) {
                while (rs.hasNext()) {
                    classes.add(new HashMap<>(rs.next().toMap()));
                }
            }
            try (ResultSet rs = db.query("cypher", 
                    "MATCH (i:Interface) " +
                    "RETURN i.name as name, i.package as package, i.filepath as filepath, 'Interface' as type")) {
                while (rs.hasNext()) {
                    classes.add(new HashMap<>(rs.next().toMap()));
                }
            }
        });

        return classes;
    }

    @McpTool(description = "Tìm kiếm các class phụ thuộc (Dependency Injection, Extends, Implements) của một Class cụ thể")
    public List<Map<String, Object>> findClassDependencies(
            @McpToolParam(description = "Tên Class cần tìm dependency") String className) {
        Database db = arcadeDbService.getDatabase();
        List<Map<String, Object>> dependencies = new ArrayList<>();

        db.transaction(() -> {
            // Lấy quan hệ INJECTS
            try (ResultSet rsInjects = db.query("cypher", 
                    "MATCH (c:Class {name: $className})-[r:INJECTS]->(dep:Class) " +
                    "RETURN dep.name as dependency, r.fieldName as field, r.via as via", Map.of("className", className))) {
                while (rsInjects.hasNext()) {
                    Map<String, Object> dep = new HashMap<>(rsInjects.next().toMap());
                    dep.put("relation", "INJECTS");
                    dependencies.add(dep);
                }
            }

            // Lấy quan hệ EXTENDS
            try (ResultSet rsExtends = db.query("cypher", 
                    "MATCH (c:Class {name: $className})-[:EXTENDS]->(parent:Class) " +
                    "RETURN parent.name as dependency", Map.of("className", className))) {
                while (rsExtends.hasNext()) {
                    Map<String, Object> dep = new HashMap<>();
                    dep.put("dependency", rsExtends.next().getProperty("dependency"));
                    dep.put("relation", "EXTENDS");
                    dependencies.add(dep);
                }
            }

            // Lấy quan hệ IMPLEMENTS
            try (ResultSet rsImplements = db.query("cypher", 
                    "MATCH (c:Class {name: $className})-[:IMPLEMENTS]->(i:Interface) " +
                    "RETURN i.name as dependency", Map.of("className", className))) {
                while (rsImplements.hasNext()) {
                    Map<String, Object> dep = new HashMap<>();
                    dep.put("dependency", rsImplements.next().getProperty("dependency"));
                    dep.put("relation", "IMPLEMENTS");
                    dependencies.add(dep);
                }
            }
        });

        return dependencies;
    }

    @McpTool(description = "Truy vết chuỗi gọi hàm (Call Chain) từ một phương thức cụ thể (ví dụ: 'Class.methodName')")
    public List<Map<String, Object>> traceMethodCalls(
            @McpToolParam(description = "Tên đầy đủ của phương thức (ví dụ: Class.methodName)") String methodFqName) {
        Database db = arcadeDbService.getDatabase();
        List<Map<String, Object>> calls = new ArrayList<>();

        db.transaction(() -> {
            try (ResultSet rs = db.query("cypher", 
                    "MATCH (caller:Method {name: $methodFqName})-[:CALLS]->(target:Method) " +
                    "RETURN target.name as calledMethod, target.className as targetClass, target.shortName as targetMethod", 
                    Map.of("methodFqName", methodFqName))) {
                while (rs.hasNext()) {
                    calls.add(new HashMap<>(rs.next().toMap()));
                }
            }
        });

        return calls;
    }

    @McpTool(description = "Lấy thông tin chi tiết của một Class hoặc Interface, bao gồm mô tả Javadoc/docstring, package, và đường dẫn file nguồn")
    public Map<String, Object> getClassInfo(
            @McpToolParam(description = "Tên Class hoặc Interface cần tra cứu") String className) {
        Database db = arcadeDbService.getDatabase();
        Map<String, Object> result = new HashMap<>();

        db.transaction(() -> {
            // Thử tìm Class trước
            try (ResultSet rs = db.query("cypher",
                    "MATCH (c:Class {name: $name}) " +
                    "RETURN c.name as name, c.package as package, c.filepath as filepath, c.description as description, 'Class' as type",
                    Map.of("name", className))) {
                if (rs.hasNext()) {
                    result.putAll(rs.next().toMap());
                    return;
                }
            }
            // Thử tìm Interface nếu không thấy Class
            try (ResultSet rs = db.query("cypher",
                    "MATCH (i:Interface {name: $name}) " +
                    "RETURN i.name as name, i.package as package, i.filepath as filepath, i.description as description, 'Interface' as type",
                    Map.of("name", className))) {
                if (rs.hasNext()) {
                    result.putAll(rs.next().toMap());
                }
            }
        });

        if (result.isEmpty()) {
            result.put("error", "Không tìm thấy Class/Interface với tên: " + className);
        }
        return result;
    }

    @McpTool(description = "Lấy thông tin chi tiết của một Method, bao gồm mô tả Javadoc/docstring, tên Class cha và đường dẫn file nguồn")
    public Map<String, Object> getMethodInfo(
            @McpToolParam(description = "Tên đầy đủ của phương thức (ví dụ: Class.methodName)") String methodFqName) {
        Database db = arcadeDbService.getDatabase();
        Map<String, Object> result = new HashMap<>();

        db.transaction(() -> {
            try (ResultSet rs = db.query("cypher",
                    "MATCH (c:Class)-[:CONTAINS]->(m:Method {name: $name}) " +
                    "RETURN m.name as fqName, m.shortName as shortName, m.className as className, " +
                    "m.description as description, c.filepath as filepath",
                    Map.of("name", methodFqName))) {
                if (rs.hasNext()) {
                    result.putAll(rs.next().toMap());
                }
            }
        });

        if (result.isEmpty()) {
            result.put("error", "Không tìm thấy Method với tên: " + methodFqName);
        }
        return result;
    }
}
