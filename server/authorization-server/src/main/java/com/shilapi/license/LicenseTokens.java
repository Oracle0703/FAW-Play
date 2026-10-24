package com.shilapi.license;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class LicenseTokens {
    private final LicenseProperties config;
    private final Clock clock;
    private final ECPrivateKey privateKey;
    private final ECPublicKey publicKey;
    private final String keyId;

    LicenseTokens(LicenseProperties config, Clock clock) {
        this.config = config; this.clock = clock;
        try {
            byte[] bytes = readPem(config.privateKeyFile(), "PRIVATE KEY");
            privateKey = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(bytes));
            publicKey = Crypto.publicKey(Crypto.encode(readPem(config.publicKeyFile(), "PUBLIC KEY")));
            if (!privateKey.getParams().getOrder().equals(publicKey.getParams().getOrder())) throw new IllegalArgumentException("Signing key must use P-256");
            byte[] test = "license-key-pair-check".getBytes(StandardCharsets.UTF_8);
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey); signature.update(test);
            if (!Crypto.verify(publicKey, test, Crypto.encode(signature.sign()))) throw new IllegalArgumentException("Signing public/private key mismatch");
            keyId = Crypto.sha256(publicKey.getEncoded()).substring(0, 16);
        } catch (Exception e) { throw new IllegalStateException("Configure valid P-256 PKCS8 private and SPKI public key files before starting", e); }
    }

    private static byte[] readPem(String path, String type) throws Exception {
        String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII).trim();
        String begin = "-----BEGIN " + type + "-----", end = "-----END " + type + "-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) throw new IllegalArgumentException("Expected " + type + " PEM");
        return Base64.getDecoder().decode(pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", ""));
    }

    String signUpdateManifest(String encodedManifest) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            signature.update(("FAW-UPDATE-V1\n" + encodedManifest).getBytes(StandardCharsets.UTF_8));
            return Crypto.encode(signature.sign());
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Unable to sign update manifest", e); }
    }

    String issue(String subject, String licenseId, String deviceId, String keyHash, String appId, Long validUntil, String challengeId) {
        return issue(subject, licenseId, deviceId, keyHash, appId, validUntil, challengeId, config.offlineDays());
    }

    String issue(String subject, String licenseId, String deviceId, String keyHash, String appId, Long validUntil, String challengeId, int offlineDays) {
        long now = clock.instant().getEpochSecond();
        long offlineExpiry = now + OfflineLicensePeriod.boundedDays(offlineDays, config.offlineDays()) * 86400L;
        long expiry = validUntil == null ? offlineExpiry : Math.min(validUntil, offlineExpiry);
        if (expiry <= now) throw ApiException.denied("LICENSE_EXPIRED", "Purchase authorization has expired");
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(config.issuer()).audience(appId).subject(subject)
            .claim("license_id", licenseId).claim("device_id", deviceId).claim("device_key_sha256", keyHash)
            .claim("challenge_id", challengeId)
            .claim("features", List.of("hud", "instrument-map", "steering-wheel"))
            .claim("permanent", validUntil == null).claim("purchase_valid_until", validUntil)
            .issueTime(new Date(now * 1000)).notBeforeTime(new Date(now * 1000))
            .expirationTime(new Date(expiry * 1000)).build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID(keyId).build(), claims);
            jwt.sign(new ECDSASigner(privateKey)); return jwt.serialize();
        } catch (JOSEException e) { throw new IllegalStateException("Unable to sign license", e); }
    }

    JWTClaimsSet verify(String encoded) { return verify(encoded, true); }
    JWTClaimsSet verifyForRenew(String encoded) { return verify(encoded, false); }
    private JWTClaimsSet verify(String encoded, boolean requireCurrentValidity) {
        try {
            SignedJWT token = SignedJWT.parse(encoded);
            if (!JWSAlgorithm.ES256.equals(token.getHeader().getAlgorithm()) || !token.verify(new ECDSAVerifier(publicKey))) throw new IllegalArgumentException();
            JWTClaimsSet c = token.getJWTClaimsSet();
            long now = clock.instant().getEpochSecond();
            if (!config.issuer().equals(c.getIssuer()) || c.getAudience().size() != 1 || !config.allowedAppIds().contains(c.getAudience().get(0))
                || c.getSubject() == null || c.getExpirationTime() == null || (requireCurrentValidity && c.getExpirationTime().toInstant().getEpochSecond() <= now)
                || c.getNotBeforeTime() == null || c.getNotBeforeTime().toInstant().getEpochSecond() > now
                || c.getIssueTime() == null || c.getIssueTime().toInstant().getEpochSecond() > now
                || !c.getExpirationTime().after(c.getIssueTime()) || !c.getExpirationTime().after(c.getNotBeforeTime())
                // Lowering issuance policy must not invalidate a previously signed ninety-day ticket.
                || c.getExpirationTime().getTime() - c.getIssueTime().getTime() > OfflineLicensePeriod.MAX_DAYS * 86400000L
                || !(c.getStringListClaim("features").contains("carplay") || c.getStringListClaim("features").containsAll(List.of("hud", "instrument-map", "steering-wheel")))) throw new IllegalArgumentException();
            UUID.fromString(c.getStringClaim("license_id")); UUID.fromString(c.getStringClaim("device_id"));
            UUID.fromString(c.getStringClaim("challenge_id"));
            if (!c.getStringClaim("device_key_sha256").matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            return c;
        } catch (Exception e) { throw new ApiException(401, "INVALID_LICENSE", "License signature, audience or validity is invalid"); }
    }
}
