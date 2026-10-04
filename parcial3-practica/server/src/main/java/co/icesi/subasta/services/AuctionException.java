package co.icesi.subasta.services;

public class AuctionException extends RuntimeException {

    public AuctionException(String code) {
        super(code);
    }
}
