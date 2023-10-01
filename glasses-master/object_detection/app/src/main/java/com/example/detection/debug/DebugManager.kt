package com.example.detection.debug


import kotlin.math.round

class DebugManager() {

    //----------------DEBUG OPTIONS----------------//

    /**
     * Manually disable the object detection
     *
     */
    val isObjectDetectionEnabled = true

    /**
     * Manually disable audio feedback on stop sign detection here
     */
    val isAudioFeedbackEnabled = true

    /**
     * Manually enable the TextView showing how frequently
     * the object detection model refreshes its outputs
     */
    val isObjectDetectionFrequencyEnabled = false

    /**
     * Manually disable the camera stream here.
     */
    val isCameraStreamEnabled = false

    /**
     * Manually disable visual feedback (STOP text) on detection here
     */
    val isVisualFeedbackEnabled = true




    //----------------CLASS FIELDS----------------//

    /**
     * Counts how many camera frames have been displayed
     */
    private var frameCount = 0

    /**
     * Counts how many object detection outputs were generated
     */
    private var objectDetectionOutputCount = 0

    /**
     * Unix Timestamp of last Stop Sign Detection. Only updated when isVisualFeedbackEnabled is true.
     */
    var lastStopTimestamp: Long = 0

    /**
     * Minimum duration of visual feedback in milliseconds.
     */
    val minimumVisualFeedbackTime = 1000



    //----------------FUNCTIONS----------------//

    /**
     * Is called when a new camera frame has been displayed
     */
    fun logFrame(){
        frameCount++
    }

    /**
     * Is called when a new object detection output is generated
     */
    fun logObjectDetectionOutput(){
        objectDetectionOutputCount++
    }

    /**
     * @return (1st value): The ratio of frames logged and object detection outputs.
     * Values close to 1 are optimal. Lower values result in only updating
     * the bounding boxes every n frames and higher values waste processing
     * power.
     * (2nd value): The number of frames in between object detection outputs
     */
    /*
    Values with old SSD 320x320:
    Redmi 8: 0.06, 15
    Vuzix 1: 0.03, 35
    Vuzix 2: 0.04, 24

    Value with new SSD 320x320:
    Redmi 8: 0.09, 11
    Vuzix 2: 0.06, 16
     */
    fun getFrameObjectRatio(): String{
        val ratio = objectDetectionOutputCount / frameCount.toFloat()
        //round to 2 decimal places
        return String.format("%.2f", ratio) + ", " + round(1 / ratio).toInt()
    }


    /**
     * Resets all class fields (excluding debug options)
     */
    fun reset(){
        objectDetectionOutputCount = 0
        frameCount = 0
    }

}