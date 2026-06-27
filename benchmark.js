const fs = require('fs');
const path = require('path');

// Đường dẫn thư mục nguồn quét mặc định hoặc nhận từ đối số dòng lệnh
const srcDir = process.argv[2] ? path.resolve(process.argv[2]) : path.resolve(process.cwd(), 'src/main/java');

function walkDir(dir, files = []) {
    if (!fs.existsSync(dir)) return files;
    const list = fs.readdirSync(dir);
    list.forEach(file => {
        const fullPath = path.join(dir, file);
        const stat = fs.statSync(fullPath);
        if (stat && stat.isDirectory()) {
            walkDir(fullPath, files);
        } else if (/\.(java|go|py|ts|sql|pas|dpr)$/i.test(file)) {
            files.push(fullPath);
        }
    });
    return files;
}

function estimateTokens(text) {
    // Ước lượng thô: 1 token tương đương khoảng 4 ký tự
    return Math.ceil(text.length / 4);
}

function runBenchmark() {
    console.log("==========================================================================");
    console.log("             CÔNG CỤ ĐO LƯỜNG THỰC TẾ LƯỢNG TIẾT KIỆM TOKEN               ");
    console.log("==========================================================================");
    console.log(`Đang quét thư mục nguồn: ${srcDir}\n`);

    const javaFiles = walkDir(srcDir);
    if (javaFiles.length === 0) {
        console.log("Không tìm thấy file nguồn Java nào để đo lường.");
        return;
    }

    let totalRawTokens = 0;
    let totalGraphTokens = 0;
    let fileReports = [];

    javaFiles.forEach(file => {
        const content = fs.readFileSync(file, 'utf8');
        const relativePath = path.relative(srcDir, file);
        
        // 1. Đo lường Token mã nguồn thô
        const rawTokens = estimateTokens(content);
        totalRawTokens += rawTokens;

        // 2. Mô phỏng dữ liệu Graph Metadata được lưu và gửi đi qua MCP
        // Lấy tên class và danh sách các phương thức qua Regex đơn giản
        const classNameMatch = content.match(/(class|interface|enum)\s+(\w+)/);
        const className = classNameMatch ? classNameMatch[2] : path.basename(file, '.java');
        
        const packageMatch = content.match(/package\s+([\w.]+);/);
        const packageName = packageMatch ? packageMatch[1] : "";

        // Trích xuất tên phương thức cơ bản
        const methodMatches = [...content.matchAll(/(public|private|protected|static|\s) +[\w\<\>\[\]]+\s+(\w+)\s*\([^\)]*\)\s*(\{|\b)/g)];
        const methods = methodMatches.map(m => m[2]).filter(name => !["if", "for", "while", "switch", "catch", "synchronized"].includes(name));

        // Trích xuất import để làm dependency giả lập
        const importMatches = [...content.matchAll(/import\s+([\w.]+);/g)];
        const dependencies = importMatches.map(m => m[1].split('.').pop());

        // Cấu trúc dữ liệu JSON biểu diễn nút Đồ thị sẽ truyền qua MCP
        const graphMetadata = {
            name: className,
            package: packageName,
            type: content.includes('interface ') ? 'Interface' : 'Class',
            filepath: file,
            dependencies: dependencies,
            methods: methods
        };

        const metadataString = JSON.stringify(graphMetadata, null, 2);
        const graphTokens = estimateTokens(metadataString);
        totalGraphTokens += graphTokens;

        const savings = ((rawTokens - graphTokens) / rawTokens * 100).toFixed(1);
        fileReports.push({
            name: className,
            rawTokens,
            graphTokens,
            savings: `${savings}%`
        });
    });

    // In bảng kết quả chi tiết
    console.log(String.prototype.concat(
        "STT".padEnd(5),
        "Tên Class".padEnd(30),
        "Token Thô (File)".padEnd(20),
        "Token Đồ Thị (MCP)".padEnd(20),
        "Tiết Kiệm".padEnd(12)
    ));
    console.log("-".repeat(90));
    
    fileReports.slice(0, 15).forEach((report, index) => {
        console.log(String.prototype.concat(
            String(index + 1).padEnd(5),
            report.name.padEnd(30),
            String(report.rawTokens).padEnd(20),
            String(report.graphTokens).padEnd(20),
            report.savings.padEnd(12)
        ));
    });

    if (fileReports.length > 15) {
        console.log(`... và ${fileReports.length - 15} file khác.`);
    }

    console.log("-".repeat(90));
    console.log("TỔNG CỘNG:");
    console.log(`- Tổng Token mã nguồn thô (nếu tải cả project): ${totalRawTokens.toLocaleString()} tokens`);
    console.log(`- Tổng Token mô tả đồ thị (khi MCP trả về):       ${totalGraphTokens.toLocaleString()} tokens`);
    
    const totalSavings = ((totalRawTokens - totalGraphTokens) / totalRawTokens * 100).toFixed(2);
    console.log(`=> Tỷ lệ tiết kiệm Token thực tế:                ${totalSavings}%\n`);
    console.log("Nhận xét:");
    console.log(`- Nếu AI Agent tìm kiếm qua MCP Đồ thị, chỉ tốn khoảng ${(totalGraphTokens / totalRawTokens * 100).toFixed(1)}% lượng token so với việc đọc trực tiếp.`);
    console.log("- Đồ thị giúp AI Agent tự do điều hướng và phân tích cấu trúc dự án mà không sợ tràn ngữ cảnh.");
    console.log("==========================================================================");
}

runBenchmark();
