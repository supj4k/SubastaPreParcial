package co.icesi.subasta;

import co.icesi.subasta.controllers.TCPController;
import co.icesi.subasta.controllers.UDPController;
import co.icesi.subasta.services.ServicesImpl;

public class Main {

    public static void main(String[] args) {
        ServicesImpl serv = new ServicesImpl();

        TCPController tcp = new TCPController(serv);
        tcp.startService();

        UDPController udp = new UDPController(serv, 5000);
        udp.startService();
    }
}
