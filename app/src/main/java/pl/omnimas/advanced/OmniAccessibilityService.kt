package pl.omnimas.advanced

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class OmniAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val grounding = GroundingAgent()
    private val memory = MemoryAgent()
    private val security = SecurityAgent()
    private val llm = LocalOllama()
    private lateinit var executor: ExecutorAgent

    override fun onServiceConnected() {
        super.onServiceConnected()
        executor = ExecutorAgent(this)
        Instance.service = this
        Instance.lastStatus = "Accessibility połączone"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Instance.packageName = event?.packageName?.toString().orEmpty()
    }

    override fun onInterrupt() {
        Instance.lastStatus = "Przerwane"
    }

    fun launchMission(mission: String) {
        val normalizedMission = mission.trim()
        if (normalizedMission.isBlank()) {
            Instance.lastStatus = "Brak misji"
            return
        }
        if (Instance.running) return

        Instance.running = true
        Instance.lastStatus = "Tworzę plan..."
        scope.launch {
            try {
                val plan = llm.plan(normalizedMission)
                require(plan.isNotEmpty()) { "Planner zwrócił pusty plan" }

                outer@ for ((index, step) in plan.withIndex()) {
                    Instance.lastStatus = "Krok ${index + 1}/${plan.size}: ${step.objective}"
                    var completed = false

                    repeat(MAX_ATTEMPTS_PER_STEP) { attempt ->
                        val root = rootInActiveWindow ?: run {
                            delay(300)
                            return@repeat
                        }

                        val state = grounding.capture(root, currentPackage())
                        val rawNodes = collect(root)
                        val action = llm.decide(normalizedMission, step, state, memory)
                        Instance.lastAction = action

                        if (security.requiresConfirmation(action)) {
                            Instance.lastStatus = "POTWIERDZENIE WYMAGANE: ${action.reason ?: "akcja wysokiego ryzyka"}"
                            Instance.awaitingConfirmation = action
                            return@repeat
                        }

                        if (action.type == "DONE") {
                            completed = true
                            return@repeat
                        }

                        val before = state.fingerprint
                        val ok = executor.execute(action, rawNodes)
                        rawNodes.forEach { node -> node.recycle() }

                        if (!ok) {
                            Instance.lastStatus = "Akcja nieudana (próba ${attempt + 1})"
                            return@repeat
                        }

                        memory.record(action, before)
                        delay(700)

                        val after = grounding.capture(rootInActiveWindow, currentPackage())
                        val changed = after.fingerprint.isNotBlank() && after.fingerprint != before
                        val expectedVisible = action.expected?.let { expected ->
                            after.nodes.any { node ->
                                "${node.text.orEmpty()} ${node.desc.orEmpty()} ${node.resourceId.orEmpty()}"
                                    .contains(expected, ignoreCase = true)
                            }
                        } ?: true

                        if (changed && expectedVisible) {
                            completed = true
                            return@repeat
                        }

                        if (memory.repeatedState() || memory.repeatedAction()) {
                            Instance.lastStatus = "Wykryto pętlę — wymagana replanning"
                            delay(300)
                        }
                    }

                    if (!completed) {
                        Instance.lastStatus = "Nie ukończono kroku: ${step.objective}"
                        break@outer
                    }
                }

                if (Instance.awaitingConfirmation == null) {
                    Instance.lastStatus = "MISJA ZAKOŃCZONA / WYMAGA WERYFIKACJI"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Instance.lastStatus = "BŁĄD: ${error.message ?: error::class.simpleName}"
            } finally {
                Instance.running = false
            }
        }
    }

    fun continueAfterConfirmation(approved: Boolean) {
        val pending = Instance.awaitingConfirmation ?: return
        Instance.awaitingConfirmation = null
        if (!approved) {
            Instance.lastStatus = "Akcja wysokiego ryzyka odrzucona"
            return
        }

        if (pending.type.equals("DONE", ignoreCase = true)) {
            Instance.lastStatus = "Potwierdzona"
            return
        }

        val root = rootInActiveWindow ?: run {
            Instance.lastStatus = "Brak aktywnego okna"
            return
        }
        val rawNodes = collect(root)
        val ok = executor.execute(pending, rawNodes)
        rawNodes.forEach { it.recycle() }
        Instance.lastStatus = if (ok) "Potwierdzona akcja wykonana" else "Potwierdzona akcja nieudana"
    }

    private fun currentPackage(): String = rootInActiveWindow?.packageName?.toString()
        ?: Instance.packageName

    private fun collect(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isClickable || node.isEditable ||
                !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            ) out += AccessibilityNodeInfo.obtain(node)

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    walk(child)
                    child.recycle()
                }
            }
        }
        walk(root)
        return out
    }

    override fun onDestroy() {
        scope.cancel()
        if (Instance.service === this) Instance.service = null
        super.onDestroy()
    }

    companion object {
        private const val MAX_ATTEMPTS_PER_STEP = 10
    }

    object Instance {
        @Volatile var service: OmniAccessibilityService? = null
        @Volatile var packageName: String = ""
        @Volatile var running: Boolean = false
        @Volatile var lastStatus: String = "Gotowy"
        @Volatile var lastAction: Action? = null
        @Volatile var awaitingConfirmation: Action? = null
    }
}
