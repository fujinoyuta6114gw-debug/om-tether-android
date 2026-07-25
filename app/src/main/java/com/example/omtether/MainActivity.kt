package com.example.omtether

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.omtether.ui.OmTetherApp
import com.example.omtether.ui.OmTetherTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.handleIntent(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            OmTetherTheme {
                OmTetherApp(
                    state = state,
                    onUsbConnect = viewModel::openUsbConnectionGuide,
                    onSetupUsbConnect = viewModel::confirmUsbSetupAndConnect,
                    onDismissConnectionGuide = viewModel::dismissUsbConnectionGuide,
                    onConfirmConnectionGuide = viewModel::confirmUsbSetupAndConnect,
                    onRestartLiveView = viewModel::restartLiveView,
                    onDemo = viewModel::startDemo,
                    onCapture = viewModel::capture,
                    onPhoneSaveFormatChange = viewModel::setPhoneSaveFormat,
                    onExposureChange = viewModel::setExposure,
                    onHighlightEnabled = viewModel::setHighlightEnabled,
                    onHighlightThreshold = viewModel::setHighlightThreshold,
                    onOpenSetupGuide = viewModel::openSetupGuide,
                    onDismissSetupGuide = viewModel::dismissSetupGuide,
                    onNextSetupStep = viewModel::nextSetupStep,
                    onPreviousSetupStep = viewModel::previousSetupStep,
                    onDisplayCalibration = viewModel::setDisplayCalibration,
                    onResetDisplayCalibration = viewModel::resetDisplayCalibration,
                    onSetupWbConfirmed = viewModel::setSetupWbConfirmed,
                    onSetupGrayCardSkipped = viewModel::setSetupGrayCardSkipped,
                    onCompleteSetupGuide = viewModel::completeSetupGuide,
                    diagnostics = viewModel::diagnosticsText,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegroundChanged(true)
    }

    override fun onStop() {
        viewModel.onAppForegroundChanged(false)
        super.onStop()
    }
}
