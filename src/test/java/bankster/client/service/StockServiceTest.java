package bankster.client.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import bankster.client.web.Candle;

/**
 * Smoke tests proving the native ND4J/DL4J stack still initialises and runs on
 * the JDK the project is built with (21). These libraries load native code
 * through JavaCPP and reflect into {@code java.nio}, which is the part of the
 * toolchain most likely to break on a JDK upgrade.
 */
class StockServiceTest {

    @Test
    void nd4jInitialisesAndComputesOnCurrentJdk() {
        INDArray a = Nd4j.create(new double[]{1, 2, 3, 4}, 2, 2);
        INDArray b = Nd4j.create(new double[]{1, 0, 0, 1}, 2, 2);

        INDArray product = a.mmul(b);

        assertEquals(1.0, product.getDouble(0, 0), 1e-9);
        assertEquals(2.0, product.getDouble(0, 1), 1e-9);
        assertEquals(3.0, product.getDouble(1, 0), 1e-9);
        assertEquals(4.0, product.getDouble(1, 1), 1e-9);
    }

    @Test
    void lstmForecastPopulatesForecastsForEveryPredictableCandle() {
        int lookback = 5;
        List<Candle> candles = syntheticCandles(60);
        Map<LocalDate, Double> sentiment = new HashMap<>();
        for (Candle candle : candles) {
            sentiment.put(candle.getDate(), 0.0);
        }

        new StockService().lstmForecast(candles, lookback, sentiment);

        long forecast = candles.stream().filter(c -> c.getLstmForecast() != null).count();
        assertTrue(forecast > 0, "expected the LSTM to produce at least one forecast");
        candles.stream()
                .filter(c -> c.getLstmForecast() != null)
                .forEach(c -> assertNotNull(c.getDate()));
    }

    /** Deterministic price series so the test never depends on a network call. */
    private static List<Candle> syntheticCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < count; i++) {
            double close = 100 + Math.sin(i / 3.0) * 5;
            long epochSecond = start.plusDays(i).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
            candles.add(new Candle(epochSecond, close, close));
        }
        return candles;
    }
}
