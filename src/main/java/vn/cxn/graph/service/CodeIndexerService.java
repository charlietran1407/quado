package vn.cxn.graph.service;

import com.arcadedb.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.cxn.graph.indexer.MethodCallInfo;
import vn.cxn.graph.indexer.SourceCodeIndexer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lớp điều phối (Dispatcher) cho tiến trình quét mã nguồn đa ngôn ngữ.
 */
@Service
public class CodeIndexerService {
    private static final Logger log = LoggerFactory.getLogger(CodeIndexerService.class);

    private final ArcadeDbService arcadeDbService;
    private final List<SourceCodeIndexer> indexers;

    public CodeIndexerService(ArcadeDbService arcadeDbService, List<SourceCodeIndexer> indexers) {
        this.arcadeDbService = arcadeDbService;
        this.indexers = indexers;
        log.info("Khởi tạo bộ quét đa ngôn ngữ thành công. Đã nạp {} Indexers: {}", 
                indexers.size(), indexers.stream().map(i -> i.getClass().getSimpleName()).collect(Collectors.toList()));
    }

    public interface ProgressListener {
        void onProgress(String message, double progress);
        void onComplete(int totalFiles);
        void onError(Throwable t);
    }

    /**
     * Khởi chạy tiến trình quét mã nguồn bất đồng bộ sử dụng Virtual Threads.
     */
    public CompletableFuture<Void> scanProjectAsync(File projectDir, ProgressListener listener) {
        return CompletableFuture.runAsync(() -> {
            try {
                scanProject(projectDir, listener);
            } catch (Exception e) {
                listener.onError(e);
                throw new RuntimeException(e);
            }
        });
    }

    private void scanProject(File projectDir, ProgressListener listener) throws IOException {
        log.info("Bắt đầu quét mã nguồn đa ngôn ngữ tại thư mục: {}", projectDir.getAbsolutePath());
        listener.onProgress("Đang tìm kiếm các file mã nguồn hỗ trợ...", 0.0);

        List<Path> allFiles;
        try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
            allFiles = walk
                    .filter(Files::isRegularFile)
                    // Bỏ qua thư mục test/build/node_modules/git để tăng tốc độ phân tích
                    .filter(path -> {
                        String str = path.toString();
                        return !str.contains(File.separator + "test" + File.separator) &&
                               !str.contains(File.separator + "target" + File.separator) &&
                               !str.contains(File.separator + "node_modules" + File.separator) &&
                               !str.contains(File.separator + ".git" + File.separator);
                    })
                    .filter(this::isSupportedFile)
                    .collect(Collectors.toList());
        }

        int totalFiles = allFiles.size();
        log.info("Tìm thấy {} file mã nguồn hợp lệ để tiến hành index.", totalFiles);
        listener.onProgress("Tìm thấy " + totalFiles + " file mã nguồn. Bắt đầu phân tích...", 0.05);

        Database db = arcadeDbService.getDatabase();

        // 1. Quét sơ bộ và xóa sạch dữ liệu cũ của các file sắp quét để tránh trùng lặp dữ liệu
        listener.onProgress("Đang dọn dẹp dữ liệu cũ của các file...", 0.1);
        db.transaction(() -> {
            for (Path file : allFiles) {
                String filepath = file.toAbsolutePath().toString();
                // Thực thi lệnh xóa các Node Class/Interface cũ liên kết với file này
                db.command("cypher", "MATCH (c {filepath: $filepath}) DETACH DELETE c", Map.of("filepath", filepath));
            }
        });

        // 2. Tiến hành phân tích từng file và đưa vào database
        int count = 0;
        List<MethodCallInfo> pendingMethodCalls = new ArrayList<>();

        for (Path file : allFiles) {
            String filepath = file.toAbsolutePath().toString();
            count++;
            double progress = 0.1 + ((double) count / totalFiles) * 0.8;
            listener.onProgress("Đang phân tích (" + count + "/" + totalFiles + "): " + file.getFileName(), progress);

            String ext = getFileExtension(file);
            SourceCodeIndexer indexer = findIndexer(ext);
            if (indexer != null) {
                try {
                    db.transaction(() -> {
                        try {
                            indexer.index(file, db, pendingMethodCalls);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                } catch (Exception e) {
                    log.error("Sai sót khi phân tích file {}: {}", filepath, e.getMessage());
                    // Tiếp tục quét các file khác, không để 1 file lỗi dừng cả hệ thống
                }
            }
        }

        // 3. Giải quyết liên kết lời gọi hàm (CALLS) - Best-effort resolution
        listener.onProgress("Đang thiết lập liên kết lời gọi hàm (CALLS)...", 0.95);
        log.info("Đang xử lý {} liên kết CALLS tích lũy...", pendingMethodCalls.size());
        db.transaction(() -> {
            for (MethodCallInfo call : pendingMethodCalls) {
                db.command("cypher",
                        "MATCH (caller:Method {name: $callerName}) " +
                        "MATCH (target:Method) WHERE target.shortName = $calledName " +
                        "MERGE (caller)-[:CALLS]->(target)",
                        Map.of("callerName", call.callerMethodFqName(), "calledName", call.calledMethodShortName())
                );
            }
        });

        listener.onProgress("Quá trình quét hoàn tất thành công!", 1.0);
        listener.onComplete(totalFiles);
        log.info("Index đồ thị hoàn thành. Đã xử lý {} file.", totalFiles);
    }

    private boolean isSupportedFile(Path path) {
        String ext = getFileExtension(path);
        return indexers.stream().anyMatch(indexer -> indexer.supports(ext));
    }

    private SourceCodeIndexer findIndexer(String extension) {
        return indexers.stream()
                .filter(indexer -> indexer.supports(extension))
                .findFirst()
                .orElse(null);
    }

    private String getFileExtension(Path path) {
        String filename = path.getFileName().toString();
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx > 0 && dotIdx < filename.length() - 1) {
            return filename.substring(dotIdx + 1);
        }
        return "";
    }
}
