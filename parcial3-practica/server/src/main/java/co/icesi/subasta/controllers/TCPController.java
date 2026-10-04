package co.icesi.subasta.controllers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import co.icesi.subasta.controllers.dtos.Request;
import co.icesi.subasta.controllers.dtos.Response;
import co.icesi.subasta.model.Item;
import co.icesi.subasta.services.AuctionException;
import co.icesi.subasta.services.ServicesImpl;

public class TCPController {

    private ServicesImpl services;

    private ServerSocket serverSocket;

    private boolean running;

    private Executor executor;

    private Gson gson;

    public TCPController(ServicesImpl services) {
        this(services, 9090);
    }

    public TCPController(ServicesImpl services, int port) {
        this.services = services;
        try {
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName("192.168.131.42"));
            executor = Executors.newFixedThreadPool(5);
            gson = new GsonBuilder().create();
        } catch (Exception e) {
            e.printStackTrace();
        }
        running = true;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    public void startService() {
        System.out.println("TCP Service started on port " + serverSocket.getLocalPort());
        while (running) {
            try {
                executor.execute(new TCPClientHandler(serverSocket.accept(), services));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    class TCPClientHandler implements Runnable {

        private Socket clientSocket;
        private ServicesImpl services;

        public TCPClientHandler(Socket clientSocket, ServicesImpl services) {
            this.clientSocket = clientSocket;
            this.services = services;
        }

        @Override
        public void run() {
            try {
                System.out.println("Client connected: " + clientSocket.getRemoteSocketAddress());
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));

                while (true) {
                    String user = null;
                    String line = reader.readLine();
                    Request rq = gson.fromJson(line, Request.class);
                    Map<String, String> data = rq.data;
                    Response response = new Response();
                    response.data = new HashMap<>();

                    switch (rq.action) {
                        case "LOGIN":
                            try {
                                services.login(data.get("user"));
                                user = data.get("user");
                                response.status = "OK";
                                response.data.put("user", user);
                            } catch (AuctionException e) {
                                response.status = "ERROR";
                                response.data.put("message", e.getMessage());
                            }
                            break;
                        case "LIST_ITEMS":
                            response.status = "OK";
                            response.data.put("items", services.getItems());
                            break;
                        case "BID":
                            if (user == null) {
                                response.status = "ERROR";
                                response.data.put("message", "NOT_LOGGED_IN");
                                break;
                            }
                            double amount = Double.parseDouble(data.get("amount"));
                            try {
                                Item item = services.bid(user, data.get("itemId"), amount);
                                response.status = "OK";
                                response.data.put("item", item);
                            } catch (AuctionException e) {
                                response.status = "ERROR";
                                response.data.put("message", e.getMessage());
                            }
                            break;
                        case "LOGOUT":
                            services.logout(user);
                            response.status = "OK";
                            writer.write(gson.toJson(response));
                            writer.newLine();
                            writer.flush();
                            clientSocket.close();
                            System.out.println("Client disconnected: " + clientSocket.getRemoteSocketAddress());
                            return;
                        default:
                            break;
                    }

                    writer.write(gson.toJson(response));
                    writer.newLine();
                    writer.flush();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
