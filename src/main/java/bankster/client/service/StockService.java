package bankster.client.service;

import bankster.client.web.Candle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.deeplearning4j.datasets.iterator.utilty.ListDataSetIterator;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.LSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.springframework.stereotype.Service;

@Service
public class StockService {

    public void lstmForecast(List<Candle> candles, int lookback, Map<LocalDate, Double> sentiments) {
        int batchSize = 32;

        // --- 1. Compute returns ---
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            double prevClose = candles.get(i - 1).getClose();
            double currClose = candles.get(i).getClose();
            returns.add((currClose - prevClose) / prevClose);
        }

        // --- 2. Build sliding windows ---
        List<DataSet> sequences = new ArrayList<>();
        for (int i = 0; i < returns.size() - lookback; i++) {
            INDArray input = Nd4j.create(1, 2, lookback);
            INDArray label = Nd4j.create(1, 1, lookback); // same sequence length as input

            for (int j = 0; j < lookback; j++) {
                input.putScalar(new int[]{0, 0, j}, returns.get(i + j));
                input.putScalar(new int[]{0, 1, j}, sentiments.getOrDefault(candles.get(i + j).getDate(), 0.0));  // <- from FinBERT
                label.putScalar(new int[]{0, 0, j}, returns.get(i + j + 1)); // next step
            }

            sequences.add(new DataSet(input, label));
        }

        // --- 3. Train/test split ---
        int split = (int) (sequences.size() * 0.8);
        List<DataSet> train = sequences.subList(0, split);
        List<DataSet> test = sequences.subList(split, sequences.size());

        DataSetIterator trainIter = new ListDataSetIterator<>(train, batchSize);
        DataSetIterator testIter = new ListDataSetIterator<>(test, batchSize);

        // --- 4. Build LSTM model ---
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(new Adam(0.001))
                .list()
                .layer(new LSTM.Builder()
                        .nIn(2)
                        .nOut(50)
                        .activation(Activation.TANH)
                        .build())
                .layer(new RnnOutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .activation(Activation.IDENTITY)
                        .nIn(50).nOut(1)
                        .build())
                .build();

        MultiLayerNetwork model = new MultiLayerNetwork(conf);
        model.init();
        model.setListeners(new ScoreIterationListener(20));

        // --- 5. Train ---
        for (int epoch = 0; epoch < 20; epoch++) {
            trainIter.reset();
            model.fit(trainIter);
        }

        // --- 6. Predict & update candles ---
        int candleIndex = split + lookback;
        testIter.reset();
        while (testIter.hasNext()) {
            DataSet ds = testIter.next();
            INDArray features = ds.getFeatures();
            INDArray predicted = model.output(features, false);

            for (int row = 0; row < predicted.size(0); row++) {
                for (int t = 0; t < predicted.size(2); t++) { // iterate over sequence
                    double forecast = predicted.getDouble(row, 0, t);
                    int index = candleIndex - ((int) predicted.size(2)) + t + 1;
                    Candle targetCandle = candles.get(index);
                    targetCandle.setLstmForecast(forecast);
                    targetCandle.setLstm(candles.get(index + 1));
                    System.out.printf(
                            "ts=%d forecast=%.6f%n",
                            targetCandle.getDate().toEpochDay(),
                            targetCandle.getLstmForecast());
                }
                candleIndex++;
            }
        }
    }
}
