package com.ambikatelecom.rewards.reporting.application;

import com.ambikatelecom.rewards.customers.persistence.CustomerReportRow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Builds the paginated A4 report: title, period, generated time and row count on the first page, then one row
 * per customer (serial number, name, phone and nothing else). The rows arrive page by page from the database,
 * so memory use stays flat however many customers there are.
 *
 * Fonts: the built-in Helvetica cannot print Hindi or other non-Latin names. Set REPORT_FONT_PATH to a TTF that
 * covers your customers' scripts (see README); names with characters the font lacks print "?" for those characters
 * and the last page says how many were replaced.
 */
@Component
public class PdfReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfReportRenderer.class);

    /** Supplies the next page of rows, or an empty list when there are no more. */
    public interface RowSource {
        List<CustomerReportRow> next();
    }

    static final int FIRST_PAGE_ROWS = 30;
    static final int OTHER_PAGE_ROWS = 36;

    private static final float PAGE_H = PDRectangle.A4.getHeight();
    private static final float MARGIN = 50f;
    private static final float ROW_H = 18f;
    private static final float BODY_SIZE = 11f;
    private static final float COL_NO = MARGIN;
    private static final float COL_NAME = MARGIN + 42f;
    private static final float COL_PHONE = MARGIN + 380f;
    private static final float NAME_MAX_W = COL_PHONE - COL_NAME - 12f;
    private static final DateTimeFormatter GENERATED =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH);

    private final ReportSettings settings;

    public PdfReportRenderer(ReportSettings settings) {
        this.settings = settings;
    }

    static int pageCount(long total) {
        if (total <= FIRST_PAGE_ROWS) return 1;
        return 1 + (int) Math.ceil((total - FIRST_PAGE_ROWS) / (double) OTHER_PAGE_ROWS);
    }

    public void render(Path target, ReportPeriod period, ZonedDateTime generatedAt, long total, RowSource rows)
            throws IOException {
        int pages = pageCount(total);
        try (PDDocument doc = new PDDocument()) {
            PDFont head = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont body = loadBodyFont(doc, plain);
            doc.getDocumentInformation().setTitle("Ambika Telecom new customers " + period.label());
            doc.getDocumentInformation().setCreator("Ambika Telecom Rewards");

            Deque<CustomerReportRow> queue = new ArrayDeque<>();
            long written = 0;
            int[] replaced = {0};

            for (int p = 1; p <= pages; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                int capacity = p == 1 ? FIRST_PAGE_ROWS : OTHER_PAGE_ROWS;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = PAGE_H - MARGIN;
                    if (p == 1) {
                        y = drawFirstPageHeader(cs, head, plain, period, generatedAt, total, y);
                    } else {
                        text(cs, plain, 9f, MARGIN, y - 9f, "Ambika Telecom: new customers, " + period.humanMonth() + " (continued)");
                        y -= 30f;
                    }
                    y = drawTableHead(cs, head, y);

                    int onPage = 0;
                    while (onPage < capacity && written < total) {
                        if (queue.isEmpty()) {
                            queue.addAll(rows.next());
                            if (queue.isEmpty()) {
                                throw new IllegalStateException("Fewer customers than were counted");
                            }
                        }
                        CustomerReportRow row = queue.poll();
                        written++;
                        onPage++;
                        y -= ROW_H;
                        text(cs, plain, BODY_SIZE, COL_NO, y, String.valueOf(written));
                        String name = fit(body, BODY_SIZE, NAME_MAX_W, printable(body, row.getName(), replaced));
                        text(cs, body, BODY_SIZE, COL_NAME, y, name);
                        text(cs, plain, BODY_SIZE, COL_PHONE, y, formatPhone(row.getPhoneE164()));
                    }
                    if (total == 0) {
                        text(cs, plain, 12f, MARGIN, y - 28f, "No new customers registered in this period.");
                    }
                    if (p == pages && replaced[0] > 0) {
                        text(cs, plain, 8f, MARGIN, 46f, replaced[0]
                                + " character(s) in names could not be printed with this font and show as ?.");
                    }
                    text(cs, plain, 8f, MARGIN, 30f,
                            "Confidential: customer contact details.   Page " + p + " of " + pages);
                }
            }
            if (!queue.isEmpty() || !rows.next().isEmpty()) {
                throw new IllegalStateException("More customers than were counted");
            }
            if (replaced[0] > 0) {
                log.warn("Report font could not print {} character(s) in customer names", replaced[0]);
            }
            doc.save(target.toFile());
        }
    }

    private float drawFirstPageHeader(PDPageContentStream cs, PDFont head, PDFont plain, ReportPeriod period,
                                      ZonedDateTime generatedAt, long total, float top) throws IOException {
        float y = top - 20f;
        text(cs, head, 20f, MARGIN, y, "Ambika Telecom");
        y -= 26f;
        text(cs, head, 14f, MARGIN, y, "New customers: " + period.humanMonth());
        y -= 24f;
        text(cs, plain, 10f, MARGIN, y, "Period: " + period.humanRange() + " (" + period.zone().getId() + ")");
        y -= 15f;
        text(cs, plain, 10f, MARGIN, y, "Generated: " + generatedAt.format(GENERATED));
        y -= 15f;
        text(cs, plain, 10f, MARGIN, y, "Customers first registered in this period: " + total);
        return y - 22f;
    }

    private float drawTableHead(PDPageContentStream cs, PDFont head, float y) throws IOException {
        text(cs, head, BODY_SIZE, COL_NO, y, "#");
        text(cs, head, BODY_SIZE, COL_NAME, y, "Name");
        text(cs, head, BODY_SIZE, COL_PHONE, y, "Phone");
        cs.setLineWidth(0.8f);
        cs.moveTo(MARGIN, y - 5f);
        cs.lineTo(PDRectangle.A4.getWidth() - MARGIN, y - 5f);
        cs.stroke();
        return y - 4f;
    }

    private static void text(PDPageContentStream cs, PDFont font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    /** +919876543210 becomes +91 98765 43210. Anything else is shown as stored. */
    static String formatPhone(String e164) {
        if (e164 != null && e164.matches("\\+91\\d{10}")) {
            return "+91 " + e164.substring(3, 8) + " " + e164.substring(8);
        }
        return e164 == null ? "" : e164;
    }

    /** Control characters become spaces and characters the font cannot encode become "?" (counted in replaced[0]). */
    private static String printable(PDFont font, String s, int[] replaced) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F) {
                sb.append(' ');
                continue;
            }
            String ch = new String(Character.toChars(cp));
            try {
                font.getStringWidth(ch);
                sb.append(ch);
            } catch (IllegalArgumentException | IOException e) {
                sb.append('?');
                replaced[0]++;
            }
        }
        return sb.toString();
    }

    private static String fit(PDFont font, float size, float maxWidth, String s) throws IOException {
        if (width(font, size, s) <= maxWidth) return s;
        int end = s.length();
        while (end > 0) {
            if (Character.isHighSurrogate(s.charAt(end - 1))) {
                end--;
                continue;
            }
            if (width(font, size, s.substring(0, end) + "...") <= maxWidth) break;
            end--;
        }
        return s.substring(0, end) + "...";
    }

    private static float width(PDFont font, float size, String s) throws IOException {
        return font.getStringWidth(s) / 1000f * size;
    }

    /** Names and phones use the configured Unicode font when there is one, otherwise Helvetica. */
    private PDFont loadBodyFont(PDDocument doc, PDFont fallback) {
        String path = settings.fontPath();
        if (path.isEmpty()) return fallback;
        try (InputStream in = openFont(path)) {
            // Not subsetted: keeps the font's glyph substitution tables available for scripts that need them.
            return PDType0Font.load(doc, in, false);
        } catch (Exception e) {
            log.warn("Report font could not be loaded, using Helvetica ({})", e.getClass().getSimpleName());
            return fallback;
        }
    }

    private static InputStream openFont(String path) throws IOException {
        if (path.startsWith("classpath:")) {
            return new ClassPathResource(path.substring("classpath:".length())).getInputStream();
        }
        return Files.newInputStream(Path.of(path));
    }
}
