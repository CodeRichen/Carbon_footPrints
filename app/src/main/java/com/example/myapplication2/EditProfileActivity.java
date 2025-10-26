package com.example.myapplication2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Base64;

public class EditProfileActivity extends AppCompatActivity {

    private ImageView imageProfile;
    private EditText editName;
    private Button buttonSelectImage, buttonSave;

    private Uri selectedImageUri;
    private SharedPreferences prefs;

    // 🎯 壓縮目標設定
    private static final int TARGET_SIZE_KB = 35;  // 目標大小 35 KB
    private static final int MAX_SIZE_KB = 45;     // 最大允許 45 KB
    private static final int MIN_QUALITY = 10;     // 最低畫質
    private static final int MAX_QUALITY = 95;     // 最高畫質

    // 🔧 傳輸緩衝區設定
    private static final int BUFFER_SIZE = 65536;  // 64KB 緩衝區

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_profile);

        imageProfile = findViewById(R.id.image_profile);
        editName = findViewById(R.id.edit_name);
        buttonSelectImage = findViewById(R.id.button_select_image);
        buttonSave = findViewById(R.id.button_save);
        prefs = getSharedPreferences("user_profile", MODE_PRIVATE);

        // Load existing data
        String savedName = prefs.getString("name", "");
        String imagePath = prefs.getString("image_path", null);
        editName.setText(savedName);

        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Glide.with(this)
                        .load(imgFile)
                        .skipMemoryCache(true)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                        .circleCrop()
                        .placeholder(R.drawable.profile_placeholder)
                        .into(imageProfile);
            } else {
                imageProfile.setImageResource(R.drawable.profile_placeholder);
            }
        } else {
            imageProfile.setImageResource(R.drawable.profile_placeholder);
        }

        // Select image
        buttonSelectImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            imagePickerLauncher.launch(intent);
        });

        buttonSave.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "請輸入名稱", Toast.LENGTH_SHORT).show();
                return;
            }

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("name", name);

            // 儲存頭像並自動上傳
            if (selectedImageUri != null) {
                String safeName = name.replaceAll("[^a-zA-Z0-9]", "_");
                File file = new File(getFilesDir(), "profile_images/" + safeName + ".jpg");
                editor.putString("image_path", file.getAbsolutePath());

                // 更新 UI
                Glide.with(this)
                        .load(file)
                        .circleCrop()
                        .placeholder(R.drawable.profile_placeholder)
                        .into(imageProfile);

                // ✅ 自動上傳圖片到 Server（智能壓縮 + 優化傳輸）
                uploadImageToServer(file, name);
            }

            editor.apply();

            Toast.makeText(this, "正在儲存並上傳個人資料...", Toast.LENGTH_SHORT).show();
        });
    }

    // Pick image result handler
    private final ActivityResultLauncher<Intent> imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    try {
                        String currentName = editName.getText().toString();
                        File savedFile = saveImageToInternalStorage(uri, currentName);
                        selectedImageUri = Uri.fromFile(savedFile);

                        Glide.with(this)
                                .load(savedFile)
                                .circleCrop()
                                .placeholder(R.drawable.profile_placeholder)
                                .into(imageProfile);
                    } catch (IOException e) {
                        e.printStackTrace();
                        Toast.makeText(this, "無法儲存圖片", Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    // Save picked image to internal storage
    private File saveImageToInternalStorage(Uri imageUri, String userName) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(imageUri);
        Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
        inputStream.close();

        File directory = new File(getFilesDir(), "profile_images");
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String safeName = userName.replaceAll("[^a-zA-Z0-9]", "_");
        File file = new File(directory, safeName + ".jpg");

        FileOutputStream fos = new FileOutputStream(file);
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
        fos.close();

        return file;
    }

    /**
     * 🧠 智能壓縮圖片並儲存為臨時檔案
     * 自動偵測圖片大小並動態調整壓縮參數
     */
    private File smartCompressImageToFile(Bitmap originalBitmap, String userName) throws IOException {
        int originalWidth = originalBitmap.getWidth();
        int originalHeight = originalBitmap.getHeight();

        System.out.println("=== 開始智能壓縮 ===");
        System.out.println("原始尺寸: " + originalWidth + "x" + originalHeight);

        // 步驟 1: 初步估算縮放比例
        ByteArrayOutputStream testBaos = new ByteArrayOutputStream();
        originalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, testBaos);
        int estimatedSize = testBaos.size();
        System.out.println("預估原始大小: " + (estimatedSize / 1024) + " KB (85% 品質)");

        // 計算需要的縮放比例
        double scaleFactor = 1.0;
        if (estimatedSize > TARGET_SIZE_KB * 1024) {
            scaleFactor = Math.sqrt((double)(TARGET_SIZE_KB * 1024) / estimatedSize);
            scaleFactor = Math.max(0.2, Math.min(1.0, scaleFactor));
        }

        int targetWidth = (int)(originalWidth * scaleFactor);
        int targetHeight = (int)(originalHeight * scaleFactor);

        System.out.println("計算縮放比例: " + String.format("%.2f", scaleFactor));
        System.out.println("目標尺寸: " + targetWidth + "x" + targetHeight);

        // 步驟 2: 縮放圖片
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true);

        // 步驟 3: 二分搜尋法找出最佳品質
        int bestQuality = findOptimalQuality(scaledBitmap, TARGET_SIZE_KB, MAX_SIZE_KB);

        System.out.println("最佳品質設定: " + bestQuality + "%");

        // 步驟 4: 儲存壓縮後的圖片到臨時檔案
        File tempDir = new File(getCacheDir(), "compressed_images");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }

        String safeName = userName.replaceAll("[^a-zA-Z0-9]", "_");
        File compressedFile = new File(tempDir, safeName + "_compressed.jpg");

        FileOutputStream fos = new FileOutputStream(compressedFile);
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, bestQuality, fos);
        fos.close();

        int finalSizeKB = (int)(compressedFile.length() / 1024);
        System.out.println("最終大小: " + finalSizeKB + " KB");
        System.out.println("壓縮率: " + String.format("%.1f", (1 - (double)compressedFile.length() / estimatedSize) * 100) + "%");
        System.out.println("=== 壓縮完成 ===\n");

        // 清理資源
        if (scaledBitmap != originalBitmap) {
            scaledBitmap.recycle();
        }

        return compressedFile;
    }

    /**
     * 🔍 使用二分搜尋法找出最佳壓縮品質
     */
    private int findOptimalQuality(Bitmap bitmap, int targetKB, int maxKB) {
        int left = MIN_QUALITY;
        int right = MAX_QUALITY;
        int bestQuality = MIN_QUALITY;
        int bestSize = Integer.MAX_VALUE;

        System.out.println("開始二分搜尋最佳品質...");

        while (left <= right) {
            int mid = (left + right) / 2;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, mid, baos);
            int currentSizeKB = baos.size() / 1024;

            System.out.println("  測試品質 " + mid + "%: " + currentSizeKB + " KB");

            if (currentSizeKB <= maxKB) {
                if (Math.abs(currentSizeKB - targetKB) < Math.abs(bestSize - targetKB)) {
                    bestQuality = mid;
                    bestSize = currentSizeKB;
                }

                if (currentSizeKB < targetKB) {
                    left = mid + 1;
                } else {
                    right = mid - 1;
                }
            } else {
                right = mid - 1;
            }
        }

        // 安全檢查
        ByteArrayOutputStream finalCheck = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, bestQuality, finalCheck);
        int finalCheckSizeKB = finalCheck.size() / 1024;

        if (finalCheckSizeKB > maxKB && bestQuality > MIN_QUALITY) {
            bestQuality -= 5;
            bestQuality = Math.max(MIN_QUALITY, bestQuality);
            System.out.println("  安全調整: 品質降至 " + bestQuality + "%");
        }

        return bestQuality;
    }

    /**
     * ✅ 上傳圖片到 Server（使用分塊傳輸，類似測試檔案）
     */
    private void uploadImageToServer(File originalImageFile, String userName) {
        new Thread(() -> {
            File compressedFile = null;
            try {
                long totalStartTime = System.currentTimeMillis();

                // 讀取原始圖片
                Bitmap originalImage = BitmapFactory.decodeFile(originalImageFile.getAbsolutePath());

                if (originalImage == null) {
                    throw new IOException("無法讀取圖片");
                }

                // 🧠 智能壓縮並儲存為臨時檔案
                compressedFile = smartCompressImageToFile(originalImage, userName);
                originalImage.recycle();

                // 檢查壓縮後大小
                long fileSize = compressedFile.length();
                int fileSizeKB = (int)(fileSize / 1024);

                System.out.println("📤 準備上傳檔案: " + compressedFile.getName());
                System.out.println("📊 檔案大小: " + fileSizeKB + " KB");

                if (fileSizeKB > MAX_SIZE_KB) {
                    runOnUiThread(() ->
                            Toast.makeText(this,
                                    "警告：壓縮後仍超過限制 (" + fileSizeKB + " KB > " + MAX_SIZE_KB + " KB)",
                                    Toast.LENGTH_LONG).show()
                    );
                }

                // 📡 連接 Server 並上傳
                long uploadStartTime = System.currentTimeMillis();

                try (Socket socket = new Socket(Config.SERVER_IP, Config.SERVER_PORT);
                     DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE));
                     DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
                     FileInputStream fis = new FileInputStream(compressedFile)) {

                    // 設定 socket 選項
                    socket.setSendBufferSize(BUFFER_SIZE);
                    socket.setTcpNoDelay(false);

                    // 發送命令
                    out.writeUTF("UPLOAD_IMAGE");
                    out.writeUTF(userName);
                    out.writeLong(fileSize);
                    out.flush();

                    // 分塊傳送檔案
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long totalSent = 0;
                    int bytesRead;

                    System.out.println("⏳ 開始傳送...");

                    while ((bytesRead = fis.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalSent += bytesRead;

                        // 顯示進度（每 10 個緩衝區或最後一塊時更新）
                        if (totalSent % (BUFFER_SIZE * 10) == 0 || totalSent == fileSize) {
                            final int progress = (int) ((totalSent * 100) / fileSize);
                            final long currentSent = totalSent;

                            System.out.println("傳送進度: " + progress + "% (" + (currentSent / 1024) + " KB / " + fileSizeKB + " KB)");

                            runOnUiThread(() -> {
                                Toast.makeText(this,
                                        "上傳進度: " + progress + "%",
                                        Toast.LENGTH_SHORT).show();
                            });
                        }
                    }

                    out.flush();
                    System.out.println("✅ 檔案傳送完成！");

                    // 接收伺服器回應
                    String response = in.readUTF();
                    long uploadEndTime = System.currentTimeMillis();

                    // 計算統計資訊
                    double uploadSeconds = (uploadEndTime - uploadStartTime) / 1000.0;
                    double totalSeconds = (uploadEndTime - totalStartTime) / 1000.0;
                    double uploadSpeed = (fileSize / 1024.0 / 1024.0) / uploadSeconds;

                    System.out.println("\n伺服器回應: " + response);
                    System.out.println("📈 傳輸統計:");
                    System.out.println("   壓縮後大小: " + fileSizeKB + " KB");
                    System.out.println("   上傳耗時: " + String.format("%.2f", uploadSeconds) + " 秒");
                    System.out.println("   總耗時: " + String.format("%.2f", totalSeconds) + " 秒");
                    System.out.println("   平均速度: " + String.format("%.2f", uploadSpeed) + " MB/s");

                    // 在主執行緒顯示結果
                    runOnUiThread(() -> {
                        String successMsg = String.format(
                                "✅ 上傳成功！\n" +
                                        "壓縮後: %d KB\n" +
                                        "上傳耗時: %.2f 秒\n" +
                                        "速度: %.2f MB/s",
                                fileSizeKB,
                                uploadSeconds,
                                uploadSpeed
                        );
                        Toast.makeText(this, successMsg, Toast.LENGTH_LONG).show();
                        finish();
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "上傳失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            } finally {
                // 清理臨時壓縮檔案
                if (compressedFile != null && compressedFile.exists()) {
                    compressedFile.delete();
                    System.out.println("已清理臨時檔案");
                }
            }
        }).start();
    }
}