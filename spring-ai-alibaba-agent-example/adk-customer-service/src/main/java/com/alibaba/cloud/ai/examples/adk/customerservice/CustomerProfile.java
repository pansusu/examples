package com.alibaba.cloud.ai.examples.adk.customerservice;

public class CustomerProfile {
    private String id;
    private String name;
    private String location;

    public CustomerProfile(String id, String name, String location) {
        this.id = id;
        this.name = name;
        this.location = location;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getLocation() { return location; }

    public String toJson() {
        return String.format("{\"id\":\"%s\",\"name\":\"%s\",\"location\":\"%s\"}", id, name, location);
    }

    public static CustomerProfile current() {
        // Minimal stub to mirror Customer.get_customer("123")
        return new CustomerProfile("123", "Alex Smith", "Las Vegas, NV");
    }
}
