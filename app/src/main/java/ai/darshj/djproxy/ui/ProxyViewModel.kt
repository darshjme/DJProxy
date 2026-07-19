package ai.darshj.djproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyParser
import ai.darshj.djproxy.proxy.ProxyError
import ai.darshj.djproxy.proxy.ValidationResult
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.LogEvent
import ai.darshj.djproxy.vpn.VpnController
import ai.darshj.djproxy.vpn.VpnState
import ai.darshj.djproxy.vpn.LogBus
import ai.darshj.djproxy.vpn.seams.CriticalFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val LOG_HISTORY_CAP = 400

/** Everything the paste box + fields + inline error card need, independent of the tunnel's own
 *  [VpnState] (which the screen observes separately from [VpnController.state]). */
data class ProxyUiState(
    val pasteText: String = "",
    val config: ProxyConfig = ProxyConfig(),
    val pasteError: String? = null,
    val validationError: ProxyError? = null,
    /** True right after a successful apply, cleared on the next edit — drives a brief success flash. */
    val justSucceeded: Boolean = false,
)

/**
 * Owns the canonical [ProxyConfig] and mediates every write path into it (paste box, individual
 * fields), plus drives [VpnController.apply]/[VpnController.stop]. The controller itself is
 * supplied asynchronously by [attachController] once the host activity has bound to the VPN
 * service — until then Apply is disabled and the UI shows a "preparing" state.
 */
class ProxyViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

    private val _controller = MutableStateFlow<VpnController?>(null)

    /** True once the VPN service is bound and Apply/Stop can actually do something. */
    val controllerReady: StateFlow<Boolean> =
        _controller.map { it != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Live tunnel state — [VpnState.IDLE] whenever no controller is attached yet. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val vpnState: StateFlow<VpnState> = _controller
        .flatMapLatest { it?.state ?: MutableStateFlow(VpnState.IDLE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VpnState.IDLE)

    /** Rolling window of the last [LOG_HISTORY_CAP] log lines for [LogView]. */
    val logs: StateFlow<List<LogEvent>> = LogBus.events
        .scan(emptyList<LogEvent>()) { acc, event -> (acc + event).takeLast(LOG_HISTORY_CAP) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Called once by the host activity after binding to the VPN service. Idempotent. */
    fun attachController(controller: VpnController) {
        _controller.value = controller
    }

    fun onPasteTextChanged(text: String) {
        val current = _uiState.value
        if (text.isBlank()) {
            _uiState.value = current.copy(pasteText = text, pasteError = null, justSucceeded = false)
            return
        }
        when (val result = ProxyParser.parse(text, default = current.config)) {
            is ProxyParser.Result.Ok -> _uiState.value = current.copy(
                pasteText = text,
                config = result.config,
                pasteError = null,
                justSucceeded = false,
            )
            is ProxyParser.Result.Err -> _uiState.value = current.copy(
                pasteText = text,
                pasteError = result.hint?.let { "${result.message} — $it" } ?: result.message,
                justSucceeded = false,
            )
        }
    }

    fun onConfigChanged(config: ProxyConfig) {
        _uiState.value = _uiState.value.copy(config = config, justSucceeded = false)
    }

    /** Runs the real pre-flight (via the controller — the exact same dial path the live proxy
     *  uses) and only brings the tunnel up on genuine success. Never optimistic. */
    fun onApply() {
        val controller = _controller.value ?: run {
            LogBus.w("UI", "Apply tapped before the VPN service finished binding.")
            return
        }
        val config = _uiState.value.config
        val fieldError = config.validate()
        if (fieldError != null) {
            _uiState.value = _uiState.value.copy(validationError = ProxyError.Io(fieldError))
            return
        }
        _uiState.value = _uiState.value.copy(validationError = null, justSucceeded = false)
        viewModelScope.launch {
            when (val result = controller.apply(config)) {
                is ValidationResult.Success -> _uiState.value = _uiState.value.copy(
                    validationError = null,
                    justSucceeded = true,
                )
                is ValidationResult.Failure -> _uiState.value = _uiState.value.copy(
                    validationError = result.error,
                    justSucceeded = false,
                )
            }
        }
    }

    fun onStop() {
        _controller.value?.stop()
        _uiState.value = _uiState.value.copy(justSucceeded = false)
    }

    fun dismissValidationError() {
        _uiState.value = _uiState.value.copy(validationError = null)
    }

    /**
     * User-initiated "Send report" from the inline error card. Routes the current
     * [ProxyError] through the same [ai.darshj.djproxy.vpn.seams.CriticalFailureSink] seam core
     * uses for crashes/engine-death (§9.4) — the diagnostics lane (if attached) decides how to
     * turn this into a mailto report. This call is always wrapped by [FeatureRegistry.reportCritical]
     * (runCatching) so a missing/faulty diagnostics lane can never throw into the UI. A no-op if
     * there is no current error or no diagnostics lane attached.
     */
    fun sendErrorReport() {
        val error = _uiState.value.validationError ?: return
        FeatureRegistry.reportCritical(
            CriticalFailure(
                category = CriticalFailure.Category.BRINGUP_FAILED,
                reason = "${error.message} — ${error.hint}",
            ),
        )
        LogBus.i("UI", "User requested a diagnostic report for: ${error.message}")
    }
}
