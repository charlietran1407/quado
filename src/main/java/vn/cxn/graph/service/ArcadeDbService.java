package vn.cxn.graph.service;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.schema.Schema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ArcadeDbService {
    private static final Logger log = LoggerFactory.getLogger(ArcadeDbService.class);
    
    private DatabaseFactory factory;
    private Database database;

    @Value("${arcadedb.path:./arcadedb_graph}")
    private String dbPath;

    @PostConstruct
    public void init() {
        log.info("Khởi tạo ArcadeDB tại thư mục: {}", dbPath);
        factory = new DatabaseFactory(dbPath);
        if (!factory.exists()) {
            log.info("Cơ sở dữ liệu chưa tồn tại. Đang tạo mới...");
            database = factory.create();
            initializeSchema();
        } else {
            log.info("Cơ sở dữ liệu đã tồn tại. Đang mở kết nối...");
            database = factory.open();
        }
    }

    private void initializeSchema() {
        database.transaction(() -> {
            Schema schema = database.getSchema();
            // Khởi tạo các Node Type (Vertex)
            if (!schema.existsType("Class")) {
                schema.createVertexType("Class");
            }
            if (!schema.existsType("Interface")) {
                schema.createVertexType("Interface");
            }
            if (!schema.existsType("Method")) {
                schema.createVertexType("Method");
            }
            
            // Khởi tạo các Relationship Type (Edge)
            if (!schema.existsType("EXTENDS")) {
                schema.createEdgeType("EXTENDS");
            }
            if (!schema.existsType("IMPLEMENTS")) {
                schema.createEdgeType("IMPLEMENTS");
            }
            if (!schema.existsType("INJECTS")) {
                schema.createEdgeType("INJECTS");
            }
            if (!schema.existsType("CONTAINS")) {
                schema.createEdgeType("CONTAINS");
            }
            if (!schema.existsType("CALLS")) {
                schema.createEdgeType("CALLS");
            }
        });
        log.info("Định dạng Schema của ArcadeDB thành công.");
    }

    public Database getDatabase() {
        return database;
    }

    public void cleanDatabase() {
        log.info("Đang tiến hành dọn dẹp (clean) dữ liệu cũ trong Graph DB...");
        database.transaction(() -> {
            // Xóa toàn bộ các đỉnh, các cạnh liên kết sẽ tự động bị xóa theo tính chất CASCADE
            database.command("sql", "DELETE FROM Class");
            database.command("sql", "DELETE FROM Interface");
            database.command("sql", "DELETE FROM Method");
        });
        log.info("Dọn dẹp cơ sở dữ liệu hoàn tất.");
    }

    @PreDestroy
    public void close() {
        if (database != null && database.isOpen()) {
            log.info("Đang đóng cơ sở dữ liệu ArcadeDB...");
            database.close();
        }
    }
}
