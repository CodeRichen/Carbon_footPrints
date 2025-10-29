/*
 javac TestClient.java
 java TestClient localhost 5000
*/
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.Scanner;

public class TestClient {
    private static final int BUFFER_SIZE = 65536; // 64KB 緩衝區

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java TestClient [server_ip] [port]");
            System.out.println("Example: java TestClient localhost 5000");
            System.exit(1);
        }

        String serverIP = args[0];
        int port = Integer.parseInt(args[1]);

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\n========== 測試選單 ==========");
            System.out.println("1. 上傳一般資料（姓名 + 碳排放量）");
            System.out.println("2. 上傳圖片檔案");
            System.out.println("3. 查詢排行榜");
            System.out.println("4. 壓力測試（上傳多個圖片）");
            System.out.println("0. 退出");
            System.out.print("請選擇: ");

            String choice = scanner.nextLine();

            try {
                switch (choice) {
                    case "1":
                        uploadData(serverIP, port, scanner);
                        break;
                    case "2":
                        uploadImage(serverIP, port, scanner);
                        break;
                    case "3":
                        getRanking(serverIP, port);
                        break;
                    case "4":
                        stressTest(serverIP, port, scanner);
                        break;
                    case "0":
                        System.out.println("再見！");
                        scanner.close();
                        System.exit(0);
                    default:
                        System.out.println("無效的選擇！");
                }
            } catch (Exception e) {
                System.out.println("❌ 錯誤: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 上傳一般資料
     */
    private static void uploadData(String serverIP, int port, Scanner scanner) throws IOException {
        System.out.print("請輸入姓名: ");
        String name = scanner.nextLine();
        System.out.print("請輸入碳排放量: ");
        String total = scanner.nextLine();

        try (Socket socket = new Socket(serverIP, port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            // 發送命令
            out.writeUTF("UPLOAD_DATA");
            out.writeUTF(name + "," + total);
            out.flush();

            // 接收回應
            String response = in.readUTF();
            System.out.println("\n伺服器回應:\n" + response);
        }
    }

    /**
     * 上傳圖片檔案
     */
    private static void uploadImage(String serverIP, int port, Scanner scanner) throws IOException {
        System.out.print("請輸入姓名: ");
        String name = scanner.nextLine();
        System.out.print("請輸入圖片檔案路徑: ");
        String filePath = scanner.nextLine();

        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("❌ 檔案不存在: " + filePath);
            return;
        }

        System.out.println("📤 準備上傳檔案: " + file.getName());
        System.out.println("📊 檔案大小: " + (file.length() / 1024) + " KB");

        long startTime = System.currentTimeMillis();

        try (Socket socket = new Socket(serverIP, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
             FileInputStream fis = new FileInputStream(file)) {

            // 設定 socket 選項
            socket.setSendBufferSize(BUFFER_SIZE);
            socket.setTcpNoDelay(false);

            // 發送命令
            out.writeUTF("UPLOAD_IMAGE");
            out.writeUTF(name);
            out.writeLong(file.length());
            out.flush();

            // 分塊傳送檔案
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalSent = 0;
            int bytesRead;

            System.out.println("⏳ 開始傳送...");

            while ((bytesRead = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalSent += bytesRead;

                // 顯示進度
                if (totalSent % (BUFFER_SIZE * 10) == 0 || totalSent == file.length()) {
                    int progress = (int) ((totalSent * 100) / file.length());
                    System.out.print("\r傳送進度: " + progress + "% (" + (totalSent / 1024) + " KB / " + (file.length() / 1024) + " KB)");
                }
            }

            out.flush();
            System.out.println("\n✅ 檔案傳送完成！");

            // 接收伺服器回應
            String response = in.readUTF();
            long endTime = System.currentTimeMillis();
            double seconds = (endTime - startTime) / 1000.0;
            double speed = (file.length() / 1024.0 / 1024.0) / seconds;

            System.out.println("\n伺服器回應: " + response);
            System.out.println("📈 傳輸統計:");
            System.out.println("   總耗時: " + String.format("%.2f", seconds) + " 秒");
            System.out.println("   平均速度: " + String.format("%.2f", speed) + " MB/s");
        }
    }

    /**
     * 查詢排行榜
     */
    private static void getRanking(String serverIP, int port) throws IOException {
        try (Socket socket = new Socket(serverIP, port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            out.writeUTF("GET_RANKING");
            out.flush();

            String response = in.readUTF();
            System.out.println("\n" + response);
        }
    }

    /**
     * 壓力測試：連續上傳多個圖片
     */
    private static void stressTest(String serverIP, int port, Scanner scanner) throws IOException {
        System.out.print("請輸入圖片檔案路徑: ");
        String filePath = scanner.nextLine();
        System.out.print("請輸入上傳次數: ");
        int count = Integer.parseInt(scanner.nextLine());

        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("❌ 檔案不存在: " + filePath);
            return;
        }

        System.out.println("\n🚀 開始壓力測試，將上傳 " + count + " 次");
        long totalStartTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        for (int i = 1; i <= count; i++) {
            System.out.println("\n--- 第 " + i + " 次上傳 ---");
            try {
                String name = "測試使用者_" + i;
                
                try (Socket socket = new Socket(serverIP, port);
                     DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE));
                     DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
                     FileInputStream fis = new FileInputStream(file)) {

                    socket.setSendBufferSize(BUFFER_SIZE);
                    socket.setTcpNoDelay(false);

                    out.writeUTF("UPLOAD_IMAGE");
                    out.writeUTF(name);
                    out.writeLong(file.length());
                    out.flush();

                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;

                    while ((bytesRead = fis.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }

                    out.flush();
                    String response = in.readUTF();
                    System.out.println("回應: " + response);
                    
                    if (response.contains("✅")) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                }
            } catch (Exception e) {
                System.out.println("❌ 上傳失敗: " + e.getMessage());
                failCount++;
            }

            // 每次上傳之間暫停 500ms
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        long totalEndTime = System.currentTimeMillis();
        double totalSeconds = (totalEndTime - totalStartTime) / 1000.0;

        System.out.println("\n========== 壓力測試結果 ==========");
        System.out.println("總次數: " + count);
        System.out.println("成功: " + successCount);
        System.out.println("失敗: " + failCount);
        System.out.println("總耗時: " + String.format("%.2f", totalSeconds) + " 秒");
        System.out.println("平均每次: " + String.format("%.2f", totalSeconds / count) + " 秒");
    }
}