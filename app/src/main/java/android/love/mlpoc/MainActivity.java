package android.love.mlpoc;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.love.mlpoc.util.Constants;
import android.love.mlpoc.util.DBHelper;
import android.love.mlpoc.util.DemoCamera;
import android.love.mlpoc.util.NetworkUtil;
import android.media.Image;
import android.media.ImageReader;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.PeripheralManager;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.ml.common.FirebaseMLException;
import com.google.firebase.ml.common.modeldownload.FirebaseCloudModelSource;
import com.google.firebase.ml.common.modeldownload.FirebaseLocalModelSource;
import com.google.firebase.ml.common.modeldownload.FirebaseModelDownloadConditions;
import com.google.firebase.ml.common.modeldownload.FirebaseModelManager;
import com.google.firebase.ml.custom.FirebaseModelDataType;
import com.google.firebase.ml.custom.FirebaseModelInputOutputOptions;
import com.google.firebase.ml.custom.FirebaseModelInputs;
import com.google.firebase.ml.custom.FirebaseModelInterpreter;
import com.google.firebase.ml.custom.FirebaseModelOptions;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

public class MainActivity extends Activity {

    private BroadcastReceiver EventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getBooleanExtra(Constants.IS_CONNECT, false)) {
                if (intent.getStringExtra(Constants.DIRECTION).equals(Constants.FORWARD)) {
                    try {
                        forward();
                    } catch (IOException e) {
                        Log.d(TAG, "onReceive: "+e.getMessage());
                    }
                } else {
                    try {
                        reverse();
                    } catch (IOException e) {
                        Log.d(TAG, "onReceive: "+e.getMessage());
                    }
                }
            } else {
                try {
                    stopMove();
                } catch (IOException e) {
                    Log.d(TAG, "onReceive: "+e.getMessage());
                }
            }
        }
    };

    /**
     * Listener for new camera images.
     */
    private ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            reader -> {
                Image image = reader.acquireLatestImage();
                // get image bytes
                ByteBuffer imageBuf = image.getPlanes()[0].getBuffer();
                final byte[] imageBytes = new byte[imageBuf.remaining()];
                imageBuf.get(imageBytes);
                image.close();

                onPictureTaken(imageBytes);
            };

    private GpioCallback gpioCallback = new GpioCallback() {
        @Override
        public boolean onGpioEdge(Gpio gpio) {
            try {
                if (gpio.getValue()) {
                    takePic();
                }
            } catch (IOException e) {
                Log.d(TAG, "onGpioEdge: "+e.getMessage());
            }

            return true;
        }
    };

    private String TAG = MainActivity.class.getSimpleName();
    //motor one
    private String MOTOR_IN1 = "GPIO6_IO14";
    private String MOTOR_IN2 = "GPIO6_IO15";
    //motor two
    private String MOTOR_IN3 = "GPIO2_IO00";
    private String MOTOR_IN4 = "GPIO2_IO05";

    private String IR_RECEIVER = "GPIO2_IO07";

    private Gpio mMotorOnePin1 = null;
    private Gpio mMotorOnePin2 = null;
    private Gpio mMotorTwoPin1 = null;
    private Gpio mMotorTwoPin2 = null;
    private HandlerThread mCameraThread;
    private DemoCamera mCamera;
    private ImageView image_view;
    private RelativeLayout layout;
    private Spinner spinner;
    private int choice;
    private TextView outputTV;

    private static final int DIM_BATCH_SIZE = 1;
    private static final int DIM_PIXEL_SIZE = 3;
    private static final int DIM_IMG_SIZE_X = 224;
    private static final int DIM_IMG_SIZE_Y = 224;
    private static final int RESULTS_TO_SHOW = 3;

    private static final String HOSTED_MODEL_NAME = "cloud_model_1";
    private static final String LOCAL_MODEL_ASSET = "mobilenet_v1_1.0_224_quant.tflite";

    private final int[] intValues = new int[DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y];

    private static final String LABEL_PATH = "labels.txt";


    private List<String> mLabelList;

    private final PriorityQueue<Map.Entry<String, Float>> sortedLabels =
            new PriorityQueue<>(
                    RESULTS_TO_SHOW,
                    (o1, o2) -> (o1.getValue()).compareTo(o2.getValue()));
    private FirebaseModelInputOutputOptions mDataOptions;
    private FirebaseModelInterpreter mInterpreter;
    private Bitmap bitmap;
    private Bitmap rotatedBitmap;
    private DBHelper database;
    private Gpio mIRPin;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        FirebaseApp.initializeApp(this);
        PeripheralManager peripheralManager = PeripheralManager.getInstance();
        try {
            //configuring pins as output with initial LOW
            mMotorOnePin1 = peripheralManager.openGpio(MOTOR_IN1);
            mMotorOnePin1.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mMotorOnePin2 = peripheralManager.openGpio(MOTOR_IN2);
            mMotorOnePin2.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);


            mMotorTwoPin1 = peripheralManager.openGpio(MOTOR_IN3);
            mMotorTwoPin1.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mMotorTwoPin2 = peripheralManager.openGpio(MOTOR_IN4);
            mMotorTwoPin2.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);

            mIRPin = peripheralManager.openGpio(IR_RECEIVER);
            mIRPin.setDirection(Gpio.DIRECTION_IN);
            mIRPin.setEdgeTriggerType(Gpio.EDGE_RISING);
            mIRPin.registerGpioCallback(gpioCallback);
            registerMyReceiver();
            setUpWifi();

        } catch (IOException e) {
            e.printStackTrace();
        }
        initView();

    }

    private void initView() {

        image_view = findViewById(R.id.image_view);
        layout = findViewById(R.id.layout);
        spinner = findViewById(R.id.spinner);
        outputTV = findViewById(R.id.textview);
        RadioGroup radioGroup = findViewById(R.id.radioGroup);
       database = new DBHelper(getApplicationContext());
        mCameraThread = new HandlerThread("CameraBackground");
        mCameraThread.start();
        Handler mCameraHandler = new Handler(mCameraThread.getLooper());
        mCamera = DemoCamera.getInstance(this);
        mCamera.initializeCamera(this, mCameraHandler, mOnImageAvailableListener);
        findViewById(R.id.button).setOnClickListener(v -> {
            takePic();
        });

        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            switch (checkedId){
                case R.id.radioButton1:
                    choice = 1;
                    break;
                case R.id.radioButton2:
                    choice = 2;
                    break;
                case R.id.radioButton3:
                    choice = 3;
                    break;
            }
        });
        findViewById(R.id.add_entry).setOnClickListener(v ->{
                    database.addContact(spinner.getSelectedItem().toString(),choice);
                }
        );

        initializeML();
    }

    private void takePic() {
        layout.setVisibility(View.GONE);
        image_view.setVisibility(View.VISIBLE);
        mCamera.takePicture();
    }

    /**
     * Regesting broadcast receiver for actions received in fcm message
     */
    private void registerMyReceiver() {
        try {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Constants.BROADCAST_ACTION);
            registerReceiver(EventReceiver, intentFilter);
        } catch (Exception e) {
            Log.d(TAG, "registerMyReceiver: " + e.getMessage());
        }
    }


    private void setUpWifi() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (!wifiManager.isWifiEnabled()) {
                wifiManager.setWifiEnabled(true);
            }
            WifiConfiguration wifiConfig = new WifiConfiguration();
            wifiConfig.SSID = "wifiConfig.SSID";
            wifiConfig.preSharedKey = "incorrect";
            int netWorkId = wifiManager.addNetwork(wifiConfig);
            wifiManager.disconnect();
            wifiManager.enableNetwork(netWorkId, true);
            boolean isConnected = wifiManager.reconnect();
            if (isConnected && NetworkUtil.isConnected(getApplicationContext())) {

                FirebaseMessaging.getInstance().subscribeToTopic("androidThings");
            } else if (isConnected) {
                FirebaseMessaging.getInstance().subscribeToTopic("androidThings");
            }

        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
        }
    }

    private void forward() throws IOException {
        mMotorOnePin1.setValue(true);
        mMotorOnePin2.setValue(false);

        mMotorTwoPin1.setValue(true);
        mMotorTwoPin2.setValue(false);

    }

    private void reverse() throws IOException {
        mMotorOnePin1.setValue(false);
        mMotorOnePin2.setValue(true);

        mMotorTwoPin1.setValue(false);
        mMotorTwoPin2.setValue(false);
    }

    private void stopMove() throws IOException {
        mMotorOnePin1.setValue(true);
        mMotorOnePin2.setValue(true);

        mMotorTwoPin1.setValue(true);
        mMotorTwoPin2.setValue(true);

    }

    private void initializeML() {
        mLabelList = loadLabelList(this);
        int[] inputDims = {DIM_BATCH_SIZE, DIM_IMG_SIZE_X, DIM_IMG_SIZE_Y, DIM_PIXEL_SIZE};
        int[] outputDims = {DIM_BATCH_SIZE, mLabelList.size()};
        try {
            mDataOptions =
                    new FirebaseModelInputOutputOptions.Builder()
                            .setInputFormat(0, FirebaseModelDataType.BYTE, inputDims)
                            .setOutputFormat(0, FirebaseModelDataType.BYTE, outputDims)
                            .build();
            FirebaseModelDownloadConditions conditions = new FirebaseModelDownloadConditions
                    .Builder()
                    .requireWifi()
                    .build();
            FirebaseLocalModelSource localModelSource =
                    new FirebaseLocalModelSource.Builder("asset")
                            .setAssetFilePath(LOCAL_MODEL_ASSET).build();
            FirebaseCloudModelSource cloudSource = new FirebaseCloudModelSource.Builder
                    (HOSTED_MODEL_NAME)
                    .enableModelUpdates(true)
                    .setInitialDownloadConditions(conditions)
                    .setUpdatesDownloadConditions(conditions)  // You could also specify
                    // different conditions
                    // for updates
                    .build();
            FirebaseModelManager manager = FirebaseModelManager.getInstance();
            manager.registerLocalModelSource(localModelSource);
            manager.registerCloudModelSource(cloudSource);
            FirebaseModelOptions modelOptions =
                    new FirebaseModelOptions.Builder()
                            .setCloudModelName(HOSTED_MODEL_NAME)
                            .setLocalModelName("asset")
                            .build();
            mInterpreter = FirebaseModelInterpreter.getInstance(modelOptions);
        } catch (FirebaseMLException e) {
            Log.d(TAG, "onCreate: "+"error" +e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Gets the top labels in the results.
     */
    private synchronized List<String> getTopLabels(byte[][] labelProbArray) {
        for (int i = 0; i < mLabelList.size(); ++i) {
            sortedLabels.add(
                    new AbstractMap.SimpleEntry<>(mLabelList.get(i), (labelProbArray[0][i] &
                            0xff) / 255.0f));
            if (sortedLabels.size() > RESULTS_TO_SHOW) {
                sortedLabels.poll();
            }
        }
        List<String> result = new ArrayList<>();
        final int size = sortedLabels.size();
        for (int i = 0; i < size; ++i) {
            Map.Entry<String, Float> label = sortedLabels.poll();
            result.add(label.getKey() + ":" + label.getValue());
        }
        Log.d(TAG, "labels: " + result.toString());
        return result;
    }

    /**
     * Reads label list from Assets.
     */
    private List<String> loadLabelList(Activity activity) {
        List<String> labelList = new ArrayList<>();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(activity.getAssets().open
                             (LABEL_PATH)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                labelList.add(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read label list.", e);
        }
        return labelList;
    }

    /**
     * Writes Image data into a {@code ByteBuffer}.
     */
    private synchronized ByteBuffer convertBitmapToByteBuffer(
            Bitmap bitmap) {
        ByteBuffer imgData =
                ByteBuffer.allocateDirect(
                        DIM_BATCH_SIZE * DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE);
        imgData.order(ByteOrder.nativeOrder());
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, DIM_IMG_SIZE_X, DIM_IMG_SIZE_Y,
                true);
        imgData.rewind();
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.getWidth(), 0, 0,
                scaledBitmap.getWidth(), scaledBitmap.getHeight());
        // Convert the image to int points.
        int pixel = 0;
        for (int i = 0; i < DIM_IMG_SIZE_X; ++i) {
            for (int j = 0; j < DIM_IMG_SIZE_Y; ++j) {
                final int val = intValues[pixel++];
                imgData.put((byte) ((val >> 16) & 0xFF));
                imgData.put((byte) ((val >> 8) & 0xFF));
                imgData.put((byte) (val & 0xFF));
            }
        }
        return imgData;
    }

    /**
     * Upload image data to Firebase as a doorbell event.
     */
    private void onPictureTaken(final byte[] imageBytes) {
        if (imageBytes != null) {
            Log.d(TAG, "onPictureTaken: ");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 4;
            bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);

            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, bitmap.getWidth(), bitmap.getHeight(), true);
            rotatedBitmap = Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.getWidth(), scaledBitmap.getHeight(), matrix, true);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    image_view.setImageBitmap(rotatedBitmap);
                }
            });


            ByteArrayOutputStream bao = new ByteArrayOutputStream();
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, bao);
            detectPicture();
        }
    }


    private void detectPicture() {
        if (mInterpreter == null) {
            Log.e(TAG, "Image classifier has not been initialized; Skipped.");
            return;
        }
        // Create input data.
        ByteBuffer imgData = convertBitmapToByteBuffer(bitmap);
        try {
            FirebaseModelInputs inputs = new FirebaseModelInputs.Builder().add(imgData).build();
            // Here's where the magic happens!!
            mInterpreter.run(inputs, mDataOptions)
                    .addOnFailureListener(Throwable::printStackTrace).continueWith(
                    task -> {
                        byte[][] labelProbArray = Objects.requireNonNull(task.getResult())
                                .getOutput(0);
                        List<String> topLabels = getTopLabels(labelProbArray);
                        ArrayList<String> spinnerArray = new ArrayList<String>();

                        for(String labels:topLabels){
                            String[] labelName = labels.split(":");
                            try {
                                Double detection = Double.valueOf(labelName[1]);
                                if((detection*1000) > 60){
                                    spinnerArray.add(labelName[0]);
                                    int value = database.getChoice(labelName[0]);
                                    if(value != 0){
                                        if(value == 1){
                                            outputTV.append(getString(R.string.forward));
                                            forward();
                                        }
                                        else if(value == 2){
                                            outputTV.append(getString(R.string.reverse));
                                            reverse();
                                        }
                                        else {
                                            outputTV.append(getString(R.string.stop));
                                            stopMove();
                                        }

                                        outputTV.append("\n");
                                    }

                                }
                            }
                            catch (Exception i){
                                Log.d(TAG, "then: "+i.getMessage());
                            }
                        }
                        ArrayAdapter<String> spinnerArrayAdapter = new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_spinner_dropdown_item, spinnerArray);
                        spinner.setAdapter(spinnerArrayAdapter);
                        image_view.setVisibility(View.GONE);
                        layout.setVisibility(View.VISIBLE);
                        return topLabels;
                    });
        } catch (FirebaseMLException e) {
            e.printStackTrace();
            Log.d(TAG, "Error running model inference: ");
        }

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(EventReceiver);
        mCameraThread.quitSafely();
        mCamera.shutDown();
        try {
            mCameraThread.join();
        } catch (InterruptedException e) {
            Log.d(TAG, "onStop: " + e);
        }

    }
}
