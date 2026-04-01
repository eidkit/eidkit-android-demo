package ro.eidkit.app.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber
import org.bouncycastle.asn1.x509.Certificate
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Calendar

private const val MAX_PDF_BYTES = 100L * 1024 * 1024  // 100 MB

// OID for ECDSA with SHA-384
private val OID_ECDSA_SHA384 = ASN1ObjectIdentifier("1.2.840.10045.4.3.3")
// OID for SHA-384
private val OID_SHA384 = ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.2")

/**
 * Holds the state between PAdES phase 1 (PDF preparation) and phase 2 (CMS embedding).
 * Kept in memory across the NFC session.
 */
class PadesContext(
    /**
     * SHA-384 hash of DER(signedAttrs) — this is what must be sent to the card for signing.
     *
     * CMS requires: signature = Sign(SHA-384(DER(signedAttrs)))
     * where signedAttrs contains messageDigest = SHA-384(PDF byte ranges).
     * The card signs whatever hash we hand it, so we must send the signedAttrs hash,
     * not the raw PDF byte-range hash.
     */
    val signedAttrsHash: ByteArray,
    /** Pre-built DER-encoded signedAttrs — embedded as-is in the CMS SignerInfo. */
    internal val signedAttrsDer: ByteArray,
    /** PDFBox external signing handle — call setSignature() after NFC. */
    internal val externalSigning: ExternalSigningSupport,
    /** Temp output stream holding the prepared PDF with placeholder. */
    internal val tempOut: ByteArrayOutputStream,
    /** The open PDDocument — must stay open until setSignature() is called, then closed. */
    internal val doc: PDDocument,
    /** Suggested output filename. */
    val suggestedFilename: String,
)

class PdfSigner(private val context: Context) {

    /**
     * Phase 1: Read the PDF, add a PAdES signature placeholder, build the CMS signedAttrs,
     * and return the SHA-384 hash of DER(signedAttrs) to send to the card.
     *
     * The card's ECDSA signature will cover this hash directly.
     * Phase 2 then embeds the returned signature into the CMS structure.
     */
    suspend fun prepare(uri: Uri, displayName: String, signedPrefix: String = "semnat"): Result<PadesContext> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver

                val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                require(size <= MAX_PDF_BYTES) {
                    "PDF prea mare (${size / (1024 * 1024)} MB). Maxim 100 MB."
                }

                val pdfBytes = resolver.openInputStream(uri)!!.use { it.readBytes() }

                val doc = PDDocument.load(pdfBytes)
                val tempOut = ByteArrayOutputStream(pdfBytes.size + 32768)

                val sig = PDSignature().also { s ->
                    s.setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    s.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
                    s.setName(signerName(doc))
                    s.setReason("Semnatura electronica cu Cartea de Identitate Electronica Romana")
                    s.setLocation("Romania")
                    s.setSignDate(Calendar.getInstance())
                }

                val options = SignatureOptions().apply {
                    preferredSignatureSize = 32768
                }

                doc.addSignature(sig, options)
                // doc stays OPEN — closing it before setSignature() invalidates externalSigning
                val externalSigning = doc.saveIncrementalForExternalSigning(tempOut)

                // Hash the PDF byte ranges (= the content to be protected by the signature)
                val contentBytes = externalSigning.content.use { it.readBytes() }
                val pdfHash = MessageDigest.getInstance("SHA-384").digest(contentBytes)

                // Build signedAttrs with the PDF hash as messageDigest.
                // These are DER-encoded and the card signs SHA-384(DER(signedAttrs)).
                val signedAttrsDer = buildSignedAttrsDer(pdfHash)
                val signedAttrsHash = MessageDigest.getInstance("SHA-384").digest(signedAttrsDer)

                PadesContext(
                    signedAttrsHash = signedAttrsHash,
                    signedAttrsDer  = signedAttrsDer,
                    externalSigning = externalSigning,
                    tempOut         = tempOut,
                    doc             = doc,
                    suggestedFilename = "${signedPrefix}_$displayName",
                )
            }
        }

    /**
     * Phase 2: Build the CMS SignedData blob using the card's ECDSA signature over
     * DER(signedAttrs), embed it into the PDF placeholder, and write the final PDF to [outputUri].
     */
    suspend fun complete(
        ctx: PadesContext,
        signatureBytes: ByteArray,   // 96-byte raw r||s from card
        certificateBytes: ByteArray, // DER X.509
        outputUri: Uri,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certificateBytes.inputStream()) as X509Certificate

                val cms = buildCms(
                    signedAttrsDer = ctx.signedAttrsDer,
                    rawSignature   = signatureBytes,
                    cert           = cert,
                )

                ctx.externalSigning.setSignature(cms)

                // tempOut now contains the complete signed PDF — write to chosen location
                context.contentResolver.openOutputStream(outputUri)!!.use { out ->
                    ctx.tempOut.writeTo(out)
                }
            } finally {
                runCatching { ctx.doc.close() }
            }
        }
    }

    // ── CMS builder ───────────────────────────────────────────────────────────

    /**
     * Build the DER-encoded signedAttrs SET for a detached CMS signature.
     *
     * signedAttrs = SET {
     *   contentType = id-data
     *   messageDigest = SHA-384(PDF byte ranges)
     * }
     *
     * This must be DER-encoded (not BER) for the signature to be verifiable.
     * The card signs SHA-384(this encoding).
     */
    private fun buildSignedAttrsDer(pdfHash: ByteArray): ByteArray {
        val vec = ASN1EncodableVector().apply {
            add(Attribute(CMSAttributes.contentType, DERSet(PKCSObjectIdentifiers.data)))
            add(Attribute(CMSAttributes.messageDigest, DERSet(DEROctetString(pdfHash))))
        }
        return DERSet(vec).encoded
    }

    /**
     * Build a minimal CMS SignedData (detached, ECDSA-SHA384) for PAdES embedding.
     *
     * Constructed as raw ASN.1 to guarantee our pre-computed signedAttrs and card signature
     * are embedded exactly — BouncyCastle high-level generators recompute the digest
     * which would produce a mismatch with an external signing device.
     *
     * Structure (RFC 5652 §5):
     * SignedData {
     *   version = 1
     *   digestAlgorithms = { SHA-384 }
     *   encapContentInfo = { id-data, absent }   -- detached
     *   certificates = { cert }
     *   signerInfos = { SignerInfo {
     *     version = 1
     *     sid = IssuerAndSerialNumber
     *     digestAlgorithm = SHA-384
     *     signedAttrs = <pre-built DER from phase 1>
     *     signatureAlgorithm = ECDSA-SHA384
     *     signature = DER(r,s)
     *   }}
     * }
     */
    private fun buildCms(
        signedAttrsDer: ByteArray,
        rawSignature: ByteArray,
        cert: X509Certificate,
    ): ByteArray {
        val derSig = rawToDer(rawSignature)
        val bcCert = Certificate.getInstance(ASN1Primitive.fromByteArray(cert.encoded))
        val issuerAndSerial = IssuerAndSerialNumber(
            bcCert.issuer,
            bcCert.serialNumber.value
        )

        // Re-parse the pre-built signedAttrsDer so it round-trips as [0] IMPLICIT
        val signedAttrsAsn1 = ASN1Primitive.fromByteArray(signedAttrsDer)

        // SignerInfo
        val signerInfoVec = ASN1EncodableVector().apply {
            add(ASN1Integer(1L))                                                    // version
            add(issuerAndSerial)                                                    // sid
            add(AlgorithmIdentifier(OID_SHA384))                                    // digestAlgorithm
            add(org.bouncycastle.asn1.DERTaggedObject(false, 0, signedAttrsAsn1))  // [0] signedAttrs
            add(AlgorithmIdentifier(OID_ECDSA_SHA384))                              // signatureAlgorithm
            add(DEROctetString(derSig))                                             // signature
        }

        // digestAlgorithms SET
        val digestAlgsVec = ASN1EncodableVector().apply {
            add(AlgorithmIdentifier(OID_SHA384))
        }

        // encapContentInfo: id-data, no content (detached)
        val encapContentInfo = DERSequence(PKCSObjectIdentifiers.data)

        // certificates [0] IMPLICIT
        val certsVec = ASN1EncodableVector().apply {
            add(ASN1Primitive.fromByteArray(cert.encoded))
        }

        // SignedData
        val signedDataVec = ASN1EncodableVector().apply {
            add(ASN1Integer(1L))                                                    // version
            add(DERSet(digestAlgsVec))                                              // digestAlgorithms
            add(encapContentInfo)                                                   // encapContentInfo
            add(org.bouncycastle.asn1.DERTaggedObject(false, 0, DERSet(certsVec))) // [0] certificates
            add(DERSet(DERSequence(signerInfoVec)))                                 // signerInfos
        }

        // ContentInfo wrapping SignedData
        val contentInfoVec = ASN1EncodableVector().apply {
            add(PKCSObjectIdentifiers.signedData)
            add(org.bouncycastle.asn1.DERTaggedObject(true, 0, DERSequence(signedDataVec)))
        }

        return DERSequence(contentInfoVec).encoded
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun signerName(doc: PDDocument): String? = runCatching {
        doc.documentInformation?.author?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Convert 96-byte raw r||s ECDSA-384 signature to DER SEQUENCE { INTEGER r, INTEGER s }.
     */
    private fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 96) { "Expected 96-byte r||s, got ${raw.size}" }
        val r = BigInteger(1, raw.copyOfRange(0, 48))
        val s = BigInteger(1, raw.copyOfRange(48, 96))
        val seq = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s)))
        return seq.encoded
    }
}
