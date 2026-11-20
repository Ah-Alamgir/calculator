package com.calculator.scientific

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.calculator.scientific.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = CalculatorEngine()
    private val openAiSolver = OpenAiSolverService()
    private lateinit var cameraManager: SmartFocusCameraManager

    private var currentExpression = StringBuilder()
    private var lastCalculatedValue: Double = 0.0
    private var isShiftActive = false
    private var isAlphaActive = false
    private var isHypActive = false
    private var isDisplayingFraction = false
    private var isDisplayingDms = false

    // Multi-line AI answer state for upward/downward scrolling inside the LCD
    private var aiAnswerLines: List<String> = emptyList()
    private var aiScrollLineOffset: Int = 0
    private var activeAiJob: Job? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            triggerCameraAndSolve()
        } else {
            binding.tvCameraAiStatus.text = "CAM DENIED"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen awake & show over lock screen so phone acts as a dedicated calculator OS
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Prevent accidental Back button exits in Single-App / Launcher mode
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Stay inside calculator app
            }
        })

        enableImmersiveFullscreen()
        enableKioskModeIfDeviceOwner()

        cameraManager = SmartFocusCameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.cameraPreviewView
        )

        setupControlAndReplayButtons()
        setupScientificKeypad()
        setupNumericAndOperatorKeypad()
        updateIndicators()
    }

    private fun enableKioskModeIfDeviceOwner() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, KioskDeviceAdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
                startLockTask()
            }
        } catch (_: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveFullscreen()
        }
    }

    private fun enableImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    private fun setupControlAndReplayButtons() {
        // Big Center Button -> Launch Camera -> Auto-Focus on Paper -> Capture -> Send to OpenAI -> Show Answer in LCD
        binding.btnBigCenterCamera.setOnClickListener {
            if (cameraManager.isActive()) {
                cameraManager.stopCamera()
                binding.cameraPreviewView.visibility = View.GONE
                binding.tvCameraAiStatus.text = ""
                return@setOnClickListener
            }
            checkPermissionAndStartCamera()
        }

        // Upward Button on Big Pad -> Scroll Display Text Upward
        binding.btnScrollUp.setOnClickListener {
            scrollDisplayTextUpward()
        }

        // Downward Button on Big Pad -> Scroll Display Text Downward
        binding.btnScrollDown.setOnClickListener {
            scrollDisplayTextDownward()
        }

        // Left / Right Buttons on Big Pad
        binding.btnCursorLeft.setOnClickListener {
            scrollDisplayTextDownward()
        }
        binding.btnCursorRight.setOnClickListener {
            scrollDisplayTextUpward()
        }

        // Top Row Control Keys
        binding.btnShift.setOnClickListener {
            isShiftActive = !isShiftActive
            if (isShiftActive) isAlphaActive = false
            updateIndicators()
        }

        binding.btnAlpha.setOnClickListener {
            isAlphaActive = !isAlphaActive
            if (isAlphaActive) isShiftActive = false
            updateIndicators()
        }

        binding.btnMode.setOnClickListener {
            // Cycle angle unit: DEG -> RAD -> GRAD
            engine.angleUnit = when (engine.angleUnit) {
                AngleUnit.DEG -> AngleUnit.RAD
                AngleUnit.RAD -> AngleUnit.GRAD
                AngleUnit.GRAD -> AngleUnit.DEG
            }
            isShiftActive = false
            isAlphaActive = false
            updateIndicators()
        }

        // Long-press MODE or ON to configure OpenAI API Key
        binding.btnMode.setOnLongClickListener {
            showApiKeyDialog()
            true
        }

        binding.btnOn.setOnClickListener {
            resetCalculatorState()
        }

        binding.btnOn.setOnLongClickListener {
            showApiKeyDialog()
            true
        }
    }

    /**
     * Scrolls the text inside the LCD display upward when the upward button is pressed.
     * Both scrolls the ScrollView upward AND steps through multi-line AI answers so the text moves upward line by line.
     */
    private fun scrollDisplayTextUpward() {
        val lineHeight = binding.tvExpressionDisplay.lineHeight.coerceAtLeast(28)
        binding.scrollDisplayArea.smoothScrollBy(0, lineHeight)

        if (aiAnswerLines.size > 1) {
            aiScrollLineOffset = (aiScrollLineOffset + 1) % aiAnswerLines.size
            val shiftedLines = aiAnswerLines.drop(aiScrollLineOffset) + aiAnswerLines.take(aiScrollLineOffset)
            binding.tvExpressionDisplay.text = shiftedLines.joinToString("\n")
            binding.tvResultDisplay.text = shiftedLines.firstOrNull().orEmpty()
        }
    }

    private fun scrollDisplayTextDownward() {
        val lineHeight = binding.tvExpressionDisplay.lineHeight.coerceAtLeast(28)
        binding.scrollDisplayArea.smoothScrollBy(0, -lineHeight)

        if (aiAnswerLines.size > 1) {
            aiScrollLineOffset = if (aiScrollLineOffset - 1 < 0) {
                aiAnswerLines.size - 1
            } else {
                aiScrollLineOffset - 1
            }
            val shiftedLines = aiAnswerLines.drop(aiScrollLineOffset) + aiAnswerLines.take(aiScrollLineOffset)
            binding.tvExpressionDisplay.text = shiftedLines.joinToString("\n")
            binding.tvResultDisplay.text = shiftedLines.firstOrNull().orEmpty()
        }
    }

    private fun checkPermissionAndStartCamera() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            triggerCameraAndSolve()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun triggerCameraAndSolve() {
        activeAiJob?.cancel()
        binding.cameraPreviewView.visibility = View.VISIBLE
        binding.tvCameraAiStatus.text = "FOCUSING..."

        cameraManager.startAutoFocusCapture(
            onStatusUpdate = { status ->
                binding.tvCameraAiStatus.text = status
            },
            onPhotoCaptured = { capturedBitmap ->
                binding.cameraPreviewView.visibility = View.GONE
                binding.tvCameraAiStatus.text = "AI..."

                val apiKey = getConfiguredOpenAiKey()
                activeAiJob = lifecycleScope.launch {
                    val result = openAiSolver.solveFromPaperBitmap(capturedBitmap, apiKey)
                    result.onSuccess { answerText ->
                        binding.tvCameraAiStatus.text = ""
                        displayAiAnswerInLcd(answerText)
                    }.onFailure { error ->
                        binding.tvCameraAiStatus.text = "ERR"
                        binding.tvExpressionDisplay.text = error.message ?: "AI request failed"
                    }
                }
            },
            onError = { err ->
                binding.cameraPreviewView.visibility = View.GONE
                binding.tvCameraAiStatus.text = err
            }
        )
    }

    private fun displayAiAnswerInLcd(rawAnswer: String) {
        val cleaned = rawAnswer.trim()
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        aiAnswerLines = if (lines.isEmpty()) listOf(cleaned) else lines
        aiScrollLineOffset = 0

        // Show full text in the upper scrollable LCD area (can be scrolled upward with ▲)
        binding.tvExpressionDisplay.text = aiAnswerLines.joinToString("\n")
        binding.scrollDisplayArea.scrollTo(0, 0)

        // Show primary/first line answer in the bottom LCD readout
        val primaryLine = aiAnswerLines.firstOrNull().orEmpty()
        binding.tvResultDisplay.text = primaryLine
    }

    private fun setupScientificKeypad() {
        binding.btnReciprocal.setOnClickListener {
            if (isShiftActive) {
                appendToken("!")
            } else {
                appendToken("⁻¹")
            }
        }

        binding.btnNcr.setOnClickListener {
            if (isShiftActive) {
                appendToken("P")
            } else {
                appendToken("C")
            }
        }

        binding.btnPol.setOnClickListener {
            if (isShiftActive) {
                appendToken("Rec(")
            } else {
                appendToken("Pol(")
            }
        }

        binding.btnCube.setOnClickListener {
            if (isShiftActive) {
                appendToken("³√(")
            } else {
                appendToken("³")
            }
        }

        binding.btnFraction.setOnClickListener {
            if (currentExpression.isEmpty() && lastCalculatedValue != 0.0) {
                isDisplayingFraction = !isDisplayingFraction
                binding.tvResultDisplay.text = if (isDisplayingFraction) {
                    engine.formatAsFraction(lastCalculatedValue)
                } else {
                    engine.formatResult(lastCalculatedValue)
                }
            } else {
                appendToken("⌟")
            }
        }

        binding.btnSqrt.setOnClickListener {
            appendToken("√(")
        }

        binding.btnSquare.setOnClickListener {
            appendToken("²")
        }

        binding.btnPower.setOnClickListener {
            appendToken("^")
        }

        binding.btnLog.setOnClickListener {
            if (isShiftActive) {
                appendToken("10^(")
            } else {
                appendToken("log(")
            }
        }

        binding.btnLn.setOnClickListener {
            if (isShiftActive) {
                appendToken("e^(")
            } else {
                appendToken("ln(")
            }
        }

        binding.btnNegate.setOnClickListener {
            appendToken("−")
        }

        binding.btnDms.setOnClickListener {
            if (currentExpression.isEmpty() && lastCalculatedValue != 0.0) {
                isDisplayingDms = !isDisplayingDms
                binding.tvResultDisplay.text = if (isDisplayingDms) {
                    engine.formatAsDms(lastCalculatedValue)
                } else {
                    engine.formatResult(lastCalculatedValue)
                }
            } else {
                appendToken("°")
            }
        }

        binding.btnHyp.setOnClickListener {
            isHypActive = !isHypActive
            updateIndicators()
        }

        binding.btnSin.setOnClickListener {
            val token = when {
                isHypActive -> "sinh("
                isShiftActive -> "sin⁻¹("
                else -> "sin("
            }
            isHypActive = false
            appendToken(token)
        }

        binding.btnCos.setOnClickListener {
            val token = when {
                isHypActive -> "cosh("
                isShiftActive -> "cos⁻¹("
                else -> "cos("
            }
            isHypActive = false
            appendToken(token)
        }

        binding.btnTan.setOnClickListener {
            val token = when {
                isHypActive -> "tanh("
                isShiftActive -> "tan⁻¹("
                else -> "tan("
            }
            isHypActive = false
            appendToken(token)
        }

        binding.btnRcl.setOnClickListener {
            appendToken("M")
        }

        binding.btnEng.setOnClickListener {
            if (lastCalculatedValue != 0.0) {
                binding.tvResultDisplay.text = engine.shiftEngNotation(lastCalculatedValue)
            }
        }

        binding.btnOpenParen.setOnClickListener {
            appendToken("(")
        }

        binding.btnCloseParen.setOnClickListener {
            appendToken(")")
        }

        binding.btnComma.setOnClickListener {
            appendToken(",")
        }

        binding.btnMPlus.setOnClickListener {
            val valueToAdd = if (currentExpression.isNotEmpty()) {
                val evaluated = engine.evaluate(currentExpression.toString())
                if (!evaluated.isNaN()) {
                    lastCalculatedValue = evaluated
                    binding.tvResultDisplay.text = engine.formatResult(evaluated)
                    evaluated
                } else {
                    0.0
                }
            } else {
                lastCalculatedValue
            }

            if (isShiftActive) {
                engine.memoryValue -= valueToAdd
            } else {
                engine.memoryValue += valueToAdd
            }
            isShiftActive = false
            updateIndicators()
        }
    }

    private fun setupNumericAndOperatorKeypad() {
        val numberButtons = listOf(
            binding.btn0 to "0",
            binding.btn1 to "1",
            binding.btn2 to "2",
            binding.btn3 to "3",
            binding.btn4 to "4",
            binding.btn5 to "5",
            binding.btn6 to "6",
            binding.btn7 to "7",
            binding.btn8 to "8",
            binding.btn9 to "9"
        )

        for ((button, digit) in numberButtons) {
            button.setOnClickListener { appendToken(digit) }
        }

        binding.btnDot.setOnClickListener {
            appendToken(".")
        }

        binding.btnAdd.setOnClickListener {
            if (currentExpression.isEmpty() && lastCalculatedValue != 0.0) {
                currentExpression.append("Ans")
            }
            appendToken("+")
        }

        binding.btnSubtract.setOnClickListener {
            if (currentExpression.isEmpty() && lastCalculatedValue != 0.0) {
                currentExpression.append("Ans")
            }
            appendToken("−")
        }

        binding.btnMultiply.setOnClickListener {
            if (currentExpression.isEmpty() && lastCalculatedValue != 0.0) {
                currentExpression.append("Ans")
            }
            appendToken("×")
        }

        binding.btnDivide.setOnClickListener {
            if (currentExpression.isEmpty() && lastCalculatedValue != 0.0) {
                currentExpression.append("Ans")
            }
            appendToken("÷")
        }

        binding.btnExp.setOnClickListener {
            if (isShiftActive) {
                appendToken("π")
            } else {
                appendToken("E")
            }
        }

        binding.btnAns.setOnClickListener {
            appendToken("Ans")
        }

        binding.btnDel.setOnClickListener {
            if (currentExpression.isNotEmpty()) {
                // Remove multi-char tokens cleanly if applicable
                val exprStr = currentExpression.toString()
                val multiCharTokens = listOf(
                    "sin⁻¹(", "cos⁻¹(", "tan⁻¹(",
                    "sinh(", "cosh(", "tanh(",
                    "sin(", "cos(", "tan(", "log(", "Pol(", "Rec(",
                    "10^(", "³√(", "ln(", "e^(","√(", "Ans", "⁻¹"
                )
                val matchedSuffix = multiCharTokens.firstOrNull { exprStr.endsWith(it) }
                if (matchedSuffix != null) {
                    currentExpression.delete(
                        currentExpression.length - matchedSuffix.length,
                        currentExpression.length
                    )
                } else {
                    currentExpression.deleteCharAt(currentExpression.length - 1)
                }
                binding.tvExpressionDisplay.text = currentExpression.toString()
            }
        }

        binding.btnAc.setOnClickListener {
            if (isShiftActive) {
                // SHIFT + AC = OFF behavior (clear screen completely)
                resetCalculatorState()
                binding.tvResultDisplay.text = ""
            } else {
                resetCalculatorState()
            }
        }

        binding.btnEquals.setOnClickListener {
            if (currentExpression.isEmpty()) return@setOnClickListener
            val expr = currentExpression.toString()
            val result = engine.evaluate(expr)
            lastCalculatedValue = result
            isDisplayingFraction = false
            isDisplayingDms = false
            binding.tvResultDisplay.text = engine.formatResult(result)
            currentExpression.clear()
        }
    }

    private fun appendToken(token: String) {
        // Clear any previous AI answer lines when typing a calculator expression
        if (aiAnswerLines.isNotEmpty()) {
            aiAnswerLines = emptyList()
            aiScrollLineOffset = 0
        }
        currentExpression.append(token)
        binding.tvExpressionDisplay.text = currentExpression.toString()
        isShiftActive = false
        isAlphaActive = false
        updateIndicators()
    }

    private fun resetCalculatorState() {
        activeAiJob?.cancel()
        cameraManager.stopCamera()
        binding.cameraPreviewView.visibility = View.GONE
        binding.tvCameraAiStatus.text = ""
        currentExpression.clear()
        aiAnswerLines = emptyList()
        aiScrollLineOffset = 0
        isShiftActive = false
        isAlphaActive = false
        isHypActive = false
        isDisplayingFraction = false
        isDisplayingDms = false
        binding.tvExpressionDisplay.text = ""
        binding.tvResultDisplay.text = "0."
        updateIndicators()
    }

    private fun updateIndicators() {
        binding.tvIndShift.visibility = if (isShiftActive) View.VISIBLE else View.INVISIBLE
        binding.tvIndAlpha.visibility = if (isAlphaActive) View.VISIBLE else View.INVISIBLE
        binding.tvIndMemory.visibility =
            if (engine.memoryValue != 0.0) View.VISIBLE else View.INVISIBLE
        binding.tvIndAngle.text = when (engine.angleUnit) {
            AngleUnit.DEG -> "D"
            AngleUnit.RAD -> "R"
            AngleUnit.GRAD -> "G"
        }
    }

    private fun getConfiguredOpenAiKey(): String {
        return EnvConfig.getOpenAiApiKey(this)
    }

    private fun showApiKeyDialog() {
        val currentKey = EnvConfig.getOpenAiApiKey(this)

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-..."
            setText(currentKey)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Calculator Setup (.env / Kiosk OS)")
            .setMessage("OpenAI API Key (loaded from .env by default) & Single-App Kiosk controls:")
            .setView(input)
            .setPositiveButton("Save Key") { _, _ ->
                val newKey = input.text.toString().trim()
                EnvConfig.saveOverrideApiKey(this, newKey)
                Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Lock Only This App") { _, _ ->
                try {
                    startLockTask()
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("Wi-Fi / Exit Kiosk") { _, _ ->
                try {
                    stopLockTask()
                } catch (_: Exception) {
                }
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.shutdown()
    }
}
