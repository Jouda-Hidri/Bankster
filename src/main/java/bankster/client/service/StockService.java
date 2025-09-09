package bankster.client.service;

import bankster.client.web.Candle;
import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.DoubleVector;
import smile.data.vector.IntVector;
import smile.projection.PCA;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import org.springframework.stereotype.Service;

@Service
public class StockService {

    public void predict(List<Candle> candles) {
        List<Double> closes = candles.stream().map(Candle::getClose).toList();
        if (closes.size() < 51) {
            return;
        }
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            returns.add((closes.get(i) - closes.get(i - 1)) / closes.get(i - 1));
        }
        List<Double> nextReturns = new ArrayList<>(returns.subList(1, returns.size()));
        returns = returns.subList(0, returns.size() - 1);

        List<Double> ma20 = movingAverages(closes, 20);
        List<Double> ma50 = movingAverages(closes, 50);
        List<Double> rsi14 = computeRSI(closes, 14);
        List<Double> vol20 = computeVolatility(returns, 20);

        int start = 50;
        int dataSize = closes.size() - start - 1;

        double[] y = new double[dataSize];
        double[] betaBase = baseRegression(dataSize, start, ma20, closes, ma50, returns, y, nextReturns);
        double[] betaEnh = enhancesRegression(dataSize, start, ma20, closes, ma50, returns, rsi14, vol20, y);
        regression(candles, start, closes, returns, ma20, ma50, rsi14, vol20, betaBase, betaEnh);

        // randomForest(candles, closes, dataSize, start, ma20, ma50, rsi14, vol20, nextReturns);
        randomForestPca(candles, closes, dataSize, start, ma20, ma50, rsi14, vol20, nextReturns);
    }

    private static double[] baseRegression(
            int dataSize,
            int start,
            List<Double> ma20,
            List<Double> closes,
            List<Double> ma50,
            List<Double> returns,
            double[] y,
            List<Double> nextReturns) {
        // --- Base regression ---
        double[][] x = new double[dataSize][3];
        for (int i = 0; i < dataSize; i++) {
            int idx = i + start;
            x[i][0] = ma20.get(idx) / closes.get(idx);
            x[i][1] = ma50.get(idx) / closes.get(idx);
            x[i][2] = returns.get(idx - 1);
            y[i] = nextReturns.get(idx - 1);
        }
        OLSMultipleLinearRegression regBase = new OLSMultipleLinearRegression();
        regBase.newSampleData(y, x);
        return regBase.estimateRegressionParameters();
    }

    private static double[] enhancesRegression(
            int dataSize,
            int start,
            List<Double> ma20,
            List<Double> closes,
            List<Double> ma50,
            List<Double> returns,
            List<Double> rsi14,
            List<Double> vol20,
            double[] y) {
        // --- Enhanced regression ---
        double[][] x = new double[dataSize][5];
        for (int i = 0; i < dataSize; i++) {
            int idx = i + start;
            x[i][0] = ma20.get(idx) / closes.get(idx);
            x[i][1] = ma50.get(idx) / closes.get(idx);
            x[i][2] = returns.get(idx - 1);
            x[i][3] = rsi14.get(idx);
            x[i][4] = vol20.get(idx - 1);
        }
        OLSMultipleLinearRegression regEnh = new OLSMultipleLinearRegression();
        regEnh.newSampleData(y, x);
        return regEnh.estimateRegressionParameters();
    }

    private static void regression(
            List<Candle> candles,
            int start,
            List<Double> closes,
            List<Double> returns,
            List<Double> ma20,
            List<Double> ma50,
            List<Double> rsi14,
            List<Double> vol20,
            double[] betaBase,
            double[] betaEnh) {
        for (int i = start; i < closes.size() - 1; i++) {
            double rToday = returns.get(i - 1);
            double ma20Norm = ma20.get(i) / closes.get(i);
            double ma50Norm = ma50.get(i) / closes.get(i);
            double rsiVal = rsi14.get(i);
            double volVal = vol20.get(i - 1);

            // base
            double predReturnBase = betaBase[0] + betaBase[1] * ma20Norm + betaBase[2] * ma50Norm + betaBase[3] * rToday;
            candles.get(i + 1).setPrediction(closes.get(i) * (1 + predReturnBase));

            // enhanced
            double predReturnEnh = betaEnh[0] + betaEnh[1] * ma20Norm + betaEnh[2] * ma50Norm + betaEnh[3] * rToday
                    + betaEnh[4] * rsiVal + betaEnh[5] * volVal;
            candles.get(i + 1).setPredictionEnhanced(closes.get(i) * (1 + predReturnEnh));

            // store indicators for display
            candles.get(i).setRsi(rsiVal);
            candles.get(i).setVolatility(volVal);
        }
    }

    private static void randomForest(
            List<Candle> candles,
            List<Double> closes,
            int dataSize,
            int start,
            List<Double> ma20,
            List<Double> ma50,
            List<Double> rsi14,
            List<Double> vol20,
            List<Double> nextReturns) {
        // random forest

        double[] ma20Arr = new double[closes.size()];
        double[] ma50Arr = new double[closes.size()];
        double[] rsiArr = new double[closes.size()];
        double[] volArr = new double[closes.size()];
        int[] labels = new int[closes.size()];

        for (int i = 0; i < dataSize; i++) {
            int idx = i + start;

            ma20Arr[i] = ma20.get(idx) / closes.get(idx);
            ma50Arr[i] = ma50.get(idx) / closes.get(idx);
            rsiArr[i] = rsi14.get(idx);
            volArr[i] = vol20.get(idx - 1);
            labels[i] = nextReturns.get(idx - 1) > 0 ? 1 : 0;
        }

        DataFrame df = DataFrame.of(
                DoubleVector.of("ma20", ma20Arr),
                DoubleVector.of("ma50", ma50Arr),
                DoubleVector.of("rsi", rsiArr),
                DoubleVector.of("vol", volArr),
                IntVector.of("label", labels)
        );

        Properties params = new Properties();
        params.setProperty("smile.random.forest.trees", "100"); // number of trees

        RandomForest rf = RandomForest.fit(
                Formula.lhs("label"), // predict label
                df,
                params // number of trees
        );

        for (int i = start; i < closes.size() - 1; i++) {
            int randomForestPrediction = rf.predict(df.slice(i, i + 1))[0];
            boolean up = closes.get(i) - closes.get(i - 1) > 0;
            boolean correctUp = up && randomForestPrediction == 1;
            boolean correctDown = !up && randomForestPrediction == 0;
            candles.get(i).setRandomForestPrediction(randomForestPrediction);
            candles.get(i).setRandomForest(correctUp || correctDown);
        }
    }

    private static void randomForestPca(
            List<Candle> candles,
            List<Double> closes,
            int dataSize,
            int start,
            List<Double> ma20,
            List<Double> ma50,
            List<Double> rsi14,
            List<Double> vol20,
            List<Double> nextReturns) {
        int[] labels = new int[dataSize];

        double[][] features = new double[dataSize][4];
        for (int i = 0; i < dataSize; i++) {
            int idx = i + start;
            features[i][0] = ma20.get(idx) / closes.get(idx);
            features[i][1] = ma50.get(idx) / closes.get(idx);
            features[i][2] = rsi14.get(idx);
            features[i][3] = vol20.get(idx - 1);
            labels[i] = nextReturns.get(idx - 1) > 0 ? 1 : 0;
        }

// Fit PCA
        PCA pca = PCA.fit(features);
        pca.setProjection(2);
        double[][] pcaFeatures = pca.project(features);

// Build training DataFrame
        DataFrame df = DataFrame.of(
                DoubleVector.of("pc1", Arrays.stream(pcaFeatures).mapToDouble(r -> r[0]).toArray()),
                DoubleVector.of("pc2", Arrays.stream(pcaFeatures).mapToDouble(r -> r[1]).toArray()),
                IntVector.of("label", labels)
        );

        Properties params = new Properties();
        params.setProperty("smile.random.forest.trees", "100");

        RandomForest rf = RandomForest.fit(
                Formula.lhs("label"),
                df,
                params
        );

// Predict for each candle
        for (int i = start; i < closes.size() - 1; i++) {
            double[] row = {
                    ma20.get(i) / closes.get(i),
                    ma50.get(i) / closes.get(i),
                    rsi14.get(i),
                    vol20.get(i - 1)
            };

            double[] rowPca = pca.project(row);

            DataFrame rowDf = DataFrame.of(
                    DoubleVector.of("pc1", new double[]{rowPca[0]}),
                    DoubleVector.of("pc2", new double[]{rowPca[1]})
            );

            int randomForestPrediction = rf.predict(rowDf)[0];
            candles.get(i).setRandomForestPrediction(randomForestPrediction);
            boolean up = closes.get(i) - closes.get(i - 1) > 0;
            boolean correctUp = up && randomForestPrediction == 1;
            boolean correctDown = !up && randomForestPrediction == 0;
            candles.get(i).setRandomForest(correctUp || correctDown);

        }
    }


    // --- Helpers ---
    private List<Double> movingAverages(List<Double> closes, int window) {
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            if (i < window - 1) {
                result.add(Double.NaN);
            } else {
                double sum = 0;
                for (int j = i - window + 1; j <= i; j++) {
                    sum += closes.get(j);
                }
                result.add(sum / window);
            }
        }
        return result;
    }

    private List<Double> computeRSI(List<Double> closes, int period) {
        List<Double> rsi = new ArrayList<>();
        rsi.add(Double.NaN); // first entry can't be computed

        for (int i = 1; i < closes.size(); i++) {
            if (i < period) {
                rsi.add(Double.NaN);
                continue;
            }

            double gain = 0, loss = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double diff = closes.get(j) - closes.get(j - 1);
                if (diff >= 0) {
                    gain += diff;
                } else {
                    loss -= diff;
                }
            }

            double avgGain = gain / period;
            double avgLoss = loss / period;
            double rs = (avgLoss == 0) ? 100 : avgGain / avgLoss;
            rsi.add(100 - (100 / (1 + rs)));
        }
        return rsi;
    }

    private List<Double> computeVolatility(List<Double> returns, int window) {
        List<Double> vol = new ArrayList<>();
        for (int i = 0; i < returns.size(); i++) {
            if (i < window - 1) {
                vol.add(Double.NaN);
            } else {
                double sum = 0;
                for (int j = i - window + 1; j <= i; j++) {
                    sum += returns.get(j);
                }
                double mean = sum / window;

                double var = 0;
                for (int j = i - window + 1; j <= i; j++) {
                    var += Math.pow(returns.get(j) - mean, 2);
                }
                vol.add(Math.sqrt(var / window));
            }
        }
        return vol;
    }
}
