package com.plovdev.bot.modules.models;

import java.math.BigDecimal;
import java.util.Objects;

public class EnterPoint {
    private String type;
    private BigDecimal price;
    private BigDecimal size;

    public EnterPoint() {
    }

    public EnterPoint(String type, BigDecimal price, BigDecimal size) {
        this.type = type;
        this.price = price;
        this.size = size;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getSize() {
        return size;
    }

    public void setSize(BigDecimal size) {
        this.size = size;
    }

    @Override
    public String toString() {
        return "EnterPoint{" +
                "type='" + type + '\'' +
                ", price=" + price +
                ", size=" + size +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        EnterPoint that = (EnterPoint) o;
        return Objects.equals(type, that.type) && Objects.equals(price, that.price) && Objects.equals(size, that.size);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, price, size);
    }
}