package com.example.finalprototype

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

class CameraFragment : Fragment(), TextToSpeech.OnInitListener {

    private val vm: SharedViewModel by activityViewModels()

    private lateinit var tts: TextToSpeech
    private var ttsReady = true

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var selectedDestination: TextView

    private var isScanning = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) startCamera() else resultText.text = "Camera permission is required"
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tts = TextToSpeech(requireContext(), this)

        previewView         = view.findViewById(R.id.previewView)
        resultText          = view.findViewById(R.id.result_text)
        selectedDestination = view.findViewById(R.id.destinationTitle)  // make sure this TextView exists in XML

        // Show current destination at the top and keep it updated
        vm.destination.observe(viewLifecycleOwner) { name ->
            selectedDestination.text = name
        }

        if (!isScanning) {
            checkCameraPermissionAndStart()
            isScanning = true
            previewView.visibility = View.VISIBLE
            resultText.text = "Scanning... point your camera at a QR code"
        }
    }

    private fun checkCameraPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                startCamera()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ->
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            else ->
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    if (ttsReady) {
                        processImageProxy(imageProxy) { raw -> handleQrResult(raw) }
                    } else {
                        imageProxy.close()
                    }
                }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            } catch (e: Exception) {
                e.printStackTrace()
                stopCamera()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopCamera() {
        isScanning = false
        previewView.visibility = View.GONE
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImageProxy(imageProxy: ImageProxy, onResult: (String) -> Unit) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        barcodes[0].rawValue?.let(onResult)
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun handleQrResult(rawValue: String) {
        activity?.runOnUiThread {
            try {
                val json = JSONObject(rawValue)
                val location = json.getString("location")
                val node = json.getInt("node").toString()
                val paths = json.getJSONObject("paths")
                val directions = buildString {
                    val it = paths.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        append("$k: ${paths.getInt(k)}°")
                        if (it.hasNext()) append(", ")
                    }
                }
                val displayText = "Location: $location\nNode: $node\nDirections: $directions"
                resultText.text = displayText
                tts.speak(displayText, TextToSpeech.QUEUE_FLUSH, null, "QR_RESULT")
            } catch (e: Exception) {
                resultText.text = "Invalid QR code format"
                tts.speak("Invalid QR code format", TextToSpeech.QUEUE_FLUSH, null, "QR_ERROR")
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { ttsReady = false }
                override fun onDone(id: String?)  { ttsReady = true }
                override fun onError(id: String?) { ttsReady = true }
            })
        }
    }

    override fun onDestroy() {
        tts.stop(); tts.shutdown()
        super.onDestroy()
    }
}
