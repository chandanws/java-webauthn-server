package com.yubico.webauthn.attestation.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.yubico.internal.util.CertificateParser;
import com.yubico.internal.util.WebAuthnCodecs;
import com.yubico.webauthn.attestation.Attestation;
import com.yubico.webauthn.attestation.AttestationResolver;
import com.yubico.webauthn.attestation.DeviceMatcher;
import com.yubico.webauthn.attestation.MetadataObject;
import com.yubico.webauthn.attestation.Transport;
import com.yubico.webauthn.attestation.matcher.ExtensionMatcher;
import com.yubico.webauthn.attestation.matcher.FingerprintMatcher;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;


public class SimpleAttestationResolver implements AttestationResolver {

    private static final String SELECTORS = "selectors";
    private static final String SELECTOR_TYPE = "type";
    private static final String SELECTOR_PARAMETERS = "parameters";

    private static final String TRANSPORTS = "transports";
    private static final String TRANSPORTS_EXT_OID = "1.3.6.1.4.1.45724.2.1.1";

    private static final Map<String, DeviceMatcher> DEFAULT_DEVICE_MATCHERS = ImmutableMap.of(
        ExtensionMatcher.SELECTOR_TYPE, new ExtensionMatcher(),
        FingerprintMatcher.SELECTOR_TYPE, new FingerprintMatcher()
    );

    private final Map<X509Certificate, MetadataObject> metadata = new HashMap<>();
    private final Map<String, DeviceMatcher> matchers;

    public SimpleAttestationResolver(
        Collection<MetadataObject> objects,
        Map<String, DeviceMatcher> matchers
    ) throws CertificateException {
        this.matchers = Collections.unmodifiableMap(matchers);

        for (MetadataObject object : objects) {
            for (String caPem : object.getTrustedCertificates()) {
                X509Certificate trustAnchor = CertificateParser.parsePem(caPem);
                metadata.put(trustAnchor, object);
            }
        }
    }

    public SimpleAttestationResolver(Collection<MetadataObject> objects) throws CertificateException {
        this(objects, DEFAULT_DEVICE_MATCHERS);
    }

    public static SimpleAttestationResolver fromMetadataJson(String metadataObjectJson) throws IOException, CertificateException {
        return new SimpleAttestationResolver(
            Collections.singleton(WebAuthnCodecs.json().readValue(metadataObjectJson, MetadataObject.class))
        );
    }

    private Optional<MetadataObject> lookupTrustAnchor(X509Certificate trustAnchor) {
        return Optional.ofNullable(metadata.get(trustAnchor));
    }

    @Override
    public Optional<Attestation> resolve(X509Certificate attestationCertificate, Optional<X509Certificate> trustAnchor) {
        return trustAnchor.flatMap(this::lookupTrustAnchor).map(metadata -> {
            Map<String, String> vendorProperties;
            Map<String, String> deviceProperties = null;
            String identifier;
            int metadataTransports = 0;

            identifier = metadata.getIdentifier();
            vendorProperties = Maps.filterValues(metadata.getVendorInfo(), Objects::nonNull);
            for (JsonNode device : metadata.getDevices()) {
                if (deviceMatches(device.get(SELECTORS), attestationCertificate)) {
                    JsonNode transportNode = device.get(TRANSPORTS);
                    if (transportNode != null) {
                        metadataTransports |= transportNode.asInt(0);
                    }
                    ImmutableMap.Builder<String, String> devicePropertiesBuilder = ImmutableMap.builder();
                    for (Map.Entry<String, JsonNode> deviceEntry : Lists.newArrayList(device.fields())) {
                        JsonNode value = deviceEntry.getValue();
                        if (value.isTextual()) {
                            devicePropertiesBuilder.put(deviceEntry.getKey(), value.asText());
                        }
                    }
                    deviceProperties = devicePropertiesBuilder.build();
                    break;
                }
            }

            return Attestation.builder(true)
                .metadataIdentifier(Optional.ofNullable(identifier))
                .vendorProperties(Optional.of(vendorProperties))
                .deviceProperties(Optional.ofNullable(deviceProperties))
                .transports(Optional.of(Transport.fromInt(getTransports(attestationCertificate) | metadataTransports)))
                .build();
        });
    }

    private boolean deviceMatches(
        JsonNode selectors,
        @NonNull X509Certificate attestationCertificate
    ) {
        if (selectors == null || selectors.isNull()) {
            return true;
        } else {
            for (JsonNode selector : selectors) {
                DeviceMatcher matcher = matchers.get(selector.get(SELECTOR_TYPE).asText());
                if (matcher != null && matcher.matches(attestationCertificate, selector.get(SELECTOR_PARAMETERS))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static int getTransports(X509Certificate cert) {
        byte[] extensionValue = cert.getExtensionValue(TRANSPORTS_EXT_OID);

        if(extensionValue == null) {
            return 0;
        }

        // Mask out unused bits (shouldn't be needed as they should already be 0).
        int unusedBitMask = 0xff;
        for(int i=0; i < extensionValue[3]; i++) {
            unusedBitMask <<= 1;
        }
        extensionValue[extensionValue.length-1] &= unusedBitMask;

        int transports = 0;
        for(int i=extensionValue.length - 1; i >= 5; i--) {
            byte b = extensionValue[i];
            for(int bi=0; bi < 8; bi++) {
                transports = (transports << 1) | (b & 1);
                b >>= 1;
            }
        }

        return transports;
    }

    @Override
    public Attestation untrustedFromCertificate(X509Certificate attestationCertificate) {
        return Attestation.builder(false)
            .transports(Optional.of(Transport.fromInt(getTransports(attestationCertificate))))
            .build();
    }

}
