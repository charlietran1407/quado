package vn.cxn.graph.indexer;

/**
 * Record lưu thông tin cuộc gọi phương thức tạm thời để giải quyết sau khi quét xong.
 */
public record MethodCallInfo(String callerMethodFqName, String calledMethodShortName) {}
