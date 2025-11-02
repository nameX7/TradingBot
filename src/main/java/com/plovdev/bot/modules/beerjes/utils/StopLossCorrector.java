package com.plovdev.bot.modules.beerjes.utils;

import com.plovdev.bot.modules.beerjes.TradeService;
import com.plovdev.bot.modules.models.SymbolInfo;
import com.plovdev.bot.modules.models.Ticker;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class StopLossCorrector {
    private final TradeService service;

    public StopLossCorrector(TradeService s) {
        service = s;
    }

    public BigDecimal correct(BigDecimal stop, String symbol, String side, SymbolInfo info) {
        Ticker ticker = service.getTicker(symbol);
        BigDecimal mark = ticker.getMarkPrice();
        BigDecimal last = ticker.getLastPrice();

        if (side.equalsIgnoreCase("LONG") || side.equalsIgnoreCase("BUY")) {
            BigDecimal minimum = mark.min(last);
            if (stop.compareTo(minimum) >= 0) {
                BigDecimal percent = BeerjUtils.getPercent(new BigDecimal("0.3"), minimum);
                stop = minimum.subtract(percent);
            }
        } else {
            BigDecimal maximum = mark.max(last);
            if (stop.compareTo(maximum) <= 0) {
                BigDecimal percent = BeerjUtils.getPercent(new BigDecimal("0.3"), maximum);
                stop = maximum.add(percent);
            }
        }

        return stop.setScale(info.getPricePlace(), RoundingMode.HALF_EVEN);
    }
}