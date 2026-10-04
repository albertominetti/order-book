package com.albertominetti.orderbook.service;

import com.albertominetti.orderbook.domain.InstrumentRef;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only catalogue of the tradable instruments offered by the search endpoint.
 *
 * <p>The catalogue is static reference data, loaded once at startup from
 * {@code classpath:instruments.csv}. The file is an RFC 4180 CSV whose header is
 * {@code symbol,name,market} and whose remaining lines are one instrument each (Swiss SIX names
 * followed by the S&amp;P 500 constituents). Names may contain commas, so they are quoted.
 * Listing an instrument here does not create its book: an instrument still only exists in the
 * {@link MarketRegistry} once an order has been submitted for it.</p>
 *
 * <p>Because the file is read from the classpath, it is declared as a native image resource (a
 * {@code resource-config.json} under {@code META-INF/native-image} and the {@code
 * OrderBookRuntimeHints} registrar), otherwise the GraalVM build would not bundle it.</p>
 *
 * <p>{@link #search(String, int)} ranks the instruments whose <em>symbol starts with</em> the query
 * first and the instruments whose <em>name contains</em> the query after them, sorted by symbol
 * inside each rank, so typing {@code A} puts the tickers such as {@code AAPL} or {@code ABNB} on
 * top and only then the companies whose name happens to contain an {@code a}. The comparison is
 * case insensitive and the lower-cased keys are computed once, at load time.</p>
 */
@Service
public class InstrumentCatalog {

    /** Classpath location of the CSV catalogue. */
    static final String RESOURCE = "classpath:instruments.csv";

    /** Path of the catalogue inside the classpath. */
    static final String RESOURCE_PATH = RESOURCE.substring("classpath:".length());

    /** Header of the CSV file, skipped while loading. */
    static final String HEADER = "symbol,name,market";

    /** Instruments sorted by symbol, used to order the matches of a single rank. */
    private static final Comparator<InstrumentRef> BY_SYMBOL =
            Comparator.comparing(InstrumentRef::symbol);

    private final List<InstrumentRef> instruments;
    private final List<Keyed> keyed;

    /** Loads the catalogue shipped with the application. */
    public InstrumentCatalog() {
        this(loadFromClasspath());
    }

    /** Builds a catalogue over an explicit list, used by tests. */
    InstrumentCatalog(List<InstrumentRef> instruments) {
        this.instruments = List.copyOf(instruments);
        this.keyed = this.instruments.stream().map(Keyed::new).toList();
    }

    /**
     * Searches the catalogue.
     *
     * <p>A blank query returns the first {@code limit} instruments in catalogue order, so opening
     * an empty search shows the Swiss names first. A non blank query is trimmed and matched case
     * insensitively: instruments whose symbol starts with it come first, then the instruments whose
     * name contains it, both sorted by symbol.</p>
     *
     * @param query free text typed by the user, {@code null} is treated as blank
     * @param limit maximum number of results, {@code 0} or less returns an empty list
     * @return at most {@code limit} instruments, never {@code null}
     */
    public List<InstrumentRef> search(String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return List.copyOf(instruments.subList(0, Math.min(limit, instruments.size())));
        }
        String needle = trimmed.toLowerCase(Locale.ROOT);

        List<InstrumentRef> symbolMatches = new ArrayList<>();
        List<InstrumentRef> nameMatches = new ArrayList<>();
        for (Keyed candidate : keyed) {
            if (candidate.symbolKey().startsWith(needle)) {
                symbolMatches.add(candidate.ref());
            } else if (candidate.nameKey().contains(needle)) {
                nameMatches.add(candidate.ref());
            }
        }
        symbolMatches.sort(BY_SYMBOL);
        nameMatches.sort(BY_SYMBOL);

        int size = symbolMatches.size() + nameMatches.size();
        List<InstrumentRef> matches = new ArrayList<>(Math.min(limit, size));
        symbolMatches.forEach(matches::add);
        nameMatches.forEach(matches::add);
        return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
    }

    /** Number of instruments in the catalogue. */
    public int size() {
        return instruments.size();
    }

    /** Every instrument of the catalogue, in catalogue order. */
    public List<InstrumentRef> all() {
        return instruments;
    }

    // ------------------------------------------------------------------ loading

    private static List<InstrumentRef> loadFromClasspath() {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (InputStream stream = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return parse(reader);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read the instrument catalogue " + RESOURCE, ex);
        }
    }

    /**
     * Parses the CSV catalogue, skipping the header line, the blank lines and the unusable lines.
     *
     * @return the instruments, in file order
     */
    static List<InstrumentRef> parse(BufferedReader reader) throws IOException {
        List<InstrumentRef> parsed = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = splitCsv(line);
            String symbol = fields.get(0).trim();
            if (HEADER.startsWith(symbol)) {
                continue;
            }
            if (fields.size() < 3) {
                continue;
            }
            parsed.add(new InstrumentRef(symbol, fields.get(1).trim(), fields.get(2).trim()));
        }
        return parsed;
    }

    /**
     * Splits one CSV line into its fields, honouring the RFC 4180 quoting rules (fields wrapped in
     * double quotes may contain commas, and a doubled quote stands for a single quote).
     *
     * @param line a single line without the trailing newline
     * @return the fields of the line, at least one
     */
    static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    /** An instrument together with the lower-cased keys the search compares against. */
    private record Keyed(InstrumentRef ref, String symbolKey, String nameKey) {

        Keyed(InstrumentRef ref) {
            this(ref,
                    ref.symbol().toLowerCase(Locale.ROOT),
                    ref.name().toLowerCase(Locale.ROOT));
        }
    }
}
