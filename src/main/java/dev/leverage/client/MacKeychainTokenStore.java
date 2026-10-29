package dev.leverage.client;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static dev.leverage.i18n.I18n.t;

// Store secrets in macOS Keychain, never in process arguments.
final class MacKeychainTokenStore implements RefreshTokenStore {
    private static final int NOT_FOUND = -25300, DUPLICATE = -25299;
    private final String service;

    MacKeychainTokenStore(String service) { this.service = service; }

    @Override public void write(String id, String token) throws IOException {
        RefreshTokenStores.validateId(id);
        Api api = api();
        byte[] clear = token.getBytes(StandardCharsets.UTF_8);
        try (Query query = query(api, id); Query update = new Query(api)) {
            Pointer data = update.data(clear);
            update.put("kSecValueData", data);
            int status = api.security.SecItemUpdate(query.dictionary, update.dictionary);
            if (status == NOT_FOUND) {
                query.put("kSecValueData", data);
                status = api.security.SecItemAdd(query.dictionary, null);
                if (status == DUPLICATE) {
                    // A second launcher may have inserted the same entry in the meantime.
                    try (Query retry = query(api, id)) {
                        status = api.security.SecItemUpdate(retry.dictionary,
                                update.dictionary);
                    }
                }
            }
            check(status);
        } finally { Arrays.fill(clear, (byte) 0); }
    }

    @Override public Optional<String> read(String id) throws IOException {
        RefreshTokenStores.validateId(id);
        Api api = api();
        PointerByReference result = new PointerByReference();
        try (Query query = query(api, id)) {
            query.put("kSecReturnData", api.coreLibrary.getGlobalVariableAddress(
                    "kCFBooleanTrue").getPointer(0));
            int status = api.security.SecItemCopyMatching(query.dictionary, result);
            if (status == NOT_FOUND) return Optional.empty();
            check(status);
            Pointer data = result.getValue();
            if (data == null || !api.core.CFGetTypeID(data).equals(
                    api.core.CFDataGetTypeID())) throw new IOException(
                    t("credentials.secureUnavailable"));
            long length = api.core.CFDataGetLength(data).longValue();
            if (length <= 0 || length > 16384) throw new IOException(
                    t("credentials.secureUnavailable"));
            byte[] clear = api.core.CFDataGetBytePtr(data).getByteArray(0, (int) length);
            try { return Optional.of(new String(clear, StandardCharsets.UTF_8)); }
            finally { Arrays.fill(clear, (byte) 0); }
        } finally {
            if (result.getValue() != null) api.core.CFRelease(result.getValue());
        }
    }

    @Override public void delete(String id) throws IOException {
        RefreshTokenStores.validateId(id);
        Api api = api();
        try (Query query = query(api, id)) {
            int status = api.security.SecItemDelete(query.dictionary);
            if (status != NOT_FOUND) check(status);
        }
    }

    private Query query(Api api, String id) throws IOException {
        Query query = new Query(api);
        try {
            query.put("kSecClass", api.key("kSecClassGenericPassword"));
            query.put("kSecAttrService", query.string(service));
            query.put("kSecAttrAccount", query.string(id));
            return query;
        } catch (IOException | RuntimeException | Error failure) {
            query.close();
            throw failure;
        }
    }

    private static void check(int status) throws IOException {
        if (status != 0) throw new IOException(t("credentials.keychainFailure", status));
    }

    private static Api api() throws IOException {
        try { return Holder.API; }
        catch (LinkageError failure) {
            throw new IOException(t("credentials.secureUnavailable"), failure);
        }
    }
    private static final class Holder { static final Api API = new Api(); }
    private static final class Api {
        final NativeLibrary securityLibrary = NativeLibrary.getInstance("Security");
        final NativeLibrary coreLibrary = NativeLibrary.getInstance("CoreFoundation");
        final Security security = Native.load("Security", Security.class);
        final Core core = Native.load("CoreFoundation", Core.class,
                Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
        Pointer key(String name) {
            return securityLibrary.getGlobalVariableAddress(name).getPointer(0);
        }
    }

    // NULL callbacks borrow pointers; Query owns them through the native call.
    private static final class Query implements AutoCloseable {
        final Api api;
        final Pointer dictionary;
        final List<Pointer> owned = new ArrayList<>();
        Query(Api api) throws IOException {
            this.api = api;
            dictionary = api.core.CFDictionaryCreateMutable(null,
                    new NativeLong(0), null, null);
            if (dictionary == null) throw new IOException(t(
                    "credentials.secureUnavailable"));
        }
        void put(String key, Pointer value) {
            api.core.CFDictionarySetValue(dictionary, api.key(key), value);
        }
        Pointer string(String value) throws IOException {
            return own(api.core.CFStringCreateWithCString(null, value, 0x08000100));
        }
        Pointer data(byte[] value) throws IOException {
            try (Memory memory = new Memory(value.length)) {
                memory.write(0, value, 0, value.length);
                try {
                    return own(api.core.CFDataCreate(null, memory,
                            new NativeLong(value.length)));
                } finally { memory.clear(); }
            }
        }
        Pointer own(Pointer value) throws IOException {
            if (value == null) throw new IOException(t("credentials.secureUnavailable"));
            owned.add(value);
            return value;
        }
        @Override public void close() {
            api.core.CFRelease(dictionary);
            for (Pointer value : owned) api.core.CFRelease(value);
        }
    }

    public interface Security extends Library {
        int SecItemAdd(Pointer attributes, PointerByReference result);
        int SecItemUpdate(Pointer query, Pointer attributes);
        int SecItemCopyMatching(Pointer query, PointerByReference result);
        int SecItemDelete(Pointer query);
    }
    public interface Core extends Library {
        Pointer CFDictionaryCreateMutable(Pointer allocator,
                NativeLong capacity, Pointer keys, Pointer values);
        void CFDictionarySetValue(Pointer dictionary, Pointer key, Pointer value);
        Pointer CFStringCreateWithCString(Pointer allocator, String value, int encoding);
        Pointer CFDataCreate(Pointer allocator, Pointer bytes, NativeLong length);
        NativeLong CFDataGetLength(Pointer data);
        Pointer CFDataGetBytePtr(Pointer data);
        NativeLong CFGetTypeID(Pointer value);
        NativeLong CFDataGetTypeID();
        void CFRelease(Pointer value);
    }
}
