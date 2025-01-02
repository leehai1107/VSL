package com.translator.vsl.view;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.translator.vsl.R;
import com.translator.vsl.databinding.ActivityCameraBinding;
import com.translator.vsl.viewmodel.CameraViewModel;

public class CameraActivity extends AppCompatActivity {

    private ActivityCameraBinding binding;
    private CameraViewModel viewModel;
    private ImageButton capture, toggleFlash, flipCamera, videoStorage;
    private PreviewView previewView;
    private int cameraFacing = CameraSelector.LENS_FACING_BACK;
    private Toast currentToast;
    private PopupWindow currentPopupWindow = null;  // Track the current PopupWindow
    private final int PICK_VIDEO_REQUEST = 22;
    private ActivityResultLauncher<Intent> videoPickerLauncher;



    private final ActivityResultLauncher<String> activityResultLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), result -> {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startCamera(cameraFacing, previewView);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityCameraBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });


        binding.translateOption.setChecked(false);

        binding.translateOption.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                binding.translateOptionText.setText(R.string.sentence_option);
            } else {
                binding.translateOptionText.setText(R.string.word_option);
            }
        });

        viewModel = new ViewModelProvider(this).get(CameraViewModel.class);
        setupViews();
        setupObservers();

        // Observe the recording state to hide/show the flip button
        viewModel.getIsRecording().observe(this, isRecording -> {
            if (isRecording) {
                flipCamera.setVisibility(View.GONE);  // Hide the flip button when recording starts
            } else {
                flipCamera.setVisibility(View.VISIBLE);  // Show the flip button when recording stops
            }
        });

        // Request permissions if not granted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activityResultLauncher.launch(Manifest.permission.CAMERA);
        } else {
            viewModel.startCamera(cameraFacing, previewView);
        }

        videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        showToast("Đang dịch từ, vui lòng đợi...", true);
                        Uri selectedVideoUri = result.getData().getData();
                        if (selectedVideoUri != null) {
                            viewModel.translateVideo(this, selectedVideoUri);
                        } else {
                            showToast("Không có video nào được chọn", false);
                        }
                    }else {
                        showToast("Không có video nào được chọn", false);
                    }
                }
        );

    }

    private void setupViews() {
        previewView = binding.viewFinder;
        capture = binding.capture;
        toggleFlash = binding.toggleFlash;
        flipCamera = binding.flipCamera;
        videoStorage = binding.videoStorage;


        capture.setOnClickListener(view -> checkPermissionsAndCapture());
        flipCamera.setOnClickListener(view -> flipCamera());
        toggleFlash.setOnClickListener(view -> viewModel.toggleFlash());
        videoStorage.setOnClickListener(view -> showSelectVideoScreen());
    }

    private void setupObservers() {
        viewModel.getTimerText().observe(this, timerText -> binding.recordingTime.setText(timerText));
        viewModel.getCaptureButtonState().observe(this, isRecording ->
                capture.setImageResource(isRecording ? R.drawable.baseline_stop_circle_24 : R.drawable.baseline_fiber_manual_record_24));
        viewModel.getToastMessage().observe(this, pair -> {
            if (pair != null) {
                showToast(pair.first, pair.second);
            }
        });


        viewModel.getFlashEnabledState().observe(this, isEnabled -> {
            toggleFlash.setImageResource(isEnabled ? R.drawable.baseline_flash_off_24 : R.drawable.baseline_flash_on_24);
        });

    }

    @SuppressLint("ObsoleteSdkInt")
    private void checkPermissionsAndCapture() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activityResultLauncher.launch(Manifest.permission.CAMERA);
        } else if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activityResultLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activityResultLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else {
            viewModel.captureVideo(previewView);
        }
    }

    private void flipCamera() {
        cameraFacing = (cameraFacing == CameraSelector.LENS_FACING_BACK) ?
                CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        viewModel.startCamera(cameraFacing, previewView);
    }

    private void showToast(String message, boolean isIndefinite) {
        // Cancel any existing PopupWindow to prevent overlaps
        if (currentPopupWindow != null && currentPopupWindow.isShowing()) {
            currentPopupWindow.dismiss();  // Dismiss the previous PopupWindow if it's showing
        }

        // Create the new toast message
        LayoutInflater inflater = getLayoutInflater();
        View customToastView = inflater.inflate(R.layout.activity_toast, null);

        // Set the message in the custom toast layout
        TextView toastText = customToastView.findViewById(R.id.toast_text);
        toastText.setText(message);

        // Create and show the PopupWindow for indefinite duration
        PopupWindow popupWindow = new PopupWindow(
                customToastView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true // Focusable to allow proper dismissal
        );

        if (isIndefinite) {
            // Show the indefinite PopupWindow
            popupWindow.showAtLocation(binding.getRoot(), Gravity.CENTER, 0, 0);

            // Add a dismiss listener to clean up references
            popupWindow.setOnDismissListener(() -> {
                currentPopupWindow = null; // Clear reference when dismissed
            });

            currentPopupWindow = popupWindow; // Keep reference to the current PopupWindow
        } else {
            // Show the regular Toast for short messages
            currentToast = new Toast(this);
            currentToast.setGravity(Gravity.TOP | Gravity.CENTER, 0, 32);
            currentToast.setDuration(Toast.LENGTH_SHORT);
            currentToast.setView(customToastView);
            currentToast.show();
        }
    }


    private void showSelectVideoScreen() {
        Intent intent = new Intent();
        intent.setType("video/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        videoPickerLauncher.launch(Intent.createChooser(intent, "Select Video"));
    }



    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

}

