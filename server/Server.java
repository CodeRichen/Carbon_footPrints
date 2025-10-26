/*
 javac -cp ".;lib/json-20231013.jar" Server.java
 java -cp ".;lib/json-20231013.jar" Server 5000 Hello
*/
import java.net.*;
import java.io.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class Server {
    private ServerSocket serverSocket;
    private static int port;
    private static String messageout;
    private static final String DATA_FILE = "data.txt";
    private static final String urlString = "https://script.google.com/macros/s/AKfycbxcTHEwwAyBUjqfDk3cwhhOzxLLaYt2N3gIVGrkVXSVRMrJnpL9Ypu9OrYklIVMvLJq4w/exec";
    public static final String URL_STRING = urlString;
    
    // 設定緩衝區大小為 64KB，適合大型檔案傳輸
    private static final int BUFFER_SIZE = 65536;
    // 設定最大檔案大小為 50MB
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    public Server() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            // 設定 socket 的接收緩衝區大小
            serverSocket.setReceiveBufferSize(BUFFER_SIZE);

            System.out.println("Server started on IP " +
                InetAddress.getLocalHost().getHostAddress() +
                " and port " + port);
            System.out.println("Max file size: " + (MAX_FILE_SIZE / 1024 / 1024) + " MB");

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Connected from client: " + socket.getInetAddress().getHostAddress());
                new Thread(new ClientHandler(socket, messageout)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                    System.out.println("Server socket closed.");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }   

    public static synchronized void saveData(String name, String total) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("name", name);
            obj.put("total", total);

            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000); // 30秒連接超時
            conn.setReadTimeout(30000);    // 30秒讀取超時

            try (OutputStream os = conn.getOutputStream()) {
                os.write(obj.toString().getBytes("UTF-8"));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("成功上傳至 Google Sheets");
            } else {
                System.out.println("上傳失敗，狀態碼: " + responseCode);
            }

            conn.disconnect();
        } catch (Exception e) {
            System.out.println("Error uploading to Google Sheets: " + e.getMessage());
        }
    }

    public static synchronized String readAllData() {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            return sb.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java Server [port] [messageout]");
            System.exit(1);
        }
        port = Integer.parseInt(args[0]);
        messageout = args[1];
        new Server();
    }
}

class ClientHandler implements Runnable {
    private Socket socket;
    private String messageout;
    private static final int BUFFER_SIZE = 65536;
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    public ClientHandler(Socket socket, String messageout) {
        this.socket = socket;
        this.messageout = messageout;
    }

    @Override
    public void run() {
        DataInputStream in = null;
        DataOutputStream out = null;
        
        try {
            // 設定 socket 選項以優化大檔案傳輸
            socket.setSendBufferSize(BUFFER_SIZE);
            socket.setReceiveBufferSize(BUFFER_SIZE);
            socket.setTcpNoDelay(false); // 啟用 Nagle 演算法以提高效率
            
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE));

            // 讀取命令類型
            String command = in.readUTF();
            System.out.println("收到命令: " + command);

            // ✅ 排行榜請求
            if (command.equals("GET_RANKING")) {
                String allData = Server.readAllData();
                out.writeUTF("=== 所有上傳資料 ===\n" + allData);
                out.flush();
            }

            // ✅ 大型圖片上傳處理（使用分塊傳輸）
            else if (command.equals("UPLOAD_IMAGE")) {
                handleImageUpload(in, out);
            }

            // ✅ 一般資料上傳
            else if (command.equals("UPLOAD_DATA")) {
                String data = in.readUTF();
                String[] parts = data.split(",");
                String name = parts.length > 0 ? parts[0] : "未知";
                String total = parts.length > 1 ? parts[1] : "N/A";

                System.out.println("[" + new Date() + "] 收到使用者: " + name + "，碳排放量: " + total + " g CO2");

                Server.saveData(name, total);

                String allData = Server.readAllData();
                out.writeUTF("伺服器已收到資料！\n\n=== 所有上傳資料 ===\n" + allData);
                out.flush();
            }

            else {
                out.writeUTF("❌ 未知的命令: " + command);
                out.flush();
            }

        } catch (IOException e) {
            System.out.println("Client disconnected: " + e.getMessage());
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 處理大型圖片上傳（使用分塊傳輸，避免記憶體溢出）
     */
    private void handleImageUpload(DataInputStream in, DataOutputStream out) throws IOException {
        try {
            // 讀取姓名
            String name = in.readUTF();
            System.out.println("接收圖片上傳請求，使用者: " + name);

            // 讀取檔案大小
            long fileSize = in.readLong();
            System.out.println("檔案大小: " + (fileSize / 1024) + " KB");

            // 檢查檔案大小
            if (fileSize > MAX_FILE_SIZE) {
                out.writeUTF("❌ 檔案過大，最大允許 " + (MAX_FILE_SIZE / 1024 / 1024) + " MB");
                out.flush();
                return;
            }

            // 使用分塊讀取，避免一次載入整個檔案到記憶體
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalRead = 0;
            int bytesRead;
            
            System.out.println("開始接收檔案資料...");
            long startTime = System.currentTimeMillis();

            while (totalRead < fileSize) {
                int toRead = (int) Math.min(BUFFER_SIZE, fileSize - totalRead);
                bytesRead = in.read(buffer, 0, toRead);
                
                if (bytesRead == -1) {
                    throw new IOException("連接中斷");
                }
                
                baos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                
                // 顯示進度
                if (totalRead % (BUFFER_SIZE * 10) == 0 || totalRead == fileSize) {
                    int progress = (int) ((totalRead * 100) / fileSize);
                    System.out.println("接收進度: " + progress + "% (" + (totalRead / 1024) + " KB / " + (fileSize / 1024) + " KB)");
                }
            }

            long endTime = System.currentTimeMillis();
            double seconds = (endTime - startTime) / 1000.0;
            double speed = (fileSize / 1024.0 / 1024.0) / seconds;
            System.out.println("接收完成！耗時: " + String.format("%.2f", seconds) + " 秒，速度: " + String.format("%.2f", speed) + " MB/s");

            // 將圖片轉換為 Base64
            byte[] imageBytes = baos.toByteArray();
            String base64Data = Base64.getEncoder().encodeToString(imageBytes);
            
            System.out.println("Base64 編碼完成，長度: " + base64Data.length());

            // 上傳到 Google Sheets
            JSONObject obj = new JSONObject();
            obj.put("name", name);
            obj.put("imageBase64", base64Data);

            URL url = new URL(Server.URL_STRING);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(60000); // 60秒超時，因為圖片可能很大
            conn.setReadTimeout(60000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(obj.toString().getBytes("UTF-8"));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                out.writeUTF("✅ 圖片已成功上傳到 Google Sheet");
                System.out.println("成功上傳圖片到 Google Sheet: " + name);
            } else {
                out.writeUTF("❌ 上傳失敗，狀態碼：" + responseCode);
                System.out.println("上傳失敗：" + responseCode);
            }

            conn.disconnect();
            out.flush();

        } catch (Exception e) {
            out.writeUTF("❌ 上傳圖片時發生錯誤：" + e.getMessage());
            out.flush();
            System.out.println("Error uploading image: " + e.getMessage());
            e.printStackTrace();
        }
    }
}