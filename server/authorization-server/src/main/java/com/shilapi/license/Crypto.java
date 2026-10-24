package com.shilapi.license;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

final class Crypto {
    static final SecureRandom RANDOM = new SecureRandom();
    private Crypto() {}
    static byte[] decode(String value) {
        try { return Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException e) { throw ApiException.invalid("Invalid base64url value"); }
    }
    static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static String hashText(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    static String deviceFingerprint(String keyHash) { return keyHash.substring(0, 16).toUpperCase(Locale.ROOT); }
    static ECPublicKey publicKey(String encoded) {
        try {
            byte[] bytes = decode(encoded);
            if (bytes.length > 200) throw ApiException.invalid("Invalid device key");
            ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(bytes));
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec p256 = parameters.getParameterSpec(ECParameterSpec.class);
            ECParameterSpec actual = key.getParams();
            if (!actual.getCurve().equals(p256.getCurve()) || !actual.getGenerator().equals(p256.getGenerator())
                || !actual.getOrder().equals(p256.getOrder()) || actual.getCofactor() != p256.getCofactor()
                || !MessageDigest.isEqual(bytes, key.getEncoded())) throw ApiException.invalid("Only canonical P-256 SPKI keys are supported");
            return key;
        } catch (GeneralSecurityException | ClassCastException e) { throw ApiException.invalid("Invalid P-256 public key"); }
    }
    static byte[] proof(String purpose, String nonce, String appId, String keyHash, String payload) {
        return ("FAW-LICENSE-V1\n" + purpose + "\n" + nonce + "\n" + appId + "\n" + keyHash + "\n" + hashText(payload))
            .getBytes(StandardCharsets.UTF_8);
    }
    static boolean verify(ECPublicKey key, byte[] proof, String encodedSignature) {
        try {
            byte[] bytes = decode(encodedSignature);
            if (bytes.length < 8 || bytes.length > 80) return false;
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(key); signature.update(proof); return signature.verify(bytes);
        } catch (GeneralSecurityException | ApiException e) { return false; }
    }
    static String randomToken(int bytes) { byte[] value = new byte[bytes]; RANDOM.nextBytes(value); return encode(value); }
}
