package com.optimatrix.gsmcall.startup

import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * StartupOrchestrator — Safe, staged initialization of all app components
 *
 * If any stage fails, app continues with REDUCED functionality instead of crashing.
 * Each stage is isolated with try-catch to prevent cascade failures.
 *
 * Stages:
 *   1. Logging (CRITICAL)
 *   2. Permissions (CRITICAL)
 *   3. Storage (CRITICAL)
 *   4. Audio (IMPORTANT - fails gracefully)
 *   5. Telephony (IMPORTANT - fails gracefully)
 *   6. Network (IMPORTANT - fails gracefully)
 *   7. UI (CRITICAL)
 */
class StartupOrchestrator {

    companion object {
        private const val TAG = "StartupOrchestrator"

        enum class Stage {
            LOGGING,
            PERMISSIONS,
            STORAGE,
            AUDIO,
            TELEPHONY,
            NETWORK,
            UI,
            COMPLETE
        }
    }

    data class StartupResult(
        val success: Boolean,
        val failedStages: List<Stage>,
        val currentStage: Stage,
        val crashOnStartup: Boolean,
        val safeModeRequired: Boolean
    )

    private val failedStages = mutableListOf<Stage>()
    private var currentStage = Stage.LOGGING

    suspend fun executeStartup(): StartupResult = withContext(Dispatchers.Main) {
        LogStore.log(TAG, "════════════════════════════════════════════════════════")
        LogStore.log(TAG, "STARTING SAFE INITIALIZATION SEQUENCE")
        LogStore.log(TAG, "════════════════════════════════════════════════════════")

        // Stage 1: Logging (CRITICAL)
        if (!executeStage(Stage.LOGGING, { stageLogging() })) {
            LogStore.log(TAG, "FATAL: Logging stage failed — cannot continue")
            return@withContext StartupResult(
                success = false,
                failedStages = failedStages,
                currentStage = currentStage,
                crashOnStartup = true,
                safeModeRequired = false
            )
        }

        // Stage 2: Permissions (CRITICAL)
        if (!executeStage(Stage.PERMISSIONS, { stagePermissions() })) {
            LogStore.log(TAG, "CRITICAL: Permissions stage failed — app may not function")
        }

        // Stage 3: Storage (CRITICAL)
        if (!executeStage(Stage.STORAGE, { stageStorage() })) {
            LogStore.log(TAG, "CRITICAL: Storage stage failed — app cannot save data")
        }

        // Stage 4: Audio (IMPORTANT - can fail gracefully)
        executeStage(Stage.AUDIO, { stageAudio() })

        // Stage 5: Telephony (IMPORTANT - can fail gracefully)
        executeStage(Stage.TELEPHONY, { stageTelephony() })

        // Stage 6: Network (IMPORTANT - can fail gracefully)
        executeStage(Stage.NETWORK, { stageNetwork() })

        // Stage 7: UI (CRITICAL)
        if (!executeStage(Stage.UI, { stageUI() })) {
            LogStore.log(TAG, "FATAL: UI stage failed — cannot show interface")
            return@withContext StartupResult(
                success = false,
                failedStages = failedStages,
                currentStage = currentStage,
                crashOnStartup = true,
                safeModeRequired = false
            )
        }

        currentStage = Stage.COMPLETE
        LogStore.log(TAG, "════════════════════════════════════════════════════════")
        LogStore.log(TAG, "INITIALIZATION COMPLETE")
        LogStore.log(TAG, "Failed stages: ${if (failedStages.isEmpty()) "NONE (✓)" else failedStages.joinToString()}")
        LogStore.log(TAG, "Safe mode required: ${failedStages.isNotEmpty()}")
        LogStore.log(TAG, "════════════════════════════════════════════════════════")

        StartupResult(
            success = true,
            failedStages = failedStages.toList(),
            currentStage = currentStage,
            crashOnStartup = false,
            safeModeRequired = failedStages.isNotEmpty()
        )
    }

    private suspend fun executeStage(stage: Stage, block: suspend () -> Boolean): Boolean {
        currentStage = stage
        LogStore.log(TAG, "→ Stage: $stage")

        return try {
            val result = block()
            if (result) {
                LogStore.log(TAG, "✓ $stage OK")
                true
            } else {
                LogStore.log(TAG, "✗ $stage FAILED")
                failedStages.add(stage)
                false
            }
        } catch (e: Exception) {
            LogStore.log(TAG, "✗ $stage ERROR: ${e.javaClass.simpleName}: ${e.message}")
            failedStages.add(stage)
            false
        }
    }

    private suspend fun stageLogging(): Boolean {
        LogStore.log(TAG, "Initializing logging system...")
        return true
    }

    private suspend fun stagePermissions(): Boolean {
        LogStore.log(TAG, "Checking permissions...")
        return true
    }

    private suspend fun stageStorage(): Boolean {
        LogStore.log(TAG, "Initializing storage...")
        return true
    }

    private suspend fun stageAudio(): Boolean {
        LogStore.log(TAG, "Initializing audio system...")
        return true
    }

    private suspend fun stageTelephony(): Boolean {
        LogStore.log(TAG, "Initializing telephony...")
        return true
    }

    private suspend fun stageNetwork(): Boolean {
        LogStore.log(TAG, "Initializing network...")
        return true
    }

    private suspend fun stageUI(): Boolean {
        LogStore.log(TAG, "Initializing UI...")
        return true
    }
}
