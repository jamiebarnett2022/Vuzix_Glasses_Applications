package com.example.detection

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.detection.databinding.ActivityMainBinding
import com.example.detection.debug.DebugManager
import com.example.detection.ml.Last.Outputs
import com.example.detection.ml.Last
import com.example.detection.sound.SoundManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import kotlin.math.roundToInt


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding





    /**
     * pre-processor of the frames for the model
     */
    private lateinit var imageProcessor: ImageProcessor

    /**
     * painter for painting the detected squares
     */
    private val paint = Paint()

    /**
     * Camera manager containing the camera characteristics
     */
    private lateinit var cameraManager : CameraManager

    /**
     * handling the camera feed
     */
    private lateinit var handler: Handler

    /**
     * cameraID of the back camera
     */
    private val cameraID = "0"

    /**
     * The back camera
     */
    private lateinit var cameraDevice : CameraDevice

    /**
     * The TensorFlow Model
     */
    private lateinit var model: Last


    /**
     * The most recent camera frame.
     */
    private var bitmap: Bitmap? = null

    /**
     * Most recent output of the object detection model
     */
    private var outputs: Outputs? = null

    /**
     * Scope for the coroutine that feeds the object detection model with frames
     */
    private lateinit var coroutineScope: CoroutineScope

    /*
    Size to scale the bitmap to, dependant on the used model to detect stop signs.
    (currently: SSD MobileNet V2 FPNLite 320x320)
     */
    private val resizeBitmapHeight = 320
    private val resizeBitmapWidth = 320

    /**
     * Managing sounds played when a stop sign is detected
     */
    private val soundManager = SoundManager()

    /**
     * Manages all debugging options
     */
    private val debugManager = DebugManager()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        //prevents the screen from going to sleep
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        //gets permission from device to use camera
        getPermission()

        //set the camera manager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        //ImageProcessor will prepare the captured frame for the model
        initializeImageProcessor()

        //initializes model
        model = Last.newInstance(this)

        //handler thread handles video capture
        initializeCameraFeedHandler()


        //Stream camera feed to TextureView
        setTextureViewSurfaceTextureListener()

        //If detection is disabled, disable the info
        if(!debugManager.isObjectDetectionEnabled)
            binding.textViewDetectionEnabled.visibility = View.INVISIBLE
    }

    override fun onStart() {
        super.onStart()

        if(debugManager.isObjectDetectionEnabled) startCoroutineModel()
    }

    override fun onResume() {
        super.onResume()

        //get the aspect ratio for the two views
        val aspectRatio = determineTextureViewAspectRatio()
        //apply that ratio to the views once they are drawn
        binding.textureView.post {
            resizeView(binding.textureView, aspectRatio)
        }
        binding.imageView.post {
            resizeView(binding.imageView, aspectRatio)
        }

        //setup the sound manager
        soundManager.startSoundManager(MediaPlayer.create(this, R.raw.notification_sound))
        //setup debug manager
        debugManager.reset()
    }

    override fun onStop() {
        if(debugManager.isObjectDetectionEnabled) stopCoroutineModel()
        soundManager.stopSoundManager()

        super.onStop()
    }


    /**
     * Sets up the SurfaceTextureListener for the TextureView.
     * Every time a new frame is received from the camera, the model will detect objects.
     */
    private fun setTextureViewSurfaceTextureListener() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                //camera is accessed, setup for the camera stream
                setupCamera()
                //start the object detection coroutine
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            /*
             * This method is called whenever the camera provides a new frame.
             * The frame will be fed to the object detection model.
             * Shapes will be drawn around the detected objects.
             */
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                //update the bitmap to the recent frame
                bitmap = binding.textureView.bitmap
                //log the frame
                debugManager.logFrame()
                updateRatioInfo()

                if(!debugManager.isObjectDetectionEnabled){
                    //if object detection is disabled, just stream the camera to imageView
                    if(debugManager.isCameraStreamEnabled)
                        binding.imageView.setImageBitmap(bitmap)
                } else {
                    //use model output to draw the shapes on top of the camera feed
                    val mutable = drawShapes(bitmap!!)
                    if(debugManager.isCameraStreamEnabled)
                        binding.imageView.setImageBitmap(mutable)
                }

            }

        }
    }


    /**
     * This handler handles the camera feed.
     */
    private fun initializeCameraFeedHandler() {
        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    /**
     * The ImageProcessor will process the image so the object detection model can use it.
     */
    private fun initializeImageProcessor() {
        /*
         The ResizeOp Operator will ensure that every bitmap captured from the camera feed
         will be scaled (not cropped) to the specified pixels. This is necessary for the chosen model.
         */
        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(resizeBitmapHeight, resizeBitmapWidth, ResizeOp.ResizeMethod.BILINEAR))
            .build()
    }

    /**
     * To ensure that the the views (displaying the camera feed) are not distorted,
     * they need to have the same aspect ratio as the camera's viewfinder.
     * @return the needed aspect ratio
     */
    private fun determineTextureViewAspectRatio(): Float {
        //get camera aspect ratio
        var aspectRatio = getCameraAspectRatio()

        //determine the orientation using the default display
        @Suppress("DEPRECATION") //is deprecated from API 30, we need API 21 for Vuzix
        val display: Display = (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay
        //if the orientation is portrait, the aspect ratio has to be inversed
        if (determineOrientation(display) == Configuration.ORIENTATION_PORTRAIT) {
            aspectRatio = 1 / aspectRatio
        }
        return aspectRatio
    }

    /**
     * Will return the orientation (caution: will also return landscape when width >= height)
     * @param display The current display
     * @return Configuration.ORIENTATION_PORTRAIT or Configuration.ORIENTATION_LANDSCAPE
     */
    private fun determineOrientation(display: Display): Int {
        //check if width >= height
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") //deprecated from API 30, we need API 21 for VUZIX
        display.getMetrics(metrics)

        if (metrics.widthPixels >= metrics.heightPixels) return Configuration.ORIENTATION_LANDSCAPE
        //otherwise, determine orientation
        return when (display.rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> Configuration.ORIENTATION_PORTRAIT
            Surface.ROTATION_90, Surface.ROTATION_270 -> Configuration.ORIENTATION_LANDSCAPE
            else -> Configuration.ORIENTATION_PORTRAIT //this should never happen
        }
    }

    /**
     * Resizes the view to accommodate the camera's aspect ratio.
     * This method assumes that both the height and width of the view are set to
     * match_parent
     */
    private fun resizeView(view: View, aspectRatio: Float) {
        //Try to resize the height
        val newHeight = (view.width / aspectRatio).roundToInt()
        //if the height is greater than the current height (match_parent), width has to be resized
        //otherwise, height will be resized
        if(newHeight <= view.height){
            //resize height
            val params = view.layoutParams
            params.height = newHeight
            view.layoutParams = params
        } else {
            //resize width
            val newWidth = (view.height * aspectRatio).roundToInt()
            val params = view.layoutParams
            params.width = newWidth
            view.layoutParams = params
        }
    }

    /**
     * Retrieves the aspect ratio of the camera.
     */
    private fun getCameraAspectRatio(): Float {
        //characteristics of the camera
        val characteristics = cameraManager.getCameraCharacteristics(cameraID)
        //resolution of the camera
        val pixelArraySize: Size = characteristics
            .get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
        //aspect ratio as a result of the resolution
        return  pixelArraySize.width.toFloat() / pixelArraySize.height.toFloat()
    }

    override fun onDestroy() {
        super.onDestroy()
        model.close()
    }

    /**
     * Sets up the camera for streaming to the TextureView
     */
    private fun setupCamera() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            getPermission()
        }
        cameraManager.openCamera(cameraID, object: CameraDevice.StateCallback(){
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera

                //setup the streaming to the TextureView
                val surfaceTexture = binding.textureView.surfaceTexture
                val surface = Surface(surfaceTexture)
                val captureRequest = cameraDevice
                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureRequest.addTarget(surface)

                @Suppress("DEPRECATION") //deprecated from API 30
                cameraDevice.createCaptureSession(listOf(surface),
                    object: CameraCaptureSession.StateCallback(){
                        override fun onConfigured(session: CameraCaptureSession) {
                            session.setRepeatingRequest(captureRequest.build(), null, null)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                        }
                    }, handler)

            }
            override fun onDisconnected(camera: CameraDevice) {

            }
            override fun onError(camera: CameraDevice, error: Int) {

            }
        }, handler)
    }

    /**
     * Requests the camera permissions unless they are already granted
     */
    private fun getPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission is not granted
            Log.d("checkCameraPermissions", "No Camera Permissions")
            ActivityCompat.requestPermissions(
                (this as Activity), arrayOf(Manifest.permission.CAMERA),
                100
            )
        }

    }

    /**
     * Draw the shapes around the detected objects.
     * @param bitmap Initial bitmap provided by the camera
     * @return the new bitmap with the shapes on top of the camera frame
     * or the initial bitmap if outputs is null
     */
    private fun drawShapes(bitmap: Bitmap): Bitmap {
        if(outputs == null) return bitmap
        //information extracted from model (locations and certainties of detected objects)
        val locations = outputs!!.outputFeature1AsTensorBuffer.floatArray
        val scores = outputs!!.outputFeature0AsTensorBuffer.floatArray

        //this will be the resulting bitmap
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        //setup the canvas and paint
        val canvas = Canvas(mutable)
        val h = mutable.height
        val w = mutable.width
        paint.textSize = h / 20f
        paint.strokeWidth = h / 100f
        var x: Int

        //for every detected object above 50% confidence, draw bounding box to screen
        //Log whether an object was detected
        var detected = false
        scores.forEachIndexed { index, fl ->
            x = index
            x *= 4
            if (fl > 0.25) {
                detected = true
                paint.color = Color.RED
                paint.style = Paint.Style.STROKE
                //draw the bounding box
                canvas.drawRect(
                    RectF(
                        locations[x + 1] * w,
                        locations[x] * h,
                        locations[x + 3] * w,
                        locations[x + 2] * h
                    ),
                    paint
                )
                //draw the confidence (in percent)
                paint.style = Paint.Style.FILL
                canvas.drawText(
                    (scores[index] * 100.0).roundToInt().toString() + "%",
                    locations[x + 1] * w + 2.5f,
                    locations[x + 2] * h - 2.5f,
                    paint
                )
            }
        }
        //Give audio feedback on detected stop sign
        if(detected && debugManager.isAudioFeedbackEnabled) soundManager.playSound()
        //Give visual feedback on detected stop sign
        if(debugManager.isVisualFeedbackEnabled)
            if(detected){
                setVisualFeedbackVisible()
            } else {
                setVisualFeedbackInvisible()
            }
        return mutable
    }

    /**
     * Activates the visual feedback.
     */
    private fun setVisualFeedbackVisible() {
        debugManager.lastStopTimestamp = System.currentTimeMillis()
        binding.textViewVisualFeedbackStop.visibility = View.VISIBLE
    }

    /**
     * Deactivates the visual feedback if the minimum visual feedback time is met.
     */
    private fun setVisualFeedbackInvisible() {
        if(System.currentTimeMillis() - debugManager.lastStopTimestamp
            > debugManager.minimumVisualFeedbackTime)
            binding.textViewVisualFeedbackStop.visibility = View.INVISIBLE
    }

    /**
     * Pre-processes the camera frame (bitmap) and feeds it to the
     * object detection model.
     * Resulting output will be set to the outputs variable
     */
    private fun computeModelOutput(bitmap: Bitmap) {
        //contents of bitmap put into tensorImage
        val tImage = TensorImage(DataType.FLOAT32)
        tImage.load(bitmap)
        imageProcessor.process(tImage)
        val byteBuffer = tImage.buffer

        //tensorImage loaded into model
        val inputFeature0 = TensorBuffer
            .createFixedSize(
                intArrayOf(1, resizeBitmapHeight, resizeBitmapWidth, 3),
                DataType.FLOAT32
            )
        inputFeature0.loadBuffer(byteBuffer)

        //model processes camera frame
        try{
            outputs = model.process(inputFeature0)
        } catch (e: Exception){
            e.printStackTrace()
            finish()
        }
    }

    /**
     * This background task will continuously run the object detection model on the
     * most recent frame
     */
    private fun startCoroutineModel() {
        coroutineScope = CoroutineScope(Dispatchers.IO)
        coroutineScope.launch {
            while(isActive) {
                //permanently run the model to update the output
                //recompute outputs using TensorFlow model
                if(bitmap != null){
                    computeModelOutput(bitmap!!)
                    //log the output
                    debugManager.logObjectDetectionOutput()
                }
                delay(50)
            }
        }
    }

    /**
     * Updates the ratio debug info
     */
    private fun updateRatioInfo() {
        if(debugManager.isObjectDetectionFrequencyEnabled){
            binding.textViewRatio.text = debugManager.getFrameObjectRatio()
        }
    }

    /**
     * Stop the object detection model background task
     */
    private fun stopCoroutineModel() {
        coroutineScope.cancel()
    }
}