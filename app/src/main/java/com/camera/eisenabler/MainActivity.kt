package com.camera.eisenabler

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executor
import java.util.*
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.content.pm.ActivityInfo
import android.content.res.Configuration

class MainActivity : AppCompatActivity() {
    private lateinit var textureView: TextureView
    private lateinit var tvTimer: TextView
    private lateinit var btnRecord: ImageButton

    private var cameraDevice: CameraDevice? = null
    private var previewSession: CameraCaptureSession? = null
    private var recordSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private var isRecording = false
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null
    private var recordingStartTime = 0L
    private var videoUri: Uri? = null
	private var previewSurface: Surface? = null
	private var recorderSurface: Surface? = null

    // Флаг для автозаписи при запуске через виджет
    private var autoStartRecording = false 
	private var isBackgroundMode = false
	
    // фиксируем для превью, чтобы EIS корректно работал с кропом
    private var previewSize = Size(960, 540) 
    private val videoSize = Size(3840, 2160)
    private var sensorOrientation = 0
	
	// Кнопка фонарика и состояние
	private lateinit var btnTorch: Button
	private var isTorchOn = false
	private var currentBuilder: CaptureRequest.Builder? = null

	// Переменные для метрик производительности
	private lateinit var tvFrameTime: TextView
	private var lastFrameTimestampNs = 0L
	private var frameCount = 0L
	private var totalFrameTimeUs = 0L
	private var totalPipelineLatencyUs = 0L
	private var frameTimeResetHandler: Handler? = null

    companion object {
        // 32772 в hex = 0x8004. Кастомный Operation Mode для MTK/Xiaomi Video Session
        const val SESSION_OPERATION_MODE_VIDEO = 0x8004 
        const val VIDEO_RECORD_CONTROL_PREPARE = 0
        const val VIDEO_RECORD_CONTROL_START = 1
        const val VIDEO_RECORD_CONTROL_STOP = 2

		private var activeInstance: MainActivity? = null

		fun startBackgroundMode(context: android.content.Context) {
			activeInstance?.let {
				if (!it.isRecording) {
					it.startRecording()
				}
			}
		}

		fun stopBackgroundMode() {
			activeInstance?.let {
				if (it.isRecording) {
					it.stopRecording()
				}
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		
	    // ЖЕСТКО ФИКСИРУЕМ ГОРИЗОНТАЛЬНЫЙ РЕЖИМ ДЛЯ ИНИЦИАЛИЗАЦИИ КАМЕРЫ
		//requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

		activeInstance = this
		
		val isQuickRecord = intent?.getBooleanExtra("EXTRA_QUICK_RECORD", false) == true
		
		// Если уже пишем и пришел сигнал из виджета - останавливаем и закрываемся
		if (isQuickRecord && isRecording) {
			stopRecording()
			finish()
			return
		}
		
		setContentView(R.layout.activity_main)
		
		// ЗАПРЕЩАЕМ СИСТЕМЕ ГАСИТЬ ЭКРАН ПО ТАЙМЕРУ
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		
		setupTorchButton()
		textureView = findViewById(R.id.textureView)
		tvTimer = findViewById(R.id.tvTimer)
		btnRecord = findViewById(R.id.btnRecord)
		tvFrameTime = findViewById(R.id.tvFrameTime)

		// Вызываем инициализацию ПОСЛЕ того, как нашли все View (иначе будет NullPointerException)
		if (isQuickRecord) {
			initBackgroundMode()
		} else {
			hideSystemUI()
		}
		
		if (!hasPermissions()) {
			requestPermissions()
		}
		
		textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
			override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
				openCamera(width, height)
			}
			override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
				configureTransform(width, height)
			}
			override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
			override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
		}
		
		btnRecord.setOnClickListener {
			if (isRecording) stopRecording() else startRecording()
		}
	}

	private fun initBackgroundMode() {
		isBackgroundMode = true
		autoStartRecording = true
		window.setBackgroundDrawableResource(android.R.color.transparent)
		
		// Скрываем UI
		textureView.alpha = 0f 
		tvTimer.visibility = TextView.GONE
		btnRecord.visibility = ImageButton.GONE
		tvFrameTime.visibility = TextView.GONE
		btnTorch.visibility = Button.GONE
		
		// Сворачиваем приложение, чтобы оно не отображалось на экране
		moveTaskToBack(true)
	}
	
	override fun onNewIntent(intent: Intent?) {
		super.onNewIntent(intent)
		setIntent(intent) // Обновляем intent, чтобы getIntent() возвращал актуальный
		intent?.let {
			val isQuickRecord = it.getBooleanExtra("EXTRA_QUICK_RECORD", false)
			if (isQuickRecord) {
				if (isRecording) {
					stopRecording()
					finish()
				} else {
					// Приложение было свернуто, а не закрыто. 
					// Инициализируем фоновый режим и начинаем запись.
					initBackgroundMode()
					
					// Если камера уже открыта (например, был просто превью), сразу начинаем запись.
					if (cameraDevice != null) {
						startRecording()
					}
					// Если камера закрыта, она откроется в onResume, 
					// а флаг autoStartRecording запустит запись автоматически.
				}
			}
		}
	}
	
	override fun onDestroy() {
		super.onDestroy()
		activeInstance = null
		if (isBackgroundMode) {
			val serviceIntent = Intent(this, BackgroundRecordService::class.java)
			stopService(serviceIntent)
		}
	}

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hasPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val storage = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return camera && audio && storage
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }
	
	private fun closeCamera() {
		try {
			previewSession?.close()
		} catch (e: Exception) {}
		previewSession = null

		try {
			recordSession?.close()
		} catch (e: Exception) {}
		recordSession = null

		try {
			cameraDevice?.close()
		} catch (e: Exception) {}
		cameraDevice = null
	}

    private fun openCamera(width: Int, height: Int) {
	    // ЗАЩИТА: Если камера уже открыта (или в процессе открытия), не открываем вторую, иначе сессия сломается
		if (cameraDevice != null) return 

        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(SurfaceTexture::class.java)
            //previewSize = chooseOptimalSize(outputSizes ?: arrayOf(Size(960, 540)), width, height)
			previewSize = chooseOptimalSize(outputSizes ?: arrayOf(Size(1280, 720)), width, height)

            configureTransform(width, height)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        // ПРИОРИТЕТ 1: Жестко выбираем для превью
        choices.firstOrNull { it.width == 720 && it.height == 480 }?.let { return it }

        // ПРИОРИТЕТ 2: Выбор по соотношению сторон
        val targetRatio = width.toDouble() / height.toDouble()
        var optimalSize = choices[0]
        var minDiff = Int.MAX_VALUE

        for (size in choices) {
            val ratio = size.width.toDouble() / size.height.toDouble()
            if (Math.abs(ratio - targetRatio) < 0.1) {
                val diff = Math.abs(size.height - height)
                if (diff < minDiff) {
                    optimalSize = size
                    minDiff = diff
                }
            }
        }
        return optimalSize
    }
	
	private fun configureTransform(viewWidth: Int, viewHeight: Int) {
		if (null == textureView || null == previewSize) return
		val rotation = windowManager.defaultDisplay.rotation
		val matrix = Matrix()
		val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
		val centerX = viewRect.centerX()
		val centerY = viewRect.centerY()
		
		val scale = Math.max(
			viewHeight.toFloat() / previewSize.height,
			viewWidth.toFloat() / previewSize.width
		)

		if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
			// Альбомная ориентация (90° или 270°)
			val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
			bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
			matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
			matrix.postScale(scale, scale, centerX, centerY)
			matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
		} else if (Surface.ROTATION_180 == rotation) {
			// Вверх ногами (180°)			
			// Буфер камеры горизонтальный (960x540), мысленно поворачиваем его для расчета
			val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
			bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
			
			// ИСПРАВЛЕНИЕ: Используем CENTER вместо FILL. 
			// FILL растягивал картинку, а postScale зумил её ВТОРОЙ РАЗ, из-за чего был дикий кроп.
			matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.CENTER)
			
			// Считаем корректный масштаб для заполнения экрана (Center Crop)
			val scaleX = viewRect.width() / bufferRect.width()
			val scaleY = viewRect.height() / bufferRect.height()
			val scale = Math.max(scaleX, scaleY)
			
			// Применяем ТОЛЬКО ОДИН масштаб (убираем двойной зум)
			matrix.postScale(scale, scale, centerX, centerY)
			
			// Поворачиваем картинку на 180 градусов, так как сенсор выдает горизонтальный кадр
			matrix.postRotate(180f, centerX, centerY)
		} else {
			// Портретная ориентация (0°)
			// Буфер камеры горизонтальный (960x540), мысленно поворачиваем его для расчета
			val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
			bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
			
			// ИСПРАВЛЕНИЕ: Используем CENTER вместо FILL. 
			// FILL растягивал картинку, а postScale зумил её ВТОРОЙ РАЗ, из-за чего был дикий кроп.
			matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.CENTER)
			
			// Считаем корректный масштаб для заполнения экрана (Center Crop)
			val scaleX = viewRect.width() / bufferRect.width()
			val scaleY = viewRect.height() / bufferRect.height()
			val scale = Math.max(scaleX, scaleY)
			
			// Применяем ТОЛЬКО ОДИН масштаб (убираем двойной зум)
			matrix.postScale(scale, scale, centerX, centerY)
			
			// Поворачиваем картинку на 90 градусов, так как сенсор выдает горизонтальный кадр
			//matrix.postRotate(90f, centerX, centerY)
		}
		
		textureView.setTransform(matrix)
	}

	// Динамическое создание кнопки фонарика
	private fun setupTorchButton() {
		btnTorch = Button(this).apply {
			text = "🔦"
			setBackgroundColor(Color.argb(150, 0, 0, 0))
			setTextColor(Color.WHITE)
			textSize = 20f
		}
		val frameParams = android.widget.FrameLayout.LayoutParams(150, 150).apply {
			gravity = Gravity.TOP or Gravity.END
			marginEnd = 32
			topMargin = 100
		}
		(window.decorView as ViewGroup).addView(btnTorch, frameParams)
		
		btnTorch.setOnClickListener { toggleTorch() }
	}

    // Переключение фонарика (логика FreeDCam)
	private fun toggleTorch() {
		if (cameraDevice == null) return
		
		isTorchOn = !isTorchOn
		val session = if (isRecording) recordSession else previewSession
		if (session == null) return

		// Создаем локальные неизменяемые копии (val), чтобы Kotlin не ругался на многопоточность
		val localPreview = previewSurface
		val localRecorder = recorderSurface

		if (localPreview == null) return

		// Все операции в фоновом потоке
		backgroundHandler?.post {
			try {
				// ШАГ 1: Жестко сбрасываем в OFF и блокируем AE/AWB
				val builderOff = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
				configureVideoRequest(builderOff, localPreview, localRecorder, false, VIDEO_RECORD_CONTROL_START)
				session.setRepeatingRequest(builderOff.build(), performanceCallback, backgroundHandler)

				// ШАГ 2: Через 50мс включаем нужное состояние (TORCH или OFF)
				backgroundHandler?.postDelayed({
					try {
						val builderTorch = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
						configureVideoRequest(builderTorch, localPreview, localRecorder, isTorchOn, VIDEO_RECORD_CONTROL_START)
						session.setRepeatingRequest(builderTorch.build(), performanceCallback, backgroundHandler)

						// ШАГ 3: Еще через 100мс разблокируем AE/AWB
						backgroundHandler?.postDelayed({
							try {
								val builderUnlock = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
								configureVideoRequest(builderUnlock, localPreview, localRecorder, isTorchOn, VIDEO_RECORD_CONTROL_START)
								session.setRepeatingRequest(builderUnlock.build(), performanceCallback, backgroundHandler)
							} catch (e: Exception) { e.printStackTrace() }
						}, 100)
					} catch (e: Exception) { e.printStackTrace() }
				}, 50)
			} catch (e: Exception) { e.printStackTrace() }
		}

		runOnUiThread {
			btnTorch.text = if (isTorchOn) "💡" else "🔦"
		}
	}

	// Коллбэк для сбора метрик
	private val performanceCallback = object : CameraCaptureSession.CaptureCallback() {
		override fun onCaptureCompleted(
			session: CameraCaptureSession,
			request: CaptureRequest,
			result: TotalCaptureResult
		) {
			val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
			
			// 1. Считаем время между кадрами в МИКРОСЕКУНДАХ
			if (lastFrameTimestampNs != 0L) {
				val frameTimeUs = (sensorTimestamp - lastFrameTimestampNs) / 1_000
				totalFrameTimeUs += frameTimeUs
				frameCount++
			}
			lastFrameTimestampNs = sensorTimestamp

			// 2. Считаем задержку конвейера в МИКРОСЕКУНДАХ
			val nowNs = SystemClock.elapsedRealtimeNanos()
			val pipelineLatencyUs = (nowNs - sensorTimestamp) / 1_000
			totalPipelineLatencyUs += pipelineLatencyUs
		}
	}

	// Функция для сброса и вывода счетчиков каждую секунду
	private fun startFrameTimeResetter() {
		frameTimeResetHandler = Handler(Looper.getMainLooper())
		val updateRunnable = object : Runnable {
			override fun run() {
				// 1. Считаем средние значения (переводим микросекунды в миллисекунды с дробной частью)
				val avgFrameMs = if (frameCount > 0) (totalFrameTimeUs / 1000.0) / frameCount else 0.0
				val avgPipeMs = if (frameCount > 0) (totalPipelineLatencyUs / 1000.0) / frameCount else 0.0
				
				// 2. Выводим на экран с форматированием до 1 знака после запятой
				runOnUiThread {
					tvFrameTime.text = String.format("Frame: %.1fms | Pipe: %.1fms", avgFrameMs, avgPipeMs)
				}
				
				// 3. Обнуляем накопители
				frameCount = 0L
				totalFrameTimeUs = 0L
				totalPipelineLatencyUs = 0L
				
				frameTimeResetHandler?.postDelayed(this, 500)
			}
		}
		frameTimeResetHandler?.postDelayed(updateRunnable, 500)
	}

	private fun stopFrameTimeResetter() {
		frameTimeResetHandler?.removeCallbacksAndMessages(null)
		frameTimeResetHandler = null
	}

	private fun startPreview() {
		if (cameraDevice == null || !textureView.isAvailable) return

		val surfaceTexture = textureView.surfaceTexture ?: return
		surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
		previewSurface = Surface(surfaceTexture)
		
		// 2. Создаем локальную неизменяемую копию val для безопасного использования в лямбдах
		val localPreviewSurface = previewSurface
		if (localPreviewSurface == null) return


		try {
			// 1. Создаем конфигурацию сессии с тем же режимом, что и для записи
			val previewConfig = OutputConfiguration(localPreviewSurface)
			val outputConfigs = listOf(previewConfig)
			val executor: Executor = ContextCompat.getMainExecutor(this)

			val sessionConfig = SessionConfiguration(
				SESSION_OPERATION_MODE_VIDEO, // Используем 0x8004 вместо стандартного режима
				outputConfigs,
				executor,
				object : CameraCaptureSession.StateCallback() {
					override fun onConfigured(session: CameraCaptureSession) {
						previewSession = session
						try {
						
							// РАЗБЛОКИРУЕМ ОРИЕНТАЦИЮ, ТЕПЕРЬ ЭКРАН БУДЕТ СЛЕДОВАТЬ ЗА ДАТЧИКОМ
							// Нельзя, т.к. ломает запись в перевёрнутом режиме
							//requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
							
							
							// РАЗБЛОКИРУЕМ ОРИЕНТАЦИЮ (РАЗРЕШАЕМ 0, 90, 270, НО ЗАПРЕЩАЕМ 180)
							requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER

							// 2. Создаем запрос на базе TEMPLATE_RECORD (а не PREVIEW)
							val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
							
							// 3. Применяем те же настройки, что и для видео, но передаем null вместо recorderSurface
							configureVideoRequest(captureRequestBuilder, localPreviewSurface, null, isTorchOn, VIDEO_RECORD_CONTROL_START)
							
							/* 
							 * ПРИМЕЧАНИЕ: Если в твоем коде нет функции configureVideoRequest, 
							 * а все теги прописаны вручную внутри startRecording через apply, 
							 * то просто скопируй весь блок apply с тегами, кропом и Tonemap 
							 * из startRecording сюда, оставив только addTarget(previewSurface!!).
							 */

							currentBuilder = captureRequestBuilder
							session.setRepeatingRequest(captureRequestBuilder.build(), performanceCallback, backgroundHandler)

							// Если запустились через ярлык, ждем и стартуем запись
							if (autoStartRecording) {
								autoStartRecording = false
								backgroundHandler?.postDelayed({ startRecording() }, 100)
								//startRecording() // Запускаем сразу, без задержки
							}
						} catch (e: Exception) {
							e.printStackTrace()
						}
					}

					override fun onConfigureFailed(session: CameraCaptureSession) {
						runOnUiThread {
							Toast.makeText(this@MainActivity, "Preview Failed", Toast.LENGTH_SHORT).show()
						}
					}
				}
			)

			// 4. Создаем сессию через SessionConfiguration
			cameraDevice!!.createCaptureSession(sessionConfig)

		} catch (e: CameraAccessException) {
			e.printStackTrace()
		}
	}

    private fun setVendorTag(builder: CaptureRequest.Builder, keyName: String, value: Any) {
        try {
            val keyClass = Class.forName("android.hardware.camera2.CaptureRequest\$Key")
            val constructor = keyClass.getDeclaredConstructor(String::class.java, Class::class.java)
            constructor.isAccessible = true

            val typeClass = when (value) {
                is Int -> java.lang.Integer::class.java
                is Byte -> java.lang.Byte::class.java
                is Boolean -> java.lang.Boolean::class.java
                is Float -> java.lang.Float::class.java
                else -> java.lang.Integer::class.java
            }

            @Suppress("UNCHECKED_CAST")
            val key = constructor.newInstance(keyName, typeClass) as CaptureRequest.Key<Any>
            builder.set(key, value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startRecording() {
        if (cameraDevice == null || !textureView.isAvailable) return

        previewSession?.close()
        previewSession = null

        val surfaceTexture = textureView.surfaceTexture!!
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(surfaceTexture)

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VIDEO_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (videoUri == null) {
            runOnUiThread {
                Toast.makeText(this, "Failed to create video file", Toast.LENGTH_LONG).show()
            }
            startPreview()
            return
        }

        val fileDescriptor = resolver.openFileDescriptor(videoUri!!, "w")

        mediaRecorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(fileDescriptor?.fileDescriptor)

            setVideoSize(videoSize.width, videoSize.height)
            setVideoFrameRate(30)
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            setVideoEncodingBitRate(50000000)
            //setOrientationHint(0)
			setOrientationHint(calculateVideoOrientationHint())

            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(2)
            setAudioSamplingRate(48000)
            setAudioEncodingBitRate(256000)

            prepare()
        }

        recorderSurface = mediaRecorder!!.surface

        try {
            val previewConfig = OutputConfiguration(previewSurface!!)
            val recorderConfig = OutputConfiguration(recorderSurface!!)
            val outputConfigs = listOf(previewConfig, recorderConfig)

            val executor: Executor = ContextCompat.getMainExecutor(this)

            // ПЕРЕДАЕМ 0x8004 напрямую в sessionType
            val sessionConfig = SessionConfiguration(
                SESSION_OPERATION_MODE_VIDEO, 
                outputConfigs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        recordSession = session
                        try {
							val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
							configureVideoRequest(captureRequestBuilder, previewSurface!!, recorderSurface, isTorchOn, VIDEO_RECORD_CONTROL_START)
							
							// Сохраняем builder (он больше не будет использоваться для горячего обновления, но нужен для отладки)
							currentBuilder = captureRequestBuilder 
                            session.setRepeatingRequest(captureRequestBuilder.build(), performanceCallback, backgroundHandler)
                            mediaRecorder!!.start()
                            isRecording = true

							if (isBackgroundMode) {
								// Заменяем this на this@MainActivity
								val serviceIntent = Intent(this@MainActivity, BackgroundRecordService::class.java)
								startForegroundService(serviceIntent)
								
								// Сворачиваем приложение в фон
								moveTaskToBack(true)
							}

                            runOnUiThread {
                                btnRecord.setImageResource(android.R.drawable.ic_media_pause)
                                startTimer()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Record start failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Record Failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

            cameraDevice!!.createCaptureSession(sessionConfig)

        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopRecording() {
        try {
            // 1. Плавно останавливаем повторяющиеся запросы
            recordSession?.stopRepeating()
            
            // 2. Отправляем вендорный тег STOP (Xiaomi)
            if (recordSession != null && cameraDevice != null) {
                try {
                    val stopBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    setVendorTag(stopBuilder, "xiaomi.video.recordControl", VIDEO_RECORD_CONTROL_STOP)
                    recordSession?.capture(stopBuilder.build(), null, backgroundHandler)
                } catch (e: Exception) {
                    // Игнорируем, если сессия уже в процессе закрытия
                }
            }

            // 3. Останавливаем и освобождаем MediaRecorder (СТРОГО ДО закрытия сессии!)
            mediaRecorder?.let {
                try {
                    it.stop()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                it.reset()
                it.release()
            }
            mediaRecorder = null

            // 4. Закрываем сессию записи
            recordSession?.close()
            recordSession = null
			recorderSurface = null

            // 5. Сохраняем файл в галерею
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            videoUri?.let { contentResolver.update(it, contentValues, null, null) }
            videoUri = null

            isRecording = false

            // 6. Обновляем UI
            runOnUiThread {
                btnRecord.setImageResource(android.R.drawable.ic_media_play)
                stopTimer()
                Toast.makeText(this, "Видео сохранено в DCIM/Camera", Toast.LENGTH_SHORT).show()
            }

            // 7. Возобновляем превью
            startPreview()

        } catch (e: Exception) {
            e.printStackTrace()
            // Аварийное освобождение ресурсов при краше
            mediaRecorder?.let {
                try { it.reset(); it.release() } catch (ex: Exception) {}
            }
            mediaRecorder = null
            
            videoUri?.let { 
                try { contentResolver.delete(it, null, null) } catch (ex: Exception) {}
            }
            videoUri = null
            isRecording = false
            
            runOnUiThread {
                btnRecord.setImageResource(android.R.drawable.ic_media_play)
                stopTimer()
            }
            startPreview()
        }
    }
	
	// Формируем чистый CaptureRequest для видео (как в FreeDCam)
	private fun configureVideoRequest(
		builder: CaptureRequest.Builder,
		previewSurface: Surface,
		recorderSurface: Surface?,
		torchOn: Boolean,
		recordControlState: Int = VIDEO_RECORD_CONTROL_PREPARE 
	) {
		// Превью во время записи (уменьшает буфер записи с 350мс до 160мс)
		builder.addTarget(previewSurface)
		if (recorderSurface != null) builder.addTarget(recorderSurface)

		builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
		// MTK MFNR шумоподавление, отлично работает на poco m5s 
		setVendorTag(builder, "com.mediatek.nrfeature.3dnrmode", 1)
		setVendorTag(builder, "xiaomi.ainr.enabled", 1)

		
		
		//setVendorTag(builder, "com.vidhance.stabilizer.mode", 1)
		setVendorTag(builder, "xiaomi.pro.video.histogram.stats.enabled", 0)
		// 1. ОТКЛЮЧАЕМ КОНФЛИКТУЮЩИЕ СТАБИЛИЗАТОРЫ
		setVendorTag(builder, "com.mediatek.eisfeature.eismode", 0)
		setVendorTag(builder, "com.mediatek.eisfeature.previeweis", 0)

		// 2. 
		setVendorTag(builder, "com.vidhance.stabilizer.mode", 0)
		//setVendorTag(builder, "com.xiaomi.camera.supportedfeatures.videoEis", 1)

		// 3. ЗАСТАВЛЯЕМ HAL СДЕЛАТЬ АППАРАТНЫЙ OVERFETCH
		// Эти теги говорят HAL: "Сам считай лишние пиксели с краев сенсора для EIS, не заставляй приложение делать кроп"
		//setVendorTag(builder, "com.xiaomi.camera.eis.fov", 1)
		//setVendorTag(builder, "xiaomi.pro.video.eis.fov", 1)
		
		// Включаем аппаратные фильтры энкодера HEVC, убирают квадраты на границах
		setVendorTag(builder, "com.mediatek.vencfeature.deblocking", 1)
		setVendorTag(builder, "com.mediatek.vencfeature.sao", 1)
		setVendorTag(builder, "com.mediatek.streamingfeature.deblocking", 1)

		setVendorTag(builder, "xiaomi.remosaic.enabled", 0)
		setVendorTag(builder, "com.mediatek.control.capture.remosaicenable", 0)
		setVendorTag(builder, "xiaomi.ai.asd.enabled", 0)
		setVendorTag(builder, "com.mediatek.mfnrfeature.mfbmode", 0)
		
		setVendorTag(builder, "com.xiaomi.camera.supportedfeatures.videoBeauty", 0)
		setVendorTag(builder, "com.xiaomi.camera.supportedfeatures.videofilter", 0)
		setVendorTag(builder, "com.xiaomi.camera.supportedfeatures.colorenhancement", 0)
		setVendorTag(builder, "com.xiaomi.camera.supportedfeatures.videomimovie", 0)
		setVendorTag(builder, "com.xiaomi.camera.supportedfeatures.beautyMakeup", 0)
		setVendorTag(builder, "com.mediatek.streamingfeature.cropOuterLinesEnable", 0) 
		setVendorTag(builder, "com.mediatek.control.capture.ispMetaEnable", 0)
		setVendorTag(builder, "com.mediatek.streamingfeature.hdr10", 0)
		setVendorTag(builder, "com.mediatek.bgservicefeature.prerelease", 0)

		//setVendorTag(builder, "xiaomi.video.recordControl", VIDEO_RECORD_CONTROL_START)
		//setVendorTag(builder, "xiaomi.pro.video.log.enabled", 1)
	
		// ИСПРАВЛЕНИЕ: Передаем состояние динамически. 
		// В превью камера "готовится" (PREPARE), и только при старте записи получает "START".
		setVendorTag(builder, "xiaomi.video.recordControl", recordControlState)
		
		// === НОВЫЕ ТЕГИ ДЛЯ ПЛАВНОГО ПЕРЕХОДА  ===
		// 1. Включаем плавный переход AE на MTK
		setVendorTag(builder, "com.mediatek.3afeature.aeSmoothTransition", 1)
		setVendorTag(builder, "com.mediatek.3afeature.aeSmoothEnable", 1)
		// 2. Замедляем скорость сходимости AE (чтобы яркость не менялась мгновенно)
		setVendorTag(builder, "com.mediatek.3afeature.aeConvergeSpeed", 1) 
		// 3. Сглаживание для видеорежима Xiaomi
		setVendorTag(builder, "xiaomi.pro.video.ae.smooth", 1)
		setVendorTag(builder, "xiaomi.pro.video.ae.stable", 1)
		
		
		
		setVendorTag(builder, "com.mediatek.3afeature.aeSensorGain", 3424)
		setVendorTag(builder, "com.xiaomi.sensorbps.gain", 3.34375000f)
		setVendorTag(builder, "com.mediatek.3afeature.adrcGain", 3213312)
		
		// ЖЕСТКО БЛОКИРУЕМ 30FPS, чтобы HAL не уходил в 20fps после сворачивания
		builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30, 30))
		
		val cropRect = android.graphics.Rect(0, 434, 4624, 3038)
		builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect) 								
		
		builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
		builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
		builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

		builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
		// CONTRAST_CURVE (Rec.709+HLG+усиление_теней)		
		val rec709Points = floatArrayOf(
			0.0f, 0.0f,
			0.02f, 0.15f, // Отсекает\добавляет шум сенсора, точно подобрать сложно
			0.05f, 0.35f,
			0.1f, 0.50f, 
			0.2f, 0.65f, 
			0.3f, 0.75f, 
			0.4f, 0.82f, 
			0.5f, 0.85f,
			0.7f, 0.92f, 
			0.85f, 0.97f, 
			1.0f, 1.0f
		)
		val tonemapCurve = TonemapCurve(rec709Points, rec709Points, rec709Points)
		builder.set(CaptureRequest.TONEMAP_CURVE, tonemapCurve)

		// Блокировка AE/AWB на время переключения, чтобы сенсор не сбросил EIS
		//if (lockAeAwb) {
		//	builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
		//	builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
		//}

		// Применяем фонарик
		builder.set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
	}

    private fun startTimer() {
        tvTimer.visibility = TextView.VISIBLE
        recordingStartTime = System.currentTimeMillis()
        timerHandler = Handler(Looper.getMainLooper())
        timerRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val hours = (elapsed / 3600000).toInt()
                val minutes = ((elapsed % 3600000) / 60000).toInt()
                val seconds = ((elapsed % 60000) / 1000).toInt()
                tvTimer.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                timerHandler?.postDelayed(this, 1000)
            }
        }
        timerHandler?.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerHandler?.removeCallbacks(timerRunnable!!)
        tvTimer.visibility = TextView.GONE
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
		startFrameTimeResetter()
		
		// ФИКСИРУЕМ ЛАНДШАФТ ПЕРЕД КАЖДЫМ ОТКРЫТИЕМ КАМЕРЫ
		//requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
		
		// Используем post, чтобы дождаться завершения всех вращений экрана 
		// и стабилизации размеров TextureView.
		textureView.post {
			if (textureView.isAvailable && cameraDevice == null) {
				// Принудительно пересчитываем матрицу под текущий (уже стабильный) размер
				configureTransform(textureView.width, textureView.height)
				openCamera(textureView.width, textureView.height)
			}
		}

    }

	override fun onPause() {
		stopFrameTimeResetter()
		if (isRecording) {
			// Останавливаем запись только если это НЕ фоновый режим
			if (!isBackgroundMode) {
				stopRecording()
			}
		} else {
			// Закрываем камеру только если это НЕ фоновый режим
			if (!isBackgroundMode) {
				cameraDevice?.close()
				cameraDevice = null
				closeCamera()
				
				// HARD RESET: Убиваем абсолютно всё, чтобы HAL полностью освободил железо
				try { previewSession?.close() } catch (e: Exception) {}
				previewSession = null
				
				try { recordSession?.close() } catch (e: Exception) {}
				recordSession = null
				
				try { cameraDevice?.close() } catch (e: Exception) {}
				cameraDevice = null
				
				// Освобождаем Surface, иначе HAL зависнет
				previewSurface?.release()
				previewSurface = null
				
				recorderSurface?.release()
				recorderSurface = null

				stopBackgroundThread()
			}
		}
		super.onPause()
	}
	
	private fun calculateVideoOrientationHint(): Int {
		val manager = getSystemService(CAMERA_SERVICE) as CameraManager
		val cameraId = manager.cameraIdList[0]
		val characteristics = manager.getCameraCharacteristics(cameraId)
		
		val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
		val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
		val rotation = windowManager.defaultDisplay.rotation
		
		val degrees = when (rotation) {
			Surface.ROTATION_0 -> 0
			Surface.ROTATION_90 -> 90
			Surface.ROTATION_180 -> 180
			Surface.ROTATION_270 -> 270
			else -> 0
		}
		
		val result = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
			(sensorOrientation + degrees) % 360
		} else {
			(sensorOrientation - degrees + 360) % 360
		}
		
		return result
	}
	
	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		
		// При реальном повороте устройства пересчитываем трансформацию превью
		if (::textureView.isInitialized && textureView.isAvailable) {
			configureTransform(textureView.width, textureView.height)
		}
		
		// Если идет запись, можно обновить UI (например, таймер или кнопки), 
		// но сам CaptureRequest (и cropRect) мы не трогаем, так как он уже настроен.
	}
}