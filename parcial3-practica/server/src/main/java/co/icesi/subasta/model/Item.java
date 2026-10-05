package co.icesi.subasta.model;

public class Item {

    private String id;
    private String name;
    private double basePrice;
    private double currentPrice;
    private String leader;
    private int bids;

    public Item(String id, String name, double basePrice) {
        this.id = id;
        this.name = name;
        this.basePrice = basePrice;
        this.currentPrice = basePrice;
        this.leader = null;
        this.bids = 0;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public double getBasePrice() {
        return basePrice;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(double currentPrice) {
        this.currentPrice = currentPrice;
    }

    public String getLeader() {
        return leader;
    }

    public void setLeader(String leader) {
        this.leader = leader;
    }

    public int getBids() {
        return bids;
    }

    public void setBids(int bids) {
        this.bids = bids;
    }

    @Override
    public String toString() {
        return "Item{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", basePrice=" + basePrice +
                ", currentPrice=" + currentPrice +
                ", leader='" + leader + '\'' +
                ", bids=" + bids +
                '}';
    }
}
