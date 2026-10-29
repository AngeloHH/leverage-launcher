package dev.leverage.ui;

import dev.leverage.client.LauncherAuthClient;
import dev.leverage.client.LauncherUsersClient;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class UsersPanelTest {
    private static final URI HOST = URI.create("http://127.0.0.1:9000");

    @Test void administratorLoadsRealPagesOffEdtWithTwentyFiveByDefault(
            ) throws Exception {
        ControlledLoader loader = new ControlledLoader();
        UsersPanel panel = create(loader);
        onEdt(() -> panel.setSession(HOST, session("ADMIN", "fixture-token")));
        Call call = loader.next();
        assertEquals(HOST, call.host());
        assertEquals("fixture-token", call.token());
        assertEquals("", call.query());
        assertEquals(1, call.page());
        assertEquals(25, call.pageSize());
        assertFalse(call.onEdt());
        CompletableFuture<Void> completion = pending(panel);
        onEdt(() -> {
            assertEquals("Cargando usuarios…", named(panel,
                    "users-message", JLabel.class).getText());
            assertFalse(named(panel, "users-next", JButton.class).isEnabled());
            JComboBox<?> size = named(panel, "users-page-size", JComboBox.class);
            assertEquals(List.of(10, 25, 50, 100), java.util.stream.IntStream.range(0,
                    size.getItemCount()).mapToObj(size::getItemAt).toList());
            assertEquals(25, size.getSelectedItem());
        });
        call.response().complete(page(1, 25, 26, user("Angelo")));
        await(completion);
        onEdt(() -> {
            assertEquals(1, countRows(panel));
            assertEquals("1 / 2", named(panel, "users-page-status",
                    JLabel.class).getText());
            assertTrue(named(panel, "users-next", JButton.class).isEnabled());
            assertFalse(named(panel, "users-previous", JButton.class).isEnabled());
            assertEquals("1–25 de 26 usuarios", named(panel,
                    "users-total-status", JLabel.class).getText());
            assertEquals(JourneyStyle.INK, panel.getBackground());
        });
    }

    @Test void searchEnterButtonsAndPageSizeUseServerPaginationAndResetPage(
            ) throws Exception {
        ControlledLoader loader = new ControlledLoader();
        UsersPanel panel = create(loader);
        onEdt(() -> panel.setSession(HOST, session("ADMIN", "first")));
        complete(panel, loader.next(), page(1, 25, 80, user("First")));
        onEdt(() -> named(panel, "users-next", JButton.class).doClick(0));
        Call second = loader.next();
        assertEquals(2, second.page());
        complete(panel, second, page(2, 25, 80, user("Second")));
        onEdt(() -> named(panel, "users-previous", JButton.class).doClick(0));
        Call previous = loader.next();
        assertEquals(1, previous.page());
        complete(panel, previous, page(1, 25, 80, user("First")));
        onEdt(() -> {
            JTextField input = named(panel, "users-search-input", JTextField.class);
            input.setText("  Weenda  ");
            input.postActionEvent();
        });
        Call search = loader.next();
        assertEquals("Weenda", search.query());
        assertEquals(1, search.page());
        complete(panel, search, page(1, 25, 40, user("Weenda")));
        onEdt(() -> named(panel, "users-page-size", JComboBox.class).setSelectedItem(50));
        Call resized = loader.next();
        assertEquals(50, resized.pageSize());
        assertEquals(1, resized.page());
        assertEquals("Weenda", resized.query());
        complete(panel, resized, page(1, 50, 40, user("Weenda")));
        onEdt(() -> {
            assertFalse(named(panel, "users-next", JButton.class).isEnabled());
            assertEquals("1–40 de 40 usuarios", named(panel,
                    "users-total-status", JLabel.class).getText());
        });
    }

    @Test void logoutAndNonAdministratorsCannotFetchOrReceiveAnOldResponse(
            ) throws Exception {
        ControlledLoader loader = new ControlledLoader();
        UsersPanel panel = create(loader);
        onEdt(() -> panel.setSession(HOST, session("USER", "non-admin")));
        assertTrue(loader.calls.isEmpty());
        onEdt(() -> {
            assertFalse(named(panel, "users-search", JButton.class).isEnabled());
            panel.setSession(HOST, session("ADMIN", "admin"));
        });
        Call old = loader.next();
        CompletableFuture<Void> completion = pending(panel);
        onEdt(panel::clearSession);
        old.response().complete(page(1, 25, 1, user("Private old account")));
        await(completion);
        onEdt(() -> {
            assertEquals(0, countRows(panel));
            assertEquals("Disponible solo para administradores.", named(panel,
                    "users-message", JLabel.class).getText());
            assertEquals("", named(panel, "users-search-input",
                    JTextField.class).getText());
            assertFalse(named(panel, "users-page-size", JComboBox.class).isEnabled());
        });
    }

    @Test void serverSwitchAndNewSearchDiscardOldResultsAndErrors() throws Exception {
        ControlledLoader loader = new ControlledLoader();
        UsersPanel panel = create(loader);
        onEdt(() -> panel.setSession(HOST, session("ADMIN", "old")));
        Call old = loader.next();
        CompletableFuture<Void> oldCompletion = pending(panel);
        URI other = URI.create("http://127.0.0.1:9001");
        onEdt(() -> panel.setSession(other, session("ADMIN", "new")));
        Call changed = loader.next();
        assertEquals(other, changed.host());
        assertEquals("new", changed.token());
        complete(panel, changed, page(1, 25, 1, user("Current")));
        old.response().completeExceptionally(new IOException(
                "old token must never be displayed"));
        await(oldCompletion);
        onEdt(() -> {
            assertEquals("Current", named(panel, "users-row-name",
                    JLabel.class).getText());
            named(panel, "users-search-input", JTextField.class).setText("first query");
            named(panel, "users-search", JButton.class).doClick(0);
        });
        Call first = loader.next();
        CompletableFuture<Void> firstCompletion = pending(panel);
        onEdt(() -> {
            named(panel, "users-search-input", JTextField.class).setText("latest query");
            named(panel, "users-search", JButton.class).doClick(0);
        });
        Call latest = loader.next();
        complete(panel, latest, page(1, 25, 1, user("Latest")));
        first.response().complete(page(1, 25, 1, user("Stale")));
        await(firstCompletion);
        onEdt(() -> assertEquals("Latest", named(panel,
                "users-row-name", JLabel.class).getText()));
    }

    @Test void errorsAndEmptySearchHaveNoFakeRowsAndCanBeRetried() throws Exception {
        ControlledLoader loader = new ControlledLoader();
        UsersPanel panel = create(loader);
        onEdt(() -> panel.setSession(HOST, session("ADMIN", "token")));
        Call failure = loader.next();
        CompletableFuture<Void> completion = pending(panel);
        failure.response().completeExceptionally(new IOException(
                "secret-sensitive-detail"));
        await(completion);
        onEdt(() -> {
            assertEquals(0, countRows(panel));
            assertTrue(named(panel, "users-message",
                    JLabel.class).getText().startsWith("No se pudo cargar"));
            assertFalse(named(panel, "users-message",
                    JLabel.class).getText().contains("secret"));
            named(panel, "users-search-input", JTextField.class).setText("Nobody");
            named(panel, "users-search", JButton.class).doClick(0);
        });
        complete(panel, loader.next(), page(1, 25, 0));
        onEdt(() -> {
            assertEquals("No se encontraron usuarios.", named(panel,
                    "users-message", JLabel.class).getText());
            assertEquals("0 usuarios", named(panel, "users-total-status",
                    JLabel.class).getText());
            assertFalse(named(panel, "users-next", JButton.class).isEnabled());
        });
    }

    @Test void rowsAndControlsKeepModTypographyWithoutOverlapsOrHtmlRendering(
            ) throws Exception {
        onEdt(() -> {
            var row = new UsersPanel.UserRow(new LauncherUsersClient.User(
                    UUID.randomUUID(), "<html>Long administrator name",
                    "<html>mail@example.test", "ADMIN", "ACTIVE"));
            assertEquals(LauncherFonts.minecraftTen(20), row.username.getFont());
            assertEquals(LauncherFonts.minecraftFive(12), row.role.getFont());
            assertEquals(LauncherFonts.minecraftSeven(14), row.email.getFont());
            assertEquals("Administrador", row.role.getText());
            assertEquals("Activo", row.status.getText());
            assertEquals(Boolean.TRUE, row.username.getClientProperty("html.disable"));
            assertEquals("Moderador", new UsersPanel.UserRow(new LauncherUsersClient.User(
                    UUID.randomUUID(), "Helper", "helper@example.test",
                    "MODERATOR", "ACTIVE")).role.getText());
            for (int width : new int[] { 360, 600, 1000 }) {
                row.setSize(width, width<550?216:176);
                row.doLayout();
                assertEquals(row.username.getX(), row.role.getX());
                assertTrue(row.role.getY()>=row.username.getY()+row.username.getHeight());
                assertTrue(row.email.getX()>=row.username.getX()+row.username.getWidth());
                assertEquals(row.email.getY(), row.created.getY());
                assertEquals(row.created.getY(), row.lastSeen.getY());
                assertEquals(row.lastSeen.getY(), row.status.getY());
                assertTrue(row.status.getX()+row.status.getWidth()<=width);
                assertEquals("Sin registro", row.created.getText());
                assertEquals("Sin registro", row.lastSeen.getText());
                for (UsersPanel.Action action:UsersPanel.Action.values()) {
                    JButton control=row.button(action);
                    assertTrue(control.getY()+control.getHeight()<=row.getHeight());
                    assertTrue(control.getX()+control.getWidth()<=width);
                    assertFalse(control.isEnabled());
                }
            }
            UsersPanel panel = new UsersPanel((host, token, query,
                    page, size)->page(1, size, 0));
            for (int width : new int[] { 360, 600, 1000 }) {
                panel.setSize(width, 400);
                JourneyStyle.layoutTree(panel);
                var input=named(panel, "users-search-input", JTextField.class);
                var button=named(panel, "users-search", JButton.class);
                assertTrue(input.getX()+input.getWidth()<button.getX());
                assertTrue(button.getX()+button.getWidth()<=width);
                var previous=named(panel, "users-previous", JButton.class);
                var next=named(panel, "users-next", JButton.class);
                var status=named(panel, "users-page-status", JLabel.class);
                assertTrue(previous.getX()+previous.getWidth()<status.getX());
                assertTrue(status.getX()+status.getWidth()<next.getX());
                assertTrue(next.getX()+next.getWidth()<=next.getParent().getWidth());
                assertNotNull(input.getAccessibleContext().getAccessibleName());
            }
        });
    }

    @Test void searchAndPaginatorStayVisibleWhileOnlyTheUsersScroll() throws Exception {
        AtomicReference<UsersPanel> reference=new AtomicReference<>();
        var users=java.util.stream.IntStream.range(0,
                25).mapToObj(index->user("Player"+index)).toList();
        onEdt(()-> {
            UsersPanel panel=new UsersPanel((host, token, query, page,
                    size)->new LauncherUsersClient.Page(users, 1, 25, 25, 1));
            reference.set(panel);
            panel.setSession(HOST, session("ADMIN", "token"));
        });
        UsersPanel panel=reference.get();
        await(pending(panel));
        onEdt(()-> {
            panel.setSize(900, 500);
            JourneyStyle.layoutTree(panel);
            JScrollPane scroll=named(panel, "users-list-scroll", JScrollPane.class);
            var input=named(panel, "users-search-input", JTextField.class);
            var next=named(panel, "users-next", JButton.class);
            Point inputBefore=SwingUtilities.convertPoint(input, new Point(), panel);
            Point nextBefore=SwingUtilities.convertPoint(next, new Point(), panel);
            assertTrue(inputBefore.y+input.getHeight()<=scroll.getY());
            assertTrue(nextBefore.y>=scroll.getY()+scroll.getHeight());
            assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                    scroll.getHorizontalScrollBarPolicy());
            assertEquals(scroll.getViewport().getWidth(),
                    scroll.getViewport().getView().getWidth());
            scroll.getViewport().setViewPosition(new Point(0, 1500));
            JourneyStyle.layoutTree(panel);
            assertTrue(scroll.getViewport().getViewPosition().y>0);
            assertEquals(inputBefore, SwingUtilities.convertPoint(input,
                    new Point(), panel));
            assertEquals(nextBefore, SwingUtilities.convertPoint(next,
                    new Point(), panel));
            assertTrue(nextBefore.y+next.getHeight()<=panel.getHeight());
        });
    }

    private static UsersPanel create(ControlledLoader loader) throws Exception {
        AtomicReference<UsersPanel> ref=new AtomicReference<>();
        onEdt(()->ref.set(new UsersPanel(loader)));
        return ref.get();
    }
    private static CompletableFuture<Void> pending(UsersPanel panel) throws Exception {
        AtomicReference<CompletableFuture<Void>> ref=new AtomicReference<>();
        onEdt(()->ref.set(panel.pendingLoad()));
        return ref.get();
    }
    private static void complete(UsersPanel panel, Call call,
            LauncherUsersClient.Page result) throws Exception {
        CompletableFuture<Void> completion=pending(panel);
        call.response().complete(result);
        await(completion);
    }
    private static void await(CompletableFuture<Void> completion) throws Exception {
        completion.get(10, TimeUnit.SECONDS);
    }
    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }
    private static LauncherAuthClient.Session session(String role, String token) {
        return new LauncherAuthClient.Session(token, Instant.now().plusSeconds(600),
                null, null, "device", UUID.randomUUID(), "Admin", role);
    }
    private static LauncherUsersClient.User user(String name) {
        return new LauncherUsersClient.User(UUID.randomUUID(), name,
                name+"@example.test", "USER", "ACTIVE");
    }
    private static LauncherUsersClient.Page page(int page, int size,
            long total, LauncherUsersClient.User... users) {
        return new LauncherUsersClient.Page(List.of(users), page, size,
                total, Math.max(1, (int)((total+size-1)/size)));
    }
    private static int countRows(Container parent) {
        int count=0;
        for (Component child:parent.getComponents()) {
            if (child instanceof UsersPanel.UserRow) count++;
            else if (child instanceof Container nested) count+=countRows(nested);
        } return count; }
    private static <T extends Component> T named(Container parent,
            String name, Class<T> type) {
        for (Component child:parent.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(
                    child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found=find(nested, name, type);
                if (found!=null)return found;
            }
        } throw new AssertionError("Missing component: "+name);
    }
    private static <T extends Component> T find(Container parent,
            String name, Class<T> type) {
        for (Component child:parent.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(
                    child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found=find(nested, name, type);
                if (found!=null)return found;
            }
        } return null;
    }
    private record Call(URI host, String token, String query, int page, int pageSize,
            boolean onEdt, CompletableFuture<LauncherUsersClient.Page> response) { }
    private static final class ControlledLoader implements UsersPanel.QueryLoader {
        final BlockingQueue<Call> calls=new LinkedBlockingQueue<>();
        @Override public LauncherUsersClient.Page search(URI host,
                String token, String query, int page,
                int pageSize) throws IOException, InterruptedException {
            CompletableFuture<LauncherUsersClient.Page> response
                    =new CompletableFuture<>();
            calls.add(new Call(host, token, query, page, pageSize,
                    SwingUtilities.isEventDispatchThread(), response));
            try { return response.get(10, TimeUnit.SECONDS); }
            catch (ExecutionException|TimeoutException failure) {
                throw new IOException(failure);
            }
        }
        Call next() throws InterruptedException {
            Call call=calls.poll(10, TimeUnit.SECONDS);
            assertNotNull(call, "Expected server query");
            return call;
        }
    }
}
