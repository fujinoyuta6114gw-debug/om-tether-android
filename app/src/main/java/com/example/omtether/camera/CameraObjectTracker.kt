package com.example.omtether.camera

/**
 * Tracks PTP object handles across both card slots.
 *
 * A camera-side shutter can be reported by the interrupt endpoint, by storage polling,
 * or by both. This class de-duplicates those paths and retains a short pending batch so
 * RAW/JPEG companions can be imported together before the Android save policy selects one.
 */
internal class CameraObjectTracker {
    private val known = linkedSetOf<Long>()
    private val pending = linkedSetOf<Long>()
    private val preBaselineEvents = linkedSetOf<Long>()
    private var initialized = false

    @Synchronized
    fun initialize(handles: Iterable<Long>) {
        known.clear()
        known += handles.filter(::isUsableHandle)
        pending.clear()
        preBaselineEvents.clear()
        initialized = true
    }

    /**
     * Records an interrupt-endpoint ObjectAdded notification.
     *
     * The pending item is retained even if the first full storage snapshot has not yet
     * completed; that first snapshot then becomes the baseline without losing the event.
     */
    @Synchronized
    fun recordEvent(handle: Long, queueForImport: Boolean): Boolean {
        if (!isUsableHandle(handle)) return false
        val isNew = known.add(handle)
        if (isNew && queueForImport && !initialized) preBaselineEvents += handle
        return isNew && queueForImport && pending.add(handle)
    }

    /**
     * Observes the union of card 1/card 2 handles. The first successful snapshot is only a
     * baseline, preventing existing card contents from being imported on app launch.
     */
    @Synchronized
    fun observeSnapshot(handles: Iterable<Long>, queueForImport: Boolean): Boolean {
        val usable = handles.filter(::isUsableHandle)
        if (!initialized) {
            var queued = false
            if (queueForImport && preBaselineEvents.isNotEmpty()) {
                // PTP object handles created by one shutter release are normally adjacent.
                // Keep only a very small neighborhood around a real ObjectAdded event; the
                // transfer layer then verifies filename stem/capture time before selecting
                // RAW or JPEG. This recovers a card-2 companion without importing the card's
                // pre-existing contents as the initial baseline.
                usable.forEach { candidate ->
                    val nearEvent = preBaselineEvents.any { event ->
                        unsignedHandleDistance(candidate, event) <= PRE_BASELINE_COMPANION_DISTANCE
                    }
                    if (nearEvent && known.add(candidate)) {
                        queued = pending.add(candidate) || queued
                    }
                }
            }
            known += usable
            preBaselineEvents.clear()
            initialized = true
            return queued
        }
        var queued = false
        usable.forEach { handle ->
            if (known.add(handle) && queueForImport) {
                queued = pending.add(handle) || queued
            }
        }
        return queued
    }

    @Synchronized
    fun markKnown(handles: Iterable<Long>) {
        known += handles.filter(::isUsableHandle)
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun takePending(): List<Long> = pending.toList().also { pending.clear() }

    companion object {
        private const val PRE_BASELINE_COMPANION_DISTANCE = 3L

        private fun isUsableHandle(handle: Long): Boolean =
            handle != 0L && handle != 0xFFFF_FFFFL

        private fun unsignedHandleDistance(first: Long, second: Long): Long {
            val a = first and 0xFFFF_FFFFL
            val b = second and 0xFFFF_FFFFL
            return if (a >= b) a - b else b - a
        }
    }
}
