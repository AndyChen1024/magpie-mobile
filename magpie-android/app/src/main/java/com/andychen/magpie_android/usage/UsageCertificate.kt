package com.andychen.magpie_android.usage

import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

internal const val MAX_USAGE_CERTIFICATE_BYTES = 16 * 1024

internal fun readUsageCertificate(stream: InputStream): X509Certificate {
    val bytes = ByteArray(MAX_USAGE_CERTIFICATE_BYTES + 1)
    var size = 0
    while (size < bytes.size) {
        val count = stream.read(bytes, size, bytes.size - size)
        if (count <= 0) break
        size += count
    }
    return parseUsageCertificate(bytes.copyOf(size)).apply { checkValidity() }
}

internal fun parseUsageCertificate(bytes: ByteArray): X509Certificate {
    if (bytes.size !in 1..MAX_USAGE_CERTIFICATE_BYTES) throw CertificateException("Invalid CA size")
    val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificates(bytes.inputStream()).singleOrNull() as? X509Certificate
        ?: throw CertificateException("Expected one X.509 CA certificate")
    if (certificate.basicConstraints < 0 || certificate.keyUsage?.getOrNull(5) == false) {
        throw CertificateException("Expected a certificate authorized to sign certificates")
    }
    return certificate
}

internal fun usageSocketFactory(certificate: X509Certificate): SSLSocketFactory {
    certificate.checkValidity()
    val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null)
        setCertificateEntry("gateway-ca", certificate)
    }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(anchors)
    }
    return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
}
