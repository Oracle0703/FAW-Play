package com.shilapi.license;

import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkUtils;
import com.android.apksig.internal.apk.AndroidBinXmlParser;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Metadata and signature are read from the uploaded bytes, never from browser fields. */
final class ApkInspection {
    record Details(String packageName, int versionCode, String versionName, int minSdk, String signingCertSha256) {}
    static Details inspect(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null || entry.getSize() < 1 || entry.getSize() > 8 * 1024 * 1024) throw invalid();
            byte[] bytes;
            try (var input = zip.getInputStream(entry)) {
                bytes = input.readNBytes(8 * 1024 * 1024 + 1);
                if (bytes.length != entry.getSize()) throw invalid();
            }
            ByteBuffer manifest = ByteBuffer.wrap(bytes);
            String app = ApkUtils.getPackageNameFromBinaryAndroidManifest(manifest.duplicate());
            long version = ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(manifest.duplicate());
            int minimum = ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(manifest.duplicate());
            if (version < 1 || version > Integer.MAX_VALUE || minimum < 28
                || ApkUtils.getDebuggableFromBinaryAndroidManifest(manifest.duplicate())) throw invalid();
            // apksig's bounded AXML reader supplies the versionName missing from ApkUtils's public helpers.
            var xml = new AndroidBinXmlParser(manifest.duplicate());
            String name = null;
            while (xml.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
                if (xml.getEventType() != AndroidBinXmlParser.EVENT_START_ELEMENT) continue;
                if ("uses-split".equals(xml.getName())) throw invalid();
                if (xml.getDepth() != 1 || !"manifest".equals(xml.getName())) continue;
                for (int i = 0; i < xml.getAttributeCount(); i++) {
                    String ns = xml.getAttributeNamespace(i), attr = xml.getAttributeName(i);
                    if ("split".equals(attr) && (ns == null || ns.isEmpty()) && !xml.getAttributeStringValue(i).isEmpty()) throw invalid();
                    if (!"http://schemas.android.com/apk/res/android".equals(ns)) continue;
                    if ("isFeatureSplit".equals(attr) && xml.getAttributeBooleanValue(i)) throw invalid();
                    if ("versionName".equals(attr) && xml.getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_STRING)
                        name = xml.getAttributeStringValue(i);
                }
            }
            if (name == null || name.isBlank() || name.length() > 128) throw invalid();
            var verified = new ApkVerifier.Builder(file.toFile()).build().verify();
            if (!verified.isVerified() || verified.getSignerCertificates().size() != 1) throw invalid();
            return new Details(app, (int) version, name, minimum, Crypto.sha256(verified.getSignerCertificates().get(0).getEncoded()));
        } catch (Exception e) {
            if (e instanceof ApiException api) throw api;
            throw invalid();
        }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_APK", "Upload a signed, non-debuggable standalone release APK with numeric version and minSdk >= 28"); }
}
