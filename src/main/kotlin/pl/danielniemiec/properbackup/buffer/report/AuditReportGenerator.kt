package pl.danielniemiec.properbackup.buffer.report

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class AuditReportGenerator {

    data class ReportData(
        val serverName: String,
        val serverId: String,
        val ownerEmail: String,
        val generatedAt: Instant,
        val backupRoots: List<RootEntry>,
        val totalFiles: Long,
        val totalSnapshots: Long,
        val lastBackupAt: String?,
        val verificationStatus: String,
        val storageLimitLabel: String,
        val storageUsedLabel: String,
    )

    data class RootEntry(
        val rootPath: String,
        val snapshotCount: Long,
        val lastSnapshot: String?,
    )

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

    private val titleFont = Font(Font.HELVETICA, 18f, Font.BOLD, Color(30, 30, 30))
    private val subtitleFont = Font(Font.HELVETICA, 11f, Font.NORMAL, Color(100, 100, 100))
    private val headingFont = Font(Font.HELVETICA, 13f, Font.BOLD, Color(30, 30, 30))
    private val normalFont = Font(Font.HELVETICA, 10f, Font.NORMAL, Color(50, 50, 50))
    private val boldFont = Font(Font.HELVETICA, 10f, Font.BOLD, Color(50, 50, 50))
    private val smallFont = Font(Font.HELVETICA, 8f, Font.NORMAL, Color(150, 150, 150))
    private val accentColor = Color(37, 99, 235)

    fun generate(data: ReportData): ByteArray {
        val baos = ByteArrayOutputStream()
        val document = Document(PageSize.A4, 50f, 50f, 50f, 50f)
        PdfWriter.getInstance(document, baos)
        document.open()

        document.add(Paragraph("ProperBackup", titleFont).apply {
            alignment = Element.ALIGN_LEFT
        })
        document.add(Paragraph("Audit Report", Font(Font.HELVETICA, 14f, Font.NORMAL, accentColor)).apply {
            spacingAfter = 4f
        })
        document.add(Paragraph(dateFmt.format(data.generatedAt), subtitleFont).apply {
            spacingAfter = 20f
        })

        addLine(document)

        document.add(Paragraph("Server Information", headingFont).apply {
            spacingBefore = 16f
            spacingAfter = 8f
        })
        addKeyValue(document, "Server name", data.serverName)
        addKeyValue(document, "Server ID", data.serverId)
        addKeyValue(document, "Owner", data.ownerEmail)
        addKeyValue(document, "Storage used", "${data.storageUsedLabel} / ${data.storageLimitLabel}")

        addLine(document)

        document.add(Paragraph("Backup Summary", headingFont).apply {
            spacingBefore = 16f
            spacingAfter = 8f
        })
        addKeyValue(document, "Total files tracked", data.totalFiles.toString())
        addKeyValue(document, "Total archive snapshots", data.totalSnapshots.toString())
        addKeyValue(document, "Last backup", data.lastBackupAt ?: "—")
        addKeyValue(document, "Verification status", data.verificationStatus)

        addLine(document)

        if (data.backupRoots.isNotEmpty()) {
            document.add(Paragraph("Backup Paths", headingFont).apply {
                spacingBefore = 16f
                spacingAfter = 8f
            })

            val table = PdfPTable(3)
            table.widthPercentage = 100f
            table.setWidths(floatArrayOf(55f, 20f, 25f))

            listOf("Path", "Snapshots", "Last snapshot").forEach { header ->
                table.addCell(PdfPCell(Phrase(header, boldFont)).apply {
                    backgroundColor = Color(245, 245, 245)
                    paddingBottom = 6f
                    paddingTop = 4f
                    borderColor = Color(220, 220, 220)
                })
            }

            for (root in data.backupRoots) {
                table.addCell(PdfPCell(Phrase(root.rootPath, normalFont)).apply {
                    paddingBottom = 5f
                    paddingTop = 3f
                    borderColor = Color(235, 235, 235)
                })
                table.addCell(PdfPCell(Phrase(root.snapshotCount.toString(), normalFont)).apply {
                    paddingBottom = 5f
                    paddingTop = 3f
                    borderColor = Color(235, 235, 235)
                    horizontalAlignment = Element.ALIGN_CENTER
                })
                table.addCell(PdfPCell(Phrase(root.lastSnapshot ?: "—", normalFont)).apply {
                    paddingBottom = 5f
                    paddingTop = 3f
                    borderColor = Color(235, 235, 235)
                })
            }

            document.add(table)
        }

        document.add(Paragraph(
            "This report was automatically generated by ProperBackup. " +
                "All backups use zero-knowledge encryption (Argon2id + AES-256-GCM). " +
                "The backup provider cannot read your data.",
            smallFont,
        ).apply {
            spacingBefore = 30f
        })

        document.close()
        return baos.toByteArray()
    }

    private fun addKeyValue(document: Document, key: String, value: String) {
        val p = Paragraph()
        p.add(Phrase("$key: ", boldFont))
        p.add(Phrase(value, normalFont))
        p.spacingAfter = 3f
        document.add(p)
    }

    private fun addLine(document: Document) {
        val table = PdfPTable(1)
        table.widthPercentage = 100f
        table.setSpacingBefore(8f)
        table.setSpacingAfter(0f)
        val cell = PdfPCell()
        cell.border = PdfPCell.BOTTOM
        cell.borderColor = Color(220, 220, 220)
        cell.borderWidth = 0.5f
        cell.fixedHeight = 1f
        table.addCell(cell)
        document.add(table)
    }
}
