package com.example.remoteandroid.server

import android.content.Context
import com.example.remoteandroid.util.Network
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Builds the self-signed certificate used by the HTTPS server. The key pair and
 * certificate are generated on first launch, with a subjectAltName covering the
 * device's current IP addresses, and persisted so the browser warning only has
 * to be accepted once per device.
 */
object Tls {
    private const val PASSWORD = "remoteandroid"
    private const val ALIAS = "server"
    private const val FILE_NAME = "server.p12"

    fun sslContext(context: Context): SSLContext {
        val ks = loadOrCreate(context)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD.toCharArray())
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
    }

    private fun loadOrCreate(context: Context): KeyStore {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            try {
                return KeyStore.getInstance("PKCS12").apply {
                    FileInputStream(file).use { load(it, PASSWORD.toCharArray()) }
                }
            } catch (_: Exception) {
                file.delete()
            }
        }
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            val kp = generateKeyPair()
            val cert = selfSigned(kp)
            ks.setKeyEntry(ALIAS, kp.private, PASSWORD.toCharArray(), arrayOf(cert))
            FileOutputStream(file).use { ks.store(it, PASSWORD.toCharArray()) }
            ks
        } catch (_: Exception) {
            loadAssetKeystore(context)
        }
    }

    /** Fallback: load a cert embedded by `build.sh --gen-cert` into res/raw/. */
    private fun loadAssetKeystore(context: Context): KeyStore {
        val id = context.resources.getIdentifier("server_p12", "raw", context.packageName)
        require(id != 0) { "Runtime certificate generation failed and no embedded cert found" }
        return KeyStore.getInstance("PKCS12").apply {
            context.resources.openRawResource(id).use { load(it, PASSWORD.toCharArray()) }
        }
    }

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    private fun selfSigned(kp: KeyPair): java.security.cert.X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000L)
        val notAfter = Date(now + 3650L * 24L * 60L * 60L * 1000L)
        val subject = X500Name("CN=RemoteAndroid")
        val builder =
            JcaX509v3CertificateBuilder(
                subject,
                BigInteger(128, SecureRandom()),
                notBefore,
                notAfter,
                subject,
                kp.public,
            )

        val sans = mutableListOf<GeneralName>()
        sans.add(GeneralName(GeneralName.dNSName, "localhost"))
        sans.add(GeneralName(GeneralName.iPAddress, "127.0.0.1"))
        for (ip in Network.localIPv4s()) {
            sans.add(GeneralName(GeneralName.iPAddress, ip))
        }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans.toTypedArray()))

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
