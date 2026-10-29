package dev.leverage.skin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class YggdrasilKeys {
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public YggdrasilKeys(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path privatePath = root.resolve("private-key.der");
        Path publicPath = root.resolve("public-key.der");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            if (Files.isRegularFile(privatePath) && Files.isRegularFile(publicPath)) {
                privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                        Files.readAllBytes(privatePath)));
                publicKey = factory.generatePublic(new X509EncodedKeySpec(
                        Files.readAllBytes(publicPath)));
            } else {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                privateKey = pair.getPrivate();
                publicKey = pair.getPublic();
                writeAtomically(privatePath, privateKey.getEncoded());
                writeAtomically(publicPath, publicKey.getEncoded());
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("No se pudo cargar la clave de firma Yggdrasil.",
                    exception);
        }
    }

    public String sign(String value) {
        try {
            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(privateKey);
            signer.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo firmar el perfil de Minecraft.",
                    exception);
        }
    }

    public String publicKeyPem() {
        String body = Base64.getMimeEncoder(64, new byte[] {
            '\n'
        }).encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(),
                target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, data);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
