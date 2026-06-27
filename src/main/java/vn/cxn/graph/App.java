package vn.cxn.graph;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public class App extends Application {
    private ConfigurableApplicationContext springContext;
    private Parent rootNode;

    @Override
    public void init() throws Exception {
        // Khởi chạy Spring Boot context trong luồng init của JavaFX
        springContext = new SpringApplicationBuilder(SpringApp.class).run();
        
        FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/vn/cxn/graph/ui/main.fxml"));
        // Cấu hình để Spring Boot quản lý và tự động inject controller
        fxmlLoader.setControllerFactory(springContext::getBean);
        rootNode = fxmlLoader.load();
    }

    @Override
    public void start(Stage stage) throws Exception {
        stage.setTitle("Quado - Desktop Indexer & MCP");
        Scene scene = new Scene(rootNode, 900, 650);
        scene.getStylesheets().add(getClass().getResource("/vn/cxn/graph/ui/style.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        springContext.close();
        Platform.exit();
    }

    public static void main(String[] args) {
        boolean headless = java.util.Arrays.asList(args).contains("--headless")
                || "true".equals(System.getProperty("spring.ai.mcp.server.stdio.enabled"));

        if (headless) {
            // Chạy chế độ headless (chỉ Spring Boot, phục vụ làm MCP Server qua STDIO)
            new SpringApplicationBuilder(SpringApp.class)
                    .properties("spring.main.web-application-type=none") // Tắt Web server vì dùng STDIO
                    .run(args);
        } else {
            // Chạy giao diện JavaFX
            launch(args);
        }
    }
}
