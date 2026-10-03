// SPDX-License-Identifier: GPL-3.0-only
// On-device generation of the experimental MFi accessory material, ported from EasyPlay's
// CertificateMaterialGenerator: an EC P-256 key pair plus a self-signed X.509 certificate,
// written in exactly the shapes LocalMfiAuthenticationClient.load expects (PKCS#8
// identity.pk8 + DER certificate.p7b).
//
// The EasyPlay reference also stamps BasicConstraints (OID 2.5.29.19) for real-iPhone
// fidelity; our local validator only checks the EC P-256 public key and the key/cert
// signature match, so the certificate ships with an empty extension set.
// A generated identity proves key consistency like the shipped experimental one does —
// an iPhone still does not factory-trust it. It only removes the hard dependency on
// bundled credential assets so asset-free builds stay usable (beta material fallback).
package com.shilapi.xcertplay.mfi

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date

internal object GeneratedMfiMaterial {
    private const val CERTIFICATE_LIFETIME_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000

    /**
     * Generates the material into [directory] when it is not there yet. Returns true when
     * the directory afterwards holds a loadable identity; every failure is thrown.
     */
    fun ensure(directory: File): Boolean {
        if (File(directory, "identity.pk8").isFile && File(directory, "certificate.p7b").isFile) {
            return true
        }
        check(directory.isDirectory || directory.mkdirs()) { "Could not prepare the identity directory" }

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val publicKey = pair.public as? ECPublicKey ?: error("Expected an EC key pair")

        val now = System.currentTimeMillis()
        val random = SecureRandom()
        val serial = BigInteger(63, random).abs()
        val subject = X500Name("CN=DiPlay Local Accessory ${"%08X".format(random.nextInt())}")
        val signatureIdentifier = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)

        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(serial))
            setIssuer(subject)
            setSubject(subject)
            setStartDate(Time(Date(now - 60L * 60 * 1000)))
            setEndDate(Time(Date(now + CERTIFICATE_LIFETIME_MILLIS)))
            setSignature(signatureIdentifier)
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(tbs.getEncoded("DER"))

        // bcprov 1.79 keeps the Certificate constructor private; go through the sequence factory.
        val sequence = ASN1EncodableVector()
        sequence.add(tbs)
        sequence.add(signatureIdentifier)
        sequence.add(DERBitString(signer.sign()))
        val certificate = Certificate.getInstance(DERSequence(sequence))
        val encoded = certificate.getEncoded("DER")

        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(encoded.inputStream()) as X509Certificate
        runCatching { parsed.verify(pair.public) }.onFailure {
            error("Generated certificate signature does not verify: ${it.message}")
        }
        require(parsed.publicKey is ECPublicKey) { "Generated certificate is not EC" }

        File(directory, "identity.pk8").writeBytes(pair.private.encoded)
        File(directory, "certificate.p7b").writeBytes(encoded)
        return true
    }
}
