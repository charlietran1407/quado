package vn.cxn.graph.service;

import com.arcadedb.database.Database;
import com.arcadedb.query.sql.executor.ResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dịch vụ phân tích mã nguồn bằng AI sử dụng Spring AI và Ollama.
 */
@Service
public class AiAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    @Value("${quado.ai.parallel-threads:3}")
    private int parallelThreads;

    private final ArcadeDbService arcadeDbService;
    private final ChatModel chatModel;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean debugEnabled = new AtomicBoolean(false);
    private final AtomicInteger processedCount = new AtomicInteger(0);

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled.set(enabled);
    }

    public boolean isDebugEnabled() {
        return this.debugEnabled.get();
    }

    private int totalCount = 0;

    public interface ProgressListener {
        void onProgress(String message, double progress);

        void onComplete(int processed, int total);

        void onError(Throwable t);
    }

    public AiAnalysisService(ArcadeDbService arcadeDbService, @Autowired(required = false) ChatModel chatModel) {
        this.arcadeDbService = arcadeDbService;
        this.chatModel = chatModel;
        if (chatModel == null) {
            log.warn(
                    "OllamaChatModel (ChatModel) chưa được cấu hình hoặc không tìm thấy. Phân tích AI có thể không hoạt động.");
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public int getTotalCount() {
        return totalCount;
    }

    public CompletableFuture<Void> analyzeClassesAsync(ProgressListener listener) {
        if (chatModel == null) {
            listener.onError(new IllegalStateException(
                    "Spring AI ChatModel chưa được cấu hình. Vui lòng kiểm tra lại cài đặt Ollama."));
            return CompletableFuture.failedFuture(new IllegalStateException("ChatModel missing"));
        }
        if (!isRunning.compareAndSet(false, true)) {
            listener.onError(new IllegalStateException("Tiến trình phân tích AI đang chạy."));
            return CompletableFuture.failedFuture(new IllegalStateException("Already running"));
        }

        return CompletableFuture.runAsync(() -> {
            try {
                analyzeClasses(listener);
            } catch (Exception e) {
                listener.onError(e);
            } finally {
                isRunning.set(false);
            }
        });
    }

    private void analyzeClasses(ProgressListener listener) {
        Database db = arcadeDbService.getDatabase();
        List<Map<String, Object>> unanalyzedNodes = new ArrayList<>();

        db.transaction(() -> {
            try (ResultSet rs = db.query("cypher",
                    "MATCH (c:Class) " +
                            "WHERE c.is_analyzed IS NULL OR c.is_analyzed = false " +
                            "RETURN c.name as name, c.filepath as filepath, 'Class' as type")) {
                while (rs.hasNext()) {
                    unanalyzedNodes.add(new HashMap<>(rs.next().toMap()));
                }
            }
            try (ResultSet rs = db.query("cypher",
                    "MATCH (i:Interface) " +
                            "WHERE i.is_analyzed IS NULL OR i.is_analyzed = false " +
                            "RETURN i.name as name, i.filepath as filepath, 'Interface' as type")) {
                while (rs.hasNext()) {
                    unanalyzedNodes.add(new HashMap<>(rs.next().toMap()));
                }
            }
        });

        this.totalCount = unanalyzedNodes.size();
        this.processedCount.set(0);

        if (totalCount == 0) {
            listener.onProgress("Không tìm thấy Class/Interface nào chưa được phân tích trong CSDL.", 1.0);
            listener.onComplete(0, 0);
            return;
        }

        listener.onProgress("Tìm thấy " + totalCount + " Class/Interface chưa được phân tích. Bắt đầu xử lý AI song song...",
                0.0);

        try (ExecutorService executor = Executors.newFixedThreadPool(parallelThreads)) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Map<String, Object> node : unanalyzedNodes) {
                if (!isRunning.get()) {
                    break;
                }

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    if (!isRunning.get()) {
                        return;
                    }

                    String name = (String) node.get("name");
                    String filepath = (String) node.get("filepath");
                    String type = (String) node.get("type");

                    String sourceCode = readFileContent(filepath);
                    if (sourceCode.isEmpty()) {
                        log.warn("Bỏ qua {} {}: Mã nguồn trống hoặc không tìm thấy file.", type, name);
                        db.transaction(() -> {
                            Map<String, Object> params = new HashMap<>();
                            params.put("name", name);
                            params.put("filepath", filepath);
                            db.command("cypher",
                                    String.format("MATCH (n:%s) WHERE n.name = $name AND n.filepath = $filepath " +
                                            "SET n.is_analyzed = true, n.ai_summary = 'Mã nguồn không khả dụng'", type),
                                    params);
                        });
                        int currentProcessed = processedCount.incrementAndGet();
                        double progress = (double) currentProcessed / totalCount;
                        listener.onProgress("Đang phân tích (" + currentProcessed + "/" + totalCount + "): " + name, progress);
                        return;
                    }

                    // Đã loại bỏ giới hạn dòng code theo chỉ đạo của đồng chí Sáng lập
                    boolean isJava = filepath != null && filepath.toLowerCase().endsWith(".java");
                    String compressedCode = stripCommentsAndBlankLines(sourceCode, isJava);

                    try {
                        String prompt = "Summarize the main functionality of the following Java or Delphi class/interface in a maximum of 3 short sentences, focusing on its business role and intended use within the system. Return ONLY the summary in English:\n\n"
                                + compressedCode;

                        int currentProcessed = processedCount.incrementAndGet();
                        double progress = (double) currentProcessed / totalCount;
                        if (debugEnabled.get()) {
                            listener.onProgress("[DEBUG PROMPT sent to Ollama for " + name + "]:\n" + prompt, progress);
                        } else {
                            listener.onProgress("Đang phân tích (" + currentProcessed + "/" + totalCount + "): " + name, progress);
                        }

                        String summary = chatModel.call(prompt);

                        String finalSummary = (summary != null) ? summary.trim() : "";
                        if (debugEnabled.get()) {
                            listener.onProgress("[DEBUG RESPONSE received from Ollama for " + name + "]:\n" + finalSummary, progress);
                        }

                        db.transaction(() -> {
                            Map<String, Object> params = new HashMap<>();
                            params.put("name", name);
                            params.put("filepath", filepath);
                            params.put("summary", finalSummary);
                            db.command("cypher",
                                    String.format("MATCH (n:%s) WHERE n.name = $name AND n.filepath = $filepath " +
                                            "SET n.ai_summary = $summary, n.is_analyzed = true", type),
                                    params);
                        });
                    } catch (Exception e) {
                        log.error("Lỗi khi phân tích {} {}: {}", type, name, e.getMessage());
                        int currentProcessed = processedCount.incrementAndGet();
                        double progress = (double) currentProcessed / totalCount;
                        listener.onProgress("Lỗi phân tích (" + currentProcessed + "/" + totalCount + "): " + name, progress);
                    }
                }, executor);

                futures.add(future);
            }

            // Chờ tất cả hoàn thành
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        listener.onProgress("Hoàn thành phân tích AI thành công!", 1.0);
        listener.onComplete(processedCount.get(), totalCount);
    }

    public void stopAnalysis() {
        isRunning.set(false);
    }

    private String readFileContent(String filepath) {
        if (filepath == null) {
            return "";
        }
        try {
            Path path = Path.of(filepath);
            if (!Files.exists(path)) {
                return "";
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF) {
                return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            }
            if (bytes.length >= 2) {
                if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                    return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
                }
                if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                    return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
                }
            }
            try {
                CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
                decoder.onMalformedInput(CodingErrorAction.REPORT);
                decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                CharBuffer cb = decoder.decode(buf);
                return cb.toString();
            } catch (Exception e) {
                try {
                    return new String(bytes, Charset.forName("windows-1252"));
                } catch (Exception ex) {
                    return new String(bytes, StandardCharsets.ISO_8859_1);
                }
            }
        } catch (IOException e) {
            log.error("Lỗi khi đọc file nguồn: {}", filepath, e);
            return "";
        }
    }

    private String stripCommentsAndBlankLines(String code, boolean isJava) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        StringBuilder cleanCode = new StringBuilder();
        int len = code.length();
        boolean inString = false;
        boolean inSingleComment = false;
        boolean inCurlyComment = false; // { }
        boolean inStarComment = false;  // /* */ or (* *)
        
        for (int i = 0; i < len; i++) {
            char c = code.charAt(i);
            
            // Handle newlines
            if (c == '\n' || c == '\r') {
                if (inSingleComment) {
                    inSingleComment = false;
                }
                if (!inCurlyComment && !inStarComment) {
                    cleanCode.append(c);
                }
                continue;
            }
            
            // If inside a single line comment, skip characters until newline
            if (inSingleComment) {
                continue;
            }
            
            // If inside a curly comment (Delphi { }), check for closing }
            if (inCurlyComment) {
                if (c == '}') {
                    inCurlyComment = false;
                }
                continue;
            }
            
            // If inside a star comment (/* */ or (* *)), check for closing
            if (inStarComment) {
                if (isJava && c == '*' && i + 1 < len && code.charAt(i + 1) == '/') {
                    inStarComment = false;
                    i++; // skip '/'
                } else if (!isJava && c == '*' && i + 1 < len && code.charAt(i + 1) == ')') {
                    inStarComment = false;
                    i++; // skip ')'
                }
                continue;
            }
            
            // If we are in a string literal, we check for closing quote
            if (inString) {
                cleanCode.append(c);
                if (isJava && c == '"') {
                    // Check for escaped quote in Java (e.g. \")
                    boolean escaped = false;
                    int k = i - 1;
                    while (k >= 0 && code.charAt(k) == '\\') {
                        escaped = !escaped;
                        k--;
                    }
                    if (!escaped) {
                        inString = false;
                    }
                } else if (!isJava && c == '\'') {
                    // Single quote for Delphi
                    inString = false;
                }
                continue;
            }
            
            // Check for starting of comments or string literals
            if (isJava && c == '"') {
                inString = true;
                cleanCode.append(c);
            } else if (!isJava && c == '\'') {
                inString = true;
                cleanCode.append(c);
            } else if (c == '/' && i + 1 < len && code.charAt(i + 1) == '/') {
                inSingleComment = true;
                i++; // skip second '/'
            } else if (isJava && c == '/' && i + 1 < len && code.charAt(i + 1) == '*') {
                inStarComment = true;
                i++; // skip '*'
            } else if (!isJava && c == '(' && i + 1 < len && code.charAt(i + 1) == '*') {
                inStarComment = true;
                i++; // skip '*'
            } else if (!isJava && c == '{') {
                inCurlyComment = true;
            } else {
                cleanCode.append(c);
            }
        }
        
        // Post-process to remove blank lines
        StringBuilder result = new StringBuilder();
        for (String line : cleanCode.toString().split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                result.append(line).append("\n");
            }
        }
        return result.toString();
    }
}
