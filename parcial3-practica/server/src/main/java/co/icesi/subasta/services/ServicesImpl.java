package co.icesi.subasta.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

import co.icesi.subasta.model.Item;

public class ServicesImpl {

    public static final double MIN_INCREMENT = 10.0;

    private Map<String, Item> items;
    private Set<String> online;
    private Semaphore semaphore;

    public ServicesImpl() {
        items = new LinkedHashMap<>();
        items.put("A1", new Item("A1", "Portatil", 1000));
        items.put("A2", new Item("A2", "Monitor 27", 400));
        items.put("A3", new Item("A3", "Teclado mecanico", 150));
        items.put("A4", new Item("A4", "Audifonos", 120));
        items.put("A5", new Item("A5", "Silla gamer", 600));
        online = new HashSet<>();
        semaphore = new Semaphore(1);
    }

    public List<Item> getItems() {
        return new ArrayList<>(items.values());
    }

    public Item getItem(String id) {
        return items.get(id);
    }

    public void login(String user) {
        if (user == null || user.trim().isEmpty()) {
            throw new AuctionException("INVALID_DATA");
        }
        if (online.contains(user)) {
            throw new AuctionException("USER_IN_USE");
        }
        System.out.println("LOGIN " + user + " [" + Thread.currentThread().getName() + "]");
        online.add(user);
    }

    public void logout(String user) {
        if (user != null) {
            online.remove(user);
            System.out.println("LOGOUT " + user);
        }
    }

    public Item bid(String user, String itemId, double amount) throws InterruptedException {
        semaphore.acquire();
        if (itemId == null || !Double.isFinite(amount) || amount <= 0) {
            throw new AuctionException("INVALID_DATA");
        }
        Item item = items.get(itemId);
        if (item == null) {
            throw new AuctionException("UNKNOWN_ITEM");
        }
        if (user.equals(item.getLeader())) {
            throw new AuctionException("ALREADY_LEADER");
        }
        double min = item.getLeader() == null ? item.getBasePrice() : item.getCurrentPrice() + MIN_INCREMENT;
        if (amount < min) {
            throw new AuctionException("BID_TOO_LOW");
        }
        item.setCurrentPrice(amount);
        item.setLeader(user);
        item.setBids(item.getBids() + 1);
        System.out.println("BID " + itemId + " " + amount + " " + user);
        semaphore.release();
        return item;
    }

    public List<Item> myLeads(String user) {
        List<Item> list = new ArrayList<>();
        for (Item item : items.values()) {
            if (user.equals(item.getLeader())) {
                list.add(item);
            }
        }
        return list;
    }

    public Item top() {
        Item best = null;
        for (Item item : items.values()) {
            if (item.getBids() > 0 && (best == null || item.getBids() > best.getBids())) {
                best = item;
            }
        }
        return best;
    }
}
