package dev.leverage.client;

// Worker-thread progress callbacks must marshal UI changes onto the EDT.
@FunctionalInterface public interface ProgressListener {
    ProgressListener NONE = (message, completed, total) -> { };
    void onProgress(String message, long completed, long total);
}
