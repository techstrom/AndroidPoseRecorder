package jp.example.poserecorder

import android.graphics.Bitmap

/** YOLO pose result for one person. */
data class VideoPoseCandidate(val points: List<PosePoint>)

fun detectVideoPoseCandidates(detector: YoloPoseDetector, frame: Bitmap): List<VideoPoseCandidate> =
    detector.detect(frame).map(::VideoPoseCandidate)
