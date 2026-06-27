package vn.cxn.graph.indexer.sql;

import com.arcadedb.database.Database;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.cxn.graph.indexer.MethodCallInfo;
import vn.cxn.graph.indexer.SourceCodeIndexer;
import vn.cxn.graph.indexer.antlr.sql.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Bộ quét mã nguồn dành riêng cho SQL sử dụng ANTLR TSqlParser.
 */
@Component
public class SqlIndexer implements SourceCodeIndexer {
    private static final Logger log = LoggerFactory.getLogger(SqlIndexer.class);

    @Override
    public boolean supports(String fileExtension) {
        return "sql".equalsIgnoreCase(fileExtension);
    }

    @Override
    public void index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls) throws Exception {
        String targetFilepath = file.toAbsolutePath().toString();
        log.info("Starting SQL scan: {}", targetFilepath);

        try {
            TSqlLexer lexer = new TSqlLexer(CharStreams.fromPath(file, StandardCharsets.UTF_8));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            TSqlParser parser = new TSqlParser(tokens);
            ParseTree tree = parser.tsql_file();

            ParseTreeWalker walker = new ParseTreeWalker();
            SqlParseListener listener = new SqlParseListener();
            walker.walk(listener, tree);

            importToDatabase(listener, targetFilepath, db);
        } catch (Exception e) {
            log.error("Error parsing SQL file {}: {}", targetFilepath, e.getMessage(), e);
        }
    }

    private void importToDatabase(SqlParseListener listener, String filepath, Database db) {
        // Import Tables
        for (String table : listener.getTables()) {
            db.command("cypher",
                    "MERGE (t:Table {name: $name}) " +
                    "SET t.filepath = $filepath",
                    Map.of("name", table, "filepath", filepath)
            );
        }

        // Import Stored Procedures / Functions as Methods
        for (String proc : listener.getProcedures()) {
            String filename = new java.io.File(filepath).getName();
            String moduleClassName = filename.substring(0, filename.lastIndexOf('.'));

            db.command("cypher",
                    "MERGE (c:Class {name: $moduleClassName}) " +
                    "SET c.package = 'sql_module', c.filepath = $filepath, c.description = 'SQL Module container' " +
                    "WITH c " +
                    "MERGE (m:Method {name: $fqName}) " +
                    "SET m.shortName = $shortName, m.className = $moduleClassName, m.description = 'SQL Stored Procedure/Function', m.filepath = $filepath " +
                    "MERGE (c)-[:CONTAINS]->(m)",
                    Map.of("moduleClassName", moduleClassName, "filepath", filepath, "fqName", moduleClassName + "." + proc, "shortName", proc)
            );
        }
    }

    public static class SqlParseListener extends TSqlParserBaseListener {
        private final Set<String> tables = new HashSet<>();
        private final Set<String> procedures = new HashSet<>();

        public Set<String> getTables() {
            return tables;
        }

        public Set<String> getProcedures() {
            return procedures;
        }

        @Override
        public void enterFull_table_name(TSqlParser.Full_table_nameContext ctx) {
            tables.add(cleanName(ctx.getText()));
        }

        @Override
        public void enterTable_name(TSqlParser.Table_nameContext ctx) {
            tables.add(cleanName(ctx.getText()));
        }

        @Override
        public void enterCreate_or_alter_procedure(TSqlParser.Create_or_alter_procedureContext ctx) {
            if (ctx.func_proc_name_schema() != null) {
                procedures.add(cleanName(ctx.func_proc_name_schema().getText()));
            }
        }

        @Override
        public void enterCreate_or_alter_function(TSqlParser.Create_or_alter_functionContext ctx) {
            if (ctx.func_proc_name_schema() != null) {
                procedures.add(cleanName(ctx.func_proc_name_schema().getText()));
            }
        }

        private String cleanName(String name) {
            if (name == null) return null;
            return name.replace("[", "").replace("]", "").replace("`", "").trim();
        }
    }
}
