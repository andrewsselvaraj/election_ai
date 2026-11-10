package com.election.ai;

import com.election.ai.model.Form20Sheet;
import com.election.ai.model.PollingStation;
import com.election.ai.service.Form20PdfParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Districts print Form 20 differently. Each sample below has a layout the parser must work out:
 * AC046 rotated candidate names drawn over the rows; AC075 a serial plus polling-station number
 * and the station address between rows; AC150 NOTA before a valid total that includes it;
 * AC234 a round-wise abstract ordered valid, NOTA, tendered, rejected, total.
 */
class Form20LayoutsTest {

    @ParameterizedTest
    @CsvSource({
            // file, candidates, stations, EVM valid, postal valid, total polled
            "AC046, 11, 319, 226911, 1366, 229252",
            "AC075, 24, 310, 206650, 2180, 209859",
            "AC150, 19, 322, 225801, 2331, 228895",
            "AC234, 13, 302, 174341, 2691, 178628",
    })
    void readsDistrictLayout(String file, int candidates, int stations, int evmValid, int postalValid, int polled)
            throws Exception {
        byte[] pdf = new ClassPathResource("form20/" + file + ".pdf").getContentAsByteArray();
        for (Integer expected : new Integer[]{candidates, null}) { // with and without the Form 7A count
            Form20Sheet s = Form20PdfParser.parse(pdf, expected);

            assertEquals(candidates, s.candidateCount(), file);
            assertEquals(stations, s.pollingStations().size(), file);
            assertEquals(List.of(), s.unreadableRows(), file);
            assertTrue(s.pollingStations().stream().allMatch(p -> p.votes().consistent()), file);
            int sum = s.pollingStations().stream().map(PollingStation::votes).mapToInt(v -> v.totalValid()).sum();
            assertEquals(evmValid, sum, file + " station rows add up to the EVM total");
            assertEquals(evmValid, s.evmTotal().totalValid(), file);
            assertEquals(postalValid, s.postalTotal().totalValid(), file);
            assertEquals(polled, s.grandTotal().total(), file);
        }
    }
}
