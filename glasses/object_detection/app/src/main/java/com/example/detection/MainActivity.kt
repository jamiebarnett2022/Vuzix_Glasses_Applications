package com.example.detection

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.detection.ml.Last
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer


class MainActivity : AppCompatActivity() {

    lateinit var imageProcessor: ImageProcessor
    val paint = Paint()
    lateinit var imageView : ImageView
    lateinit var textureView: TextureView
    lateinit var cameraManager : CameraManager
    lateinit var handler: Handler
    lateinit var cameraDevice : CameraDevice
    lateinit var bitmap : Bitmap
    lateinit var model:Last
    private var stopSign : Boolean = false
    lateinit var context : Context


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        context = this

        //gets permission from device to use camera
        get_permission()

        //processes the image so the object detection model can use it
        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(320, 320, ResizeOp.ResizeMethod.BILINEAR)).build()

        //initializes model
        model = Last.newInstance(this)

        //handler thread handles video capture
        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler=Handler(handlerThread.looper)


        imageView = findViewById(R.id.imageView)
        textureView = findViewById(R.id.textureView)
//        contents of camera written to textureview, every time a new frame is update on the view,
//        onSurfaceTextureUpdates is activated so that a new frame is accessed

        textureView.surfaceTextureListener = object:TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                //camera is accessed, contents of camera written to textureView
                open_camera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {

            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                //contents of the textureview is saved in a bitmap
                bitmap = textureView.bitmap!!

                var mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutable)


                val h = mutable.height
                val w = mutable.width
                paint.textSize = h/20f
                paint.strokeWidth = h/100f
                var x = 0


                //contents of bitmap put into tensorImage
                var tImage = TensorImage(DataType.FLOAT32)
                tImage.load(bitmap)
                imageProcessor.process(tImage)
                var byteBuffer = tImage.buffer

                //tensorImage loaded into model
                val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, 320, 320, 3), DataType.FLOAT32)
                inputFeature0.loadBuffer(byteBuffer)

                //model processes camera frame
                val outputs = model.process(inputFeature0)

                //information extracted from model
                val locations = outputs.outputFeature1AsTensorBuffer.floatArray
                val scores = outputs.outputFeature0AsTensorBuffer.floatArray
                val classes = outputs.outputFeature3AsTensorBuffer.floatArray
                val numberDetections = outputs.outputFeature2AsTensorBuffer.floatArray

                //for every detected object above 50% confidence, draw bounding box to screen
                scores.forEachIndexed {index, fl ->
                    x = index
                    x *= 4
                    if(fl > 0.5) {
                        paint.setColor(Color.RED)
                        paint.style = Paint.Style.STROKE
                        canvas.drawRect(RectF(locations.get(x+1)*w, locations.get(x)*h, locations.get(x+3)*w, locations.get(x+2)*h), paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawText(scores.get(index).toString(), locations.get(x+1)*w, locations.get(x+2)*h, paint)
                        imageView.setImageBitmap(mutable)

                    }
                    imageView.setImageBitmap(mutable)

                }
            }

        }
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

    }

    override fun onDestroy() {
        super.onDestroy()
        model.close()
    }

    fun open_camera() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            get_permission()
        }
        cameraManager.openCamera(cameraManager.cameraIdList[0], object: CameraDevice.StateCallback(){
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera

                //draw cameraview to textureview
                var surfaceTexture = textureView.surfaceTexture
                var surface = Surface(surfaceTexture)
                var captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureRequest.addTarget(surface)

                cameraDevice.createCaptureSession(listOf(surface), object: CameraCaptureSession.StateCallback(){
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

    fun get_permission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission is not granted
            Log.d("checkCameraPermissions", "No Camera Permissions")
            ActivityCompat.requestPermissions(
                (context as Activity)!!, arrayOf(Manifest.permission.CAMERA),
                100
            )
        }

    }
}