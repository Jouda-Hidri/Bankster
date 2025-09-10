package bankster.client.web;

import lombok.Getter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class Candle {
    private LocalDateTime dateTime;
    private Double open;
    private Double close;
    private Double prediction;
    private Double predictionEnhanced;
    private Integer randomForestPrediction;
    private boolean randomForest; // prediction is correct
    @Getter private Double lstmForecast;
    private Double rsi;
    private Double volatility;

    // constructor
    public Candle(long timestamp, Double open, Double close) {
        this.dateTime = LocalDateTime.ofEpochSecond(timestamp, 0, ZoneOffset.UTC);
        this.open = open;
        this.close = close;
    }

    // getters

    public LocalDateTime getDateTime() {
        return dateTime;
    }

    public Double getOpen() { return open; }
    public Double getClose() { return close; }

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
}

