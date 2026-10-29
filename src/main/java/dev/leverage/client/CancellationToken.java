package dev.leverage.client;

import static dev.leverage.i18n.I18n.t;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

// Cancellation is cooperative and never removes previously verified cache files.
public final class CancellationToken {
    public static final CancellationToken NONE = new CancellationToken(false);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final boolean mutable;
    public CancellationToken() { this(true); }
    private CancellationToken(boolean mutable) { this.mutable = mutable; }
    public void cancel() { if (mutable) cancelled.set(true); }
    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }
    public void throwIfCancelled() throws InterruptedIOException {
        if (isCancelled()) throw new InterruptedIOException(t("artifact.cancelled"));
    }
}
