package com.example.myapplication2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RankingActivity extends AppCompatActivity {
    private TableLayout tableRanking;
    String serverIP = Config.SERVER_IP;
    int port = Config.SERVER_PORT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ranking);
        tableRanking = findViewById(R.id.table_ranking);
        new Thread(this::fetchRankingFromServer).start();
    }

    private void fetchRankingFromServer() {
        Socket socket = null;
        DataOutputStream out = null;
        DataInputStream in = null;

        try {
            socket = new Socket(serverIP, port);
            socket.setReceiveBufferSize(65536); // 64KB 緩衝區

            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));

            // ✅ 傳送排行榜請求(含圖片)
            out.writeUTF("GET_RANKING_WITH_IMAGE");
            out.flush();
            Log.d("Ranking", "已發送 GET_RANKING_WITH_IMAGE 請求");

            // 🔹 使用 InputStream 讀取大型資料
            String response = readLargeResponse(in);

            Log.d("Ranking", "收到回應，長度: " + response.length() + " 字元 (" + (response.length() / 1024) + " KB)");
            Log.d("Ranking", "前 500 字元: " + response.substring(0, Math.min(500, response.length())));

            // ✅ 提取 JSON 陣列部分
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');

            if (start < 0 || end < 0) {
                Log.e("Ranking", "無法找到 JSON 陣列: start=" + start + ", end=" + end);
                runOnUiThread(() -> showError("伺服器回傳格式錯誤"));
                return;
            }

            String jsonPart = response.substring(start, end + 1);
            Log.d("Ranking", "JSON 部分長度: " + jsonPart.length() + " 字元");

            JSONArray arr = new JSONArray(jsonPart);
            Log.d("Ranking", "JSON 陣列大小: " + arr.length());

            List<UserData> list = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String name = obj.optString("name", "未知");
                String total = obj.optString("total", "0");
                String imageBase64 = obj.optString("image", "");

                Log.d("Ranking", "======= 使用者 " + (i+1) + " =======");
                Log.d("Ranking", "姓名: " + name);
                Log.d("Ranking", "碳排放: " + total);
                Log.d("Ranking", "圖片 Base64 長度: " + imageBase64.length() + " 字元");

                if (!imageBase64.isEmpty()) {
                    Log.d("Ranking", "Base64 前 100 字元: " + imageBase64.substring(0, Math.min(100, imageBase64.length())));
                }

                double totalValue = 0;
                try {
                    totalValue = Double.parseDouble(total);
                } catch (Exception e) {
                    Log.e("Ranking", "無法解析 total: " + total);
                }

                // 🔹 解碼 Base64 為 Bitmap
                Bitmap bitmap = decodeBase64Image(imageBase64, name);
                list.add(new UserData(name, totalValue, bitmap));
            }

            // ✅ 依照 total 排序（數值越小名次越前）
            list.sort(Comparator.comparingDouble(u -> u.total));

            // ✅ 更新 UI
            final int imageCount = (int) list.stream().filter(u -> u.image != null).count();
            runOnUiThread(() -> {
                showRankingTable(list);
                Toast.makeText(this,
                        "載入 " + list.size() + " 筆資料，" + imageCount + " 張圖片",
                        Toast.LENGTH_LONG).show();
            });

        } catch (IOException e) {
            Log.e("Ranking", "連線錯誤: " + e.getMessage());
            e.printStackTrace();
            runOnUiThread(() -> showError("❌ 無法連線到伺服器: " + e.getMessage()));
        } catch (Exception e) {
            Log.e("Ranking", "解析錯誤: " + e.getMessage());
            e.printStackTrace();
            runOnUiThread(() -> showError("❌ 資料解析錯誤: " + e.getMessage()));
        } finally {
            // 確保資源被關閉
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
     * 🔹 使用 InputStream 讀取大型回應資料
     */
    private String readLargeResponse(DataInputStream in) throws IOException {
        Log.d("Ranking", "開始讀取伺服器回應...");

        // 先讀取資料長度（DataInputStream.writeUTF 會先寫入長度）
        int length = in.readUnsignedShort();
        Log.d("Ranking", "資料長度標記: " + length + " bytes");

        // 使用 ByteArrayOutputStream 收集資料
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; // 8KB 緩衝區
        int totalRead = 0;
        int bytesRead;

        // 讀取指定長度的資料
        while (totalRead < length) {
            int toRead = Math.min(buffer.length, length - totalRead);
            bytesRead = in.read(buffer, 0, toRead);

            if (bytesRead == -1) {
                throw new IOException("連接中斷，已讀取 " + totalRead + "/" + length + " bytes");
            }

            baos.write(buffer, 0, bytesRead);
            totalRead += bytesRead;

            // 顯示進度
            if (totalRead % (50 * 1024) == 0 || totalRead == length) {
                Log.d("Ranking", "讀取進度: " + (totalRead * 100 / length) + "% (" + (totalRead / 1024) + " KB / " + (length / 1024) + " KB)");
            }
        }

        String result = baos.toString("UTF-8");
        Log.d("Ranking", "讀取完成！總計: " + totalRead + " bytes");

        return result;
    }

    /**
     * 🔹 解碼 Base64 圖片
     */
    private Bitmap decodeBase64Image(String imageBase64, String userName) {
        if (imageBase64.isEmpty()) {
            Log.w("Ranking", "⚠️ 無圖片資料: " + userName);
            return null;
        }

        try {
            // 🔹 移除可能的前綴 (data:image/...)
            if (imageBase64.contains(",")) {
                imageBase64 = imageBase64.split(",")[1];
                Log.d("Ranking", "移除前綴後長度: " + imageBase64.length());
            }

            // 🔹 移除所有空白字元和換行
            imageBase64 = imageBase64.replaceAll("\\s+", "");
            Log.d("Ranking", "移除空白後長度: " + imageBase64.length());

            // 🔹 解碼
            byte[] decodedBytes = Base64.decode(imageBase64, Base64.DEFAULT);
            Log.d("Ranking", "解碼後 byte 陣列大小: " + decodedBytes.length + " bytes (" + (decodedBytes.length / 1024) + " KB)");

            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

            if (bitmap != null) {
                Log.d("Ranking", "✅ 圖片解碼成功! 使用者: " + userName + ", 尺寸: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            } else {
                Log.e("Ranking", "❌ BitmapFactory 回傳 null: " + userName);
            }

            return bitmap;

        } catch (IllegalArgumentException e) {
            Log.e("Ranking", "❌ Base64 解碼失敗 (" + userName + "): " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            Log.e("Ranking", "❌ 圖片處理失敗 (" + userName + "): " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    private void showRankingTable(List<UserData> list) {
        tableRanking.removeAllViews();

        // 🏁 表頭
        TableRow header = new TableRow(this);
        header.addView(createCell("名次", true));
        header.addView(createCell("圖片", true));
        header.addView(createCell("使用者", true));
        header.addView(createCell("碳排放量 (g CO₂)", true));
        tableRanking.addView(header);

        // 🧍‍♂️ 資料列
        int rank = 1;
        for (UserData user : list) {
            TableRow row = new TableRow(this);
            row.setPadding(0, 8, 0, 8);

            // 名次
            row.addView(createCell(String.valueOf(rank), false));

            // 圖片 🔹
            ImageView imgView = new ImageView(this);
            TableRow.LayoutParams params = new TableRow.LayoutParams(150, 150);
            params.setMargins(8, 8, 8, 8);
            imgView.setLayoutParams(params);
            imgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imgView.setPadding(4, 4, 4, 4);
            imgView.setBackgroundColor(0xFFEEEEEE);

            if (user.image != null) {
                imgView.setImageBitmap(user.image);
                Log.d("Ranking", "顯示圖片: " + user.name);
            } else {
                imgView.setImageResource(android.R.drawable.ic_menu_gallery);
                Log.d("Ranking", "無圖片，顯示預設圖示: " + user.name);
            }
            row.addView(imgView);

            // 使用者名稱
            row.addView(createCell(user.name, false));

            // 碳排放量
            row.addView(createCell(String.format("%.2f", user.total), false));

            tableRanking.addView(row);
            rank++;
        }
    }

    private TextView createCell(String text, boolean isHeader) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(16, 12, 16, 12);
        tv.setTextSize(isHeader ? 18 : 16);
        tv.setTextColor(isHeader ? 0xFF000000 : 0xFF333333);
        tv.setBackgroundColor(isHeader ? 0xFFE0E0E0 : 0xFFFFFFFF);
        tv.setGravity(android.view.Gravity.CENTER);
        return tv;
    }

    private void showError(String message) {
        tableRanking.removeAllViews();
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setPadding(20, 20, 20, 20);
        tv.setTextSize(16);
        tableRanking.addView(tv);
    }

    // 資料類別 🔹 加入 Bitmap
    static class UserData {
        String name;
        double total;
        Bitmap image;

        UserData(String n, double t, Bitmap img) {
            name = n;
            total = t;
            image = img;
        }
    }
}