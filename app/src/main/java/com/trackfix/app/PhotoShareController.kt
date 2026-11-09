package com.trackfix.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.trackfix.app.core.ActivityStats
import com.trackfix.app.core.ActivityStatsCalculator
import com.trackfix.app.core.FixEngine
import com.trackfix.app.core.Geo
import com.trackfix.app.core.GpxCodec
import com.trackfix.app.core.GpxException
import com.trackfix.app.core.ShareMetric
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.math.roundToInt

/**
 * Owns the "photo" mode: track selection, statistics, metric checkboxes,
 * preview rendering, PNG saving and sharing through the system chooser.
 */
class PhotoShareController(
    private val activity: AppCompatActivity,
    private val executor: ExecutorService
) {

    private data class TrackSelection(
        val uri: Uri,
        val name: String,
        val stats: ActivityStats
    )

    private enum class ActivityType {
        RUN,
        BIKE,
        CUSTOM
    }

    private val metricCheckBoxes = LinkedHashMap<ShareMetric, MaterialCheckBox>()
    private val selectedMetrics = linkedSetOf<ShareMetric>()

    private var trackSelection: TrackSelection? = null
    private var photoUri: Uri? = null
    private var photoBitmap: Bitmap? = null
    private var previewBitmap: Bitmap? = null
    private var pendingSelectedMetrics: Set<ShareMetric>? = null

    private var activityType = ActivityType.RUN
    private var customActivityText = ""
    private var runActivityIcon: Bitmap? = null
    private var bikeActivityIcon: Bitmap? = null
    private var logoBitmap: Bitmap? = null
    private val customTextHandler = Handler(Looper.getMainLooper())
    private val customTextRender = Runnable {
        if (activityType == ActivityType.CUSTOM) requestRender()
    }

    private var trackLoadToken = 0
    private var photoLoadToken = 0
    private var renderToken = 0
    private var loadingTrack = false
    private var loadingPhoto = false
    private var rendering = false
    private var saving = false
    private var sharing = false
    private var updatingMetrics = false

    private lateinit var tvPhotoTrackName: TextView
    private lateinit var tvPhotoTrackInfo: TextView
    private lateinit var btnPickPhotoTrack: MaterialButton
    private lateinit var btnPickPhoto: MaterialButton
    private lateinit var tvPhotoMetricsNote: TextView
    private lateinit var activityGroup: MaterialButtonToggleGroup
    private lateinit var cbPhotoLogo: MaterialCheckBox
    private lateinit var tilPhotoActivityCustom: TextInputLayout
    private lateinit var etPhotoActivityCustom: TextInputEditText
    private lateinit var photoPreviewCard: View
    private lateinit var ivPhotoPreview: ImageView
    private lateinit var tvPhotoPreviewPlaceholder: TextView
    private lateinit var photoPreviewProgress: CircularProgressIndicator
    private lateinit var btnPhotoSave: MaterialButton
    private lateinit var btnPhotoShare: MaterialButton

    private val preferences by lazy {
        activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private val openTrack = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            rememberReadPermission(uri)
            loadTrack(uri)
        }
    }

    private val openPhoto = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            rememberReadPermission(uri)
            loadPhoto(uri)
        }
    }

    private val createDocument = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null) saveTo(uri)
    }

    fun bind() {
        tvPhotoTrackName = activity.findViewById(R.id.tvPhotoTrackName)
        tvPhotoTrackInfo = activity.findViewById(R.id.tvPhotoTrackInfo)
        btnPickPhotoTrack = activity.findViewById(R.id.btnPickPhotoTrack)
        btnPickPhoto = activity.findViewById(R.id.btnPickPhoto)
        tvPhotoMetricsNote = activity.findViewById(R.id.tvPhotoMetricsNote)
        photoPreviewCard = activity.findViewById(R.id.photoPreviewCard)
        ivPhotoPreview = activity.findViewById(R.id.ivPhotoPreview)
        tvPhotoPreviewPlaceholder = activity.findViewById(R.id.tvPhotoPreviewPlaceholder)
        photoPreviewProgress = activity.findViewById(R.id.photoPreviewProgress)
        btnPhotoSave = activity.findViewById(R.id.btnPhotoSave)
        btnPhotoShare = activity.findViewById(R.id.btnPhotoShare)
        activityGroup = activity.findViewById(R.id.activityGroup)
        cbPhotoLogo = activity.findViewById(R.id.cbPhotoLogo)
        tilPhotoActivityCustom = activity.findViewById(R.id.tilPhotoActivityCustom)
        etPhotoActivityCustom = activity.findViewById(R.id.etPhotoActivityCustom)

        metricCheckBoxes[ShareMetric.MOVING_TIME] =
            activity.findViewById(R.id.cbPhotoMovingTime)
        metricCheckBoxes[ShareMetric.DISTANCE] =
            activity.findViewById(R.id.cbPhotoDistance)
        metricCheckBoxes[ShareMetric.AVERAGE_PACE] =
            activity.findViewById(R.id.cbPhotoAveragePace)
        metricCheckBoxes[ShareMetric.AVERAGE_SPEED] =
            activity.findViewById(R.id.cbPhotoAverageSpeed)
        metricCheckBoxes[ShareMetric.AVERAGE_HEART_RATE] =
            activity.findViewById(R.id.cbPhotoAverageHeartRate)
        metricCheckBoxes[ShareMetric.AVERAGE_POWER] =
            activity.findViewById(R.id.cbPhotoAveragePower)
        metricCheckBoxes[ShareMetric.TOTAL_ASCENT] =
            activity.findViewById(R.id.cbPhotoTotalAscent)

        ivPhotoPreview.maxHeight = dp(480)
        photoPreviewCard.visibility = View.VISIBLE

        btnPickPhotoTrack.setOnClickListener {
            try {
                openTrack.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.error_read, Toast.LENGTH_LONG).show()
            }
        }
        btnPickPhoto.setOnClickListener {
            try {
                openPhoto.launch(arrayOf("image/*"))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.photo_load_error, Toast.LENGTH_LONG).show()
            }
        }
        btnPhotoSave.setOnClickListener { requestSave() }
        btnPhotoShare.setOnClickListener { share() }

        for ((metric, checkBox) in metricCheckBoxes) {
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (updatingMetrics) return@setOnCheckedChangeListener
                if (isChecked) {
                    selectedMetrics.add(metric)
                } else {
                    selectedMetrics.remove(metric)
                }
                updatePreviewPlaceholder()
                requestRender()
            }
        }

        runActivityIcon = decodeActivityIcon(R.drawable.ic_activity_run)
        bikeActivityIcon = decodeActivityIcon(R.drawable.ic_activity_bike)
        logoBitmap = decodeActivityIcon(R.drawable.kr_logo)
        cbPhotoLogo.isChecked = preferences.getBoolean(PREF_SHOW_LOGO, true)
        cbPhotoLogo.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(PREF_SHOW_LOGO, isChecked).apply()
            requestRender()
        }

        activityType = preferences.getString(PREF_ACTIVITY_TYPE, null)
            ?.let { saved -> ActivityType.values().firstOrNull { it.name == saved } }
            ?: ActivityType.RUN
        customActivityText =
            preferences.getString(PREF_ACTIVITY_CUSTOM_TEXT, "").orEmpty()
        etPhotoActivityCustom.setText(customActivityText)
        activityGroup.check(activityButtonId(activityType))

        activityGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newType = when (checkedId) {
                R.id.btnActivityBike -> ActivityType.BIKE
                R.id.btnActivityCustom -> ActivityType.CUSTOM
                else -> ActivityType.RUN
            }
            if (newType == activityType) return@addOnButtonCheckedListener
            activityType = newType
            preferences.edit().putString(PREF_ACTIVITY_TYPE, newType.name).apply()
            customTextHandler.removeCallbacks(customTextRender)
            updateActivityUi()
            requestRender()
        }

        etPhotoActivityCustom.doAfterTextChanged { editable ->
            val text = editable?.toString().orEmpty()
            if (text == customActivityText) return@doAfterTextChanged
            customActivityText = text
            preferences.edit().putString(PREF_ACTIVITY_CUSTOM_TEXT, text).apply()
            if (activityType == ActivityType.CUSTOM) {
                scheduleCustomTextRender()
            }
        }

        updateActivityUi()
        updateTrackUi()
        updatePhotoUi()
        updateMetricsUi()
        updatePreviewPlaceholder()
        updateActionButtons()
    }

    fun saveState(outState: Bundle) {
        outState.putString(KEY_STATE_ACTIVITY_TYPE, activityType.name)
        outState.putString(KEY_STATE_ACTIVITY_TEXT, customActivityText)
        trackSelection?.let { outState.putString(KEY_TRACK_URI, it.uri.toString()) }
        photoUri?.let { outState.putString(KEY_PHOTO_URI, it.toString()) }
        val metricsToSave = pendingSelectedMetrics ?: selectedMetrics
        if (trackSelection != null || pendingSelectedMetrics != null) {
            outState.putStringArrayList(
                KEY_METRICS,
                ArrayList(metricsToSave.map { it.name })
            )
        }
    }

    fun restore(savedInstanceState: Bundle) {
        savedInstanceState.getString(KEY_STATE_ACTIVITY_TYPE)?.let { saved ->
            ActivityType.values().firstOrNull { it.name == saved }?.let {
                activityType = it
            }
        }
        savedInstanceState.getString(KEY_STATE_ACTIVITY_TEXT)?.let { text ->
            customActivityText = text
            etPhotoActivityCustom.setText(text)
        }
        activityGroup.check(activityButtonId(activityType))
        updateActivityUi()

        val savedMetrics = savedInstanceState.getStringArrayList(KEY_METRICS)
        if (savedMetrics != null) {
            pendingSelectedMetrics = savedMetrics.mapNotNull { name ->
                runCatching { ShareMetric.valueOf(name) }.getOrNull()
            }.toSet()
        }
        savedInstanceState.getString(KEY_PHOTO_URI)?.let { loadPhoto(Uri.parse(it)) }
        savedInstanceState.getString(KEY_TRACK_URI)?.let { loadTrack(Uri.parse(it)) }
    }

    private fun loadTrack(uri: Uri) {
        if (loadingTrack) return
        val token = ++trackLoadToken
        val name = displayName(uri)
        loadingTrack = true
        tvPhotoTrackName.text = name
        tvPhotoTrackInfo.setText(R.string.loading_file)
        tvPhotoTrackInfo.visibility = View.VISIBLE
        clearPreview()
        tvPhotoPreviewPlaceholder.setText(R.string.loading_file)
        tvPhotoPreviewPlaceholder.visibility = View.VISIBLE
        updateActionButtons()

        executor.execute {
            try {
                val file = FixEngine.parseTrackFile(readBytes(uri), name)
                val stats = ActivityStatsCalculator.calculate(file)
                activity.runOnUiThread {
                    if (token != trackLoadToken || activity.isFinishing ||
                        activity.isDestroyed
                    ) {
                        return@runOnUiThread
                    }
                    val firstTrack = trackSelection == null
                    trackSelection = TrackSelection(uri, name, stats)
                    loadingTrack = false
                    applyMetricSelection(stats, firstTrack)
                    updateTrackUi()
                    updateMetricsUi()
                    updateActionButtons()
                    requestRender()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot read photo-mode track", e)
                activity.runOnUiThread {
                    if (token != trackLoadToken || activity.isFinishing ||
                        activity.isDestroyed
                    ) {
                        return@runOnUiThread
                    }
                    loadingTrack = false
                    updateTrackUi()
                    updateMetricsUi()
                    updateActionButtons()
                    requestRender()
                    Toast.makeText(
                        activity,
                        photoTrackErrorMessage(e),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun loadPhoto(uri: Uri) {
        if (loadingPhoto) return
        val token = ++photoLoadToken
        loadingPhoto = true
        photoPreviewProgress.visibility = View.VISIBLE
        if (previewBitmap == null) {
            tvPhotoPreviewPlaceholder.setText(R.string.photo_loading)
            tvPhotoPreviewPlaceholder.visibility = View.VISIBLE
        }
        updateActionButtons()

        executor.execute {
            try {
                val bitmap = PhotoLoader.load(activity, uri, PREVIEW_MAX_DIMENSION)
                activity.runOnUiThread {
                    if (token != photoLoadToken || activity.isFinishing ||
                        activity.isDestroyed
                    ) {
                        bitmap.recycle()
                        return@runOnUiThread
                    }
                    loadingPhoto = false
                    photoUri = uri
                    photoBitmap = bitmap
                    clearPreview()
                    updatePhotoUi()
                    updateActionButtons()
                    requestRender()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot load photo from $uri", e)
                activity.runOnUiThread {
                    if (token != photoLoadToken || activity.isFinishing ||
                        activity.isDestroyed
                    ) {
                        return@runOnUiThread
                    }
                    loadingPhoto = false
                    updatePhotoUi()
                    updateActionButtons()
                    updatePreviewPlaceholder()
                    photoPreviewProgress.visibility = View.GONE
                    Toast.makeText(
                        activity,
                        R.string.photo_load_error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun applyMetricSelection(stats: ActivityStats, firstTrack: Boolean) {
        val available = stats.availableMetrics().toSet()
        val desired = when {
            pendingSelectedMetrics != null -> pendingSelectedMetrics!!
            firstTrack -> defaultMetrics(available)
            else -> selectedMetrics.toSet()
        }
        pendingSelectedMetrics = null
        selectedMetrics.clear()
        selectedMetrics.addAll(desired.filter { it in available })
    }

    private fun defaultMetrics(available: Set<ShareMetric>): List<ShareMetric> {
        val preferred = listOf(ShareMetric.MOVING_TIME, ShareMetric.DISTANCE)
            .filter { it in available }
        return if (preferred.isNotEmpty()) preferred else available.take(2)
    }

    private fun updateTrackUi() {
        val selection = trackSelection
        if (selection == null) {
            tvPhotoTrackName.setText(R.string.file_not_selected)
            tvPhotoTrackInfo.visibility = View.GONE
            btnPickPhotoTrack.setText(R.string.choose_gpx)
            return
        }

        tvPhotoTrackName.text = selection.name
        val movingTime = selection.stats.movingTimeSeconds?.let { Geo.formatDuration(it) }
        tvPhotoTrackInfo.text = if (movingTime != null) {
            activity.getString(
                R.string.photo_track_info,
                selection.stats.pointCount,
                movingTime,
                Geo.formatDistance(selection.stats.distanceMeters)
            )
        } else {
            activity.getString(
                R.string.photo_track_info_no_time,
                selection.stats.pointCount,
                Geo.formatDistance(selection.stats.distanceMeters)
            )
        }
        tvPhotoTrackInfo.visibility = View.VISIBLE
        btnPickPhotoTrack.setText(R.string.change_gpx)
    }

    private fun updatePhotoUi() {
        btnPickPhoto.setText(
            if (photoUri != null) R.string.photo_change_photo else R.string.photo_choose_photo
        )
    }

    private fun updateMetricsUi() {
        val stats = trackSelection?.stats
        updatingMetrics = true
        var hasUnavailable = false
        for ((metric, checkBox) in metricCheckBoxes) {
            val supported = stats?.supports(metric) == true
            if (!supported) hasUnavailable = true
            checkBox.isEnabled = supported
            checkBox.isChecked = supported && metric in selectedMetrics
        }
        updatingMetrics = false
        tvPhotoMetricsNote.visibility =
            if (stats != null && hasUnavailable) View.VISIBLE else View.GONE
    }

    private fun updateActivityUi() {
        tilPhotoActivityCustom.visibility =
            if (activityType == ActivityType.CUSTOM) View.VISIBLE else View.GONE
    }

    private fun activityButtonId(type: ActivityType): Int {
        return when (type) {
            ActivityType.RUN -> R.id.btnActivityRun
            ActivityType.BIKE -> R.id.btnActivityBike
            ActivityType.CUSTOM -> R.id.btnActivityCustom
        }
    }

    private fun currentActivityOverlay(): PhotoActivityOverlay? {
        return when (activityType) {
            ActivityType.RUN ->
                runActivityIcon?.let { PhotoActivityOverlay(icon = it) }
            ActivityType.BIKE ->
                bikeActivityIcon?.let { PhotoActivityOverlay(icon = it) }
            ActivityType.CUSTOM -> customActivityText.trim()
                .takeIf { it.isNotEmpty() }
                ?.let { PhotoActivityOverlay(text = it) }
        }
    }

    private fun decodeActivityIcon(resId: Int): Bitmap? {
        return runCatching {
            BitmapFactory.decodeResource(
                activity.resources,
                resId,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
            )
        }.onFailure {
            Log.e(TAG, "Cannot decode activity icon", it)
        }.getOrNull()
    }

    private fun scheduleCustomTextRender() {
        customTextHandler.removeCallbacks(customTextRender)
        customTextHandler.postDelayed(customTextRender, CUSTOM_TEXT_DEBOUNCE_MS)
    }

    fun destroy() {
        customTextHandler.removeCallbacks(customTextRender)
    }

    private fun updatePreviewPlaceholder() {
        if (previewBitmap != null) {
            tvPhotoPreviewPlaceholder.visibility = View.GONE
            return
        }
        tvPhotoPreviewPlaceholder.visibility = View.VISIBLE
        tvPhotoPreviewPlaceholder.setText(
            when {
                photoBitmap == null -> R.string.photo_preview_placeholder
                trackSelection == null -> R.string.photo_no_track
                selectedMetrics.isEmpty() -> R.string.photo_no_metrics
                rendering -> R.string.photo_rendering
                else -> R.string.photo_preview_placeholder
            }
        )
    }

    private fun requestRender() {
        val token = ++renderToken
        val photo = photoBitmap
        val stats = trackSelection?.stats
        val lines = if (stats != null) metricLines(stats) else emptyList()

        if (photo == null || stats == null || lines.isEmpty()) {
            rendering = false
            photoPreviewProgress.visibility = View.GONE
            clearPreview()
            updateActionButtons()
            return
        }

        rendering = true
        photoPreviewProgress.visibility = View.VISIBLE
        if (previewBitmap == null) {
            tvPhotoPreviewPlaceholder.visibility = View.VISIBLE
            tvPhotoPreviewPlaceholder.setText(R.string.photo_rendering)
        }
        updateActionButtons()

        val subtitle = stats.startTime?.let { formatDate(it) }
        val title = activity.getString(R.string.photo_overlay_title)
        val activityOverlay = currentActivityOverlay()
        val logo = if (cbPhotoLogo.isChecked) logoBitmap else null

        executor.execute {
            try {
                val rendered =
                    PhotoCardRenderer.render(
                        photo,
                        lines,
                        title,
                        subtitle,
                        activityOverlay,
                        logo
                    )
                activity.runOnUiThread {
                    if (token != renderToken || activity.isFinishing ||
                        activity.isDestroyed
                    ) {
                        rendered.recycle()
                        return@runOnUiThread
                    }
                    showPreview(rendered)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot render photo card", e)
                activity.runOnUiThread {
                    if (token != renderToken || activity.isFinishing ||
                        activity.isDestroyed
                    ) {
                        return@runOnUiThread
                    }
                    rendering = false
                    photoPreviewProgress.visibility = View.GONE
                    updatePreviewPlaceholder()
                    updateActionButtons()
                    Toast.makeText(
                        activity,
                        R.string.photo_render_error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showPreview(bitmap: Bitmap) {
        previewBitmap = bitmap
        ivPhotoPreview.setImageBitmap(bitmap)
        ivPhotoPreview.visibility = View.VISIBLE
        tvPhotoPreviewPlaceholder.visibility = View.GONE
        photoPreviewProgress.visibility = View.GONE
        rendering = false
        updateActionButtons()
    }

    private fun clearPreview() {
        renderToken++
        rendering = false
        previewBitmap = null
        ivPhotoPreview.setImageBitmap(null)
        ivPhotoPreview.visibility = View.GONE
        photoPreviewProgress.visibility = View.GONE
        updatePreviewPlaceholder()
        updateActionButtons()
    }

    private fun metricLines(stats: ActivityStats): List<PhotoMetricLine> {
        return ShareMetric.values()
            .filter { it in selectedMetrics && stats.supports(it) }
            .mapNotNull { metric ->
                val value = metricValue(metric, stats) ?: return@mapNotNull null
                PhotoMetricLine(activity.getString(shortLabel(metric)), value)
            }
    }

    private fun metricValue(metric: ShareMetric, stats: ActivityStats): String? {
        return when (metric) {
            ShareMetric.MOVING_TIME -> stats.movingTimeSeconds?.let {
                Geo.formatDuration(it)
            }
            ShareMetric.DISTANCE -> Geo.formatDistance(stats.distanceMeters)
            ShareMetric.AVERAGE_PACE -> stats.averagePaceSecondsPerKm?.let {
                String.format(Locale.US, "%d:%02d /км", it / 60, it % 60)
            }
            ShareMetric.AVERAGE_SPEED -> stats.averageSpeedKmh?.let {
                String.format(Locale("ru", "RU"), "%.1f км/ч", it)
            }
            ShareMetric.AVERAGE_HEART_RATE -> stats.averageHeartRate?.let {
                String.format(Locale("ru", "RU"), "%.0f уд/мин", it)
            }
            ShareMetric.AVERAGE_POWER -> stats.averagePowerWatts?.let {
                String.format(Locale("ru", "RU"), "%.0f Вт", it)
            }
            ShareMetric.TOTAL_ASCENT -> stats.totalAscentMeters?.let {
                String.format(Locale("ru", "RU"), "%.0f м", it)
            }
        }
    }

    private fun shortLabel(metric: ShareMetric): Int {
        return when (metric) {
            ShareMetric.MOVING_TIME -> R.string.photo_metric_moving_time_short
            ShareMetric.DISTANCE -> R.string.photo_metric_distance_short
            ShareMetric.AVERAGE_PACE -> R.string.photo_metric_average_pace_short
            ShareMetric.AVERAGE_SPEED -> R.string.photo_metric_average_speed_short
            ShareMetric.AVERAGE_HEART_RATE ->
                R.string.photo_metric_average_heart_rate_short
            ShareMetric.AVERAGE_POWER -> R.string.photo_metric_average_power_short
            ShareMetric.TOTAL_ASCENT -> R.string.photo_metric_total_ascent_short
        }
    }

    private fun formatDate(time: Instant): String {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.forLanguageTag("ru-RU"))
            .withZone(ZoneId.systemDefault())
            .format(time)
    }

    private fun requestSave() {
        if (previewBitmap == null || saving) return
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        try {
            createDocument.launch(activity.getString(R.string.photo_file_name, timestamp))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.photo_save_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveTo(uri: Uri) {
        val bitmap = previewBitmap ?: return
        if (saving) return
        saving = true
        updateActionButtons()

        executor.execute {
            try {
                val output = activity.contentResolver.openOutputStream(uri)
                    ?: throw IOException("cannot open output stream")
                output.use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IOException("cannot encode PNG")
                    }
                    stream.flush()
                }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    saving = false
                    updateActionButtons()
                    Toast.makeText(activity, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot save photo card", e)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    saving = false
                    updateActionButtons()
                    Toast.makeText(
                        activity,
                        R.string.photo_save_error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun share() {
        val bitmap = previewBitmap ?: return
        if (sharing) return
        sharing = true
        updateActionButtons()

        executor.execute {
            try {
                val directory = File(activity.cacheDir, "shared")
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("cannot create cache directory")
                }
                val file = File(directory, "trackfix_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("cannot encode PNG")
                    }
                    output.flush()
                }
                val uri = FileProvider.getUriForFile(
                    activity,
                    activity.packageName + ".fileprovider",
                    file
                )
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    sharing = false
                    updateActionButtons()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.photo_share_text))
                        clipData = ClipData.newRawUri("trackfix", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        activity.startActivity(
                            Intent.createChooser(
                                send,
                                activity.getString(R.string.photo_share_chooser)
                            )
                        )
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(
                            activity,
                            R.string.photo_share_error,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot share photo card", e)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    sharing = false
                    updateActionButtons()
                    Toast.makeText(
                        activity,
                        R.string.photo_share_error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateActionButtons() {
        val pickEnabled = !loadingTrack && !loadingPhoto && !saving && !sharing
        btnPickPhotoTrack.isEnabled = pickEnabled
        btnPickPhoto.isEnabled = pickEnabled
        val ready = previewBitmap != null && !rendering && !loadingTrack &&
            !loadingPhoto && !saving && !sharing
        btnPhotoSave.isEnabled = ready
        btnPhotoShare.isEnabled = ready
    }

    private fun readBytes(uri: Uri): ByteArray {
        val input = activity.contentResolver.openInputStream(uri)
            ?: throw GpxException("cannot open file")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > GpxCodec.MAX_FILE_BYTES) {
                    throw GpxException("file too large")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        runCatching {
            activity.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index)
                }
            }
        }
        return name
            ?: uri.lastPathSegment?.substringAfterLast('/')?.let { Uri.decode(it) }
            ?: "track.gpx"
    }

    private fun rememberReadPermission(uri: Uri) {
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun photoTrackErrorMessage(e: Exception): String {
        return when {
            e.message?.contains("too large", ignoreCase = true) == true ->
                activity.getString(R.string.file_too_large_hint)
            e is GpxException -> activity.getString(R.string.photo_track_load_error)
            else -> activity.getString(
                R.string.error_generic,
                e.message ?: activity.getString(R.string.photo_track_load_error)
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).roundToInt()
    }

    companion object {
        private const val TAG = "TrackFix"
        private const val KEY_TRACK_URI = "photo_track_uri"
        private const val KEY_PHOTO_URI = "photo_image_uri"
        private const val KEY_METRICS = "photo_metrics"
        private const val KEY_STATE_ACTIVITY_TYPE = "photo_activity_type_state"
        private const val KEY_STATE_ACTIVITY_TEXT = "photo_activity_text_state"
        private const val PREFERENCES_NAME = "photo_preferences"
        private const val PREF_ACTIVITY_TYPE = "photo_activity_type"
        private const val PREF_ACTIVITY_CUSTOM_TEXT = "photo_activity_custom_text"
        private const val PREF_SHOW_LOGO = "photo_show_logo"
        private const val CUSTOM_TEXT_DEBOUNCE_MS = 350L
        private const val PREVIEW_MAX_DIMENSION = 1600
    }
}
