package ro.eidkit.app.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.cert.X509CertificateHolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Calendar

private const val MAX_PDF_BYTES = 100L * 1024 * 1024  // 100 MB

// Pre-encoded OID bytes (tag 0x06 + length + value)
private val OID_ECDSA_SHA384 = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x03)
private val OID_SHA384       = byteArrayOf(0x06, 0x09, 0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x02)
private val OID_DATA         = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x01)
private val OID_CONTENT_TYPE = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x09, 0x03)
private val OID_MSG_DIGEST   = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x09, 0x04)
private val OID_SIGNED_DATA  = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02)

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
                val cms = buildCms(
                    signedAttrsDer = ctx.signedAttrsDer,
                    rawSignature   = signatureBytes,
                    certBytes      = certificateBytes,
                )

                Log.d("PdfSigner", "CMS hex: ${cms.toHexString()}")
                Log.d("PdfSigner", "signedAttrsDer hex: ${ctx.signedAttrsDer.toHexString()}")
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
     * Hand-rolled to guarantee stable byte ordering — BouncyCastle DERSet sorts
     * members canonically which would break the hash the card signed.
     *
     * SET {
     *   SEQUENCE { OID contentType,   SET { OID id-data } }
     *   SEQUENCE { OID messageDigest, SET { OCTET STRING pdfHash } }
     * }
     */
    private fun buildSignedAttrsDer(pdfHash: ByteArray): ByteArray {
        val contentTypeAttr = asn1Seq(asn1Bytes(OID_CONTENT_TYPE) + asn1Set(asn1Bytes(OID_DATA)))
        val msgDigestAttr   = asn1Seq(asn1Bytes(OID_MSG_DIGEST) + asn1Set(asn1OctetString(pdfHash)))
        return asn1Set(contentTypeAttr + msgDigestAttr)
    }

    /**
     * Build a minimal CMS SignedData (detached, ECDSA-SHA384) for PAdES embedding.
     * Hand-rolled to embed signedAttrs verbatim — no BouncyCastle re-encoding.
     */
    private fun buildCms(
        signedAttrsDer: ByteArray,
        rawSignature: ByteArray,
        certBytes: ByteArray,
    ): ByteArray {
        val derSig = rawToDer(rawSignature)

        val holder = X509CertificateHolder(certBytes)
        val issuerDer  = holder.issuer.encoded                    // DER Name SEQUENCE
        val serialDer  = org.bouncycastle.asn1.ASN1Integer(holder.serialNumber).encoded  // DER INTEGER
        val issuerAndSerial = asn1Seq(issuerDer + serialDer)

        // [0] IMPLICIT SET — retag 0x31 → 0xA0, content bytes identical to what was hashed
        val signedAttrsTagged = signedAttrsDer.clone().also { it[0] = 0xA0.toByte() }

        val signerInfo = asn1Seq(
            asn1Integer(1) +                          // version
            issuerAndSerial +                          // sid
            asn1Seq(asn1Bytes(OID_SHA384)) +           // digestAlgorithm
            signedAttrsTagged +                        // [0] signedAttrs
            asn1Seq(asn1Bytes(OID_ECDSA_SHA384)) +     // signatureAlgorithm
            asn1OctetString(derSig)                    // signature
        )

        // certificates [0] IMPLICIT — retag SET 0x31 → 0xA0
        val certsTagged = asn1Set(certBytes).also { it[0] = 0xA0.toByte() }  // certBytes = raw DER, not re-encoded

        val signedData = asn1Seq(
            asn1Integer(1) +                                        // version
            asn1Set(asn1Seq(asn1Bytes(OID_SHA384))) +              // digestAlgorithms
            asn1Seq(asn1Bytes(OID_DATA)) +                         // encapContentInfo (detached)
            certsTagged +                                           // [0] certificates
            asn1Set(signerInfo)                                     // signerInfos
        )

        // ContentInfo { OID signedData, [0] EXPLICIT signedData }
        val taggedSignedData = byteArrayOf(0xA0.toByte()) + berLength(signedData.size) + signedData
        return asn1Seq(asn1Bytes(OID_SIGNED_DATA) + taggedSignedData)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun signerName(doc: PDDocument): String? = runCatching {
        doc.documentInformation?.author?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Convert 96-byte raw r||s to DER SEQUENCE { INTEGER r, INTEGER s }. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 96) { "Expected 96-byte r||s, got ${raw.size}" }
        val r = encodeAsn1Integer(raw.copyOfRange(0, 48))
        val s = encodeAsn1Integer(raw.copyOfRange(48, 96))
        return asn1Seq(r + s)
    }

    private fun encodeAsn1Integer(value: ByteArray): ByteArray {
        var bytes = value.dropWhile { it == 0x00.toByte() }.toByteArray()
        if (bytes.isEmpty() || bytes[0].toInt() and 0x80 != 0) bytes = byteArrayOf(0x00) + bytes
        return byteArrayOf(0x02) + berLength(bytes.size) + bytes
    }

}

// ── Hand-rolled ASN.1 primitives ─────────────────────────────────────────────

private fun berLength(len: Int): ByteArray = when {
    len < 0x80   -> byteArrayOf(len.toByte())
    len <= 0xFF  -> byteArrayOf(0x81.toByte(), len.toByte())
    else         -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
}

private fun asn1Seq(body: ByteArray)      = byteArrayOf(0x30) + berLength(body.size) + body
private fun asn1Set(body: ByteArray)      = byteArrayOf(0x31) + berLength(body.size) + body
private fun asn1OctetString(v: ByteArray) = byteArrayOf(0x04) + berLength(v.size) + v
private fun asn1Integer(v: Int)           = byteArrayOf(0x02, 0x01, v.toByte())
private fun asn1Bytes(v: ByteArray)       = v
private fun ByteArray.toHexString()      = joinToString("") { "%02x".format(it) }
