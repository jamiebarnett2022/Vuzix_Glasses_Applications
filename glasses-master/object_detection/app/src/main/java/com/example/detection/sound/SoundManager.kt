package com.example.detection.sound

import android.media.MediaPlayer

/**
 * This class will play sounds.
 */
class SoundManager() {

    /**
     * Unix Timestamp of last time a stop sign was detected
     */
    private var timeOfLastDetection: Long = 0

    /**
     * Minimum time of no stop sign detection to play a sound again (in milliseconds)
     */
    private val minTimeBetweenDetections = 1000


    private var mediaPlayer: MediaPlayer? = null

    /**
     * Starts the sound manager
     */
    fun startSoundManager(mediaPlayer: MediaPlayer) {
        this.mediaPlayer = mediaPlayer
    }

    /**
     * Stops the sound manager.
     */
    fun stopSoundManager() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * Plays a sound if the min time in between sounds is met
     */
    fun playSound() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - timeOfLastDetection >= minTimeBetweenDetections) mediaPlayer?.start()
        timeOfLastDetection = currentTime
    }


}