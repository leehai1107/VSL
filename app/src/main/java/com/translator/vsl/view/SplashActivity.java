package com.translator.vsl.view;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ImageView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.squareup.picasso.Picasso;
import com.translator.vsl.R;
import com.translator.vsl.databinding.ActivitySplashBinding;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    private ActivitySplashBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!isTaskRoot()) {
            // Check if this activity is not the root of the task
            // If not, finish this activity and return
            Intent intent = getIntent();
            if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN.equals(intent.getAction())) {
                finish();
                return;
            }
        }

        EdgeToEdge.enable(this);

        // Initialize View Binding
        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Fade in the splash screen
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);


        // Load image with Picasso, resizing it to fit the ImageView and avoid memory issues
        ImageView splashImage = binding.splashImage;
        Picasso.get()
                .load(R.drawable.so_khcn_lamdong_logo)       // Load the drawable resource
                .resize(800, 800)              // Resize to a smaller size to save memory (adjust as needed)
                .centerInside()                // Center inside the ImageView bounds
                .error(R.drawable.so_khcn_lamdong_logo)        // Optional: image in case of error
                .into(splashImage);

        new Handler().postDelayed(() -> {
                // User is signed in, navigate to home screen
                startActivity(new Intent(SplashActivity.this, HomeActivity.class));
                // Fade out the splash screen
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            finish();
        }, 3000); // 3 seconds delay

    }
}