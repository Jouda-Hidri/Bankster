package bankster.client.service;

import bankster.client.web.Candle;
import lombok.val;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class StockService {

    public void predict(List<Candle> candles) {
        List<Double> closes = candles.stream().map(Candle::getClose).toList();

        if (closes.size() < 51) return;

        // compute returns
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            returns.add((closes.get(i) - closes.get(i - 1)) / closes.get(i - 1));
        }

        List<Double> nextReturns = new ArrayList<>(returns.subList(1, returns.size()));
        returns = returns.subList(0, returns.size() - 1);

        // indicators
        List<Double> ma20 = movingAverages(closes, 20);
        List<Double> ma50 = movingAverages(closes, 50);
        List<Double> rsi14 = computeRSI(closes, 14);
        List<Double> vol20 = computeVolatility(returns, 20);

        int start = 50;
        int dataSize = closes.size() - start - 1;

        // --- Base regression ---
        double[][] Xbase = new double[dataSize][3];
        double[] y = new double[dataSize];
        for (int i = 0; i < dataSize; i++) {
            int idx = i + start;
            Xbase[i][0] = ma20.get(idx) / closes.get(idx);
            Xbase[i][1] = ma50.get(idx) / closes.get(idx);
            Xbase[i][2] = returns.get(idx - 1);
            y[i] = nextReturns.get(idx - 1);
        }
        OLSMultipleLinearRegression regBase = new OLSMultipleLinearRegression();
        regBase.newSampleData(y, Xbase);
        double[] betaBase = regBase.estimateRegressionParameters();

        // --- Enhanced regression ---
        double[][] Xenh = new double[dataSize][5];
        for (int i = 0; i < dataSize; i++) {
            int idx = i + start;
            Xenh[i][0] = ma20.get(idx) / closes.get(idx);
            Xenh[i][1] = ma50.get(idx) / closes.get(idx);
            Xenh[i][2] = returns.get(idx - 1);
            Xenh[i][3] = rsi14.get(idx);
            Xenh[i][4] = vol20.get(idx - 1);
        }
        OLSMultipleLinearRegression regEnh = new OLSMultipleLinearRegression();
        regEnh.newSampleData(y, Xenh);
        double[] betaEnh = regEnh.estimateRegressionParameters();

        // --- Compute predictions ---
        for (int i = start; i < closes.size() - 1; i++) {
            double rToday = returns.get(i - 1);
            double ma20Norm = ma20.get(i) / closes.get(i);
            double ma50Norm = ma50.get(i) / closes.get(i);
            double rsiVal = rsi14.get(i);
            double volVal = vol20.get(i - 1);

            // base
            double predReturnBase = betaBase[0] + betaBase[1]*ma20Norm + betaBase[2]*ma50Norm + betaBase[3]*rToday;
            candles.get(i + 1).setPrediction(closes.get(i) * (1 + predReturnBase));

            // enhanced
            double predReturnEnh = betaEnh[0] + betaEnh[1]*ma20Norm + betaEnh[2]*ma50Norm + betaEnh[3]*rToday
                    + betaEnh[4]*rsiVal + betaEnh[5]*volVal;
            candles.get(i + 1).setPredictionEnhanced(closes.get(i) * (1 + predReturnEnh));

            // store indicators for display
            candles.get(i).setRsi(rsiVal);
            candles.get(i).setVolatility(volVal);
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
                if (diff >= 0) gain += diff;
                else loss -= diff;
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
