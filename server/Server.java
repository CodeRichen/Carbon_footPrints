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

    public Server() {
        try {
            serverSocket = new ServerSocket(port);
           serverSocket.setReuseAddress(true);

            System.out.println("Server started on IP " +
                InetAddress.getLocalHost().getHostAddress() +
                " and port " + port);


            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Connected from client: " + socket.getInetAddress().getHostAddress());
                new Thread(new ClientHandler(socket, messageout)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
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
        // 建立 JSON 資料
        JSONObject obj = new JSONObject();
        obj.put("name", name);
        obj.put("total", total);

        // Google Script 部署網址

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);

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

    public ClientHandler(Socket socket, String messageout) {
        this.socket = socket;
        this.messageout = messageout;
    }

@Override
public void run() {
    try {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        String message = in.readUTF();

        // ✅ 排行榜請求
        if (message.equals("GET_RANKING")) {
            String allData = Server.readAllData();
            out.writeUTF("=== 所有上傳資料 ===\n" + allData);
            out.flush();
        }

        // ✅ 圖片上傳處理
 else if (message.startsWith("IMAGE:")) {
    String[] parts = message.split(":", 3);
    if (parts.length < 3) {
        out.writeUTF("錯誤：圖片格式不正確");
        out.flush();
        return;
    }

    String name = parts[1];        // 第二段為姓名
    String base64Data = parts[2];  // 第三段為圖片 Base64

    try {
        // 🔹 建立要上傳的 JSON 資料
        JSONObject obj = new JSONObject();
        obj.put("name", name);
        obj.put("imageBase64", base64Data);

        // 🔹 Google Script 網址
        URL url = new URL(Server.URL_STRING);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);

        // 🔹 傳送資料到 Google Sheet
        try (OutputStream os = conn.getOutputStream()) {
            os.write(obj.toString().getBytes("UTF-8"));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            out.writeUTF("✅ 圖片 Base64 已成功上傳到 Google Sheet（欄 C）");
            System.out.println("成功上傳圖片 Base64 到 Google Sheet: " + name);
        } else {
            out.writeUTF("❌ 上傳失敗，狀態碼：" + responseCode);
            System.out.println("上傳失敗：" + responseCode);
        }

        conn.disconnect();
    } catch (Exception e) {
        out.writeUTF("❌ 上傳圖片 Base64 時發生錯誤：" + e.getMessage());
        System.out.println("Error uploading Base64: " + e.getMessage());
    }

    out.flush();
}



        // ✅ 一般資料上傳
        else {
            String[] parts = message.split(",");
            String name = parts.length > 0 ? parts[0] : "未知";
            String total = parts.length > 1 ? parts[1] : "N/A";

            System.out.println("[" + new Date() + "] 收到使用者: " + name + "，碳排放量: " + total + " g CO2");

            // 儲存到 Google Sheets
            Server.saveData(name, total);

            // 回覆全部資料
            String allData = Server.readAllData();
            out.writeUTF("伺服器已收到資料！\n\n=== 所有上傳資料 ===\n" + allData);
            out.flush();
        }

        in.close();
        out.close();
        socket.close();
    } catch (IOException e) {
        System.out.println("Client disconnected: " + e.getMessage());
    }
}


}
