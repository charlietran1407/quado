package vn.cxn.graph.indexer;

import com.arcadedb.database.Database;
import java.nio.file.Path;
import java.util.List;

/**
 * Interface đại diện cho bộ quét mã nguồn của từng ngôn ngữ lập trình cụ thể.
 */
public interface SourceCodeIndexer {
    /**
     * Phân tích file nguồn và import dữ liệu cấu trúc vào ArcadeDB.
     */
    void index(Path file, Database db, List<MethodCallInfo> pendingMethodCalls) throws Exception;

    /**
     * Kiểm tra xem Indexer này có hỗ trợ định dạng file mở rộng cụ thể hay không.
     */
    boolean supports(String fileExtension);
}
