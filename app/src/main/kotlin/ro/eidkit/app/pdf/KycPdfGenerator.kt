package ro.eidkit.app.pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.eidkit.app.R
import ro.eidkit.sdk.model.ActiveAuthStatus
import ro.eidkit.sdk.model.PassiveAuthStatus
import ro.eidkit.sdk.model.ReadResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class KycPdfGenerator(context: Context) {

    // Ensure Romanian locale for all getString() calls regardless of system locale
    private val context: Context = run {
        val locale = java.util.Locale("ro", "RO")
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.createConfigurationContext(config)
    }

    suspend fun generate(result: ReadResult, timestamp: LocalDateTime): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching { buildAndSave(result, timestamp) }
        }

    private fun buildAndSave(result: ReadResult, timestamp: LocalDateTime): Uri {
        val cnp = result.identity?.cnp ?: "unknown"
        val filenameTs = timestamp.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val filename = "KYC_${cnp}_$filenameTs.pdf"

        val doc = PDDocument()
        try {
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            val pageWidth  = page.mediaBox.width   // 595 pt
            val pageHeight = page.mediaBox.height  // 842 pt
            val margin = 40f
            val contentWidth = pageWidth - 2 * margin

            // Load Roboto from bundled assets — guaranteed on every device, supports Romanian diacritics
            val fontBold    = context.assets.open("fonts/Roboto-Bold.ttf").use { PDType0Font.load(doc, it) }
            val fontRegular = context.assets.open("fonts/Roboto-Regular.ttf").use { PDType0Font.load(doc, it) }
            val fontSmall   = fontRegular

            // Pre-load images before opening the content stream — createFromByteArray must
            // not be called while the stream is open (causes "nested beginText" state corruption).
            // Transcode JPEG→PNG via Android Bitmap to avoid PDFBox JPEG tiling artifacts in
            // some PDF viewers (caused by CMYK/YCbCr colorspace mismatches in the JPEG stream).
            val photoImg: PDImageXObject? = result.photo?.let { bytes ->
                runCatching { PDImageXObject.createFromByteArray(doc, jpegToPng(bytes), "photo") }.getOrNull()
            }
            val sigImg: PDImageXObject? = result.signatureImage?.let { bytes ->
                runCatching { PDImageXObject.createFromByteArray(doc, jpegToPng(bytes), "signature") }.getOrNull()
            }

            PDPageContentStream(doc, page).use { cs ->
                var y = pageHeight - margin

                // ── Header bar ──────────────────────────────────────────────
                cs.setNonStrokingColor(0.145f, 0.388f, 0.922f)   // ElectricBlue #2563EB
                cs.addRect(margin, y - 36f, contentWidth, 36f)
                cs.fill()
                cs.setNonStrokingColor(1f, 1f, 1f)
                cs.beginText()
                cs.setFont(fontBold, 16f)
                cs.newLineAtOffset(margin + 10f, y - 26f)
                cs.showText(context.getString(R.string.pdf_title))
                cs.endText()
                y -= 48f

                // ── Timestamp ────────────────────────────────────────────────
                cs.setNonStrokingColor(0.4f, 0.4f, 0.4f)
                cs.beginText()
                cs.setFont(fontSmall, 9f)
                cs.newLineAtOffset(margin, y)
                cs.showText(context.getString(R.string.pdf_generated, timestamp.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss"))))
                cs.endText()
                y -= 20f

                // ── Photo (top-right) ────────────────────────────────────────
                val photoWidth  = 90f
                val photoHeight = 112f
                val photoPlaced = photoImg != null
                if (photoImg != null) {
                    cs.drawImage(photoImg, pageWidth - margin - photoWidth, y - photoHeight, photoWidth, photoHeight)
                }

                val tableRight = if (photoPlaced) pageWidth - margin - photoWidth - 12f else pageWidth - margin

                // ── Identity section ─────────────────────────────────────────
                cs.setNonStrokingColor(0f, 0f, 0f)
                cs.beginText()
                cs.setFont(fontBold, 11f)
                cs.newLineAtOffset(margin, y)
                cs.showText(context.getString(R.string.pdf_section_identity))
                cs.endText()
                y -= 16f

                val colLeft  = margin
                val colRight = margin + (tableRight - margin) / 2f + 4f

                fun row(label: String, value: String?, col: Float, rowY: Float) {
                    cs.setNonStrokingColor(0.5f, 0.5f, 0.5f)
                    cs.beginText()
                    cs.setFont(fontSmall, 8f)
                    cs.newLineAtOffset(col, rowY + 1f)
                    cs.showText(label.uppercase())
                    cs.endText()
                    cs.setNonStrokingColor(0f, 0f, 0f)
                    cs.beginText()
                    cs.setFont(fontRegular, 10f)
                    cs.newLineAtOffset(col, rowY - 10f)
                    cs.showText((value ?: "-").take(40))
                    cs.endText()
                }

                // Full-width row with 2-line word wrap for long values
                fun rowWide(label: String, value: String?, rowY: Float): Float {
                    val text = value ?: "-"
                    cs.setNonStrokingColor(0.5f, 0.5f, 0.5f)
                    cs.beginText(); cs.setFont(fontSmall, 8f)
                    cs.newLineAtOffset(margin, rowY + 1f); cs.showText(label.uppercase()); cs.endText()
                    cs.setNonStrokingColor(0f, 0f, 0f)
                    // Split into chunks of ~65 chars to fit full width at 10pt
                    val line1 = text.take(65)
                    val line2 = text.drop(65).take(65)
                    cs.beginText(); cs.setFont(fontRegular, 10f)
                    cs.newLineAtOffset(margin, rowY - 10f); cs.showText(line1); cs.endText()
                    return if (line2.isNotEmpty()) {
                        cs.beginText(); cs.setFont(fontRegular, 10f)
                        cs.newLineAtOffset(margin, rowY - 22f); cs.showText(line2); cs.endText()
                        rowY - 40f
                    } else {
                        rowY - 28f
                    }
                }

                val id = result.identity
                val pd = result.personalData

                row(context.getString(R.string.pdf_field_full_name),    id?.let { "${it.firstName} ${it.lastName}" }, colLeft,  y)
                row(context.getString(R.string.pdf_field_cnp),         id?.cnp,                                       colRight, y)
                y -= 28f
                row(context.getString(R.string.pdf_field_dob),         id?.dateOfBirth?.let { formatDob(it) },        colLeft,  y)
                row(context.getString(R.string.pdf_field_nationality),  id?.nationality,                               colRight, y)
                y -= 28f
                row(context.getString(R.string.pdf_field_birthplace),  pd?.birthPlace,                                colLeft,  y)
                row(context.getString(R.string.pdf_field_doc_no),      pd?.documentNumber,                            colRight, y)
                y -= 28f
                pd?.issueDate?.let { row(context.getString(R.string.pdf_field_issue_date), formatDob(it), colLeft, y) }
                pd?.expiryDate?.let { row(context.getString(R.string.pdf_field_expires),   formatDob(it), colRight, y) }
                if (pd?.issueDate != null || pd?.expiryDate != null) y -= 28f
                pd?.issuingAuthority?.let { auth ->
                    y = rowWide(context.getString(R.string.pdf_field_issuing_authority), auth, y)
                }
                y = rowWide(context.getString(R.string.pdf_field_address), pd?.address, y)
                y -= 8f

                // ── Divider ──────────────────────────────────────────────────
                cs.setStrokingColor(0.85f, 0.85f, 0.85f)
                cs.moveTo(margin, y)
                cs.lineTo(pageWidth - margin, y)
                cs.stroke()
                y -= 16f

                // ── Document Authenticity ────────────────────────────────────
                cs.setNonStrokingColor(0f, 0f, 0f)
                cs.beginText()
                cs.setFont(fontBold, 11f)
                cs.newLineAtOffset(margin, y)
                cs.showText(context.getString(R.string.pdf_section_authenticity))
                cs.endText()
                y -= 16f

                when (val pa = result.passiveAuth) {
                    is PassiveAuthStatus.Valid -> {
                        cs.setNonStrokingColor(0.18f, 0.65f, 0.31f)   // green
                        cs.beginText(); cs.setFont(fontRegular, 10f)
                        cs.newLineAtOffset(margin, y); cs.showText("[${context.getString(R.string.pdf_ok)}] ${context.getString(R.string.result_passive_auth_valid)}"); cs.endText()
                        y -= 14f
                        row(context.getString(R.string.pdf_field_doc_cert),  pa.dscSubject, margin, y); y -= 28f
                        row(context.getString(R.string.pdf_field_issued_by), pa.issuer,     margin, y); y -= 28f
                    }
                    is PassiveAuthStatus.Invalid -> {
                        cs.setNonStrokingColor(0.97f, 0.32f, 0.29f)   // red
                        cs.beginText(); cs.setFont(fontRegular, 10f)
                        cs.newLineAtOffset(margin, y); cs.showText("[${context.getString(R.string.pdf_fail)}] ${pa.reason}"); cs.endText()
                        y -= 20f
                    }
                }

                // ── Chip Genuineness ─────────────────────────────────────────
                when (val aa = result.activeAuth) {
                    is ActiveAuthStatus.Verified -> {
                        cs.setNonStrokingColor(0f, 0f, 0f)
                        cs.beginText(); cs.setFont(fontBold, 11f)
                        cs.newLineAtOffset(margin, y); cs.showText(context.getString(R.string.pdf_section_chip)); cs.endText()
                        y -= 16f
                        cs.setNonStrokingColor(0.18f, 0.65f, 0.31f)
                        cs.beginText(); cs.setFont(fontRegular, 10f)
                        cs.newLineAtOffset(margin, y); cs.showText("[${context.getString(R.string.pdf_ok)}] ${context.getString(R.string.result_active_auth_verified)}"); cs.endText()
                        y -= 14f
                        row(context.getString(R.string.pdf_field_chip_cert), aa.certSubject, margin, y); y -= 28f
                    }
                    is ActiveAuthStatus.Failed -> {
                        cs.setNonStrokingColor(0f, 0f, 0f)
                        cs.beginText(); cs.setFont(fontBold, 11f)
                        cs.newLineAtOffset(margin, y); cs.showText(context.getString(R.string.pdf_section_chip)); cs.endText()
                        y -= 16f
                        cs.setNonStrokingColor(0.97f, 0.32f, 0.29f)
                        cs.beginText(); cs.setFont(fontRegular, 10f)
                        cs.newLineAtOffset(margin, y); cs.showText("[${context.getString(R.string.pdf_fail)}] ${aa.reason}"); cs.endText()
                        y -= 20f
                    }
                    is ActiveAuthStatus.Skipped -> { /* omit section */ }
                }

                // ── Handwritten signature ────────────────────────────────────
                if (sigImg != null) {
                    val sigHeight = 56f
                    // Need room for: divider(1) + gap(16) + label(14) + image(56) + footer clearance(40)
                    val neededY = sigHeight + 16f + 14f + 40f
                    if (y > neededY) {
                        cs.setStrokingColor(0.85f, 0.85f, 0.85f)
                        cs.moveTo(margin, y); cs.lineTo(pageWidth - margin, y); cs.stroke()
                        y -= 16f
                        cs.setNonStrokingColor(0f, 0f, 0f)
                        cs.beginText(); cs.setFont(fontBold, 11f)
                        cs.newLineAtOffset(margin, y)
                        cs.showText(context.getString(R.string.pdf_section_signature))
                        cs.endText()
                        y -= 14f
                        // Render at a fixed physical height (56pt ≈ 20mm), preserving aspect ratio.
                        // Using a fixed drawH avoids PDFBox tiling artifacts that occur when
                        // the computed scale causes sub-pixel rounding issues.
                        val imgW = sigImg.width.toFloat().coerceAtLeast(1f)
                        val imgH = sigImg.height.toFloat().coerceAtLeast(1f)
                        val drawH = sigHeight
                        val drawW = minOf((imgW / imgH) * drawH, contentWidth)
                        val drawY = maxOf(y - drawH, 64f)
                        cs.drawImage(sigImg, margin, drawY, drawW, drawH)
                        y -= drawH + 8f
                    }
                }

                // ── Footer ───────────────────────────────────────────────────
                cs.setNonStrokingColor(0.6f, 0.6f, 0.6f)
                cs.beginText(); cs.setFont(fontSmall, 8f)
                cs.newLineAtOffset(margin, 24f)
                cs.showText("EidKit - ${timestamp.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))}")
                cs.endText()
            }

            return saveToDownloads(doc, filename)
        } finally {
            doc.close()
        }
    }

    /**
     * Decode a JPEG to a Bitmap and re-encode as PNG. This normalises the colorspace to sRGB,
     * which prevents PDF viewers from misinterpreting JPEG YCbCr/CMYK streams and tiling the image.
     */
    private fun jpegToPng(jpegBytes: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return jpegBytes  // fallback: return original if decode fails
        return ByteArrayOutputStream().also { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
        }.toByteArray()
    }

    private fun saveToDownloads(doc: PDDocument, filename: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            resolver.openOutputStream(uri)!!.use { doc.save(it) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { doc.save(it) }
            Uri.fromFile(file)
        }
    }

    private fun formatDob(raw: String): String {
        if (raw.length != 8) return raw
        return "${raw.substring(0, 2)}/${raw.substring(2, 4)}/${raw.substring(4, 8)}"
    }
}
