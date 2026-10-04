package co.icesi.subasta.controllers;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

import co.icesi.subasta.model.Item;
import co.icesi.subasta.services.ServicesImpl;

public class UDPController {

    private ServicesImpl services;
    private DatagramSocket socket;
    private int port;
    private boolean running;

    public UDPController(ServicesImpl services, int port) {
        this.services = services;
        this.port = port;
    }

    public void startService() {
        try {
            socket = new DatagramSocket(port);
            running = true;
            System.out.println("UDP Service started on port " + port);
            while (running) {
                byte[] data = new byte[1024];
                DatagramPacket packet = new DatagramPacket(data, data.length);
                socket.receive(packet);

                String message = new String(data);
                String resp = process(message);
                System.out.println("UDP " + packet.getAddress() + ":" + packet.getPort() + " -> " + resp);

                byte[] out = resp.getBytes();
                socket.send(new DatagramPacket(out, out.length, packet.getAddress(), packet.getPort()));
            }
        } catch (Exception e) {
            if (running) {
                e.printStackTrace();
            }
        }
    }

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }

    public String process(String message) {
        String[] parts = message.split(";");
        switch (parts[0]) {
            case "PING":
                return parts.length == 1 ? "PONG" : "ERROR;INVALID_FORMAT";
            case "PRICE":
                if (parts.length != 2) {
                    return "ERROR;INVALID_FORMAT";
                }
                Item item = services.getItem(parts[1]);
                if (item == null) {
                    return "ERROR;UNKNOWN_ITEM";
                }
                String leader = item.getLeader() == null ? "-" : item.getLeader();
                return "PRICE;" + item.getId() + ";" + item.getCurrentPrice() + ";" + leader;
            default:
                return "ERROR;INVALID_FORMAT";
        }
    }
}
