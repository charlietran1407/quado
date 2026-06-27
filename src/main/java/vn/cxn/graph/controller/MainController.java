package vn.cxn.graph.controller;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Controller;
import vn.cxn.graph.service.ArcadeDbService;
import vn.cxn.graph.service.CodeIndexerService;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

@Controller
public class MainController implements ApplicationListener<WebServerInitializedEvent> {
    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @FXML
    private TextField pathField;
    @FXML
    private Button btnScan;
    @FXML
    private Button btnClean;
    @FXML
    private Label statusText;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Button btnToggleMcp;
    @FXML
    private Label mcpStatusLabel;
    @FXML
    private TextField mcpUrlField;
    @FXML
    private TextArea logConsole;

    private final CodeIndexerService codeIndexerService;
    private final ArcadeDbService arcadeDbService;

    private boolean mcpServerRunning = true;
    private int localServerPort = 3000;

    public MainController(CodeIndexerService codeIndexerService, ArcadeDbService arcadeDbService) {
        this.codeIndexerService = codeIndexerService;
        this.arcadeDbService = arcadeDbService;
    }

    @FXML
    public void initialize() {
        appendLog("Hệ thống khởi chạy thành công. Sẵn sàng phục vụ Đồng chí Sáng lập.");
        mcpStatusLabel.setText("ONLINE");
        mcpStatusLabel.getStyleClass().setAll("status-badge-online");
        btnToggleMcp.setText("Tắt MCP Server (SSE)");
        mcpUrlField.setText("http://localhost:" + localServerPort + "/mcp");
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.localServerPort = event.getWebServer().getPort();
        Platform.runLater(() -> {
            if (mcpUrlField != null) {
                mcpUrlField.setText("http://localhost:" + localServerPort + "/mcp");
            }
            appendLog("Dịch vụ MCP Server đang lắng nghe qua giao thức Streamable HTTP trên cổng: " + localServerPort);
        });
    }

    @FXML
    private void handleBrowse(ActionEvent event) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Chọn Thư Mục Mã Nguồn Java (src/main)");
        File selectedDirectory = directoryChooser.showDialog(new Stage());
        if (selectedDirectory != null) {
            pathField.setText(selectedDirectory.getAbsolutePath());
            appendLog("Đồng chí đã chọn thư mục mục tiêu: " + selectedDirectory.getAbsolutePath());
        }
    }

    @FXML
    private void handleScan(ActionEvent event) {
        String path = pathField.getText();
        if (path == null || path.trim().isEmpty()) {
            showAlert("Sai sót cấu hình", "Vui lòng chọn đường dẫn thư mục nguồn trước khi khởi động quét.");
            return;
        }

        File projectDir = new File(path);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            showAlert("Sai sót đường dẫn", "Đường dẫn không hợp lệ hoặc không phải là một thư mục chính thống.");
            return;
        }

        btnScan.setDisable(true);
        btnClean.setDisable(true);
        progressBar.setProgress(0.0);
        statusText.setText("Trạng thái: Đang phân tích mã nguồn...");

        codeIndexerService.scanProjectAsync(projectDir, new CodeIndexerService.ProgressListener() {
            @Override
            public void onProgress(String message, double progress) {
                Platform.runLater(() -> {
                    progressBar.setProgress(progress);
                    statusText.setText("Tiến trình: " + message);
                    appendLog(message);
                });
            }

            @Override
            public void onComplete(int totalFiles) {
                Platform.runLater(() -> {
                    btnScan.setDisable(false);
                    btnClean.setDisable(false);
                    progressBar.setProgress(1.0);
                    statusText.setText("Trạng thái: Hoàn thành quét " + totalFiles + " file.");
                    appendLog(">>> TRIỆT ĐỂ HOÀN THÀNH NHIỆM VỤ QUÉT MÃ NGUỒN. TỔNG SỐ FILE XỬ LÝ: " + totalFiles);
                    showAlert("Báo cáo hoàn thành",
                            "Đã quét và cập nhật bản đồ quan hệ đồ thị thành công cho " + totalFiles + " file!");
                });
            }

            @Override
            public void onError(Throwable t) {
                Platform.runLater(() -> {
                    btnScan.setDisable(false);
                    btnClean.setDisable(false);
                    progressBar.setProgress(0.0);
                    statusText.setText("Trạng thái: Gặp sai sót.");
                    appendLog(">>> CẢNH BÁO SAI SÓT PHÁT SINH: " + t.getMessage());
                    showAlert("Sai sót hệ thống", "Có phần tử lỗi phát sinh trong quá trình quét: " + t.getMessage());
                });
            }
        });
    }

    @FXML
    private void handleClean(ActionEvent event) {
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Xác nhận hành động");
        confirmAlert.setHeaderText("Quyết định dọn dẹp cơ sở dữ liệu");
        confirmAlert.setContentText("Hành động này sẽ dọn sạch toàn bộ dữ liệu đồ thị cũ. Đồng chí có chắc chắn?");

        confirmAlert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    arcadeDbService.cleanDatabase();
                    appendLog(">>> BÀI TRỪ TOÀN BỘ PHẦN TỬ CŨ TRONG ĐỒ THỊ DATABASE THÀNH CÔNG.");
                    statusText.setText("Trạng thái: Đã làm sạch DB.");
                    showAlert("Thành công", "Đồ thị cơ sở dữ liệu đã được dọn sạch hoàn toàn.");
                } catch (Exception e) {
                    appendLog(">>> THẤT BẠI KHI DỌN DẸP DB: " + e.getMessage());
                    showAlert("Sai sót dọn dẹp", "Không thể làm sạch DB: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void handleToggleMcp(ActionEvent event) {
        if (mcpServerRunning) {
            mcpServerRunning = false;
            mcpStatusLabel.setText("OFFLINE");
            mcpStatusLabel.getStyleClass().setAll("status-badge-offline");
            btnToggleMcp.setText("Bật MCP Server (SSE)");
            appendLog(">>> Tạm ngắt dịch vụ MCP Server (Mô phỏng ngắt kết nối).");
        } else {
            mcpServerRunning = true;
            mcpStatusLabel.setText("ONLINE");
            mcpStatusLabel.getStyleClass().setAll("status-badge-online");
            btnToggleMcp.setText("Tắt MCP Server (SSE)");
            appendLog(">>> Kích hoạt lại dịch vụ MCP Server tại port " + localServerPort);
        }
    }

    private void appendLog(String message) {
        Platform.runLater(() -> {
            if (logConsole != null) {
                logConsole
                        .appendText("[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + message + "\n");
            } else {
                log.info(message);
            }
        });
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
