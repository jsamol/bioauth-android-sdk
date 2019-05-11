package pl.edu.agh.bioauth.internal.biometrics.facerecognition.ui

import pl.edu.agh.bioauth.exception.CameraException
import pl.edu.agh.bioauth.internal.base.BaseViewModel
import pl.edu.agh.bioauth.internal.biometrics.common.photo.PhotoProcessor
import pl.edu.agh.bioauth.internal.biometrics.common.type.AuthenticationMethod
import pl.edu.agh.bioauth.internal.biometrics.common.type.BiometricsType
import pl.edu.agh.bioauth.internal.biometrics.common.type.MethodType
import pl.edu.agh.bioauth.internal.biometrics.common.type.RegistrationMethod
import pl.edu.agh.bioauth.internal.biometrics.facerecognition.CameraCaptureState
import pl.edu.agh.bioauth.internal.biometrics.facerecognition.CameraCaptureState.FACE_DETECTION
import pl.edu.agh.bioauth.internal.network.ApiController
import pl.edu.agh.bioauth.internal.network.callback.AuthenticationCallback
import pl.edu.agh.bioauth.internal.network.callback.RegistrationCallback
import pl.edu.agh.bioauth.internal.util.ErrorUtil
import pl.edu.agh.bioauth.internal.util.SecurityUtil
import java.io.File

internal class FaceRecognitionViewModel : BaseViewModel() {

    override val biometricsType: BiometricsType by inject()

    val photos: MutableList<File> = mutableListOf()
    val hasNotEnoughPhotos: Boolean
        get() = photos.size < PHOTOS_REQUIRED_NUMBER

    var method: MethodType<*>? = null
        set(value) {
            field = value
            value?.let {
                when (it) {
                    is RegistrationMethod -> registrationCallback.listener = it.listener
                    is AuthenticationMethod -> authenticationCallback.listener = it.listener
                }
            }
        }

    var cameraCaptureState: CameraCaptureState = FACE_DETECTION

    private val apiController: ApiController by inject()

    private val photoProcessor: PhotoProcessor by inject()

    private val securityUtil: SecurityUtil by inject()

    private val registrationCallback: RegistrationCallback by inject()

    private val authenticationCallback: AuthenticationCallback by inject()

    @Throws(IllegalStateException::class)
    fun processPhotos() {
        method?.let {
            val processedPhotos = photoProcessor.preprocessPhotos(photos)
            when (it) {
                is RegistrationMethod -> registerPhotos(it.userId, processedPhotos)
                is AuthenticationMethod -> authenticatePhotos(it.userId, processedPhotos)
            }
        } ?: ErrorUtil.failWithIllegalState()
    }

    fun onCameraError(cameraException: CameraException) {
        method?.listener?.onFailure(cameraException)
    }

    private fun registerPhotos(userId: String, photos: List<File>) {
        apiController
            .registerSamples(userId, photos, biometricsType)
            .enqueue(registrationCallback)
    }

    private fun authenticatePhotos(userId: String?, photos: List<File>) {
        val challenge = securityUtil.challenge
        apiController
            .authenticate(userId, photos, challenge, biometricsType)
            .enqueue(authenticationCallback.also { it.challenge = challenge })
    }

    companion object {
        private const val PHOTOS_REQUIRED_NUMBER = 3
    }
}