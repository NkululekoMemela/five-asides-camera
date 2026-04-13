package com.fiveasidesnearme.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fiveasidesnearme.camera.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    data class LaunchPlayer(
        val id: String,
        val name: String,
        val teamId: String? = null
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var recorderManager: HighlightRecorderManager? = null

    private var sourceApp: String? = null
    private var matchIsLive: Boolean = false
    private var canUseOutsideOfficialMatch: Boolean = true

    private var teamAId: String? = null
    private var teamBId: String? = null
    private var teamAName: String = "Team A"
    private var teamBName: String = "Team B"
    private var teamAPlayers: List<LaunchPlayer> = emptyList()
    private var teamBPlayers: List<LaunchPlayer> = emptyList()
    private var launchMatchId: String? = null
    private var launchMatchNo: Int? = null
    private var launchSeasonId: String? = null
    private var launchGameFormat: String = "3_TEAM_LEAGUE"

    private var initialBufferTimer: CountDownTimer? = null
    private var playerSelectTimer: CountDownTimer? = null
    private var preSaveWaitTimer: CountDownTimer? = null
    private var endMatchConfirmTimer: CountDownTimer? = null

    private var secondsBuffered: Int = 0
    private var isRecordingActive: Boolean = false
    private var isAwaitingFullBufferBeforeSave: Boolean = false
    private var pendingTag: String? = null
    private var pendingSelectedPlayer: LaunchPlayer? = null
    private var pendingSelectedTeamName: String? = null

    private var hasRecordingSessionStarted: Boolean = false
    private var explicitStopRequested: Boolean = false
    private var hasShownKeepRecordingBanner: Boolean = false
    private var isEndMatchConfirming: Boolean = false

    private val startBlue = Color.parseColor("#1565C0")
    private val stopDark = Color.parseColor("#263238")
    private val stopActiveBlue = Color.parseColor("#1E88E5")
    private val stopConfirmRed = Color.parseColor("#C62828")
    private val recordingRed = Color.parseColor("#D50000")

    private val goalRed = Color.parseColor("#E53935")
    private val saveCyan = Color.parseColor("#00BCD4")
    private val skillGold = Color.parseColor("#F9A825")
    private val tagDefault = Color.parseColor("#122033")
    private val textWhite = Color.parseColor("#F8FAFC")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = REQUIRED_PERMISSIONS.all { grants[it] == true }
        if (granted) {
            setupCamera()
        } else {
            Toast.makeText(
                this,
                "Camera and audio permissions are required.",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        handleIncomingLaunchIntent(intent)

        applyWindowInsets()
        setupUi()

        if (hasAllPermissions()) {
            setupCamera()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingLaunchIntent(intent)

        recorderManager?.setOfficialMatchContext(isOfficialMatchContext())

        if (::binding.isInitialized) {
            binding.statusText.text = currentIdleStatusText()
            updateOfflineWarningVisibility()
        }
    }

    private fun handleIncomingLaunchIntent(incomingIntent: Intent?) {
        matchIsLive = incomingIntent?.getBooleanExtra("matchIsLive", false) == true

        val uri = incomingIntent?.data ?: run {
            if (::binding.isInitialized) {
                binding.statusText.text = currentIdleStatusText()
                updateOfflineWarningVisibility()
            }
            return
        }

        if (uri.scheme != "fiveasidesnearmecamera" || uri.host != "open") {
            if (::binding.isInitialized) {
                binding.statusText.text = currentIdleStatusText()
                updateOfflineWarningVisibility()
            }
            return
        }

        val rawPayload = uri.getQueryParameter("payload") ?: run {
            if (::binding.isInitialized) {
                binding.statusText.text = currentIdleStatusText()
                updateOfflineWarningVisibility()
            }
            return
        }

        try {
            val decoded = URLDecoder.decode(rawPayload, StandardCharsets.UTF_8.toString())
            val payload = JSONObject(decoded)

            sourceApp = payload.optString("sourceApp").trim().ifBlank { null }
            matchIsLive = payload.optBoolean("matchIsLive", matchIsLive)
            canUseOutsideOfficialMatch = payload.optBoolean("canUseOutsideOfficialMatch", true)

            launchMatchId = payload.optString("matchId").takeIf { it.isNotBlank() }
            launchMatchNo = payload.optInt("matchNo", -1).takeIf { it > 0 }
            launchSeasonId = payload.optString("seasonId").takeIf { it.isNotBlank() }
            launchGameFormat = payload.optString("gameFormat", "3_TEAM_LEAGUE").ifBlank {
                "3_TEAM_LEAGUE"
            }

            teamAId = payload.optString("teamAId").takeIf { it.isNotBlank() }
            teamBId = payload.optString("teamBId").takeIf { it.isNotBlank() }
            teamAName = payload.optString("teamAName", "Team A").ifBlank { "Team A" }
            teamBName = payload.optString("teamBName", "Team B").ifBlank { "Team B" }

            teamAPlayers = parseLaunchPlayers(payload.optJSONArray("teamAPlayers"), teamAId)
            teamBPlayers = parseLaunchPlayers(payload.optJSONArray("teamBPlayers"), teamBId)

            if (teamAPlayers.isEmpty() && teamBPlayers.isEmpty()) {
                val fallbackPlayers = parseLaunchPlayers(payload.optJSONArray("players"), null)
                if (fallbackPlayers.isNotEmpty()) {
                    val midpoint = max(1, fallbackPlayers.size / 2)
                    teamAPlayers = fallbackPlayers.take(midpoint)
                    teamBPlayers = fallbackPlayers.drop(midpoint)
                }
            }

            if (::binding.isInitialized) {
                binding.statusText.text = currentIdleStatusText()
                updateOfflineWarningVisibility()
            }
        } catch (_: Exception) {
            if (::binding.isInitialized) {
                binding.statusText.text = currentIdleStatusText()
                updateOfflineWarningVisibility()
            }
        }
    }

    private fun parseLaunchPlayers(array: JSONArray?, fallbackTeamId: String?): List<LaunchPlayer> {
        if (array == null) return emptyList()

        val out = mutableListOf<LaunchPlayer>()
        val seen = mutableSetOf<String>()

        for (i in 0 until array.length()) {
            val item = array.opt(i) ?: continue

            val parsed = when (item) {
                is JSONObject -> {
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    val teamId = item.optString("teamId").trim().ifBlank { fallbackTeamId }

                    if (name.isBlank()) null
                    else LaunchPlayer(
                        id = if (id.isBlank()) slugFromName(name) else id,
                        name = name,
                        teamId = teamId
                    )
                }

                is String -> {
                    val name = item.trim()
                    if (name.isBlank()) null
                    else LaunchPlayer(
                        id = slugFromName(name),
                        name = name,
                        teamId = fallbackTeamId
                    )
                }

                else -> null
            }

            if (parsed != null && seen.add(parsed.id)) {
                out.add(parsed)
            }
        }

        return out
    }

    private fun slugFromName(value: String): String {
        return value.trim()
            .lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_]".toRegex(), "")
            .ifBlank { "player_${System.currentTimeMillis()}" }
    }

    private fun isOfficialMatchContext(): Boolean {
        return sourceApp == "TurfKings" && matchIsLive
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.bottomPanel.setPadding(
                binding.bottomPanel.paddingLeft,
                binding.bottomPanel.paddingTop,
                binding.bottomPanel.paddingRight,
                systemBars.bottom + 20
            )
            insets
        }
    }

    private fun setupUi() {
        binding.startBufferButton.setOnClickListener {
            if (hasRecordingSessionStarted) return@setOnClickListener

            explicitStopRequested = false
            hasRecordingSessionStarted = true
            recorderManager?.startRollingBuffer()
            showRecordingBannerSequence()
            startInitialBufferProgressUi()
            setRecordingUi(true)
            binding.statusText.text = currentRecordingStatusText()
            Toast.makeText(this, "Video recording started", Toast.LENGTH_SHORT).show()
        }

        binding.stopBufferButton.setOnClickListener {
            if (!hasRecordingSessionStarted) return@setOnClickListener

            if (isEndMatchConfirming) {
                cancelEndMatchConfirmation()
            } else {
                startEndMatchConfirmation()
            }
        }

        binding.goalButton.setOnClickListener { onEventActionTapped("goal") }
        binding.saveTagButton.setOnClickListener { onEventActionTapped("save") }
        binding.skillButton.setOnClickListener { onEventActionTapped("skill") }

        binding.statusText.text = currentIdleStatusText()
        updateOfflineWarningVisibility()
        setRecordingUi(false)
        updateEventActionButtons()
    }

    private fun onEventActionTapped(tag: String) {
        if (!isRecordingActive || isAwaitingFullBufferBeforeSave || isEndMatchConfirming) return

        pendingTag = tag
        pendingSelectedPlayer = null
        pendingSelectedTeamName = null

        if (secondsBuffered < 15) {
            showCenterEventOverlay(
                tag = tag,
                waitMode = true,
                remainingSeconds = 15 - secondsBuffered,
                progressValue = secondsBuffered
            )
            startPreSaveWait(tag)
        } else {
            showCenterEventOverlay(
                tag = tag,
                waitMode = false,
                remainingSeconds = 0,
                progressValue = 15
            )
            completeEventSave(tag)
        }
    }

    private fun startPreSaveWait(tag: String) {
        preSaveWaitTimer?.cancel()
        isAwaitingFullBufferBeforeSave = true
        updateEventActionButtons()
        updateStartButtonState()
        updateEndMatchButtonState()

        val remainingNeeded = max(1, 15 - secondsBuffered)

        preSaveWaitTimer = object : CountDownTimer(remainingNeeded * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = (millisUntilFinished / 1000L).toInt()
                val progress = 15 - remaining
                binding.eventWaitProgress.progress = progress
                binding.eventWaitText.text = "WAIT ${remaining}s TO COMPLETE BUFFER"
            }

            override fun onFinish() {
                secondsBuffered = 15
                isAwaitingFullBufferBeforeSave = false
                updateEventActionButtons()
                updateStartButtonState()
                updateEndMatchButtonState()
                showCenterEventOverlay(
                    tag = tag,
                    waitMode = false,
                    remainingSeconds = 0,
                    progressValue = 15
                )
                completeEventSave(tag)
            }
        }.start()
    }

    private fun completeEventSave(tag: String) {
        recorderManager?.saveHighlight(tag)

        if (isOfficialMatchContext()) {
            binding.rootLayout.postDelayed({
                hideCenterEventOverlay()
                showPlayerSelectionOverlay(tag)
            }, 900)
        } else {
            binding.eventWaitText.visibility = View.VISIBLE
            binding.eventWaitText.text =
                "Captured outside official kick off • saved to your phone only"
            binding.eventWaitProgress.visibility = View.VISIBLE
            binding.eventWaitProgress.progress = 15

            binding.rootLayout.postDelayed({
                hideCenterEventOverlay()
            }, 1500)
        }
    }

    private fun showCenterEventOverlay(
        tag: String,
        waitMode: Boolean,
        remainingSeconds: Int,
        progressValue: Int
    ) {
        binding.centerEventOverlay.visibility = View.VISIBLE
        binding.centerEventText.text = eventHeadline(tag)
        binding.centerEventText.setTextColor(eventColor(tag))

        if (waitMode) {
            binding.eventWaitText.visibility = View.VISIBLE
            binding.eventWaitProgress.visibility = View.VISIBLE
            binding.eventWaitText.text = "WAIT ${remainingSeconds}s TO COMPLETE BUFFER"
            binding.eventWaitProgress.max = 15
            binding.eventWaitProgress.progress = progressValue
        } else {
            binding.eventWaitText.visibility =
                if (isOfficialMatchContext()) View.GONE else View.VISIBLE
            binding.eventWaitProgress.visibility =
                if (isOfficialMatchContext()) View.GONE else View.VISIBLE

            if (!isOfficialMatchContext()) {
                binding.eventWaitText.text = "Outside official kick off • saving to your phone only"
                binding.eventWaitProgress.max = 15
                binding.eventWaitProgress.progress = 15
            }
        }
    }

    private fun hideCenterEventOverlay() {
        if (!isEndMatchConfirming) {
            binding.centerEventOverlay.visibility = View.GONE
            binding.eventWaitText.visibility = View.GONE
            binding.eventWaitProgress.visibility = View.GONE
        }
    }

    private fun startEndMatchConfirmation() {
        if (!hasRecordingSessionStarted) return

        isEndMatchConfirming = true
        updateEventActionButtons()
        updateStartButtonState()
        updateEndMatchButtonState()

        binding.centerEventOverlay.visibility = View.VISIBLE
        binding.centerEventText.text = "ENDING MATCH"
        binding.centerEventText.setTextColor(goalRed)
        binding.eventWaitText.visibility = View.VISIBLE
        binding.eventWaitProgress.visibility = View.VISIBLE
        binding.eventWaitProgress.max = 3
        binding.eventWaitProgress.progress = 0
        binding.eventWaitText.text = "Tap END MATCH again to cancel • stopping in 3s"

        endMatchConfirmTimer?.cancel()
        endMatchConfirmTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = ((millisUntilFinished + 999) / 1000L).toInt()
                val elapsed = 3 - remaining
                binding.eventWaitProgress.progress = elapsed
                binding.eventWaitText.text =
                    "Tap END MATCH again to cancel • stopping in ${remaining}s"
            }

            override fun onFinish() {
                isEndMatchConfirming = false
                performStopMatch()
            }
        }.start()
    }

    private fun cancelEndMatchConfirmation() {
        endMatchConfirmTimer?.cancel()
        endMatchConfirmTimer = null
        isEndMatchConfirming = false
        binding.statusText.text = currentRecordingStatusText()
        hideCenterEventOverlay()
        updateEventActionButtons()
        updateStartButtonState()
        updateEndMatchButtonState()
    }

    private fun performStopMatch() {
        explicitStopRequested = true
        recorderManager?.stopRollingBuffer()
        cancelPendingEventFlow()
        stopInitialBufferProgressUi()
        hidePlayerSelectionOverlay()
        hideCenterEventOverlay()
        hideRecordingBanner()
        hasRecordingSessionStarted = false
        hasShownKeepRecordingBanner = false
        setRecordingUi(false)
        binding.statusText.text = currentStoppedStatusText()
        updateOfflineWarningVisibility()
    }

    private fun showPlayerSelectionOverlay(tag: String) {
        if (teamAPlayers.isEmpty() && teamBPlayers.isEmpty()) {
            Toast.makeText(
                this,
                "Saved as unclassified clip",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        binding.playerOverlay.visibility = View.VISIBLE
        binding.playerOverlayTitle.text = "Who made that ${tag.uppercase()}?"
        binding.playerOverlaySubtitle.text = "Pick the player before the next moment"

        populateTeam(
            container = binding.teamAContainer,
            teamName = teamAName,
            players = teamAPlayers,
            tint = Color.parseColor("#1E3A8A")
        )

        populateTeam(
            container = binding.teamBContainer,
            teamName = teamBName,
            players = teamBPlayers,
            tint = Color.parseColor("#14532D")
        )

        playerSelectTimer?.cancel()
        playerSelectTimer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = (millisUntilFinished / 1000L).toInt()
                binding.playerOverlayHint.text =
                    "Select player now • ${remaining}s left • no selection = unclassified clip"
            }

            override fun onFinish() {
                hidePlayerSelectionOverlay()
                Toast.makeText(
                    this@MainActivity,
                    "Saved as unclassified clip",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun hidePlayerSelectionOverlay() {
        playerSelectTimer?.cancel()
        binding.playerOverlay.visibility = View.GONE
    }

    private fun populateTeam(
        container: LinearLayout,
        teamName: String,
        players: List<LaunchPlayer>,
        tint: Int
    ) {
        container.removeAllViews()

        val header = TextView(this).apply {
            text = teamName
            setTextColor(textWhite)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            backgroundTintList = ColorStateList.valueOf(tint)
            background = ContextCompat.getDrawable(
                this@MainActivity,
                androidx.appcompat.R.drawable.abc_btn_default_mtrl_shape
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dp()
            }
        }
        container.addView(header)

        players.forEach { player ->
            val btn = Button(this).apply {
                text = player.name
                setTextColor(textWhite)
                backgroundTintList = ColorStateList.valueOf(tagDefault)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    48.dp()
                ).apply {
                    bottomMargin = 8.dp()
                }
                setOnClickListener {
                    pendingSelectedPlayer = player
                    pendingSelectedTeamName = teamName
                    playerSelectTimer?.cancel()
                    hidePlayerSelectionOverlay()

                    Toast.makeText(
                        this@MainActivity,
                        "Selected: ${player.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                    // TODO: attach player + team metadata to saved highlight return payload / storage
                }
            }
            container.addView(btn)
        }
    }

    private fun showRecordingBannerSequence() {
        showRecordingBanner(
            title = "5 Asides Near Me",
            subtitle = "Video recording started",
            subtitleBold = false,
            durationMs = 2200L
        ) {
            showKeepRecordingBanner()
        }
    }

    private fun showKeepRecordingBanner() {
        if (hasShownKeepRecordingBanner) return
        hasShownKeepRecordingBanner = true

        val infoText = SpannableStringBuilder().apply {
            append("Keep the video recording on throughout the match.\n")
            append("Stopping the recording will interrupt highlight capture.\n\n")
            val start = length
            append("Turf Kings FC")
            setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                RelativeSizeSpan(1.45f),
                start,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        showRecordingBanner(
            title = "Recording must stay on",
            subtitle = infoText,
            subtitleBold = false,
            durationMs = 5400L,
            onComplete = null
        )
    }

    private fun showRecordingBanner(
        title: String,
        subtitle: CharSequence,
        subtitleBold: Boolean,
        durationMs: Long,
        onComplete: (() -> Unit)? = null
    ) {
        binding.recordingBannerTitle.text = title
        binding.recordingBannerSubtitle.text = subtitle
        binding.recordingBannerTitle.gravity = Gravity.CENTER
        binding.recordingBannerSubtitle.gravity = Gravity.CENTER
        binding.recordingBannerTitle.textAlignment = View.TEXT_ALIGNMENT_CENTER
        binding.recordingBannerSubtitle.textAlignment = View.TEXT_ALIGNMENT_CENTER
        binding.recordingBannerSubtitle.setTypeface(
            binding.recordingBannerSubtitle.typeface,
            if (subtitleBold) Typeface.BOLD else Typeface.NORMAL
        )

        binding.recordingBanner.visibility = View.VISIBLE
        binding.recordingBanner.alpha = 0f
        binding.recordingBanner.animate()
            .alpha(1f)
            .setDuration(250)
            .withEndAction {
                binding.recordingBanner.postDelayed({
                    binding.recordingBanner.animate()
                        .alpha(0f)
                        .setDuration(600)
                        .withEndAction {
                            binding.recordingBanner.visibility = View.GONE
                            binding.recordingBanner.alpha = 1f
                            onComplete?.invoke()
                        }
                        .start()
                }, durationMs)
            }
            .start()
    }

    private fun hideRecordingBanner() {
        binding.recordingBanner.animate().cancel()
        binding.recordingBanner.clearAnimation()
        binding.recordingBanner.visibility = View.GONE
        binding.recordingBanner.alpha = 1f
    }

    private fun startInitialBufferProgressUi() {
        initialBufferTimer?.cancel()
        secondsBuffered = 0

        initialBufferTimer = object : CountDownTimer(15_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                if (!isRecordingActive) return
                secondsBuffered = 15 - (millisUntilFinished / 1000L).toInt()
            }

            override fun onFinish() {
                secondsBuffered = 15
            }
        }.start()
    }

    private fun stopInitialBufferProgressUi() {
        initialBufferTimer?.cancel()
        initialBufferTimer = null
        secondsBuffered = 0
    }

    private fun cancelPendingEventFlow() {
        preSaveWaitTimer?.cancel()
        preSaveWaitTimer = null
        isAwaitingFullBufferBeforeSave = false
        pendingTag = null
        pendingSelectedPlayer = null
        pendingSelectedTeamName = null
        hideCenterEventOverlay()
        updateEventActionButtons()
        updateStartButtonState()
        updateEndMatchButtonState()
    }

    private fun setRecordingUi(isRecording: Boolean) {
        isRecordingActive = isRecording

        if (!isRecording && explicitStopRequested) {
            hasRecordingSessionStarted = false
            explicitStopRequested = false
        }

        updateStartButtonState()
        updateEndMatchButtonState()

        if (isRecordingActive || hasRecordingSessionStarted) {
            binding.startBufferButton.text = "LIVE"
            binding.startBufferButton.backgroundTintList = ColorStateList.valueOf(recordingRed)
            binding.recordingIndicatorRow.visibility = View.VISIBLE
            binding.stopBufferButton.text = if (isEndMatchConfirming) "CANCEL END" else "END MATCH"
        } else {
            binding.startBufferButton.text = "KICK OFF"
            binding.startBufferButton.backgroundTintList = ColorStateList.valueOf(startBlue)
            binding.recordingIndicatorRow.visibility = View.GONE
            binding.stopBufferButton.text = "END MATCH"
        }

        updateEventActionButtons()
    }

    private fun updateStartButtonState() {
        val shouldStayLocked =
            hasRecordingSessionStarted || isAwaitingFullBufferBeforeSave || isEndMatchConfirming
        binding.startBufferButton.isEnabled = !shouldStayLocked
        binding.startBufferButton.alpha = if (shouldStayLocked) 0.92f else 1f
    }

    private fun updateEndMatchButtonState() {
        val enabled = hasRecordingSessionStarted
        binding.stopBufferButton.isEnabled = enabled
        binding.stopBufferButton.alpha = if (enabled) 1f else 0.65f

        val tint = when {
            isEndMatchConfirming -> stopConfirmRed
            enabled -> stopActiveBlue
            else -> stopDark
        }
        binding.stopBufferButton.backgroundTintList = ColorStateList.valueOf(tint)
    }

    private fun updateEventActionButtons() {
        val enabled = isRecordingActive && !isAwaitingFullBufferBeforeSave && !isEndMatchConfirming

        listOf(binding.goalButton, binding.saveTagButton, binding.skillButton).forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.55f
        }
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            recorderManager = HighlightRecorderManager(
                context = this,
                cameraProvider = cameraProvider,
                previewView = binding.previewView,
                mainExecutor = ContextCompat.getMainExecutor(this),
                backgroundExecutor = cameraExecutor,
                listener = object : HighlightRecorderManager.Listener {
                    override fun onStatusChanged(message: String) {
                        if (shouldSuppressStatus(message)) return
                        binding.statusText.text = message
                    }

                    override fun onRollingStateChanged(isRunning: Boolean) {
                        if (!isRunning && !explicitStopRequested && hasRecordingSessionStarted) {
                            setRecordingUi(true)
                            return
                        }

                        setRecordingUi(isRunning)

                        if (!isRunning) {
                            cancelPendingEventFlow()
                            stopInitialBufferProgressUi()
                            hidePlayerSelectionOverlay()
                            hideCenterEventOverlay()
                        }
                    }

                    override fun onHighlightSaved(message: String) {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        binding.statusText.text = currentRecordingStatusText()
                    }

                    override fun onHighlightExported(
                        exportedFile: File,
                        tag: String,
                        isOfficialMatch: Boolean
                    ) {
                        if (isOfficialMatch) {

                            Toast.makeText(
                                this@MainActivity,
                                "Uploading highlight...",
                                Toast.LENGTH_SHORT
                            ).show()

                            FirebaseUploader.uploadHighlight(
                                file = exportedFile,
                                matchId = launchMatchId,
                                tag = tag,
                                onSuccess = {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "🔥 Uploaded to Firebase",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onError = { error ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Upload failed: $error",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )

                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Saved locally — not an official match",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onError(message: String) {
                        if (isBenignRecordingError(message)) {
                            if (!isRecordingActive) {
                                binding.statusText.text = currentIdleStatusText()
                            }
                            return
                        }

                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        binding.statusText.text = message
                    }
                }
            )

            recorderManager?.setOfficialMatchContext(isOfficialMatchContext())
            recorderManager?.bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun shouldSuppressStatus(message: String): Boolean {
        val normalized = message.trim().lowercase()
        return normalized.contains("wait a few more seconds") ||
                normalized.contains("before saving") ||
                normalized.contains("wait more") ||
                normalized.contains("saving not ready")
    }

    private fun isBenignRecordingError(message: String): Boolean {
        val normalized = message.trim().lowercase()
        return normalized == "recording failed: 4" || normalized == "recording failed 4"
    }

    private fun eventHeadline(tag: String): String {
        return when (tag) {
            "goal" -> "GOAL!!!"
            "save" -> "SAVE!"
            "skill" -> "SKILL, WOW!"
            else -> tag.uppercase()
        }
    }

    private fun eventColor(tag: String): Int {
        return when (tag) {
            "goal" -> goalRed
            "save" -> saveCyan
            "skill" -> skillGold
            else -> textWhite
        }
    }

    private fun currentIdleStatusText(): String {
        return if (isOfficialMatchContext()) {
            "Connected to live match"
        } else {
            "Outside official kick off • phone-only saves"
        }
    }

    private fun currentRecordingStatusText(): String {
        return if (isOfficialMatchContext()) {
            "Recording live"
        } else {
            "Recording outside official kick off • phone-only saves"
        }
    }

    private fun currentStoppedStatusText(): String {
        return if (isOfficialMatchContext()) {
            "Match ended"
        } else {
            "Capture ended • clips stay on this phone"
        }
    }

    private fun updateOfflineWarningVisibility() {
        binding.offlineWarningText.visibility =
            if (isOfficialMatchContext()) View.GONE else View.VISIBLE
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        initialBufferTimer?.cancel()
        playerSelectTimer?.cancel()
        preSaveWaitTimer?.cancel()
        endMatchConfirmTimer?.cancel()
        recorderManager?.release()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}