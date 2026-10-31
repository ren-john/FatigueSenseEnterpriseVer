package com.example.fatiguesense.enterprise.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.ceil
import kotlin.math.floor

/** MediaPipe handles full-frame face detection, landmarks, and head pose together. */
internal class MediaPipeLandmarker(context: Context) : AutoCloseable {
    private val task = FaceLandmarker.createFromOptions(context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder()
                .setModelAssetPath(ModelProfile.asset)
                .setDelegate(Delegate.CPU)
                .build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(3)
            .setOutputFacialTransformationMatrixes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .build())

    data class Detection(
        val boundingBox: Rect,
        val points: List<LandmarkModel.Point>,
        val cropPoints: List<LandmarkModel.Point>,
        val pose: LandmarkModel.HeadPose,
    )

    fun detect(bitmap: Bitmap): Detection? {
        // Own the input copy so closing MPImage cannot recycle the preview frame.
        val ownedBitmap = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        try {
            val image = BitmapImageBuilder(ownedBitmap).build()
            try {
                val result = task.detect(image)
                val faces = result.faceLandmarks().mapIndexed { index, mesh ->
                    index to LandmarkModel.mediaPipeGeometry(mesh.map {
                        LandmarkModel.Point(it.x(), it.y())
                    }, bitmap.width, bitmap.height)
                }
                // Keep boxes, landmarks, and pose associated with the same largest face.
                val (index, geometry) = faces.maxByOrNull { it.second.bounds.area } ?: return null
                val bounds = geometry.bounds
                val matrix = result.facialTransformationMatrixes().orElse(emptyList()).getOrNull(index)
                return Detection(
                    boundingBox = Rect(floor(bounds.left).toInt(), floor(bounds.top).toInt(),
                        ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt()),
                    points = geometry.points,
                    cropPoints = geometry.cropPoints,
                    pose = LandmarkModel.mediaPipeHeadPose(matrix),
                )
            } finally {
                image.close()
            }
        } finally {
            if (!ownedBitmap.isRecycled) ownedBitmap.recycle()
        }
    }

    override fun close() = task.close()
}
