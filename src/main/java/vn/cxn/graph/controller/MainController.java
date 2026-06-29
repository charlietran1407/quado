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
import vn.cxn.graph.service.AiAnalysisService;

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

    // AI FXML Controls
    @FXML
    private Button btnAiAnalyze;
    @FXML
    private Button btnStopAi;
    @FXML
    private Label aiStatusText;
    @FXML
    private ProgressBar aiProgressBar;
    @FXML
    private CheckBox chkDebug;

    private final CodeIndexerService codeIndexerService;
    private final ArcadeDbService arcadeDbService;
    private final AiAnalysisService aiAnalysisService;

    private boolean mcpServerRunning = true;
    private int localServerPort = 3000;

    public MainController(CodeIndexerService codeIndexerService, ArcadeDbService arcadeDbService, AiAnalysisService aiAnalysisService) {
        this.codeIndexerService = codeIndexerService;
        this.arcadeDbService = arcadeDbService;
        this.aiAnalysisService = aiAnalysisService;
    }

    @FXML
    public void initialize() {
        appendLog("System initialized successfully. Ready to serve.");
        mcpStatusLabel.setText("ONLINE");
        mcpStatusLabel.getStyleClass().setAll("status-badge-online");
        btnToggleMcp.setText("Disable MCP Server (SSE)");
        mcpUrlField.setText("http://localhost:" + localServerPort + "/mcp");
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.localServerPort = event.getWebServer().getPort();
        Platform.runLater(() -> {
            if (mcpUrlField != null) {
                mcpUrlField.setText("http://localhost:" + localServerPort + "/mcp");
            }
            appendLog("MCP Server is listening via SSE on port: " + localServerPort);
        });
    }

    @FXML
    private void handleBrowse(ActionEvent event) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select Source Directory");
        File selectedDirectory = directoryChooser.showDialog(new Stage());
        if (selectedDirectory != null) {
            pathField.setText(selectedDirectory.getAbsolutePath());
            appendLog("Selected target directory: " + selectedDirectory.getAbsolutePath());
        }
    }

    @FXML
    private void handleScan(ActionEvent event) {
        String path = pathField.getText();
        if (path == null || path.trim().isEmpty()) {
            showAlert("Configuration Error", "Please select a source directory before starting the scan.");
            return;
        }

        File projectDir = new File(path);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            showAlert("Invalid Path", "The selected path is invalid or is not a directory.");
            return;
        }

        btnScan.setDisable(true);
        btnClean.setDisable(true);
        progressBar.setProgress(0.0);
        statusText.setText("Status: Analyzing source code...");

        codeIndexerService.scanProjectAsync(projectDir, new CodeIndexerService.ProgressListener() {
            @Override
            public void onProgress(String message, double progress) {
                Platform.runLater(() -> {
                    progressBar.setProgress(progress);
                    statusText.setText("Progress: " + message);
                    appendLog(message);
                });
            }

            @Override
            public void onComplete(int totalFiles) {
                Platform.runLater(() -> {
                    btnScan.setDisable(false);
                    btnClean.setDisable(false);
                    progressBar.setProgress(1.0);
                    statusText.setText("Status: Completed scanning " + totalFiles + " files.");
                    appendLog(">>> INDEXING COMPLETED. TOTAL FILES PROCESSED: " + totalFiles);
                    showAlert("Success",
                            "Successfully scanned and updated relationship graph for " + totalFiles + " files!");
                });
            }

            @Override
            public void onError(Throwable t) {
                Platform.runLater(() -> {
                    btnScan.setDisable(false);
                    btnClean.setDisable(false);
                    progressBar.setProgress(0.0);
                    statusText.setText("Status: Error encountered.");
                    appendLog(">>> ERROR ENCOUNTERED: " + t.getMessage());
                    showAlert("System Error", "An error occurred during indexing: " + t.getMessage());
                });
            }
        });
    }

    @FXML
    private void handleClean(ActionEvent event) {
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Confirm Action");
        confirmAlert.setHeaderText("Clean Graph Database");
        confirmAlert.setContentText("This will clean all existing graph data. Are you sure you want to proceed?");

        confirmAlert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    arcadeDbService.cleanDatabase();
                    appendLog(">>> DATABASE CLEANED SUCCESSFULLY.");
                    statusText.setText("Status: Database cleaned.");
                    showAlert("Success", "The graph database has been cleaned successfully.");
                } catch (Exception e) {
                    appendLog(">>> FAILED TO CLEAN DATABASE: " + e.getMessage());
                    showAlert("Clean Error", "Failed to clean database: " + e.getMessage());
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
            btnToggleMcp.setText("Enable MCP Server (SSE)");
            appendLog(">>> Disconnected MCP Server (Simulated).");
        } else {
            mcpServerRunning = true;
            mcpStatusLabel.setText("ONLINE");
            mcpStatusLabel.getStyleClass().setAll("status-badge-online");
            btnToggleMcp.setText("Disable MCP Server (SSE)");
            appendLog(">>> Re-activated MCP Server on port " + localServerPort);
        }
    }

    @FXML
    private void handleAiAnalyze(ActionEvent event) {
        btnAiAnalyze.setDisable(true);
        btnStopAi.setDisable(false);
        aiProgressBar.setProgress(0.0);
        aiStatusText.setText("Status: Analyzing classes with local AI...");
        aiAnalysisService.setDebugEnabled(chkDebug.isSelected());

        aiAnalysisService.analyzeClassesAsync(new AiAnalysisService.ProgressListener() {
            @Override
            public void onProgress(String message, double progress) {
                Platform.runLater(() -> {
                    aiProgressBar.setProgress(progress);
                    aiStatusText.setText("Progress: " + message);
                    appendLog("[AI] " + message);
                });
            }

            @Override
            public void onComplete(int processed, int total) {
                Platform.runLater(() -> {
                    btnAiAnalyze.setDisable(false);
                    btnStopAi.setDisable(true);
                    aiProgressBar.setProgress(1.0);
                    aiStatusText.setText("Status: Completed " + processed + "/" + total + " classes.");
                    appendLog(">>> AI ANALYSIS COMPLETED. Processed: " + processed + "/" + total);
                    showAlert("Success", "Successfully enriched database with AI-generated business summaries for " + processed + " classes/interfaces!");
                });
            }

            @Override
            public void onError(Throwable t) {
                Platform.runLater(() -> {
                    btnAiAnalyze.setDisable(false);
                    btnStopAi.setDisable(true);
                    aiProgressBar.setProgress(0.0);
                    aiStatusText.setText("Status: Error encountered.");
                    appendLog(">>> AI ERROR ENCOUNTERED: " + t.getMessage());
                    showAlert("AI Service Error", "An error occurred during AI analysis: " + t.getMessage());
                });
            }
        });
    }

    @FXML
    private void handleStopAi(ActionEvent event) {
        aiAnalysisService.stopAnalysis();
        appendLog("[AI] Stopping AI Analysis on user request...");
        btnStopAi.setDisable(true);
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
