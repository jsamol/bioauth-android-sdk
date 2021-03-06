package pl.edu.agh.bioauth.internal.biometrics.facerecognition.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.Face
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlinx.android.synthetic.main.bioauth_fragment_face_recognition.*
import pl.edu.agh.bioauth.R
import pl.edu.agh.bioauth.auth.LivenessMode
import pl.edu.agh.bioauth.auth.listener.AuthenticationListener
import pl.edu.agh.bioauth.auth.listener.RegistrationListener
import pl.edu.agh.bioauth.exception.CameraException
import pl.edu.agh.bioauth.internal.base.BaseFragment
import pl.edu.agh.bioauth.internal.biometrics.common.type.AuthenticationMethod
import pl.edu.agh.bioauth.internal.biometrics.common.type.MethodType
import pl.edu.agh.bioauth.internal.biometrics.common.type.RegistrationMethod
import pl.edu.agh.bioauth.internal.biometrics.facerecognition.CameraCaptureState.*
import pl.edu.agh.bioauth.internal.biometrics.facerecognition.callback.FaceCameraStateCallback
import pl.edu.agh.bioauth.internal.biometrics.facerecognition.callback.FaceCaptureCallback
import pl.edu.agh.bioauth.internal.biometrics.facerecognition.callback.FaceCaptureSessionCallback
import pl.edu.agh.bioauth.internal.biometrics.facerecognition.callback.FacePrecaptureCallback
import pl.edu.agh.bioauth.internal.biometrics.facerecognition.listener.FaceOnImageAvailableListener
import pl.edu.agh.bioauth.internal.biometrics.facerecognition.listener.FaceSurfaceTextureListener
import pl.edu.agh.bioauth.internal.util.ErrorUtil
import pl.edu.agh.bioauth.internal.util.FileUtil
import pl.edu.agh.bioauth.internal.util.PermissionRequestCode
import pl.edu.agh.bioauth.internal.util.extension.*
import java.io.File
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal class FaceRecognitionFragment : BaseFragment<FaceRecognitionViewModel>() {

    override val viewModelType: Class<FaceRecognitionViewModel> = FaceRecognitionViewModel::class.java
    override val layoutId: Int = R.layout.bioauth_fragment_face_recognition

    private val onImageAvailableListener: FaceOnImageAvailableListener by inject {
        onImageAvailable = this@FaceRecognitionFragment::onImageAvailable
    }

    private val surfaceTextureListener: FaceSurfaceTextureListener by inject {
        initListeners(::checkPermissionsAndOpenCamera, ::configureTransform)
    }

    private val cameraStateCallback: FaceCameraStateCallback by inject {
        initCallbacks(::onCameraOpened, ::onCameraDisconnected, ::onCameraError)
    }

    private val captureSessionCallback: FaceCaptureSessionCallback by inject {
        initCallbacks(::onCaptureSessionConfigured, ::onCaptureSessionConfigureFailed)
    }

    private val precaptureCallback: FacePrecaptureCallback by inject {
        processCaptureResult = this@FaceRecognitionFragment::processCaptureResult
    }

    private val captureCallback: FaceCaptureCallback by inject {
        onCaptureCompleted = this@FaceRecognitionFragment::onCaptureCompleted
    }

    private val cameraManager: CameraManager? by lazy {
        activity?.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }

    private val cameraOpenCloseLock: Semaphore = Semaphore(1)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var imageReader: ImageReader? = null

    private var cameraDevice: CameraDevice? = null
    private var cameraId: String = ""

    private var captureSession: CameraCaptureSession? = null

    private var sensorOrientation: Int = 0
    private var previewSize: Size? = null
        get() = field?.run { if (isLandscapeOrientation) this else Size(height, width) }

    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewRequest: CaptureRequest? = null

    @get:Synchronized
    @set:Synchronized
    private var currentPhoto: File?
        get() = viewModel.photos.lastOrNull()
        set(value) { value?.let { viewModel.photos.add(it) } }

    private val isLandscapeOrientation: Boolean
        get() = (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

    private val orientation: Map<Int, Int> = mapOf(
        Surface.ROTATION_0 to 90,
        Surface.ROTATION_90 to 0,
        Surface.ROTATION_180 to 270,
        Surface.ROTATION_270 to 180
    )

    override fun onResume() {
        super.onResume()
        if (viewModel.method != null) {
            setupPreview()
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            PermissionRequestCode.CAMERA -> {
                if (grantResults.isEmpty() || grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    ErrorUtil.failWithCameraPermissionDenied(viewModel::onCameraError)
                }
            }
        }
    }

    fun register(userId: String, registrationListener: RegistrationListener, livenessMode: LivenessMode) {
        initMethod(RegistrationMethod(userId, registrationListener, livenessMode))
    }

    fun authenticate(userId: String?, authenticationListener: AuthenticationListener, livenessMode: LivenessMode) {
        initMethod(AuthenticationMethod(userId, authenticationListener, livenessMode))
    }

    private fun initMethod(methodType: MethodType<*>) {
        with (viewModel) {
            photos.clear()
            method = methodType
        }
        setupPreview()
    }

    private fun setupPreview() {
        startBackgroundThread()
        with (faceCameraTextureView) {
            when {
                isAvailable -> openCamera(width, height)
                else -> surfaceTextureListener = this@FaceRecognitionFragment.surfaceTextureListener
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        try {
            setupCameraOutputs(width, height)
            configureTransform(width, height)

            if (!cameraOpenCloseLock.tryAcquire(CAMERA_LOCK_TIMEOUT, TimeUnit.MILLISECONDS)) {
                ErrorUtil.failWithCameraTimeoutOpening()
            }

            cameraManager?.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            ErrorUtil.failWithCameraError(viewModel::onCameraError, e)
        } catch (e: InterruptedException) {
            ErrorUtil.failWithCameraInterruptedOpening(e)
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()

            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            ErrorUtil.failWithCameraInterruptedClosing(e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun onCameraOpened(camera: CameraDevice) {
        cameraOpenCloseLock.release()
        cameraDevice = camera
        createCameraPreviewSession()
    }

    private fun onCameraDisconnected() {
        cameraOpenCloseLock.release()
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun onCameraError() {
        ErrorUtil.failWithCameraError(viewModel::onCameraError)
    }

    private fun onCaptureSessionConfigured(session: CameraCaptureSession) {
        if (cameraDevice == null) return

        captureSession = session
        try {
            previewRequestBuilder?.run {
                set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
                previewRequest = build().also { captureSession?.setRepeatingRequest(it, precaptureCallback, backgroundHandler) }
            }
        } catch (e: CameraAccessException) {
            ErrorUtil.failWithCameraError(viewModel::onCameraError, e)
        }
    }

    private fun onCaptureSessionConfigureFailed() {
        ErrorUtil.failWithCameraError(viewModel::onCameraError)
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = previewSize?.let {
                faceCameraTextureView.surfaceTexture.apply { setDefaultBufferSize(it.width, it.height) }
            }

            val surface = Surface(texture)

            previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(surface)
            }

            cameraDevice?.createCaptureSession(Arrays.asList(surface, imageReader?.surface), captureSessionCallback, null)
        } catch (e: CameraAccessException) {
            ErrorUtil.failWithCameraError(viewModel::onCameraError, e)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread(CAMERA_BACKGROUND_THREAD_NAME).apply { start() }
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            ErrorUtil.failWithCameraError(viewModel::onCameraError, e)
        }
    }

    private fun checkPermissionsAndOpenCamera(width: Int, height: Int) {
        proceedOrRequestPermissions(PermissionRequestCode.CAMERA, Manifest.permission.CAMERA) {
            openCamera(width, height)
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        backgroundHandler?.post(FileUtil.ImageSaver(reader.acquireNextImage(), currentPhoto))
        with (viewModel) {
            if (hasNotEnoughPhotos) {
                currentPhoto = FileUtil.createTempFile(biometricsType)
            } else {
                processPhotos()
            }
        }
    }

    @Throws(CameraException::class)
    private fun setupCameraOutputs(width: Int, height: Int) {
        cameraManager?.let { manager ->
            try {
                manager.cameraIdList.forEach { cameraId ->
                    val characteristics = manager.getCameraCharacteristics(cameraId)
                    val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                            ?.assertEqual(CameraCharacteristics.LENS_FACING_FRONT)
                    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                    if (cameraDirection != null && map != null) {
                        val outputSize = map.getOutputSizes(ImageFormat.JPEG)
                            .filterNot { it.toComparable() > Size(MAX_CAPTURE_WIDTH, MAX_CAPTURE_HEIGHT) }
                            .maxBy { it.getArea() } ?: ErrorUtil.failWithCameraError()
                        
                        currentPhoto = FileUtil.createTempFile(viewModel.biometricsType)
                        imageReader =
                            ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, MAX_IMAGE_READER_IMAGES)
                                .apply { setOnImageAvailableListener(onImageAvailableListener, backgroundHandler) }

                        val defaultDisplay = activity?.windowManager?.defaultDisplay ?: ErrorUtil.failWithCameraError()
                        val displayRotation = defaultDisplay.rotation

                        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: ErrorUtil.failWithCameraError()
                        val swappedDimensions = areDimensionsSwapped(displayRotation)

                        val displaySize = Point().also { defaultDisplay.getSize(it) }

                        val rotatedPreviewWidth = if (swappedDimensions) height else width
                        val rotatedPreviewHeight = if (swappedDimensions) width else height
                        val maxPreviewWidth = (if (swappedDimensions) displaySize.y else displaySize.x).limitTo(
                            MAX_PREVIEW_WIDTH
                        )
                        val maxPreviewHeight = (if (swappedDimensions) displaySize.x else displaySize.y).limitTo(
                            MAX_PREVIEW_HEIGHT
                        )

                        previewSize = chooseOptimalSize(
                            map.getOutputSizes(SurfaceTexture::class.java),
                            rotatedPreviewWidth,
                            rotatedPreviewHeight,
                            maxPreviewWidth,
                            maxPreviewHeight,
                            outputSize
                        )

                        previewSize?.let {
                            with (faceCameraTextureView) {
                                onDimensionsMeasured = { width, height -> faceCameraSurfaceView.size =
                                    Size(width, height)
                                }
                                ratioSize = Size(it.width, it.height)
                            }
                        }

                        this.cameraId = cameraId

                        return
                    }
                }
            } catch (e: CameraAccessException) {
                ErrorUtil.failWithCameraError(viewModel::onCameraError, e)
            } catch (e: NullPointerException) {
                ErrorUtil.failWithCameraApiNotSupported(viewModel::onCameraError)
            } catch (e: Exception) {
                ErrorUtil.failWithCameraError(viewModel::onCameraError, e)
            }
        }
    }

    private fun areDimensionsSwapped(displayRotation: Int): Boolean =
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> sensorOrientation  == 90 || sensorOrientation == 270
            Surface.ROTATION_90, Surface.ROTATION_270 -> sensorOrientation == 0 || sensorOrientation == 180
            else -> false
        }

    private fun chooseOptimalSize(choices: Array<Size>,
                                  textureViewWidth: Int,
                                  textureViewHeight: Int,
                                  maxWidth: Int,
                                  maxHeight: Int,
                                  aspectRatio: Size): Size {

        val sizesBigEnough = mutableListOf<Size>()
        val sizesNotBigEnough = mutableListOf<Size>()

        choices
            .filter { it.width <= maxWidth && it.height <= maxHeight && it.height == it.width * aspectRatio.height / aspectRatio.width }
            .splitBy { it.width >= textureViewWidth && it.height >= textureViewHeight }
            .unwrap(sizesBigEnough, sizesNotBigEnough)

        return when {
            sizesBigEnough.isNotEmpty() -> sizesBigEnough.minBy { it.getArea() }
            sizesNotBigEnough.isNotEmpty() -> sizesNotBigEnough.maxBy { it.getArea() }
            else -> null
        } ?: choices[0]
    }

    @Throws(CameraException::class)
    private fun configureTransform(width: Int, height: Int) {
        val rotation = activity?.windowManager?.defaultDisplay?.rotation ?: ErrorUtil.failWithCameraError()

        val matrix = Matrix()

        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val bufferRect = previewSize?.run { RectF(0f, 0f, height.toFloat(), width.toFloat()) }

        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                bufferRect?.let { it.offset(centerX - it.centerX(), centerY - it.centerY()) }
                val scale = previewSize?.let {
                    Math.max(
                        height.toFloat() / it.height,
                        width.toFloat() / it.width
                    )
                } ?: ErrorUtil.failWithCameraError()

                with(matrix) {
                    setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                    postScale(scale, scale, centerX, centerY)
                    postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
                }
            }
            Surface.ROTATION_180 -> matrix.postRotate(180f, centerX, centerY)
        }

        faceCameraTextureView.setTransform(matrix)
    }

    private fun processCaptureResult(captureResult: CaptureResult) {
        when (viewModel.cameraCaptureState) {
            PREVIEW -> {
                if (captureResult.frameNumber > MIN_PREVIEW_FRAMES_TO_WAIT) {
                    viewModel.cameraCaptureState = FACE_DETECTION
                }
            }
            FACE_DETECTION -> {
                val mode = captureResult.get(CaptureResult.STATISTICS_FACE_DETECT_MODE)
                val faces = captureResult.get(CaptureResult.STATISTICS_FACES)

                if (faces?.isNotEmpty() == true && mode != null) {
                    takePhotoIfFaceInRightPosition(faces[0])
                }
            }
            WAITING_LOCK -> capturePictures(captureResult)
            WAITING_PRECAPTURE -> {
                val aeState = captureResult.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                    viewModel.cameraCaptureState = WAITING_NON_PRECAPTURE
                }
            }
            WAITING_NON_PRECAPTURE -> {
                val aeState = captureResult.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                    viewModel.cameraCaptureState = PICTURE_TAKEN
                    captureStillPictures()
                }
            }
            PICTURE_TAKEN -> Unit
        }
    }

    private fun takePhotoIfFaceInRightPosition(face: Face) {
        with (faceCameraSurfaceView) {
            size?.let { size ->
                faceBox?.let { box ->
                    val xCenter = translateX(face.bounds.exactCenterX(), size.width.toFloat())
                    val yCenter = translateY(face.bounds.exactCenterY(), size.height.toFloat())

                    val translatedBox = translateBox(box, size)
                    if (translatedBox.contains(xCenter, yCenter)) {
                        lockFocus()
                    }
                }
            }
        }
    }

    private fun translateX(x: Float, overlayWidth: Float): Float =
        abs(overlayWidth - x * getWidthScaleFactor(overlayWidth))

    private fun translateY(y: Float, overlayHeight: Float): Float =
        y * getHeightScaleFactor(overlayHeight)

    private fun translateBox(box: RectF, overlaySize: Size): RectF {
        with (box) {
            val overlayWidth = overlaySize.width.toFloat()
            val overlayHeight = overlaySize.height.toFloat()

            val xCenter = translateX(centerX(), overlayWidth)
            val yCenter = translateY(centerY(), overlayHeight)

            val xOffset = width() / 2
            val yOffset = height() / 2

            val tLeft = xCenter - xOffset
            val tTop = yCenter - yOffset
            val tRight = xCenter + xOffset
            val tBottom = yCenter + yOffset

            return RectF(tLeft, tTop, tRight, tBottom)
        }
    }

    private fun getWidthScaleFactor(overlayWidth: Float): Float = getScaleFactor(overlayWidth, previewSize?.width)

    private fun getHeightScaleFactor(overlayHeight: Float): Float = getScaleFactor(overlayHeight, previewSize?.height)

    private fun getScaleFactor(overlayDimen: Float, previewDimen: Int?): Float =
        overlayDimen / (previewDimen?.toFloat() ?: overlayDimen)

    private fun capturePictures(captureResult: CaptureResult) {
        when (captureResult.get(CaptureResult.CONTROL_AF_STATE)) {
            null -> captureStillPictures()
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                val aeState = captureResult.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    viewModel.cameraCaptureState = PICTURE_TAKEN
                    captureStillPictures()
                } else {
                    runPrecaptureSequence()
                }
            }
        }
    }

    @Throws(CameraException::class)
    private fun captureStillPictures() {
        try {
            val rotation = activity?.windowManager?.defaultDisplay?.rotation ?: ErrorUtil.failWithCameraError()
            val captureTarget = imageReader?.surface ?: ErrorUtil.failWithCameraError()
            val captureBuilder =
                cameraDevice
                    ?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    ?.apply {
                        addTarget(captureTarget)

                        val orientation = orientation[rotation] ?: ErrorUtil.failWithCameraError()
                        set(CaptureRequest.JPEG_ORIENTATION, (orientation + sensorOrientation + 270) % 360)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                        set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                    } ?: ErrorUtil.failWithCameraError()

            val captureRequests = mutableListOf<CaptureRequest>()
            for (i in 0 until FaceRecognitionViewModel.PHOTOS_REQUIRED_NUMBER) {
                captureRequests.add(captureBuilder.build())
            }

            captureSession?.run {
                stopRepeating()
                abortCaptures()
                captureBurst(captureRequests, captureCallback, null)
                captureBuilder.removeTarget(captureTarget)
            }
        } catch (e: CameraAccessException) {
            ErrorUtil.failWithCameraError(viewModel::onCameraError, e)
        }
    }

    private fun runPrecaptureSequence() {
        try {
            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            viewModel.cameraCaptureState = WAITING_PRECAPTURE
            previewRequestBuilder?.let { captureSession?.capture(it.build(), precaptureCallback, backgroundHandler) }
        } catch (e: CameraAccessException) {
            ErrorUtil.failWithCameraError(viewModel::onCameraError, e)
        }
    }

    private fun onCaptureCompleted() {
        unlockFocus()
    }

    private fun lockFocus() {
        try {
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            viewModel.cameraCaptureState = WAITING_LOCK
            previewRequestBuilder?.let { captureSession?.capture(it.build(), precaptureCallback, backgroundHandler) }
        } catch (e: CameraAccessException) {
            ErrorUtil.failWithCameraError(viewModel::onCameraError, e)
        }
    }

    private fun unlockFocus() {
        try {
            previewRequestBuilder?.run {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                captureSession?.capture(build(), precaptureCallback, backgroundHandler)
            }
            viewModel.cameraCaptureState = PICTURE_TAKEN
            previewRequest?.let { captureSession?.setRepeatingRequest(it, precaptureCallback, backgroundHandler) }
        } catch (e: CameraAccessException) {
            ErrorUtil.failWithCameraError(viewModel::onCameraError)
        }
    }

    companion object {
        private const val CAMERA_BACKGROUND_THREAD_NAME = "CameraBackgroundThread"

        private const val MAX_IMAGE_READER_IMAGES = 2

        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080

        private const val MAX_CAPTURE_WIDTH = 640
        private const val MAX_CAPTURE_HEIGHT = 480

        private const val MIN_PREVIEW_FRAMES_TO_WAIT = 25

        private const val CAMERA_LOCK_TIMEOUT = 2500L
    }
}