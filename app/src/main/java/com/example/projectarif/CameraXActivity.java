package com.example.projectarif;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraX;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.extensions.HdrImageCaptureExtender;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@SuppressWarnings("ALL")
public class CameraXActivity extends AppCompatActivity {

    private Executor executor = Executors.newSingleThreadExecutor();
    private int REQUEST_CODE_PERMISSIONS = 101;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA",
            "android.permission.WRITE_EXTERNAL_STORAGE"};
    PreviewView mPreviewView;
    int count_number_images = 0;
    Button stop_camera;
    private static final int INPUT_SIZE = 300;
    private static final int NUM_BYTES_PER_CHANNEL = 1;
    private static final int NUM_DETECTIONS = 10;
    private ByteBuffer imgData;
    private Interpreter tflite;
    private float[][][] outputLocations;
    private float[][] outputClasses;
    private float[][] outputScores;
    private float[] numDetections;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camerax);
        getSupportActionBar().hide();
        mPreviewView = findViewById(R.id.previewView);
        stop_camera=findViewById(R.id.stop_camera);
        if (allPermissionsGranted()) {
            startCamera(); //start camera if permission has been granted by user
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
        stop_camera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
    }

    private void startCamera() {
        Log.d("bindView","start camera started");
        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {

                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    Log.d("bindView","call_function");
                    bindPreview(cameraProvider);
                } catch (ExecutionException | InterruptedException e) {
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {

        Preview preview = new Preview.Builder()
                .build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .build();
        ImageCapture.Builder builder = new ImageCapture.Builder();
        HdrImageCaptureExtender hdrImageCaptureExtender = HdrImageCaptureExtender.create(builder);
        if (hdrImageCaptureExtender.isExtensionAvailable(cameraSelector)) {
            hdrImageCaptureExtender.enableExtension(cameraSelector);
        }
        final ImageCapture imageCapture = builder
                .setTargetRotation(this.getWindowManager().getDefaultDisplay().getRotation())
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
//                .setTargetAspectRatio(screenAspectRatio)
//                .setTargetResolution(screenSize)
                .build();
        preview.setSurfaceProvider(mPreviewView.createSurfaceProvider());
        Camera camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageAnalysis, imageCapture);
        count_number_images = 0;
        Log.d("bindView","before loop camera");
           final Handler h=new Handler();
            h.post(new Runnable()
            {
                public void run() {
                    imagecapture2seconds(imageCapture);
                    h.postDelayed(this,2000);
                }
            });

    }
List<String> imageList=new ArrayList<>();
    public void imagecapture2seconds(ImageCapture imageCapture)
    {
        if(count_number_images<50)
        {
            imageCapture.takePicture(executor, new  ImageCapture.OnImageCapturedCallback() {
                @Override
                public void onCaptureSuccess(@NonNull ImageProxy image)
                {
                    //super.onCaptureSuccess(image);
                    Bitmap b=getBitmap(image);
                    int bytes = b.getByteCount();
                    ByteBuffer buffer = ByteBuffer.allocate(bytes); //Create a new buffer
                    b.copyPixelsToBuffer(buffer); //Move the byte data to the buffer
                    byte[] array = buffer.array();
                    interpreter(array);

                }
                @Override
                public void onError(@NonNull ImageCaptureException error) {
                    error.printStackTrace();
                }
            });
        }
    }
    private void interpreter(byte[] inp_array)
    {
        tflite=null;
//        imgData = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * NUM_BYTES_PER_CHANNEL);
//        imgData.order(ByteOrder.nativeOrder());
        outputLocations = new float[1][NUM_DETECTIONS][4];
        outputClasses = new float[1][NUM_DETECTIONS];
        outputScores = new float[1][NUM_DETECTIONS];
        numDetections = new float[1];

        try {
            tflite = new Interpreter(loadModelFile());
        }catch (Exception ex){
            ex.printStackTrace();
        }
        Object[] inputArray = {inp_array};
        Map<Float, Object> outputMap = new HashMap<>();
        outputMap.put((float)0, outputLocations);
        outputMap.put((float)1, outputClasses);
        outputMap.put((float)2, outputScores);
        outputMap.put((float)3, numDetections);
        Log.d("BeforeOutput_Map",outputMap+"");
        tflite.run(inp_array, outputMap);
       Log.d("Output_Map",outputMap+"");
       // tflite.run(input,output);
    }
    private MappedByteBuffer loadModelFile() throws IOException
    {
        AssetFileDescriptor fileDescriptor=this.getAssets().openFd("object_detector.tflite");
        FileInputStream inputStream=new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel=inputStream.getChannel();
        long startOffset=fileDescriptor.getStartOffset();
        long declareLength=fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY,startOffset,declareLength);
    }
    private File saveimagefile()
    {
        File dir;
        File f = null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            String path = Environment.getExternalStorageDirectory().getPath() + "/Arif/.Original/" + System.currentTimeMillis() + ".jpg";
            dir = new File(Environment.getExternalStorageDirectory().getPath() + "/Arif/.Original");
            f = new File(path);
        } else {
            f = new File(CameraXActivity.this.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Arif/.Original/" + System.currentTimeMillis() + ".jpg");
            dir = new File(CameraXActivity.this.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Arif/.Original");
        }
        if (!dir.exists() && !dir.isDirectory()) {
            dir.mkdirs();
        }
        return f;
    }

    private Bitmap getBitmap(ImageProxy image)
    {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        buffer.rewind();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        byte[] clonedBytes = bytes.clone();
        return BitmapFactory.decodeByteArray(clonedBytes, 0, clonedBytes.length);
    }

    private boolean allPermissionsGranted() {

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}