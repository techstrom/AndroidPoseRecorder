package jp.example.poserecorder

class PlaybackClock {
    private var offset = 0L
    private var anchor = 0L
    var running = false
        private set
    fun position(now: Long) = offset + if (running) (now - anchor).coerceAtLeast(0) else 0
    fun resume(now: Long) { if (!running) { anchor = now; running = true } }
    fun pause(now: Long) { offset = position(now); running = false }
    fun reset() { offset = 0; running = false }
}
