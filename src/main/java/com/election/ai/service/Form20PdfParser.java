package com.election.ai.service;

import com.election.ai.model.Form20Sheet;
import com.election.ai.model.PollingStation;
import com.election.ai.model.VoteCounts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a TN Form 20 "Final Result Sheet" PDF.
 *
 * <p>Each district prints Form 20 in its own layout: one or two serial columns, sometimes the
 * polling-station address or electors inline, and the summary columns (valid, rejected, NOTA,
 * total, tendered) in different orders, sometimes followed by extra columns such as turnout.
 * Rather than relying on fixed positions, the layout is found arithmetically: in every row the
 * candidate votes must add up to the valid-votes figure and the summary columns must add up to
 * the total. The layout that fits the most rows is used for the whole sheet.
 */
public final class Form20PdfParser {

    /** Characters whose baselines are closer than this belong to the same line. */
    private static final float LINE_TOLERANCE = 1.2f;
    /** A horizontal gap wider than this starts a new word. */
    private static final float WORD_GAP = 1.5f;
    /** Header names may touch their neighbours; letters inside a name are drawn with no gap. */
    private static final float NAME_GAP = 0.5f;
    /** How far above/below a totals row its wrapped label may sit. */
    private static final float LABEL_WINDOW = 10f;

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern GROUPED_NUMBER = Pattern.compile("\\d{1,3}(,\\d{2,3})+");
    private static final Pattern SERIAL = Pattern.compile("\\d+[A-Za-z]?");
    private static final Pattern PART_TWO = Pattern.compile("(?i)\\bPART\\s*[-–:]?\\s*(II|2)\\b");
    private static final Pattern ELECTORS =
            Pattern.compile("(?i)Total\\s+No\\.?\\s*of\\s+Electors[^0-9]*?(\\d{4,})");
    private static final Pattern AC_NAME = Pattern.compile(
            "(?i)(?:Name of\\s+(?:the\\s+)?Assembly[^:.]*?[:.]+|No\\.\\s*&\\s*Name of the Assembly \\w+\\s*:)"
                    + "\\s*\\.*\\s*(.+?)\\s*(?:Assembly Election.*)?$");

    private Form20PdfParser() {
    }

    /** Thrown when a PDF has no text layer (a scanned image). */
    public static class NoTextException extends IOException {
        public NoTextException(String message) {
            super(message);
        }
    }

    /**
     * Where the summary figures sit relative to the candidate block, and how they add up.
     * {@code width} is how many columns after the candidates the check needs.
     */
    enum Summary {
        /** valid, rejected, NOTA, total, [tendered] — the ECI standard. */
        VALID_REJECTED_NOTA_TOTAL(4),
        /** NOTA, valid, rejected, total, [tendered]. */
        NOTA_VALID_REJECTED_TOTAL(4),
        /** NOTA, valid including NOTA, rejected, total, [tendered]. */
        NOTA_VALIDWITHNOTA_REJECTED_TOTAL(4),
        /** valid, NOTA, tendered, rejected, total. */
        VALID_NOTA_TENDERED_REJECTED_TOTAL(5),
        /**
         * valid, one other column, total, [tendered] — seen on some pages that drop a column. The
         * other column is taken as NOTA (EVM rejected votes are almost always zero). Only used for
         * rows that fit none of the full layouts, never to choose a sheet's layout.
         */
        VALID_OTHER_TOTAL(3);

        final int width;

        Summary(int width) {
            this.width = width;
        }

        boolean fallbackOnly() {
            return this == VALID_OTHER_TOTAL;
        }

        boolean fits(long s, long[] w, int o) {
            return switch (this) {
                case VALID_REJECTED_NOTA_TOTAL -> w[o] == s && w[o] + w[o + 1] + w[o + 2] == w[o + 3];
                case NOTA_VALID_REJECTED_TOTAL -> w[o + 1] == s && w[o] + w[o + 1] + w[o + 2] == w[o + 3];
                case NOTA_VALIDWITHNOTA_REJECTED_TOTAL ->
                        w[o + 1] == s + w[o] && w[o + 1] + w[o + 2] == w[o + 3];
                case VALID_NOTA_TENDERED_REJECTED_TOTAL ->
                        w[o] == s && w[o] + w[o + 1] + w[o + 3] == w[o + 4];
                case VALID_OTHER_TOTAL -> w[o] == s && s > 0 && w[o] + w[o + 1] == w[o + 2];
            };
        }

        /** Builds the counts; {@code w[o..]} are the columns after the candidates, {@code len} how many exist. */
        VoteCounts counts(List<Integer> votes, long s, long[] w, int o, int len) {
            int valid = (int) s;
            return switch (this) {
                case VALID_REJECTED_NOTA_TOTAL ->
                        new VoteCounts(votes, (int) w[o], (int) w[o + 1], (int) w[o + 2], (int) w[o + 3],
                                tendered(w, o + 4, len, w[o + 3]));
                case NOTA_VALID_REJECTED_TOTAL ->
                        new VoteCounts(votes, (int) w[o + 1], (int) w[o + 2], (int) w[o], (int) w[o + 3],
                                tendered(w, o + 4, len, w[o + 3]));
                case NOTA_VALIDWITHNOTA_REJECTED_TOTAL ->
                        new VoteCounts(votes, valid, (int) w[o + 2], (int) w[o], (int) w[o + 3],
                                tendered(w, o + 4, len, w[o + 3]));
                case VALID_NOTA_TENDERED_REJECTED_TOTAL ->
                        new VoteCounts(votes, (int) w[o], (int) w[o + 3], (int) w[o + 1], (int) w[o + 4],
                                (int) w[o + 2]);
                case VALID_OTHER_TOTAL ->
                        new VoteCounts(votes, (int) w[o], 0, (int) w[o + 1], (int) w[o + 2],
                                tendered(w, o + 3, len, w[o + 2]));
            };
        }

        private static int tendered(long[] w, int i, int len, long total) {
            // A further column is tendered votes only if it is small; otherwise it is something
            // else (electors, turnout) and tendered is not printed.
            return i < len && w[i] >= 0 && w[i] <= Math.max(50, total / 10) ? (int) w[i] : 0;
        }
    }

    /** n candidate columns starting {@code fromEnd} tokens before the end of the line. */
    record Layout(int candidates, int fromEnd, Summary summary) {
    }

    record Word(String text, float x1, float x2) {
        float center() {
            return (x1 + x2) / 2;
        }
    }

    record Line(int page, float y, List<TextPosition> chars, List<Word> words, String text) {
    }

    public static Form20Sheet parse(byte[] pdf) throws IOException {
        return parse(pdf, null);
    }

    /**
     * @param expectedCandidates number of contesting candidates if known (e.g. from Form 7A);
     *                           null to infer it from the sheet
     */
    public static Form20Sheet parse(byte[] pdf, Integer expectedCandidates) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return parse(doc, expectedCandidates);
        }
    }

    public static Form20Sheet parse(PDDocument doc, Integer expectedCandidates) throws IOException {
        List<Line> lines = new ArrayList<>();
        for (int p = 1; p <= doc.getNumberOfPages(); p++) {
            lines.addAll(readLines(doc, p));
        }
        if (lines.stream().noneMatch(l -> l.words().size() > 3)) {
            throw new NoTextException("This Form 20 PDF is a scanned image with no text layer");
        }
        // Some PDFs bundle the round-wise tabulation sheets (candidates as rows, booths as
        // columns) before the Form 20 table. Their numbers would be misread as polling-station
        // rows, so those pages are skipped when the PDF also has ordinary pages.
        // (Some real Form 20 pages carry a "Tabulation Sheet" title too, so the transposed
        // layout is recognised by its "Polling Booth No." / "Name of Candidate" captions.)
        Set<Integer> tabulationPages = new HashSet<>();
        for (Line l : lines) {
            String t = l.text().toLowerCase(Locale.ROOT);
            if (t.contains("polling booth no") || t.contains("name of candidate")) {
                tabulationPages.add(l.page());
            }
        }
        if (!tabulationPages.isEmpty() && tabulationPages.size() < doc.getNumberOfPages()) {
            lines.removeIf(l -> tabulationPages.contains(l.page()));
        }
        // "Part II" pages hold round-wise or postal summaries whose rows look like
        // polling-station rows (round 1, 2, 3...). They are not read as stations.
        Set<Integer> partTwoPages = new HashSet<>();
        for (Line l : lines) {
            if (PART_TWO.matcher(l.text()).find()) {
                partTwoPages.add(l.page());
            }
        }
        Set<Integer> allPages = new HashSet<>();
        lines.forEach(l -> allPages.add(l.page()));
        if (partTwoPages.size() == allPages.size()) {
            partTwoPages.clear();
        }

        Layout layout = detectLayout(lines, expectedCandidates);
        if (layout == null && expectedCandidates != null) {
            layout = detectLayout(lines, null);
        }
        if (layout == null) {
            throw new IllegalStateException("Could not recognise the Form 20 table layout");
        }
        int n = layout.candidates();

        List<PollingStation> stations = new ArrayList<>();
        Set<PollingStation> seenStations = new HashSet<>();
        Set<Integer> stationPages = new HashSet<>();
        Map<Integer, List<String>> unreadableByPage = new java.util.TreeMap<>();
        List<String> unreadable = new ArrayList<>();
        VoteCounts evm = null;
        VoteCounts postal = null;
        VoteCounts polled = null;
        // Candidate column positions, for reading the header names.
        float[] left = new float[n];
        float[] right = new float[n];
        java.util.Arrays.fill(left, Float.MAX_VALUE);
        java.util.Arrays.fill(right, -Float.MAX_VALUE);
        int firstRowPage = -1;
        float firstRowY = Float.MAX_VALUE;

        for (int li = 0; li < lines.size(); li++) {
            Line line = lines.get(li);
            List<Word> words = line.words();
            long[] v = values(words);
            int m = words.size();

            int start = m - layout.fromEnd();
            Summary summary = layout.summary();
            boolean fits = start >= 0 && fitsAt(v, start, n, summary);
            if (!fits) {
                // Extra or missing words in this line (a wrapped address), or a page printed with
                // the summary columns in another order: look anywhere in the line, any order.
                for (Summary alt : fallbackOrder(summary)) {
                    int found = findStart(v, n, alt);
                    if (found >= 0) {
                        start = found;
                        summary = alt;
                        fits = true;
                        break;
                    }
                }
            }
            boolean shaped = start >= 0 && allNumeric(v, start, start + n + summary.width);
            if (!fits && !shaped) {
                continue;
            }

            List<String> leading = words.subList(0, Math.max(0, start)).stream().map(Word::text).toList();
            String serial = serial(leading);
            if (serial != null && !fits && isCountingSequence(v)) {
                continue; // a header row of column numbers: 1 2 3 4 ...
            }

            List<Integer> votes = new ArrayList<>(n);
            long s = 0;
            for (int i = start; i < start + n; i++) {
                votes.add((int) v[i]);
                s += v[i];
            }
            int after = start + n;
            VoteCounts counts = summary.counts(votes, s, v, after, m);

            if (serial != null) {
                if (partTwoPages.contains(line.page())) {
                    continue;
                }
                if (!fits) {
                    // Shaped like a row but the numbers do not add up: report, do not guess.
                    unreadableByPage.computeIfAbsent(line.page(), p -> new ArrayList<>())
                            .add("page " + line.page() + ": " + line.text());
                    continue;
                }
                PollingStation station = new PollingStation(serial, counts);
                if (!seenStations.add(station)) {
                    continue; // some PDFs contain the whole table twice
                }
                stations.add(station);
                stationPages.add(line.page());
                for (int k = 0; k < n; k++) {
                    Word w = words.get(start + k);
                    left[k] = Math.min(left[k], w.x1());
                    right[k] = Math.max(right[k], w.x2());
                }
                if (firstRowPage < 0) {
                    firstRowPage = line.page();
                    firstRowY = line.y();
                }
            } else if (fits && counts.total() > 0) {
                TotalKind kind = TotalKind.of(String.join(" ", leading));
                if (kind == null) {
                    kind = nearestLabel(lines, li);
                }
                if (kind == TotalKind.POSTAL) {
                    postal = postal == null ? counts : postal;
                } else if (kind == TotalKind.POLLED) {
                    polled = polled == null ? counts : polled;
                } else if (kind == TotalKind.EVM) {
                    evm = evm == null ? counts : evm;
                } else if (evm == null) {
                    evm = counts;
                } else if (postal == null) {
                    postal = counts;
                } else if (polled == null) {
                    polled = counts;
                }
            }
        }

        // Rows that did not add up are only worth reporting on pages of the polling-station table;
        // elsewhere they are summaries (e.g. round totals per candidate).
        unreadableByPage.forEach((page, rows) -> {
            if (stationPages.contains(page)) {
                unreadable.addAll(rows);
            }
        });

        String acTitle = null;
        Integer electors = null;
        for (int i = 0; i < lines.size() && (acTitle == null || electors == null); i++) {
            String text = lines.get(i).text();
            if (acTitle == null) {
                Matcher m = AC_NAME.matcher(text);
                if (m.find() && !m.group(1).isBlank()) {
                    acTitle = m.group(1).trim();
                }
            }
            if (electors == null) {
                Matcher m = ELECTORS.matcher(text);
                if (m.find()) {
                    electors = Integer.parseInt(m.group(1));
                }
            }
        }

        List<String> names = headerNames(lines, left, right, firstRowPage, firstRowY);
        return new Form20Sheet(acTitle, electors, names, stations, evm, postal, polled, unreadable);
    }

    /** Finds the (candidate count, position, summary order) that fits the most lines. */
    static Layout detectLayout(List<Line> lines, Integer expected) {
        Map<Layout, Integer> votes = new HashMap<>();
        for (Line line : lines) {
            long[] v = values(line.words());
            int m = v.length;
            if (m < 6) {
                continue;
            }
            long[] prefix = new long[m + 1];
            for (int i = 0; i < m; i++) {
                prefix[i + 1] = prefix[i] + Math.max(v[i], 0);
            }
            int[] numericRun = new int[m + 1]; // consecutive numbers starting at i
            for (int i = m - 1; i >= 0; i--) {
                numericRun[i] = v[i] >= 0 ? numericRun[i + 1] + 1 : 0;
            }
            Set<Layout> seen = new HashSet<>();
            for (int start = 0; start < m; start++) {
                int minN = expected != null ? expected : 2;
                int maxN = expected != null ? expected : numericRun[start] - 4;
                for (int n = minN; n <= maxN; n++) {
                    for (Summary sm : Summary.values()) {
                        if (sm.fallbackOnly()) {
                            continue;
                        }
                        int end = start + n + sm.width;
                        if (end > m || numericRun[start] < end - start) {
                            continue;
                        }
                        if (sm.fits(prefix[start + n] - prefix[start], v, start + n)) {
                            seen.add(new Layout(n, m - start, sm));
                        }
                    }
                }
            }
            seen.forEach(l -> votes.merge(l, 1, Integer::sum));
        }
        return votes.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .max(Comparator.<Map.Entry<Layout, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparingInt(e -> e.getKey().candidates()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static boolean fitsAt(long[] v, int start, int n, Summary summary) {
        int end = start + n + summary.width;
        if (start < 0 || end > v.length || !allNumeric(v, start, end)) {
            return false;
        }
        long s = 0;
        for (int i = start; i < start + n; i++) {
            s += v[i];
        }
        return summary.fits(s, v, start + n);
    }

    /** The sheet's own summary order first, then the others. */
    private static List<Summary> fallbackOrder(Summary preferred) {
        List<Summary> order = new ArrayList<>();
        order.add(preferred);
        for (Summary s : Summary.values()) {
            if (s != preferred) {
                order.add(s);
            }
        }
        return order;
    }

    private static int findStart(long[] v, int n, Summary summary) {
        for (int start = 0; start < v.length; start++) {
            if (fitsAt(v, start, n, summary)) {
                return start;
            }
        }
        return -1;
    }

    private static boolean allNumeric(long[] v, int from, int to) {
        if (from < 0 || to > v.length) {
            return false;
        }
        for (int i = from; i < to; i++) {
            if (v[i] < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCountingSequence(long[] v) {
        int run = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] >= 0 && v[i] == v[i - 1] + 1) {
                run++;
            }
        }
        return run >= v.length / 2;
    }

    /** Numeric value of each word, or -1 for words that are not whole numbers. */
    private static long[] values(List<Word> words) {
        long[] v = new long[words.size()];
        for (int i = 0; i < v.length; i++) {
            String t = words.get(i).text();
            if (GROUPED_NUMBER.matcher(t).matches()) {
                t = t.replace(",", "");
            }
            if (t.equals("o") || t.equals("O")) {
                t = "0"; // a letter O typed in place of zero
            }
            v[i] = NUMBER.matcher(t).matches() && t.length() < 10 ? Long.parseLong(t) : -1;
        }
        return v;
    }

    /**
     * The polling-station serial from the words before the candidate votes. Some sheets print a
     * running Sl.No (or round number) before the polling-station number; then the second is used.
     * Returns null when the line does not start with a number (a totals row).
     */
    private static String serial(List<String> leading) {
        if (leading.isEmpty() || !SERIAL.matcher(leading.get(0)).matches()) {
            return null;
        }
        if (leading.size() >= 2) {
            String second = leading.get(1);
            if (second.length() == 1 && Character.isLetter(second.charAt(0))) {
                return leading.get(0) + second; // "12 A" -> auxiliary station 12A
            }
            if (SERIAL.matcher(second).matches()) {
                if (leading.size() >= 3 && leading.get(2).length() == 1 && Character.isLetter(leading.get(2).charAt(0))) {
                    return second + leading.get(2);
                }
                return second;
            }
        }
        return leading.get(0);
    }

    /** Which totals row a label names. */
    enum TotalKind {
        EVM, POSTAL, POLLED;

        static TotalKind of(String label) {
            String l = label.toLowerCase(Locale.ROOT);
            if (l.contains("postal") || l.contains("ballot")) {
                return POSTAL;
            }
            if (l.contains("polled") || l.contains("grand")) {
                return POLLED;
            }
            if (l.contains("evm") || l.contains("polling station")) {
                return EVM;
            }
            return null;
        }
    }

    /**
     * For a totals row whose own label says nothing specific ("Total", "Votes"), the label is
     * wrapped onto the lines just above or below it; take the nearest one that names a kind.
     */
    private static TotalKind nearestLabel(List<Line> lines, int index) {
        Line line = lines.get(index);
        float numbersX = firstNumberX(line);
        return lines.stream()
                .filter(o -> o != line && o.page() == line.page() && Math.abs(o.y() - line.y()) <= LABEL_WINDOW)
                .sorted(Comparator.comparingDouble(o -> Math.abs(o.y() - line.y())))
                .map(o -> TotalKind.of(o.words().stream()
                        .filter(w -> w.x2() < numbersX)
                        .map(Word::text)
                        .reduce("", (a, b) -> a + " " + b)))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static float firstNumberX(Line line) {
        for (Word w : line.words()) {
            if (NUMBER.matcher(w.text()).matches()) {
                return w.x1();
            }
        }
        return Float.MAX_VALUE;
    }

    /**
     * Candidate names from the header above the first polling-station row. Names are centred
     * over their column but may be wider than it, so each header line is split into segments at
     * small gaps and every segment goes to the column under its centre.
     */
    private static List<String> headerNames(List<Line> lines, float[] left, float[] right, int page, float firstRowY) {
        int n = left.length;
        List<String> result = new ArrayList<>(n);
        if (page < 0) {
            for (int i = 0; i < n; i++) {
                result.add("");
            }
            return result;
        }
        float[] lo = new float[n];
        float[] hi = new float[n];
        for (int k = 0; k < n; k++) {
            float prevGap = k > 0 ? (left[k] - right[k - 1]) / 2 : (k + 1 < n ? (left[k + 1] - right[k]) / 2 : 10);
            float nextGap = k + 1 < n ? (left[k + 1] - right[k]) / 2 : prevGap;
            lo[k] = left[k] - prevGap;
            hi[k] = right[k] + nextGap;
        }
        float captionY = lines.stream()
                .filter(l -> l.page() == page && l.y() < firstRowY
                        && l.text().toLowerCase(Locale.ROOT).contains("favour"))
                .map(Line::y)
                .reduce((a, b) -> b)
                .orElse(-1f);

        StringBuilder[] names = new StringBuilder[n];
        for (int i = 0; i < n; i++) {
            names[i] = new StringBuilder();
        }
        for (Line l : lines) {
            if (l.page() != page || l.y() <= captionY || l.y() >= firstRowY) {
                continue;
            }
            if (l.words().stream().allMatch(w -> w.text().matches("\\[?\\d+]?"))) {
                continue; // column numbers
            }
            for (Word seg : segments(l.chars(), NAME_GAP)) {
                float c = seg.center();
                for (int k = 0; k < n; k++) {
                    if (c >= lo[k] && c < hi[k]) {
                        names[k].append(' ').append(seg.text());
                        break;
                    }
                }
            }
        }
        for (StringBuilder sb : names) {
            result.add(sb.toString().replaceAll("\\s+", " ").trim());
        }
        return result;
    }

    /** Splits x-sorted characters wherever the gap exceeds {@code gap} or a blank is drawn. */
    private static List<Word> segments(List<TextPosition> chars, float gap) {
        List<Word> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        float x1 = 0;
        float x2 = 0;
        for (TextPosition t : chars) {
            String u = t.getUnicode();
            float start = t.getXDirAdj();
            if (!sb.isEmpty() && (u.isBlank() || start - x2 > gap)) {
                out.add(new Word(sb.toString(), x1, x2));
                sb.setLength(0);
            }
            if (!u.isBlank()) {
                if (sb.isEmpty()) {
                    x1 = start;
                }
                sb.append(u);
                x2 = start + t.getWidthDirAdj();
            }
        }
        if (!sb.isEmpty()) {
            out.add(new Word(sb.toString(), x1, x2));
        }
        return out;
    }

    static List<Line> readLines(PDDocument doc, int page) throws IOException {
        List<TextPosition> chars = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition text) {
                chars.add(text);
            }
        };
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.getText(doc);

        // Some sheets print candidate names rotated; in page coordinates those letters would land
        // inside the vote rows. Text running in each direction is grouped into lines separately.
        Map<Integer, List<TextPosition>> byDir = new java.util.TreeMap<>();
        for (TextPosition t : withoutOverprint(chars)) {
            byDir.computeIfAbsent(Math.round(t.getDir()), k -> new ArrayList<>()).add(t);
        }
        List<Line> lines = new ArrayList<>();
        for (List<TextPosition> group : byDir.values()) {
            lines.addAll(groupLines(page, group));
        }
        return lines;
    }

    /**
     * "Fake bold" draws the same text twice at almost the same spot; keep one copy so rows are
     * not read twice. (PDFTextStripper normally does this, but we collect characters directly.)
     */
    private static List<TextPosition> withoutOverprint(List<TextPosition> chars) {
        Map<String, List<TextPosition>> seen = new HashMap<>();
        List<TextPosition> out = new ArrayList<>(chars.size());
        for (TextPosition t : chars) {
            String key = t.getUnicode() + '|' + Math.round(t.getDir());
            List<TextPosition> same = seen.computeIfAbsent(key, k -> new ArrayList<>());
            float tol = Math.max(0.5f, t.getWidthDirAdj() / 2);
            boolean duplicate = false;
            for (int i = same.size() - 1; i >= 0 && i >= same.size() - 64; i--) {
                TextPosition o = same.get(i);
                if (Math.abs(o.getXDirAdj() - t.getXDirAdj()) < tol && Math.abs(o.getYDirAdj() - t.getYDirAdj()) < tol) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                same.add(t);
                out.add(t);
            }
        }
        return out;
    }

    private static List<Line> groupLines(int page, List<TextPosition> chars) {
        chars.sort(Comparator.comparingDouble(TextPosition::getYDirAdj));
        List<Line> lines = new ArrayList<>();
        List<TextPosition> current = new ArrayList<>();
        float lineY = Float.NaN;
        for (TextPosition t : chars) {
            if (!current.isEmpty() && Math.abs(t.getYDirAdj() - lineY) > LINE_TOLERANCE) {
                addLines(lines, page, lineY, current);
                current = new ArrayList<>();
            }
            if (current.isEmpty()) {
                lineY = t.getYDirAdj();
            }
            current.add(t);
        }
        if (!current.isEmpty()) {
            addLines(lines, page, lineY, current);
        }
        return lines;
    }

    /**
     * A page footer such as "Page 1" can sit on the same baseline as the last row of votes, its
     * letters interleaved with the row's digits. When text of different font sizes overlaps
     * horizontally like that, each size becomes its own line. Otherwise (e.g. a serial number
     * printed in a bigger font than the votes) the text stays one line.
     */
    private static void addLines(List<Line> lines, int page, float y, List<TextPosition> chars) {
        Map<Integer, List<TextPosition>> bySize = new java.util.TreeMap<>();
        for (TextPosition t : chars) {
            bySize.computeIfAbsent(Math.round(t.getFontSizeInPt() * 2), k -> new ArrayList<>()).add(t);
        }
        if (bySize.size() > 1 && groupsInterleave(new ArrayList<>(bySize.values()))) {
            for (List<TextPosition> group : bySize.values()) {
                lines.add(toLine(page, y, group));
            }
        } else {
            lines.add(toLine(page, y, chars));
        }
    }

    /** True if a character of one size group overlaps a character of another (letters drawn over digits). */
    private static boolean groupsInterleave(List<List<TextPosition>> groups) {
        for (int a = 0; a < groups.size(); a++) {
            for (int b = a + 1; b < groups.size(); b++) {
                for (TextPosition s : groups.get(a)) {
                    if (s.getUnicode().isBlank()) {
                        continue;
                    }
                    float s1 = s.getXDirAdj();
                    float s2 = s1 + s.getWidthDirAdj();
                    for (TextPosition t : groups.get(b)) {
                        if (t.getUnicode().isBlank()) {
                            continue;
                        }
                        float t1 = t.getXDirAdj();
                        float t2 = t1 + t.getWidthDirAdj();
                        float overlap = Math.min(s2, t2) - Math.max(s1, t1);
                        if (overlap > 0.2f * Math.min(s.getWidthDirAdj(), t.getWidthDirAdj())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static Line toLine(int page, float y, List<TextPosition> chars) {
        chars.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));
        List<Word> words = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder word = new StringBuilder();
        float wx1 = 0;
        float wx2 = 0;
        float prevEnd = Float.NaN;
        float prevWidth = 0;
        for (TextPosition t : chars) {
            String u = t.getUnicode();
            float x1 = t.getXDirAdj();
            float x2 = x1 + t.getWidthDirAdj();
            // Letters of one word touch; neighbouring numbers in tiny fonts can be < 1.5pt apart.
            float threshold = Math.min(WORD_GAP, Math.max(0.3f, 0.35f * prevWidth));
            boolean gap = !Float.isNaN(prevEnd) && x1 - prevEnd > threshold;
            prevWidth = t.getWidthDirAdj();
            if (gap && !text.isEmpty() && text.charAt(text.length() - 1) != ' ') {
                text.append(' ');
            }
            text.append(u);
            if (u.isBlank() || gap) {
                if (!word.isEmpty()) {
                    words.add(new Word(word.toString(), wx1, wx2));
                    word.setLength(0);
                }
            }
            if (!u.isBlank()) {
                if (word.isEmpty()) {
                    wx1 = x1;
                }
                word.append(u);
                wx2 = x2;
            }
            prevEnd = Math.max(Float.isNaN(prevEnd) ? x2 : prevEnd, x2);
        }
        if (!word.isEmpty()) {
            words.add(new Word(word.toString(), wx1, wx2));
        }
        return new Line(page, y, List.copyOf(chars), words, text.toString().replaceAll("\\s+", " ").trim());
    }
}
