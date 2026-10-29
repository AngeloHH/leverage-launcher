package dev.leverage.ui;

import org.junit.jupiter.api.Test;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class DesktopNotificationsTest {
    @Test void sendsInfoAndErrorsIgnoresEmptyMessagesAndDeduplicatesRapidRepeats(
            ) throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var sink=new FakeSink();
            var clock=new AtomicLong();
            var notifications=new DesktopNotifications(sink, clock::get);
            notifications.show(null, false);
            notifications.show(" ", false);
            assertTrue(sink.messages.isEmpty());
            notifications.show("Guardado", false);
            notifications.show("Guardado", false);
            assertEquals(List.of("Guardado"), sink.messages);
            notifications.show("Guardado", true);
            assertEquals(List.of(false, true), sink.errors);
            clock.set(4_000_000_000L);
            notifications.show("Guardado", true);
            assertEquals(3, sink.messages.size());
            notifications.close();
            notifications.close();
            assertEquals(1, sink.closed);
            notifications.show("Después de cerrar", true);
            assertEquals(3, sink.messages.size());
        });
    }

    @Test void unavailableNativeNotificationsDoNotThrowOrCreateAnInAppFallback(
            ) throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var sink=new FakeSink();
            sink.fail=true;
            var notifications=new DesktopNotifications(sink, ()->0L);
            assertDoesNotThrow(()->notifications.show("Error", true));
            assertDoesNotThrow(()->notifications.show("Otro error", true));
            assertEquals(1, sink.attempts);
            var hiddenOwner=new JPanel();
            assertDoesNotThrow(()->new DesktopNotifications(
                    hiddenOwner).show("Invisible", false));
            assertEquals(0, hiddenOwner.getComponentCount());
        });
    }

    @Test void retriesMessagesNotDeliveredWhileTheHomeWindowWasHidden() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var sink=new FakeSink();
            sink.ready=false;
            var notifications=new DesktopNotifications(sink, ()->0L);
            notifications.show("Listo", false);
            sink.ready=true;
            notifications.show("Listo", false);
            assertEquals(2, sink.attempts);
            assertEquals(List.of("Listo"), sink.messages);
        });
    }

    private static final class FakeSink implements DesktopNotifications.Sink {
        final List<String> messages=new ArrayList<>();
        final List<Boolean> errors=new ArrayList<>();
        boolean ready=true, fail;
        int attempts, closed;
        @Override public boolean show(String message, boolean error) throws AWTException {
            attempts++;
            if (fail)throw new AWTException("No hay bandeja del sistema");
            if (!ready)return false;
            messages.add(message);
            errors.add(error);
            return true;
        }
        @Override public void close() { closed++; }
    }
}
