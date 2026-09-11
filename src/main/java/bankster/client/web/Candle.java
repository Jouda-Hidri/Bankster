package bankster.client.web;

import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public class Candle {
    @Getter private LocalDate date;
    private Double open;
    private Double close;
    @Getter private Double lstmForecast;
    @Getter private boolean lstm; // forecast is correct
    @Getter private double pnl;

    // constructor
    public Candle(long timestamp, Double open, Double close) {
        this.date = Instant.ofEpochSecond(timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        this.open = open;
        this.close = close;
    }

    // getters

    public Double getOpen() {
        return open;
    }

    public Double getClose() {
        return close;
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
}
