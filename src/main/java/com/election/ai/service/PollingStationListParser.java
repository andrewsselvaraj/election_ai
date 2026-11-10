package com.election.ai.service;

import com.election.ai.model.PollingStationInfo;
import com.election.ai.service.Form20PdfParser.Line;
import com.election.ai.service.Form20PdfParser.Word;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the official "List of Polling Stations" PDF of one assembly constituency: for every
 * polling station its number, building and the polling areas that vote there.
 *
 * <p>Each district prints the list differently (column order, extra columns, rotated pages,
 * cells top-aligned or vertically centred, with or without ruling lines), so nothing is read at
 * fixed positions:
 * <ul>
 *   <li>columns are found from the empty vertical gutters between the text of the data rows and
 *       named from the header ("Polling Areas") and their content (numbers, "All Voters");</li>
 *   <li>each row starts at a line whose first column holds the polling-station number; the
 *       wrapped text of the other columns is given to the row it belongs to, allowing for
 *       cells that are centred on the number rather than starting level with it.</li>
 * </ul>
 */
public final class PollingStationListParser {

    public record Result(Integer acNumber, String title, List<PollingStationInfo> stations) {
    }

    private static final Pattern NUMBER = Pattern.compile("\\d{1,4}[A-Za-z]?");
    private static final Pattern FOOTER = Pattern.compile("(?i).*\\bpage\\s*(no\\.?|number)?\\s*:?\\s*\\d+\\s*(of\\s*\\d+)?\\s*$");
    /** The signature block at the end of a list: "Date : 06-04-2026", "Place : ...", "Sd. ...", "District Election Officer". */
    private static final Pattern SIGNATURE = Pattern.compile(
            "(?i)^\\s*(date|place)\\s*:.*|.*\\bsd\\s*[./].*|.*district election officer.*|.*\\bcollector\\b.*");
    private static final Pattern AC_IN_TITLE = Pattern.compile(
            "(?i)(\\d{1,3})\\s*[-–.]?\\s*[A-Za-z][^\\d]*?\\b(assembly|assemby|assembley|a\\.\\s*c\\.?)\\b");
    private static final Set<String> TYPE_WORDS = Set.of("all", "voters", "voter", "men", "women", "only",
            "male", "female", "general", "for", "and", "&", "both");

    private PollingStationListParser() {
    }

    private enum Kind { NUMBER, LOCATION, AREA, TYPE }

    private record Column(float x1, float x2, Kind kind) {
    }

    private record Fragment(float y, Kind kind, float x, String text) {
    }

    /** A station row being assembled; its text can continue on the next page. */
    private static final class Row {
        final String psNo;
        final Map<Kind, StringBuilder> text = new HashMap<>();

        Row(String psNo) {
            this.psNo = psNo;
        }

        void add(Kind kind, String s) {
            StringBuilder sb = text.computeIfAbsent(kind, k -> new StringBuilder());
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(s);
        }

        PollingStationInfo build() {
            return new PollingStationInfo(psNo, clean(text.get(Kind.LOCATION)), clean(text.get(Kind.AREA)),
                    clean(text.get(Kind.TYPE)));
        }
    }

    public static Result parse(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return parse(doc);
        }
    }

    public static Result parse(PDDocument doc) throws IOException {
        List<Page> pages = new ArrayList<>();
        Integer acNumber = null;
        String title = null;
        boolean anyText = false;
        Float[] lastAreaCentre = {null};
        for (int p = 1; p <= doc.getNumberOfPages(); p++) {
            List<Line> lines = mainDirection(Form20PdfParser.readLines(doc, p));
            if (lines.isEmpty()) {
                continue;
            }
            anyText = true;
            lines.sort(Comparator.comparingDouble(Line::y));
            lines.removeIf(l -> FOOTER.matcher(l.text()).matches() || SIGNATURE.matcher(l.text()).matches());
            int headerEnd = headerEnd(lines);
            if (title == null && headerEnd >= 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i <= headerEnd && i < 3; i++) {
                    sb.append(lines.get(i).text()).append(' ');
                }
                Matcher m = AC_IN_TITLE.matcher(sb);
                if (m.find()) {
                    acNumber = Integer.parseInt(m.group(1));
                    title = cleanTitle(lines, headerEnd);
                }
            }
            Page page = readPage(lines, headerEnd, lastAreaCentre);
            if (page != null) {
                pages.add(page);
            }
        }
        if (!anyText) {
            throw new Form20PdfParser.NoTextException("This polling station list is a scanned image with no text layer");
        }
        // Cells are centred on the row in most lists: then pages commonly have text above their
        // first station number. With top-aligned cells that happens only when a row runs over
        // from the previous page.
        long above = pages.stream().filter(Page::textAboveFirstRow).count();
        boolean centred = above * 2 >= pages.size();
        List<PollingStationInfo> stations = assignAll(pages, centred, false);
        // Most lists number each station's areas from 1; some use the electoral-roll section
        // numbers instead. Only in the first kind can the numbering correct the layout.
        if (restartsAtOne(stations)) {
            stations = new ArrayList<>(assignAll(pages, centred, true));
            repairAreaNumbering(stations);
        }
        return new Result(acNumber, title, stations);
    }

    private static List<PollingStationInfo> assignAll(List<Page> pages, boolean centred, boolean byNumbering) {
        List<Row> rows = new ArrayList<>();
        for (Page page : pages) {
            assign(page, centred, byNumbering, rows);
        }
        return rows.stream().map(Row::build).toList();
    }

    /** True if at least 90% of the stations' area lists begin with item 1. */
    private static boolean restartsAtOne(List<PollingStationInfo> stations) {
        int numbered = 0;
        int fromOne = 0;
        for (PollingStationInfo s : stations) {
            List<int[]> items = items(s.areas());
            if (!items.isEmpty()) {
                numbered++;
                if (items.get(0)[1] == 1) {
                    fromOne++;
                }
            }
        }
        return numbered > 0 && fromOne >= 0.9 * numbered;
    }

    /** An area item number: "[3] -Name", "(3)- Name", "3.Name", "3 - Name" or "3Name". */
    private static final Pattern AREA_ITEM = Pattern.compile("(?:^|(?<=[\\s,]))(?:"
            + "\\[(\\d{1,3})\\]"
            + "|\\((\\d{1,3})\\)"
            + "|(\\d{1,3})\\s?\\.(?=\\s*-?\\s*[A-Za-z(])"
            + "|(\\d{1,3})\\s?-(?=\\s*[A-Za-z(])"
            + "|(\\d{1,3})(?=[A-Z][a-z]))");

    /**
     * Polling areas are numbered 1, 2, 3... afresh for every station. A long list can overflow
     * its cell into the next row's space, so a row may start with the previous row's last items
     * ("6.West Street 1.North Street ...") or end with the first items of the next row. Such
     * items are moved back where their numbering says they belong.
     */
    static void repairAreaNumbering(List<PollingStationInfo> stations) {
        for (int k = 0; k + 1 < stations.size(); k++) {
            PollingStationInfo a = stations.get(k);
            PollingStationInfo b = stations.get(k + 1);
            List<int[]> ia = items(a.areas());
            List<int[]> ib = items(b.areas());
            if (ia.isEmpty()) {
                continue;
            }
            // a restarts at 1 part-way through, and b carries on from there (or has no areas)
            int restart = -1;
            for (int i = 1; i < ia.size(); i++) {
                if (ia.get(i)[1] == 1) {
                    restart = i;
                    break;
                }
            }
            if (restart > 0) {
                int tailLast = ia.get(ia.size() - 1)[1];
                boolean fits = ib.isEmpty() ? b.areas().isBlank() : ib.get(0)[1] == tailLast + 1;
                if (fits) {
                    int cut = ia.get(restart)[0];
                    stations.set(k, with(a, a.areas().substring(0, cut)));
                    stations.set(k + 1, with(b, a.areas().substring(cut) + " " + b.areas()));
                }
                continue;
            }
            // b starts with a's continuation (lastA+1, ...) and then restarts at 1
            int lastA = ia.get(ia.size() - 1)[1];
            if (!ib.isEmpty() && ib.get(0)[1] == lastA + 1) {
                int restartB = -1;
                for (int i = 1; i < ib.size(); i++) {
                    if (ib.get(i)[1] == 1) {
                        restartB = ib.get(i)[0];
                        break;
                    }
                }
                if (restartB > 0) {
                    stations.set(k, with(a, a.areas() + " " + b.areas().substring(0, restartB)));
                    stations.set(k + 1, with(b, b.areas().substring(restartB)));
                }
            }
        }
    }

    /** True if the text begins with a numbered area item. */
    static boolean startsWithItem(String areas) {
        List<int[]> items = items(areas);
        return !items.isEmpty() && items.get(0)[0] == 0;
    }

    /** {offset, number} of each numbered item, in order. */
    private static List<int[]> items(String areas) {
        List<int[]> out = new ArrayList<>();
        Matcher m = AREA_ITEM.matcher(areas);
        while (m.find()) {
            String n = null;
            for (int g = 1; n == null && g <= m.groupCount(); g++) {
                n = m.group(g);
            }
            out.add(new int[]{m.start(), Integer.parseInt(n)});
        }
        return out;
    }

    private static PollingStationInfo with(PollingStationInfo p, String areas) {
        return new PollingStationInfo(p.psNo(), p.location(),
                areas.replaceAll("\\s+", " ").replaceAll("^[,\\s]+|[,\\s]+$", ""), p.type());
    }

    /** One page's station rows (numbers only, so far) and the column text still to share out. */
    private record Page(List<Float> anchorY, List<String> psNos, List<Fragment> fragments) {
        boolean textAboveFirstRow() {
            float first = anchorY.get(0);
            return fragments.stream().anyMatch(f -> f.y() < first - SAME_LINE);
        }
    }

    /** Text this close to a station number's baseline is on the same line as it. */
    private static final float SAME_LINE = 2.5f;

    private static Page readPage(List<Line> lines, int headerEnd, Float[] lastAreaCentre) {
        List<Line> header = lines.subList(0, headerEnd + 1);
        List<Line> data = lines.subList(headerEnd + 1, lines.size());
        if (data.isEmpty()) {
            return null;
        }
        List<Column> columns = columns(header, data, lastAreaCentre);
        if (columns.isEmpty() || columns.get(0).kind() != Kind.NUMBER) {
            return null; // not a table page (e.g. a cover or summary page)
        }

        // Row anchors: lines whose number column holds a polling-station number.
        List<Float> anchorY = new ArrayList<>();
        List<String> psNos = new ArrayList<>();
        List<Fragment> fragments = new ArrayList<>();
        for (Line line : data) {
            List<String> numbers = new ArrayList<>();
            Map<Kind, List<Word>> byKind = new HashMap<>();
            for (Word w : line.words()) {
                Column c = columnOf(columns, w);
                if (c.kind() == Kind.NUMBER) {
                    numbers.add(w.text());
                } else {
                    byKind.computeIfAbsent(c.kind(), k -> new ArrayList<>()).add(w);
                }
            }
            String psNo = psNo(numbers);
            int last = psNos.size() - 1;
            if (psNo != null && last >= 0 && psNo.equals(psNos.get(last))) {
                // Sl.No and station number printed on separate lines: one row, centred between them
                anchorY.set(last, (anchorY.get(last) + line.y()) / 2);
            } else if (psNo != null) {
                anchorY.add(line.y());
                psNos.add(psNo);
            }
            for (Map.Entry<Kind, List<Word>> e : byKind.entrySet()) {
                StringBuilder sb = new StringBuilder();
                for (Word w : e.getValue()) {
                    sb.append(sb.isEmpty() ? "" : " ").append(w.text());
                }
                fragments.add(new Fragment(line.y(), e.getKey(), e.getValue().get(0).x1(), sb.toString()));
            }
        }
        return psNos.isEmpty() ? null : new Page(anchorY, psNos, fragments);
    }

    /**
     * Gives the text of each column on a page to its rows (index 0 of each split is text that
     * comes before the first row: the end of the previous page's last row).
     */
    private static void assign(Page page, boolean centred, boolean byNumbering, List<Row> rows) {
        List<Row> pageRows = page.psNos().stream().map(Row::new).toList();
        Map<Kind, List<Fragment>> byKind = new HashMap<>();
        for (Fragment f : page.fragments()) {
            byKind.computeIfAbsent(f.kind(), k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<Kind, List<Fragment>> e : byKind.entrySet()) {
            List<Fragment> column = e.getValue();
            column.sort(Comparator.comparingDouble(Fragment::y).thenComparingDouble(Fragment::x));
            String[] split = byNumbering && e.getKey() == Kind.AREA ? splitByNumbering(column, page.anchorY().size()) : null;
            if (split == null) {
                split = centred ? splitCentred(column, page.anchorY(), e.getKey() == Kind.AREA) : splitTopAligned(column, page.anchorY());
            }
            if (!split[0].isEmpty()) {
                (rows.isEmpty() ? pageRows.get(0) : rows.get(rows.size() - 1)).add(e.getKey(), split[0]);
            }
            for (int k = 0; k < pageRows.size(); k++) {
                if (!split[k + 1].isEmpty()) {
                    pageRows.get(k).add(e.getKey(), split[k + 1]);
                }
            }
        }
        rows.addAll(pageRows);
    }

    /**
     * Polling areas are numbered afresh for each station ("1.North Street, 2.South Street" or
     * "[1] -..."). When the page has exactly one item 1 per station, cutting the column's text
     * at those is the most reliable split: long lists can overflow their cell.
     */
    private static String[] splitByNumbering(List<Fragment> column, int rows) {
        StringBuilder all = new StringBuilder();
        for (Fragment f : column) {
            all.append(all.isEmpty() ? "" : " ").append(f.text());
        }
        List<Integer> starts = new ArrayList<>();
        for (int[] item : items(all.toString())) {
            if (item[1] == 1) {
                starts.add(item[0]);
            }
        }
        if (starts.size() != rows) {
            return null;
        }
        String[] out = new String[rows + 1];
        out[0] = all.substring(0, starts.get(0)).trim();
        for (int k = 0; k < rows; k++) {
            int end = k + 1 < rows ? starts.get(k + 1) : all.length();
            out[k + 1] = all.substring(starts.get(k), end).trim();
        }
        return out;
    }

    /** Top-aligned cells: a line belongs to the last station number at or above it. */
    private static String[] splitTopAligned(List<Fragment> column, List<Float> anchorY) {
        List<List<Fragment>> parts = new ArrayList<>();
        for (int k = 0; k <= anchorY.size(); k++) {
            parts.add(new ArrayList<>());
        }
        for (Fragment f : column) {
            int row = 0;
            while (row < anchorY.size() && anchorY.get(row) <= f.y() + SAME_LINE) {
                row++;
            }
            parts.get(row).add(f);
        }
        return join(parts);
    }

    /**
     * Cells centred on the row: each row's number sits mid-row and so does every cell, so a
     * cell's text starts as far above its number as it ends below (give or take half a line).
     * Which lines between two numbers belong to which row is chosen so that, over the page, the
     * cells' middles are as close to their numbers as possible (a small dynamic programme).
     */
    private static String[] splitCentred(List<Fragment> column, List<Float> c, boolean numberedItems) {
        int m = c.size();
        int n = column.size();
        float[] y = new float[n];
        for (int i = 0; i < n; i++) {
            y[i] = column.get(i).y();
        }
        float pitch = linePitch(y);
        // Area lists start each station with a numbered item; a cell beginning mid-item is unlikely.
        Cell cell = new Cell(y, pitch, itemStarts(column, numberedItems), Math.min(pitch, 20f));
        // split[k] = index of the first fragment of row k+1; allowed between the lines clearly
        // below number k and clearly above number k+1
        int[] lo = new int[m];
        int[] hi = new int[m];
        for (int k = 0; k + 1 < m; k++) {
            lo[k] = firstIndex(y, c.get(k) + SAME_LINE, false);
            hi[k] = firstIndex(y, c.get(k + 1) - SAME_LINE, true);
            if (hi[k] < lo[k]) {
                hi[k] = lo[k];
            }
        }
        double[][] best = new double[m][n + 1];
        int[][] from = new int[m][n + 1];
        for (double[] row : best) {
            Arrays.fill(row, Double.MAX_VALUE);
        }
        if (m == 1) {
            return join(List.of(List.of(), column));
        }
        for (int s = lo[0]; s <= hi[0]; s++) {
            best[0][s] = cell.cost(0, s, c.get(0));
        }
        for (int k = 1; k + 1 < m; k++) {
            for (int s = lo[k]; s <= hi[k]; s++) {
                for (int p = lo[k - 1]; p <= Math.min(hi[k - 1], s); p++) {
                    if (best[k - 1][p] == Double.MAX_VALUE) {
                        continue;
                    }
                    double v = best[k - 1][p] + cell.cost(p, s, c.get(k));
                    if (v < best[k][s]) {
                        best[k][s] = v;
                        from[k][s] = p;
                    }
                }
            }
        }
        int last = m - 2;
        int bestS = lo[last];
        double bestV = Double.MAX_VALUE;
        for (int s = lo[last]; s <= hi[last]; s++) {
            if (best[last][s] == Double.MAX_VALUE) {
                continue;
            }
            double v = best[last][s] + cell.cost(s, n, c.get(m - 1));
            if (v < bestV) {
                bestV = v;
                bestS = s;
            }
        }
        int[] split = new int[m];
        split[m - 1] = n;
        split[m - 2] = bestS;
        for (int k = m - 2; k > 0; k--) {
            split[k - 1] = from[k][split[k]];
        }
        List<List<Fragment>> parts = new ArrayList<>();
        parts.add(List.of());
        int start = 0;
        for (int k = 0; k < m; k++) {
            parts.add(column.subList(start, split[k]));
            start = split[k];
        }
        return join(parts);
    }

    /**
     * Scores lines [a, b) of one column as a single cell of the row numbered at {@code centre}.
     *
     * @param itemStart   per line, whether it begins with a numbered area item ("1.", "[2]")
     * @param midItemCost penalty for a cell starting part-way through an item
     */
    private record Cell(float[] y, float pitch, boolean[] itemStart, float midItemCost) {
        /**
         * How far the lines' middle is from the number, plus a penalty for a hole inside the
         * cell wider than a line (that would be two cells), plus one for a cell starting
         * mid-item, plus a hair for each line above the number so that, when both splits fit
         * equally, a line just below a number stays with it.
         */
        double cost(int a, int b, float centre) {
            if (a >= b) {
                return 0;
            }
            double cost = Math.abs((y[a] + y[b - 1]) / 2 - centre);
            for (int i = a + 1; i < b; i++) {
                cost += 2 * Math.max(0, (y[i] - y[i - 1]) - 1.4 * pitch);
            }
            if (!itemStart[a]) {
                cost += midItemCost;
            }
            for (int i = a; i < b; i++) {
                if (y[i] < centre - SAME_LINE) {
                    cost += 0.01;
                }
            }
            return cost;
        }
    }

    /**
     * Which lines begin a numbered area item. Only meaningful when most cells start with one;
     * if the column has (almost) no numbering, every line counts as a start (no penalty).
     */
    private static boolean[] itemStarts(List<Fragment> column, boolean numberedItems) {
        boolean[] out = new boolean[column.size()];
        int count = 0;
        for (int i = 0; i < out.length; i++) {
            Matcher m = AREA_ITEM.matcher(column.get(i).text());
            out[i] = numberedItems && m.find() && m.start() == 0;
            if (out[i]) {
                count++;
            }
        }
        if (count == 0) {
            Arrays.fill(out, true);
        }
        return out;
    }

    /** Typical spacing of consecutive lines in a column (the median gap). */
    private static float linePitch(float[] y) {
        List<Float> gaps = new ArrayList<>();
        for (int i = 1; i < y.length; i++) {
            if (y[i] - y[i - 1] > 1) {
                gaps.add(y[i] - y[i - 1]);
            }
        }
        if (gaps.size() < 3) {
            return Float.MAX_VALUE / 4;
        }
        gaps.sort(null);
        return gaps.get(gaps.size() / 2);
    }

    /** Index of the first y above (or, if inclusive, at least) the limit. */
    private static int firstIndex(float[] y, float limit, boolean inclusive) {
        int i = 0;
        while (i < y.length && (inclusive ? y[i] < limit : y[i] <= limit)) {
            i++;
        }
        return i;
    }

    private static String[] join(List<List<Fragment>> parts) {
        String[] out = new String[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (Fragment f : parts.get(i)) {
                sb.append(sb.isEmpty() ? "" : " ").append(f.text());
            }
            out[i] = sb.toString();
        }
        return out;
    }

    /** The polling-station number from the number column(s): the last number, plus "A" etc. for auxiliaries. */
    static String psNo(List<String> numbers) {
        String last = null;
        for (int i = 0; i < numbers.size(); i++) {
            String t = numbers.get(i).replaceAll("[().\\-]", "");
            if (t.isEmpty()) {
                continue;
            }
            if (NUMBER.matcher(t).matches()) {
                last = t.toUpperCase(Locale.ROOT);
            } else if (last != null && t.length() == 1 && Character.isLetter(t.charAt(0))) {
                last = last + t.toUpperCase(Locale.ROOT);
            } else {
                return last;
            }
        }
        return last;
    }

    /**
     * The table header ends at the "1 2 3 4 5" column-number line when there is one, otherwise
     * at the last header line naming the polling areas.
     */
    private static int headerEnd(List<Line> lines) {
        int limit = Math.min(lines.size(), 20);
        for (int i = 0; i < limit; i++) {
            List<Word> w = lines.get(i).words();
            if (w.size() >= 4 && columnNumber(w.get(0)).equals("1") && ascending(w)) {
                return i;
            }
        }
        int last = -1;
        for (int i = 0; i < limit; i++) {
            String t = lines.get(i).text().toLowerCase(Locale.ROOT);
            if (t.contains("polling area") || t.contains("areas") || t.contains("area of") || t.contains("station type")) {
                last = i;
            }
        }
        // the heading cells wrap: take in the lines that only continue them ("Located", "only")
        while (last >= 0 && last + 1 < limit && headingOnly(lines.get(last + 1))) {
            last++;
        }
        return last;
    }

    private static final Set<String> HEADING_WORDS = Set.of("sl", "slno", "no", "psno", "ps", "part", "s",
            "polling", "station", "stations", "location", "locality", "and", "name", "of", "the", "building",
            "in", "which", "it", "will", "be", "is", "located", "area", "areas", "whether", "for", "all",
            "voters", "or", "men", "women", "only", "type", "serial", "number", "address");

    /** A line made only of table-heading words (no station number, no place names). */
    private static boolean headingOnly(Line line) {
        if (line.words().isEmpty()) {
            return false;
        }
        for (Word w : line.words()) {
            if (!HEADING_WORDS.contains(w.text().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""))) {
                return false;
            }
        }
        return true;
    }

    /** "1", "(1)" or "1." as printed over a column. */
    private static String columnNumber(Word w) {
        return w.text().replaceAll("[()\\[\\].]", "");
    }

    private static boolean ascending(List<Word> words) {
        int prev = 0;
        for (Word w : words) {
            String t = columnNumber(w);
            if (!t.matches("\\d{1,2}")) {
                return false;
            }
            int v = Integer.parseInt(t);
            if (v < prev || (v == prev && v != 1)) {
                return false;
            }
            prev = v;
        }
        return true;
    }

    /**
     * Columns are the stretches of x covered by text, separated by gutters no data line crosses.
     * The leading all-number stretches are the Sl.No / station number; a trailing stretch of
     * "All Voters" words is the station type; of the rest, those from the "Polling Areas"
     * heading rightwards are the areas and the others the building / location.
     */
    private static List<Column> columns(List<Line> header, List<Line> data, Float[] lastAreaCentre) {
        float maxX = 0;
        for (Line l : data) {
            for (Word w : l.words()) {
                maxX = Math.max(maxX, w.x2());
            }
        }
        int bins = (int) Math.ceil(maxX) + 2;
        int[] cover = new int[bins];
        for (Line l : data) {
            for (TextPosition t : l.chars()) {
                if (t.getUnicode().isBlank()) {
                    continue;
                }
                int a = Math.max(0, (int) Math.floor(t.getXDirAdj()));
                int b = Math.min(bins - 1, (int) Math.ceil(t.getXDirAdj() + t.getWidthDirAdj()) - 1);
                for (int x = a; x <= b; x++) {
                    cover[x]++;
                }
            }
        }
        List<float[]> spans = new ArrayList<>();
        // A gutter may be crossed by the odd word too long for its cell; allow ~1 line in 40.
        int crossing = data.size() / 40;
        int start = -1;
        for (int x = 0; x < bins; x++) {
            if (cover[x] > crossing && start < 0) {
                start = x;
            } else if (cover[x] <= crossing && start >= 0) {
                spans.add(new float[]{start, x});
                start = -1;
            }
        }
        if (start >= 0) {
            spans.add(new float[]{start, bins});
        }

        int n = spans.size();
        Kind[] kinds = new Kind[n];
        int numberEnd = 0;
        while (numberEnd < n && mostlyNumbers(data, spans.get(numberEnd))) {
            kinds[numberEnd++] = Kind.NUMBER;
        }
        int typeStart = n;
        while (typeStart - 1 > numberEnd && typeWords(data, spans.get(typeStart - 1))) {
            kinds[--typeStart] = Kind.TYPE;
        }
        // The heading is centred over its column, so the column's left edge mirrors its right
        // edge (where the next column starts) about the heading's centre. Areas can be printed
        // as sub-columns ("1.Karur (C) | Street Ward 7"), which all fall right of that edge.
        // Continuation pages may have no heading: the columns sit where the last heading put them.
        Float areaCentre = areaHeadingCentre(header);
        if (areaCentre != null) {
            lastAreaCentre[0] = areaCentre;
        } else {
            areaCentre = lastAreaCentre[0];
        }
        int areaStart = -1;
        if (areaCentre != null) {
            float right = typeStart < n ? spans.get(typeStart)[0] : spans.get(n - 1)[1];
            float left = 2 * areaCentre - right;
            for (int i = numberEnd + 1; i < typeStart && areaStart < 0; i++) {
                if (spans.get(i)[0] >= left - 6 && spans.get(i)[0] < areaCentre) {
                    areaStart = i;
                }
            }
        }
        if (areaStart < 0) {
            areaStart = Math.max(numberEnd, typeStart - 1);
        }
        for (int i = numberEnd; i < typeStart; i++) {
            kinds[i] = i >= areaStart ? Kind.AREA : Kind.LOCATION;
        }
        List<Column> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Column(spans.get(i)[0], spans.get(i)[1], kinds[i]));
        }
        return out;
    }

    /** Middle of the "Polling Area(s)" heading, or null. */
    private static Float areaHeadingCentre(List<Line> header) {
        for (Line l : header) {
            List<Word> w = l.words();
            for (int i = 0; i < w.size(); i++) {
                if (w.get(i).text().toLowerCase(Locale.ROOT).replaceAll("[^a-z()]", "").matches("areas?|area\\(s\\)")) {
                    boolean polling = i > 0 && w.get(i - 1).text().equalsIgnoreCase("polling")
                            && w.get(i).x1() - w.get(i - 1).x2() < 8;
                    return ((polling ? w.get(i - 1).x1() : w.get(i).x1()) + w.get(i).x2()) / 2;
                }
            }
        }
        return null;
    }

    private static boolean mostlyNumbers(List<Line> data, float[] span) {
        int numbers = 0;
        int other = 0;
        for (Line l : data) {
            for (Word w : l.words()) {
                if (w.center() >= span[0] && w.center() < span[1]) {
                    if (NUMBER.matcher(w.text().replaceAll("[().\\-]", "")).matches()
                            || w.text().matches("[A-Za-z]")) {
                        numbers++;
                    } else {
                        other++;
                    }
                }
            }
        }
        return numbers > 0 && numbers >= 2 * other;
    }

    private static boolean typeWords(List<Line> data, float[] span) {
        int type = 0;
        int other = 0;
        for (Line l : data) {
            for (Word w : l.words()) {
                if (w.center() >= span[0] && w.center() < span[1]) {
                    if (TYPE_WORDS.contains(w.text().toLowerCase(Locale.ROOT).replaceAll("[^a-z&]", ""))) {
                        type++;
                    } else {
                        other++;
                    }
                }
            }
        }
        return type > 0 && type >= 4 * other;
    }

    private static Column columnOf(List<Column> columns, Word w) {
        float c = w.center();
        Column best = columns.get(0);
        float bestDist = Float.MAX_VALUE;
        for (Column col : columns) {
            if (c >= col.x1() && c < col.x2()) {
                return col;
            }
            float d = Math.min(Math.abs(c - col.x1()), Math.abs(c - col.x2()));
            if (d < bestDist) {
                bestDist = d;
                best = col;
            }
        }
        return best;
    }

    /** Keeps the text running in the page's main direction (drops rotated stamps and labels). */
    private static List<Line> mainDirection(List<Line> lines) {
        Map<Integer, Integer> count = new HashMap<>();
        for (Line l : lines) {
            if (!l.chars().isEmpty()) {
                count.merge(Math.round(l.chars().get(0).getDir()), l.chars().size(), Integer::sum);
            }
        }
        int main = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            if (!l.chars().isEmpty() && Math.round(l.chars().get(0).getDir()) == main && !l.text().isBlank()) {
                out.add(l);
            }
        }
        return out;
    }

    private static String cleanTitle(List<Line> lines, int headerEnd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= headerEnd && i < 3; i++) {
            String t = lines.get(i).text();
            if (t.toLowerCase(Locale.ROOT).matches(".*\\b(sl\\.?\\s*no|location|polling areas?)\\b.*")) {
                break;
            }
            sb.append(t).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String clean(StringBuilder sb) {
        if (sb == null) {
            return "";
        }
        return sb.toString().replaceAll("\\s+", " ").replaceAll(" ,", ",").replaceAll("^[,\\s]+|[,\\s]+$", "");
    }
}
