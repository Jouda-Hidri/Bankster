package bankster.client.web;

import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public class Candle {
    @Getter private LocalDate date;
    private Double open;
    private Double close;
    private Double prediction;
    private Double predictionEnhanced;
    private Integer randomForestPrediction;
    private boolean randomForest; // prediction is correct
    @Getter private Double lstmForecast;
    @Getter private boolean lstm; // forecast is correct
    @Getter private double pnl;
    private Double rsi;
    private Double volatility;

    // constructor
    public Candle(long timestamp, Double open, Double close) {
        this.date = Instant.ofEpochSecond(timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        this.open = open;
        this.close = close;
    }

    // getters


    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Double getOpen() {
        return open;
    }

    public Double getClose() {
        return close;
    }

    public Double getPrediction() {
        return prediction;
    }

    public void setPrediction(Double prediction) {
        this.prediction = prediction;
    }

    public Double getPredictionEnhanced() {
        return predictionEnhanced;
    }

    public void setPredictionEnhanced(Double predictionEnhanced) {
        this.predictionEnhanced = predictionEnhanced;
    }

    public Double getRsi() {
        return rsi;
    }

    public void setRsi(Double rsi) {
        this.rsi = rsi;
    }

    public Double getVolatility() {
        return volatility;
    }

    public void setVolatility(Double volatility) {
        this.volatility = volatility;
    }

    public Integer getRandomForestPrediction() {
        return randomForestPrediction;
    }

    public void setRandomForestPrediction(Integer randomForestPrediction) {
        this.randomForestPrediction = randomForestPrediction;
    }

    public Boolean getRandomForest() {
        return randomForest;
    }

    public void setRandomForest(Boolean randomForest) {
        this.randomForest = randomForest;
    }

    public void setLstmForecast(Double lstmForecast) {
        this.lstmForecast = lstmForecast;
    }

    public void setLstm(Candle actual) {
        boolean correctUp = this.lstmForecast > 0 && actual.close > this.close;
        boolean correctDown = this.lstmForecast < 0 && actual.close < this.close;
        this.lstm = correctUp || correctDown;
        // --- Trading simulation ---
        // If forecast > 0 → go long, else short
        double position = (this.lstmForecast > 0) ? 1.0 : -1.0;
        this.pnl = position * (actual.close - this.close) / this.close;
    }

    public void setPnl(double pnl) {
        this.pnl = pnl;
    }
}

