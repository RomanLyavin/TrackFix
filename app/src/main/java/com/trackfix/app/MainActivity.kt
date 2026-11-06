package com.trackfix.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.trackfix.app.core.FixEngine
import com.trackfix.app.core.FixSettings
import com.trackfix.app.core.FixWarning
import com.trackfix.app.core.Geo
import com.trackfix.app.core.GpxCodec
import com.trackfix.app.core.GpxException
import com.trackfix.app.core.MergeEngine
import com.trackfix.app.core.MergeException
import com.trackfix.app.core.MergeFileInfo
import com.trackfix.app.core.MergeItem
import com.trackfix.app.core.MergeResult
import com.trackfix.app.core.ProcessResult
import com.trackfix.app.core.ReferenceInfo
import com.trackfix.app.core.TrackInfo
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Mode { FIX, MERGE, PHOTO }

    private data class OriginalSelection(val uri: Uri, val name: String, val info: TrackInfo)
    private data class ReferenceSelection(val uri: Uri, val name: String, val info: ReferenceInfo)
    private data class MergeSelection(val uri: Uri, val name: String, val info: MergeFileInfo)

    private val executor = Executors.newSingleThreadExecutor()
    private var mode = Mode.FIX
    private var originalSelection: OriginalSelection? = null
    private var referenceSelection: ReferenceSelection? = null
    private val mergeSelections = mutableListOf<MergeSelection>()
    private var lastResult: ProcessResult? = null
    private var lastMergeResult: MergeResult? = null
    private var processing = false
    private var saving = false
    private var originalLoadToken = 0
    private var referenceLoadToken = 0
    private var mergeLoadToken = 0

    private val mergeTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru-RU"))
            .withZone(ZoneId.systemDefault())

    private lateinit var scrollView: android.widget.ScrollView
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var btnModeFix: MaterialButton
    private lateinit var btnModeMerge: MaterialButton
    private lateinit var btnModePhoto: MaterialButton
    private lateinit var fixContent: View
    private lateinit var mergeContent: View
    private lateinit var photoContent: View
    private lateinit var photoShareController: PhotoShareController
    private lateinit var tvOriginalName: TextView
    private lateinit var tvOriginalInfo: TextView
    private lateinit var tvReferenceName: TextView
    private lateinit var tvReferenceInfo: TextView
    private lateinit var btnPickOriginal: MaterialButton
    private lateinit var btnPickReference: MaterialButton
    private lateinit var btnFix: MaterialButton
    private lateinit var sliderDeviation: Slider
    private lateinit var tvDeviationValue: TextView
    private lateinit var cbFillElevation: MaterialCheckBox
    private lateinit var mergeFileList: LinearLayout
    private lateinit var tvMergeEmpty: TextView
    private lateinit var tvMergeSummary: TextView
    private lateinit var btnAddMergeFiles: MaterialButton
    private lateinit var btnClearMergeFiles: MaterialButton
    private lateinit var btnMerge: MaterialButton
    private lateinit var progressRow: LinearLayout
    private lateinit var tvProgressText: TextView
    private lateinit var resultCard: MaterialCardView
    private lateinit var tvResultSummary: TextView
    private lateinit var tvWarning: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnAnother: MaterialButton
    private lateinit var btnAbout: MaterialButton
    private lateinit var btnSupport: MaterialButton

    private val openOriginal = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            rememberReadPermission(uri)
            loadOriginal(uri)
        }
    }

    private val openReference = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            rememberReadPermission(uri)
            loadReference(uri)
        }
    }

    private val openMergeFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) addMergeUris(uris)
        }

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        if (uri != null) saveResult(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scrollView = findViewById(R.id.scrollView)
        modeGroup = findViewById(R.id.modeGroup)
        btnModeFix = findViewById(R.id.btnModeFix)
        btnModeMerge = findViewById(R.id.btnModeMerge)
        btnModePhoto = findViewById(R.id.btnModePhoto)
        fixContent = findViewById(R.id.fixContent)
        mergeContent = findViewById(R.id.mergeContent)
        photoContent = findViewById(R.id.photoContent)
        tvOriginalName = findViewById(R.id.tvOriginalName)
        tvOriginalInfo = findViewById(R.id.tvOriginalInfo)
        tvReferenceName = findViewById(R.id.tvReferenceName)
        tvReferenceInfo = findViewById(R.id.tvReferenceInfo)
        btnPickOriginal = findViewById(R.id.btnPickOriginal)
        btnPickReference = findViewById(R.id.btnPickReference)
        btnFix = findViewById(R.id.btnFix)
        sliderDeviation = findViewById(R.id.sliderDeviation)
        tvDeviationValue = findViewById(R.id.tvDeviationValue)
        cbFillElevation = findViewById(R.id.cbFillElevation)
        mergeFileList = findViewById(R.id.mergeFileList)
        tvMergeEmpty = findViewById(R.id.tvMergeEmpty)
        tvMergeSummary = findViewById(R.id.tvMergeSummary)
        btnAddMergeFiles = findViewById(R.id.btnAddMergeFiles)
        btnClearMergeFiles = findViewById(R.id.btnClearMergeFiles)
        btnMerge = findViewById(R.id.btnMerge)
        progressRow = findViewById(R.id.progressRow)
        tvProgressText = findViewById(R.id.tvProgressText)
        resultCard = findViewById(R.id.resultCard)
        tvResultSummary = findViewById(R.id.tvResultSummary)
        tvWarning = findViewById(R.id.tvWarning)
        btnSave = findViewById(R.id.btnSave)
        btnAnother = findViewById(R.id.btnAnother)
        btnAbout = findViewById(R.id.btnAbout)
        btnSupport = findViewById(R.id.btnSupport)

        photoShareController = PhotoShareController(this, executor)
        photoShareController.bind()

        sliderDeviation.addOnChangeListener { _, value, fromUser ->
            updateDeviationLabel(value)
            if (fromUser) clearResult()
        }
        updateDeviationLabel(sliderDeviation.value)
        cbFillElevation.setOnCheckedChangeListener { _, _ -> clearResult() }

        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = when (checkedId) {
                R.id.btnModeMerge -> Mode.MERGE
                R.id.btnModePhoto -> Mode.PHOTO
                else -> Mode.FIX
            }
            if (newMode == mode) return@addOnButtonCheckedListener
            mode = newMode
            clearResult()
            updateModeUi()
            updateActionButtons()
        }

        btnPickOriginal.setOnClickListener {
            try {
                openOriginal.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.error_read, Toast.LENGTH_LONG).show()
            }
        }
        btnPickReference.setOnClickListener {
            try {
                openReference.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.error_read, Toast.LENGTH_LONG).show()
            }
        }
        btnAddMergeFiles.setOnClickListener {
            try {
                openMergeFiles.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.error_read, Toast.LENGTH_LONG).show()
            }
        }
        btnClearMergeFiles.setOnClickListener { clearMergeFiles() }
        btnFix.setOnClickListener { startFix() }
        btnMerge.setOnClickListener { startMerge() }
        btnSave.setOnClickListener { requestSave() }
        btnAnother.setOnClickListener { chooseAnother() }
        btnAbout.setOnClickListener { showAbout() }
        btnSupport.setOnClickListener { showSupport() }

        if (savedInstanceState != null) {
            val deviation = savedInstanceState.getFloat(KEY_DEVIATION, 30f)
            sliderDeviation.value = deviation.coerceIn(10f, 100f)
            cbFillElevation.isChecked = savedInstanceState.getBoolean(KEY_FILL_ELEVATION, true)
            mode = savedInstanceState.getInt(KEY_MODE, Mode.FIX.ordinal).let { ordinal ->
                Mode.values().getOrNull(ordinal) ?: Mode.FIX
            }
        }

        modeGroup.check(
            when (mode) {
                Mode.MERGE -> R.id.btnModeMerge
                Mode.PHOTO -> R.id.btnModePhoto
                Mode.FIX -> R.id.btnModeFix
            }
        )
        updateDeviationLabel(sliderDeviation.value)
        updateModeUi()
        updateFileUi()
        renderMergeFiles()

        if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_ORIGINAL_URI)?.let { loadOriginal(Uri.parse(it)) }
            savedInstanceState.getString(KEY_REFERENCE_URI)?.let { loadReference(Uri.parse(it)) }
            savedInstanceState.getStringArrayList(KEY_MERGE_URIS)?.let { saved ->
                loadMergeUris(saved.map { Uri.parse(it) }, replace = true)
            }
            photoShareController.restore(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_MODE, mode.ordinal)
        outState.putFloat(KEY_DEVIATION, sliderDeviation.value)
        outState.putBoolean(KEY_FILL_ELEVATION, cbFillElevation.isChecked)
        originalSelection?.let { outState.putString(KEY_ORIGINAL_URI, it.uri.toString()) }
        referenceSelection?.let { outState.putString(KEY_REFERENCE_URI, it.uri.toString()) }
        outState.putStringArrayList(
            KEY_MERGE_URIS,
            ArrayList(mergeSelections.map { it.uri.toString() })
        )
        photoShareController.saveState(outState)
    }

    override fun onDestroy() {
        photoShareController.destroy()
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun updateDeviationLabel(value: Float) {
        tvDeviationValue.text = getString(R.string.deviation_value, value.roundToInt())
    }

    private fun clearResult() {
        if (lastResult == null && lastMergeResult == null) return
        lastResult = null
        lastMergeResult = null
        resultCard.visibility = View.GONE
        tvWarning.visibility = View.GONE
        btnSave.isEnabled = false
    }

    private fun updateModeUi() {
        fixContent.visibility = if (mode == Mode.FIX) View.VISIBLE else View.GONE
        mergeContent.visibility = if (mode == Mode.MERGE) View.VISIBLE else View.GONE
        photoContent.visibility = if (mode == Mode.PHOTO) View.VISIBLE else View.GONE
        btnAnother.setText(
            if (mode == Mode.FIX) R.string.process_another else R.string.process_another_merge
        )
    }

    private fun updateFileUi() {
        val original = originalSelection
        if (original == null) {
            tvOriginalName.setText(R.string.file_not_selected)
            tvOriginalInfo.visibility = View.GONE
            btnPickOriginal.setText(R.string.choose_gpx)
        } else {
            tvOriginalName.text = original.name
            val parts = mutableListOf<String>()
            parts.add(
                resources.getQuantityString(
                    R.plurals.points_count,
                    original.info.points,
                    original.info.points
                )
            )
            original.info.durationSeconds?.let { parts.add(Geo.formatDuration(it)) }
            parts.add(Geo.formatDistance(original.info.distanceMeters))
            tvOriginalInfo.text = parts.joinToString(" · ")
            tvOriginalInfo.visibility = View.VISIBLE
            btnPickOriginal.setText(R.string.change_gpx)
        }

        val reference = referenceSelection
        if (reference == null) {
            tvReferenceName.setText(R.string.file_not_selected)
            tvReferenceInfo.visibility = View.GONE
            btnPickReference.setText(R.string.choose_gpx)
        } else {
            tvReferenceName.text = reference.name
            val parts = mutableListOf<String>()
            parts.add(
                resources.getQuantityString(
                    R.plurals.points_count,
                    reference.info.points,
                    reference.info.points
                )
            )
            parts.add(Geo.formatDistance(reference.info.distanceMeters))
            tvReferenceInfo.text = parts.joinToString(" · ")
            tvReferenceInfo.visibility = View.VISIBLE
            btnPickReference.setText(R.string.change_gpx)
        }
        updateActionButtons()
    }

    private fun updateActionButtons() {
        val hasOutput = lastResult != null || lastMergeResult != null
        btnFix.isEnabled = !processing && originalSelection != null && referenceSelection != null
        btnMerge.isEnabled = !processing && mergeSelections.size >= 2
        btnPickOriginal.isEnabled = !processing
        btnPickReference.isEnabled = !processing
        btnAddMergeFiles.isEnabled = !processing
        btnClearMergeFiles.isEnabled = !processing && mergeSelections.isNotEmpty()
        btnSave.isEnabled = !processing && !saving && hasOutput
        btnAnother.isEnabled = !processing && !saving
        btnModeFix.isEnabled = !processing
        btnModeMerge.isEnabled = !processing
        btnModePhoto.isEnabled = !processing
        if (!processing) {
            btnFix.setText(R.string.fix_button)
            btnMerge.setText(R.string.merge_button)
        }
    }

    private fun loadOriginal(uri: Uri) {
        if (processing) return
        clearResult()
        val token = ++originalLoadToken
        val name = displayName(uri)
        originalSelection = null
        tvOriginalName.text = name
        tvOriginalInfo.setText(R.string.loading_file)
        tvOriginalInfo.visibility = View.VISIBLE
        updateActionButtons()

        executor.execute {
            try {
                val info = FixEngine.inspectOriginal(readBytes(uri))
                runOnUiThread {
                    if (token != originalLoadToken || isFinishing || isDestroyed) return@runOnUiThread
                    originalSelection = OriginalSelection(uri, name, info)
                    updateFileUi()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot read original track", e)
                runOnUiThread {
                    if (token != originalLoadToken || isFinishing || isDestroyed) return@runOnUiThread
                    originalSelection = null
                    updateFileUi()
                    showLoadError(e)
                }
            }
        }
    }

    private fun loadReference(uri: Uri) {
        if (processing) return
        clearResult()
        val token = ++referenceLoadToken
        val name = displayName(uri)
        referenceSelection = null
        tvReferenceName.text = name
        tvReferenceInfo.setText(R.string.loading_file)
        tvReferenceInfo.visibility = View.VISIBLE
        updateActionButtons()

        executor.execute {
            try {
                val info = FixEngine.inspectReference(readBytes(uri))
                runOnUiThread {
                    if (token != referenceLoadToken || isFinishing || isDestroyed) return@runOnUiThread
                    referenceSelection = ReferenceSelection(uri, name, info)
                    updateFileUi()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot read reference track", e)
                runOnUiThread {
                    if (token != referenceLoadToken || isFinishing || isDestroyed) return@runOnUiThread
                    referenceSelection = null
                    updateFileUi()
                    showLoadError(e)
                }
            }
        }
    }

    private fun addMergeUris(uris: List<Uri>) {
        if (processing) return
        val existing = mergeSelections.map { it.uri.toString() }.toSet()
        val newUris = uris.filter { it.toString() !in existing }
        if (newUris.isEmpty()) return
        newUris.forEach { rememberReadPermission(it) }
        loadMergeUris(newUris, replace = false)
    }

    private fun loadMergeUris(uris: List<Uri>, replace: Boolean) {
        if (uris.isEmpty() || processing) return
        clearResult()
        val token = ++mergeLoadToken
        val snapshot = uris.toList()
        tvMergeSummary.setText(R.string.loading_files)
        tvMergeSummary.visibility = View.VISIBLE
        setProcessing(true)
        tvProgressText.setText(R.string.loading_files)

        executor.execute {
            val loaded = mutableListOf<MergeSelection>()
            var failure: Pair<String, Exception>? = null
            for (uri in snapshot) {
                val name = displayName(uri)
                try {
                    val info = MergeEngine.inspect(readBytes(uri))
                    loaded.add(MergeSelection(uri, name, info))
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot read merge track $name", e)
                    if (failure == null) failure = name to e
                }
            }
            runOnUiThread {
                if (token != mergeLoadToken || isFinishing || isDestroyed) return@runOnUiThread
                if (replace) mergeSelections.clear()
                for (selection in loaded) {
                    if (mergeSelections.none { it.uri == selection.uri }) {
                        mergeSelections.add(selection)
                    }
                }
                renderMergeFiles()
                setProcessing(false)
                if (failure != null) {
                    showMergeLoadError(failure.first, failure.second)
                }
            }
        }
    }

    private fun renderMergeFiles() {
        mergeFileList.removeAllViews()
        for (selection in mergeSelections) {
            val row = layoutInflater.inflate(R.layout.item_merge_file, mergeFileList, false)
            row.findViewById<TextView>(R.id.tvMergeItemName).text = selection.name
            row.findViewById<TextView>(R.id.tvMergeItemInfo).text =
                mergeFileInfoText(selection.info)
            row.findViewById<View>(R.id.btnMergeItemRemove).setOnClickListener {
                removeMergeSelection(selection.uri)
            }
            mergeFileList.addView(row)
        }

        val empty = mergeSelections.isEmpty()
        tvMergeEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        btnClearMergeFiles.visibility = if (empty) View.GONE else View.VISIBLE
        tvMergeSummary.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) {
            tvMergeSummary.text = getString(
                R.string.merge_count,
                mergeSelections.size,
                mergeSelections.sumOf { it.info.points }
            )
        }
        updateActionButtons()
    }

    private fun mergeFileInfoText(info: MergeFileInfo): String {
        val points = resources.getQuantityString(
            R.plurals.points_count,
            info.points,
            info.points
        )
        val period = "${mergeTimeFormatter.format(info.startTime)} — " +
            mergeTimeFormatter.format(info.endTime)
        return getString(
            R.string.merge_file_info,
            points,
            period,
            Geo.formatDistance(info.distanceMeters)
        )
    }

    private fun removeMergeSelection(uri: Uri) {
        if (processing) return
        clearResult()
        mergeSelections.removeAll { it.uri == uri }
        renderMergeFiles()
    }

    private fun clearMergeFiles() {
        if (processing) return
        clearResult()
        mergeSelections.clear()
        renderMergeFiles()
    }

    private fun startFix() {
        val original = originalSelection ?: return
        val reference = referenceSelection ?: return
        if (processing) return

        lastResult = null
        lastMergeResult = null
        resultCard.visibility = View.GONE
        tvWarning.visibility = View.GONE
        setProcessing(true)

        val settings = FixSettings(
            maxDeviationMeters = sliderDeviation.value.toDouble(),
            fillMissingElevation = cbFillElevation.isChecked
        )
        executor.execute {
            try {
                val originalBytes = readBytes(original.uri)
                val referenceBytes = readBytes(reference.uri)
                val result = FixEngine.process(
                    originalBytes = originalBytes,
                    originalName = original.name,
                    referenceBytes = referenceBytes,
                    settings = settings
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    lastResult = result
                    lastMergeResult = null
                    setProcessing(false)
                    showResult(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot process track", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setProcessing(false)
                    showProcessError(e)
                }
            }
        }
    }

    private fun startMerge() {
        if (processing || mergeSelections.size < 2) return
        val requested = mergeSelections.toList()
        lastResult = null
        lastMergeResult = null
        resultCard.visibility = View.GONE
        tvWarning.visibility = View.GONE
        setProcessing(true)

        executor.execute {
            try {
                val items = requested.map { MergeItem(it.name, readBytes(it.uri)) }
                val result = MergeEngine.merge(items)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    lastResult = null
                    lastMergeResult = result
                    setProcessing(false)
                    showMergeResult(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot merge tracks", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setProcessing(false)
                    Toast.makeText(
                        this,
                        mergeErrorMessage(e),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setProcessing(value: Boolean) {
        processing = value
        progressRow.visibility = if (value) View.VISIBLE else View.GONE
        tvProgressText.setText(R.string.processing)
        if (value) {
            btnFix.setText(R.string.processing)
            btnMerge.setText(R.string.processing)
        }
        updateActionButtons()
    }

    private fun showResult(result: ProcessResult) {
        val stats = result.stats
        val lines = mutableListOf<String>()
        lines.add(getString(R.string.summary_points, stats.totalPoints))
        if (stats.fixedCoordinates > 0) {
            lines.add(getString(R.string.summary_fixed, stats.fixedCoordinates))
        }
        if (stats.frozenCoordinates > 0) {
            lines.add(getString(R.string.summary_frozen, stats.frozenCoordinates, stats.frozenRuns))
        }
        if (stats.missingCoordinates > 0) {
            lines.add(getString(R.string.summary_missing, stats.missingCoordinates))
        }
        if (stats.filledElevations > 0) {
            lines.add(getString(R.string.summary_elevation, stats.filledElevations))
        }
        if (stats.problemBlocks > 0) {
            lines.add(getString(R.string.summary_runs, stats.problemBlocks))
        }
        if (stats.leadingFixed > 0) {
            lines.add(getString(R.string.summary_leading, stats.leadingFixed))
        }
        if (stats.trailingFixed > 0) {
            lines.add(getString(R.string.summary_trailing, stats.trailingFixed))
        }
        if (stats.unresolvedPoints > 0) {
            lines.add(getString(R.string.summary_unresolved, stats.unresolvedPoints))
        }
        if (stats.fixedCoordinates > 0) {
            lines.add(
                getString(
                    R.string.summary_distance,
                    Geo.formatDistance(stats.distanceBefore),
                    Geo.formatDistance(stats.distanceAfter)
                )
            )
        }
        if (stats.maxDeviationBefore > 0.0) {
            if (stats.maxDeviationCapped) {
                lines.add(getString(R.string.summary_maxdev_capped, Geo.formatDistance(300.0)))
            } else {
                lines.add(getString(R.string.summary_maxdev, Geo.formatDistance(stats.maxDeviationBefore)))
            }
        }
        if (stats.fixedCoordinates == 0 && stats.unresolvedPoints == 0) {
            lines.add(getString(R.string.summary_clean))
        }
        tvResultSummary.text = lines.joinToString("\n")

        val warningLines = mutableListOf<String>()
        if (FixWarning.LARGE_CHANGES in result.warnings) {
            warningLines.add(getString(R.string.warning_large_changes))
        }
        if (FixWarning.NO_REFERENCE_COVERAGE in result.warnings) {
            warningLines.add(getString(R.string.warning_no_reference_coverage))
        }
        if (FixWarning.REFERENCE_SEGMENTS in result.warnings) {
            warningLines.add(getString(R.string.warning_reference_segments))
        }
        if (warningLines.isNotEmpty()) {
            tvWarning.text = warningLines.joinToString("\n\n")
            tvWarning.visibility = View.VISIBLE
        } else {
            tvWarning.visibility = View.GONE
        }

        showResultCard()
    }

    private fun showMergeResult(result: MergeResult) {
        val lines = mutableListOf<String>()
        lines.add(
            getString(
                R.string.merge_summary_sources,
                result.contributedSources,
                result.inputSourceCount
            )
        )
        lines.add(getString(R.string.merge_summary_points, result.totalPoints))
        lines.add(getString(R.string.merge_summary_segments, result.segmentCount))
        if (result.trimmedPoints > 0) {
            lines.add(getString(R.string.merge_summary_trimmed, result.trimmedPoints))
        }
        result.startTime?.let { start ->
            result.endTime?.let { end ->
                lines.add(
                    getString(
                        R.string.merge_summary_period,
                        formatMergeTime(start),
                        formatMergeTime(end)
                    )
                )
            }
        }
        lines.add(getString(R.string.merge_summary_distance, Geo.formatDistance(result.distanceMeters)))
        tvResultSummary.text = lines.joinToString("\n")

        val warningLines = mutableListOf<String>()
        if (result.skippedSources.isNotEmpty()) {
            warningLines.add(
                getString(
                    R.string.merge_warning_skipped,
                    result.skippedSources.joinToString(", ")
                )
            )
        }
        if (result.contributedSources < 2) {
            warningLines.add(getString(R.string.merge_warning_single))
        }
        if (warningLines.isNotEmpty()) {
            tvWarning.text = warningLines.joinToString("\n\n")
            tvWarning.visibility = View.VISIBLE
        } else {
            tvWarning.visibility = View.GONE
        }

        showResultCard()
    }

    private fun showResultCard() {
        resultCard.visibility = View.VISIBLE
        updateActionButtons()
        scrollView.post {
            scrollView.smoothScrollTo(0, resultCard.top)
        }
    }

    private fun formatMergeTime(time: Instant): String {
        return mergeTimeFormatter.format(time)
    }

    private fun requestSave() {
        val name = lastResult?.suggestedName ?: lastMergeResult?.suggestedName ?: return
        try {
            createDocument.launch(name)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveResult(uri: Uri) {
        val bytes = lastResult?.outputBytes ?: lastMergeResult?.outputBytes ?: return
        if (saving) return
        saving = true
        btnSave.isEnabled = false
        btnSave.setText(R.string.saving)

        executor.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(bytes)
                    output.flush()
                } ?: throw GpxException("cannot open output stream")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    saving = false
                    btnSave.setText(R.string.save_gpx)
                    updateActionButtons()
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot save GPX", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    saving = false
                    btnSave.setText(R.string.save_gpx)
                    updateActionButtons()
                    Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun chooseAnother() {
        if (mode == Mode.MERGE) {
            clearMergeFiles()
            try {
                openMergeFiles.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.error_read, Toast.LENGTH_LONG).show()
            }
        } else {
            originalSelection = null
            lastResult = null
            lastMergeResult = null
            resultCard.visibility = View.GONE
            tvWarning.visibility = View.GONE
            updateFileUi()
            try {
                openOriginal.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.error_read, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLoadError(e: Exception) {
        val message = when {
            e.message?.contains("too large", ignoreCase = true) == true ->
                getString(R.string.file_too_large_hint)
            e is GpxException -> getString(R.string.error_invalid_gpx)
            else -> getString(R.string.error_read)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showMergeLoadError(name: String, e: Exception) {
        Toast.makeText(this, mergeErrorMessage(e, name), Toast.LENGTH_LONG).show()
    }

    private fun showProcessError(e: Exception) {
        val message = when {
            e.message?.contains("too large", ignoreCase = true) == true ->
                getString(R.string.file_too_large_hint)
            e is GpxException -> getString(R.string.error_invalid_gpx)
            else -> getString(R.string.error_generic, e.message ?: getString(R.string.error_read))
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun mergeErrorMessage(e: Exception, name: String? = null): String {
        return when {
            e is MergeException && e.reason == MergeException.Reason.NO_TIME -> {
                if (name != null) getString(R.string.error_merge_no_time, name)
                else getString(R.string.error_invalid_gpx)
            }
            e is MergeException && e.reason == MergeException.Reason.NO_COORDINATES -> {
                if (name != null) getString(R.string.error_merge_no_coordinates, name)
                else getString(R.string.error_invalid_gpx)
            }
            e is MergeException && e.reason == MergeException.Reason.TOO_FEW_FILES ->
                getString(R.string.error_merge_need_two)
            e is MergeException && e.reason == MergeException.Reason.EMPTY_RESULT ->
                getString(R.string.error_merge_empty)
            e.message?.contains("too large", ignoreCase = true) == true ->
                getString(R.string.file_too_large_hint)
            e is GpxException -> getString(R.string.error_invalid_gpx)
            else -> getString(R.string.error_generic, e.message ?: getString(R.string.error_read))
        }
    }

    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showSupport() {
        val view = layoutInflater.inflate(R.layout.dialog_support, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.support_title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .create()
        view.findViewById<View>(R.id.imgDonate).setOnClickListener { openSupportLink() }
        dialog.show()
    }

    private fun openSupportLink() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open support link", e)
            Toast.makeText(this, R.string.error_open_link, Toast.LENGTH_LONG).show()
        }
    }

    private fun readBytes(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
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
            contentResolver.query(
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
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    companion object {
        private const val TAG = "TrackFix"
        private const val KEY_MODE = "mode"
        private const val KEY_DEVIATION = "deviation"
        private const val KEY_FILL_ELEVATION = "fill_elevation"
        private const val KEY_ORIGINAL_URI = "original_uri"
        private const val KEY_REFERENCE_URI = "reference_uri"
        private const val KEY_MERGE_URIS = "merge_uris"
        private const val SUPPORT_URL = "https://t.tb.ru/pm_short/AOdA22MAdsS"
    }
}
