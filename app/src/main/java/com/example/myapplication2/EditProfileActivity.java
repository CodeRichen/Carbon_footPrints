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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
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

                // ✅ 自動上傳圖片到 Server
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

    // ✅ 自動上傳圖片到 Server（壓縮後）
    private void uploadImageToServer(File imageFile, String userName) {
        new Thread(() -> {
            try {
                // 讀取圖片
                Bitmap originalImage = BitmapFactory.decodeFile(imageFile.getAbsolutePath());

                // 💡 降低解析度 (縮小 7 倍)
                int newWidth = originalImage.getWidth() / 7;
                int newHeight = originalImage.getHeight() / 7;
                Bitmap resizedImage = Bitmap.createScaledBitmap(originalImage, newWidth, newHeight, true);

                // 💡 壓縮畫質 (JPEG, 品質 30%)
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                resizedImage.compress(Bitmap.CompressFormat.JPEG, 30, baos);
                byte[] compressedBytes = baos.toByteArray();

                System.out.println("壓縮後圖片大小：" + compressedBytes.length / 1024 + " KB");

                // 連接 Server
                try (Socket socket = new Socket(Config.SERVER_IP, Config.SERVER_PORT);
                     DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                     DataInputStream in = new DataInputStream(socket.getInputStream())) {

                    // 轉成 Base64
                    String base64Image = Base64.getEncoder().encodeToString(compressedBytes);

                    // 傳送格式：IMAGE:使用者名稱_profile.jpg:Base64字串
                    String safeName = userName.replaceAll("[^a-zA-Z0-9]", "_");
                    String message = "IMAGE:" + safeName + "_profile.jpg:" + base64Image;
                    out.writeUTF(message);
                    out.flush();

                    // 接收伺服器回覆
                    String response = in.readUTF();
                    System.out.println("伺服器回覆: " + response);

                    // 在主執行緒顯示結果
                    runOnUiThread(() -> {
                        Toast.makeText(this, "個人資料已儲存並上傳成功！", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "上傳失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish(); // 即使上傳失敗也返回（本地資料已儲存）
                });
            }
        }).start();
    }
}