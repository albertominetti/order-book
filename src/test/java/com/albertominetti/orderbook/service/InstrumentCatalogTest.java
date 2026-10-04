package com.albertominetti.orderbook.service;

import com.albertominetti.orderbook.domain.InstrumentRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of the instrument catalogue: how the tab separated file is parsed, how a query is
 * ranked (symbol prefix first, name substring after) and how the limit is honoured.
 */
class InstrumentCatalogTest {

    /** A small, hand written catalogue with known answers, used by the ranking tests. */
    private static final List<InstrumentRef> SAMPLE = List.of(
            new InstrumentRef("AAPL", "Apple Inc.", "S&P 500"),
            new InstrumentRef("ABBV", "AbbVie", "S&P 500"),
            new InstrumentRef("ABNB", "Airbnb", "S&P 500"),
            new InstrumentRef("ALL", "Allstate", "S&P 500"),
            new InstrumentRef("BAC", "Bank of America", "S&P 500"),
            new InstrumentRef("ROG", "Roche Holding", "SIX"),
            new InstrumentRef("UBSG", "UBS Group", "SIX"),
            new InstrumentRef("ZBRA", "Zebra Technologies", "S&P 500")
    );

    private InstrumentCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new InstrumentCatalog(SAMPLE);
    }

    // ------------------------------------------------------------------ blank query

    @Test
    @DisplayName("A blank query returns the head of the catalogue, in catalogue order")
    void blankQueryReturnsTheDefaultSlice() {
        assertThat(symbols(catalog.search("", 3))).containsExactly("AAPL", "ABBV", "ABNB");
        assertThat(symbols(catalog.search("   ", 2))).containsExactly("AAPL", "ABBV");
        assertThat(symbols(catalog.search(null, 2))).containsExactly("AAPL", "ABBV");

        // A limit larger than the catalogue is capped by the catalogue size, it never pads.
        assertThat(catalog.search("", 500)).hasSize(SAMPLE.size());
    }

    // ------------------------------------------------------------------ symbol prefix

    @Test
    @DisplayName("A query matching a symbol prefix ranks those instruments first, whatever the case")
    void symbolPrefixMatchesRankFirst() {
        // "a" matches the symbols AAPL, ABBV, ABNB and ALL, and only by prefix.
        assertThat(symbols(catalog.search("a", 4)))
                .containsExactly("AAPL", "ABBV", "ABNB", "ALL");

        assertThat(symbols(catalog.search("A", 4)))
                .containsExactly("AAPL", "ABBV", "ABNB", "ALL");
        assertThat(symbols(catalog.search(" ab ", 50)))
                .containsExactly("ABBV", "ABNB");
    }

    @Test
    @DisplayName("Every symbol prefix match comes before any name match, even when it sorts after it")
    void symbolPrefixRankWinsOverNameRank() {
        // Prefix matches: AAPL, ABBV, ABNB, ALL. Name matches: BAC ("bank") and ZBRA ("zebra").
        assertThat(symbols(catalog.search("a", 50)))
                .containsExactly("AAPL", "ABBV", "ABNB", "ALL", "BAC", "ZBRA");

        // A limit that only covers the prefix rank hides the name matches.
        assertThat(symbols(catalog.search("a", 4)))
                .containsExactly("AAPL", "ABBV", "ABNB", "ALL");
    }

    // ------------------------------------------------------------------ name substring

    @Test
    @DisplayName("A query matching a name matches by substring, case insensitively")
    void nameMatchesBySubstring() {
        assertThat(catalog.search("roche", 50)).containsExactly(new InstrumentRef("ROG", "Roche Holding", "SIX"));
        assertThat(catalog.search("ROCHE", 50)).containsExactly(new InstrumentRef("ROG", "Roche Holding", "SIX"));
        assertThat(catalog.search("RoChE", 50)).containsExactly(new InstrumentRef("ROG", "Roche Holding", "SIX"));
        assertThat(catalog.search("group", 50)).containsExactly(new InstrumentRef("UBSG", "UBS Group", "SIX"));
    }

    @Test
    @DisplayName("A query that matches nothing returns an empty list")
    void unknownQueryMatchesNothing() {
        assertThat(catalog.search("nosuchticker", 50)).isEmpty();
        assertThat(catalog.search("aaplx", 50)).isEmpty();
    }

    // ------------------------------------------------------------------ limit

    @Test
    @DisplayName("The limit caps the number of results and a non positive limit returns nothing")
    void limitIsHonoured() {
        assertThat(catalog.search("a", 1)).containsExactly(new InstrumentRef("AAPL", "Apple Inc.", "S&P 500"));
        assertThat(catalog.search("a", 3)).hasSize(3);

        // Whatever the limit, the ranking is the prefix of the same ordered sequence.
        assertThat(catalog.search("a", 3))
                .containsExactlyElementsOf(catalog.search("a", 50).subList(0, 3));

        assertThat(catalog.search("a", 0)).isEmpty();
        assertThat(catalog.search("a", -1)).isEmpty();
        assertThat(catalog.search("", 0)).isEmpty();
    }

    // ------------------------------------------------------------------ loading

    @Test
    @DisplayName("The parser skips the header line, the blank lines and the truncated lines")
    void parserSkipsHeaderAndUnusableLines() throws IOException {
        String tsv = """
                # symbol\tname\tmarket

                UBSG\tUBS Group\tSIX
                truncated
                AAPL\tApple Inc.\tS&P 500\textra field ignored
                """;

        List<InstrumentRef> parsed = InstrumentCatalog.parse(new BufferedReader(new StringReader(tsv)));

        assertThat(parsed).containsExactly(
                new InstrumentRef("UBSG", "UBS Group", "SIX"),
                new InstrumentRef("AAPL", "Apple Inc.", "S&P 500"));
    }

    @Test
    @DisplayName("The catalogue shipped on the classpath holds the Swiss SIX names and the S&P 500")
    void classpathCatalogueIsLoaded() {
        InstrumentCatalog shipped = new InstrumentCatalog();

        // 47 Swiss SIX names plus 503 S&P 500 constituents, the header line excluded.
        assertThat(shipped.size()).isEqualTo(550);
        assertThat(shipped.all()).filteredOn(instrument -> "SIX".equals(instrument.market())).hasSize(47);
        assertThat(shipped.all()).extracting(InstrumentRef::symbol).doesNotHaveDuplicates();

        // A blank query opens the catalogue on the Swiss names.
        assertThat(symbols(shipped.search("", 3))).containsExactly("UBSG", "CSGN", "BAER");

        // The very symbols the UI dropdown used to ship as a static list are searchable.
        assertThat(shipped.search("ubsg", 5)).containsExactly(new InstrumentRef("UBSG", "UBS Group", "SIX"));
        assertThat(shipped.search("roche", 5)).containsExactly(new InstrumentRef("ROG", "Roche Holding", "SIX"));
        assertThat(shipped.search("nvda", 5)).containsExactly(new InstrumentRef("NVDA", "Nvidia", "S&P 500"));
    }

    private static List<String> symbols(List<InstrumentRef> instruments) {
        return instruments.stream().map(InstrumentRef::symbol).toList();
    }
}