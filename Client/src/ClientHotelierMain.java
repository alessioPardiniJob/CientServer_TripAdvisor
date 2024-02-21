import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Properties;
import com.google.gson.*;
import com.google.gson.annotations.SerializedName;


public class ClientHotelierMain {
    // Variabile per memorizzare l'username dell'utente loggato, null se non loggato
    private static String username = null;

    // Variabile per gestire lo stato del client
    private static boolean stato = true;    

    // Nome del file di configurazione
    public static final String configFile = "client.properties";

    // Variabili di configurazione del server
    public static String hostname;
    public static int serverTCPPort;
    public static int udpPort; 
    public static int bufSizeTCP;
    public static int bufSizeUDP;
    public static String exitMessage;
    public static String multicastAddress;
    public static MulticastSocket multicastSocket;

    // Oggetto di blocco per gestire l'accesso al gruppo multicast in base allo stato di login
    private static final Object loginLockForMultiCast = new Object();

    // Oggetto utilizzato per la stampa sincronizzata tra i thread
    private static final Object printSyncLock = new Object();

    // Oggetto per leggere l'input dell'utente
    private static BufferedReader userInput;

    // Canale di comunicazione con il server
    private static SocketChannel socketChannel;

    // Thread per la ricezione dati tramite UDP
    private static Thread udpReceiverThread;

    // Buffer per la lettura e scrittura dei dati
    private static ByteBuffer buffer;

    /*MAIN */
    public static void main(String[] args) {
        try {
            readConfig(); // Legge la configurazione dal file
        }
        catch (Exception e) {
            System.err.println("[CLIENT] Errore durante la lettura del file di configurazione.");
            e.printStackTrace();
            System.exit(1);
        }

        // Inizio del programma
        System.out.println("Benvenuto a HOTELIER!");

        try {
            // Aggiunta del gestore di terminazione del client
            ClientTerminationHandler clientTerminationHandler = new ClientTerminationHandler();
            Runtime.getRuntime().addShutdownHook(clientTerminationHandler);

            // Inizializzazione del socket multicast per ricevere messaggi UDP
            multicastSocket = new MulticastSocket(udpPort);
    
            // Allocazione del buffer per invio/ricezione messaggi
            buffer = ByteBuffer.allocate(bufSizeTCP);
    
            // Inizializzazione di userInput per leggere l'input da tastiera
            userInput = new BufferedReader(new InputStreamReader(System.in));
    
            // Inizializzazione di socketChannel per la connessione al server
            socketChannel = SocketChannel.open(new InetSocketAddress(hostname, serverTCPPort));
    
            // Avvio del thread per ricevere dati tramite UDP
            udpReceiverThread = new Thread(new UDPReceiver());
            udpReceiverThread.start();
    
            String choice;
            do {
                // Mostra il menu delle funzionalità disponibili
                synchronized(printSyncLock) {
                    System.out.println("Cosa desideri fare?");
                    System.out.println("1. Registrati");
                    System.out.println("2. Effettua il login");
                    System.out.println("3. Effettua il logout");
                    System.out.println("4. Cerca Hotel per nome e citta");
                    System.out.println("5. Cerca tutti gli Hotel per citta");
                    System.out.println("6. Inserisci una review");
                    System.out.println("7. Mostra i tuoi badge");
                    System.out.println("8. Esci");
                    System.out.print("Scegli: ");
                }
                choice = userInput.readLine();

                // Questo serve nel caso in cui siamo su Scegli: si fa cntrl+c farà si di avviare la terminazione ma se prima 
                if(choice == null){
                   return;
                }

                // Gestione delle scelte dell'utente
                switch (choice) {
                    case "1":
                        handleRegistration();
                        break;
                    case "2":
                        handleLogin();
                        break;
                    case "3":
                        handleLogout();
                        break;
                    case "4":
                        handleSearchHotel();
                        break;
                    case "5":
                        handleSearchAllHotels();
                        break;
                    case "6":
                        handleInsertReview();
                        break;
                    case "7":
                        handleShowMyBadge();
                        break;
                    case "8":
                        closeConnection();
                        break;
                    default:
                        synchronized(printSyncLock){
                            System.out.println("Scelta non valida. Riprova.");
                        }
                        break;
                }
            } while (stato);

            // Si chiama una system.exit in modo tale da terminare chiamando lo shutdownHook 
            System.exit(0);

        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
        private static void closeConnection() {        
            try {
                // Converti la richiesta in un array di byte
                byte[] messageBytes = exitMessage.getBytes();
                
                // Prepara il buffer per la scrittura
                buffer.clear();

                // Scrivi la lunghezza del messaggio e il messaggio stesso nel buffer
                buffer.putInt(messageBytes.length);
                buffer.put(messageBytes);
                buffer.flip(); // Prepara il buffer per la lettura

                // Invia i dati sul canale SocketChannel
                socketChannel.write(buffer);

                // Imposto lo stato a false per far terminare il thread prinicpale
                stato = false;

            } catch (IOException e) {
                System.err.println(e.getMessage());
                System.exit(1);
            }
        }
        
    /*HANDLE */
    private static String sendRequest(String request) throws IOException {
        // Converti la richiesta in un array di byte
        byte[] messageBytes = request.getBytes();
    
        // Prepara il buffer per la scrittura
        buffer.clear();
    
        // Scrivi la lunghezza del messaggio e il messaggio stesso nel buffer
        buffer.putInt(messageBytes.length);
        buffer.put(messageBytes);
        buffer.flip(); // Prepara il buffer per la lettura
    
        // Invia i dati sul canale SocketChannel
        socketChannel.write(buffer);
    
        // Prepara un StringBuilder per costruire la risposta
        StringBuilder replyBuilder = new StringBuilder();
    
        // Prepara il buffer per la lettura
        buffer.clear();
    
        // Leggi i dati dal SocketChannel nel buffer
        int bytesRead = socketChannel.read(buffer);
        buffer.flip(); // Prepara il buffer per la lettura
        int totalRead = bytesRead;
    
        // Leggi la lunghezza della risposta dal buffer
        int replyLength = buffer.getInt();
    
        // Leggi la risposta dal buffer
        byte[] replyBytes = new byte[bytesRead - Integer.BYTES];
        buffer.get(replyBytes);
        replyBuilder.append(new String(replyBytes));
    
        // Controllo se la risposta è stata letta completamente
        if (totalRead < replyLength + Integer.BYTES) {
            while (totalRead < replyLength) {
                bytesRead = socketChannel.read(buffer);
                totalRead += bytesRead;
    
                // Leggi i dati dal buffer
                buffer.flip();
                replyBytes = new byte[bytesRead];
                buffer.get(replyBytes);
                replyBuilder.append(new String(replyBytes));
                buffer.clear();
            }
        }
    
        // Visualizza la risposta ricevuta
        synchronized(printSyncLock){
            System.out.println("[CLIENT] Ricevuto: " + replyBuilder);
        }    
        // Restituisce la risposta come stringa
        return replyBuilder.toString();
    }
    private static void handleRegistration() throws IOException {
        String userName;
        String password;
    
        synchronized(printSyncLock){
            // Richiesta dell'username e della password da parte dell'utente
            System.out.print("Inserisci username: ");
            userName = userInput.readLine();
    
            System.out.print("Inserisci password: ");
            password = userInput.readLine();
        }
    
        // Costruzione della richiesta di registrazione nel formato appropriato
        String request = "register," + userName + "," + password;
    
        // Invio della richiesta al server
        sendRequest(request);
    }
    private static void handleLogin() throws IOException {
        String password;
        String jsonString;
    
        synchronized(printSyncLock){
            // Richiesta dell'username e della password da parte dell'utente
            System.out.print("Inserisci username: ");
            username = userInput.readLine();
    
            System.out.print("Inserisci password: ");
            password = userInput.readLine();
    
            // Costruzione della richiesta di login nel formato appropriato
            String request = "login," + username + "," + password;
    
            // Invio della richiesta al server e ricezione della risposta come stringa JSON
            jsonString = sendRequest(request);
        }
    
        // Creazione dell'oggetto Gson per il parsing della stringa JSON
        Gson gson = new Gson();
    
        // Parsing della stringa JSON nella classe JsonResponse
        JsonResponse response = gson.fromJson(jsonString, JsonResponse.class);
    
        // Verifica del campo "Status-code" della risposta ricevuta dal server
        if (response != null) {
            int statusCode = response.getStatusCode();
    
            // Controllo se il login è stato eseguito con successo o meno
            if (statusCode < 200 || statusCode >= 300) {
                // Se il login fallisce, reimposta l'username a null
                username = null;
            } else {
    
                // Notifica il thread che gestisce UDP del successo del login
                synchronized (loginLockForMultiCast) {
                    loginLockForMultiCast.notify();
                }
            }
        }
    }
    private static void handleLogout() throws IOException {
        // Costruzione della richiesta di logout nel formato appropriato
        String request = "logout," + username;
    
        // Invio della richiesta al server e ricezione della risposta come stringa JSON
        String jsonString = sendRequest(request);
    
        // Creazione dell'oggetto Gson per il parsing della stringa JSON
        Gson gson = new Gson();
    
        // Parsing della stringa JSON nell'oggetto JsonResponse
        JsonResponse response = gson.fromJson(jsonString, JsonResponse.class);
    
        // Verifica del campo "Status-code" della risposta
        if (response != null) {
            int statusCode = response.getStatusCode();
    
            // Controllo se il logout è stato eseguito correttamente
            if (statusCode >= 200 && statusCode < 300) {
                // Se il logout è avvenuto con successo:
                // Reset dell'username e del flag di login
                username = null;    
                // Chiusura del socket multicast
                multicastSocket.close();
            }
        }
    }
    private static void handleSearchHotel() throws IOException {
        String nomeHotel;
        String citta;
    
        synchronized(printSyncLock){
            // Richiesta all'utente di inserire il nome dell'hotel e la città da cercare
            System.out.print("Inserisci nome hotel: ");
            nomeHotel = userInput.readLine();
        
            System.out.print("Inserisci citta: ");
            citta = userInput.readLine();
        }
    
        // Costruzione della richiesta di ricerca dell'hotel nel formato appropriato
        String request = "searchHotel," + nomeHotel + "," + citta;
    
        // Invio della richiesta al server
        sendRequest(request);
    }   
    private static void handleSearchAllHotels() throws IOException {
        String citta;
    
        synchronized(printSyncLock){
            // Richiesta all'utente di inserire il nome della città per la ricerca di tutti gli hotel
            System.out.print("Inserisci citta: ");
            citta = userInput.readLine();
        }
    
        // Costruzione della richiesta per la ricerca di tutti gli hotel nella città specificata
        String request = "searchAllHotels," + citta;
    
        // Invio della richiesta al server
        sendRequest(request);
    }
    private static void handleShowMyBadge() throws IOException {
        // Costruzione della richiesta per mostrare i badge dell'utente attuale
        String request = "showMyBadge," + username;
    
        // Invio della richiesta al server
        sendRequest(request);
    }
    private static void handleInsertReview() throws IOException {
        String nomeHotel;
        String citta;
        String globalScore;
        String[] categoryNames = {"pulizia", "posizione", "servizi", "qualita"};
        String[] singleScores = new String[categoryNames.length];
    
        synchronized(printSyncLock){
            // Richiesta all'utente di inserire i dettagli della recensione
            System.out.print("Inserisci nome hotel: ");
            nomeHotel = userInput.readLine();
        
            System.out.print("Inserisci citta: ");
            citta = userInput.readLine();
        
            System.out.print("Inserisci punteggio complessivo: ");
            globalScore = userInput.readLine();
            
            // Richiesta dei punteggi per le categorie specifiche della recensione
            for (int i = 0; i < categoryNames.length; i++) {
                System.out.print("Inserisci il punteggio per la categoria '" + categoryNames[i] + "': ");
                singleScores[i] = userInput.readLine();
            }
        }
    
        // Costruzione della richiesta per inserire una nuova recensione
        String request = "insertReview," + username + "," + nomeHotel + "," + citta + "," + globalScore + "," + singleScores[0] + "." + singleScores[1] + "." + singleScores[2] + "." + singleScores[3];
    
        // Invio della richiesta al server
        sendRequest(request);
    }  
   
    /*CLASSI */
    public static class UDPReceiver implements Runnable {

        @Override
        public void run() {
            try {
                // Si ottiene l'indirizzo IP del gruppo multicast specificato
                InetAddress group = InetAddress.getByName(multicastAddress);
                // Crea un indirizzo di multicast usando l'indirizzo IP del gruppo e la porta UDP specificata
                InetSocketAddress multicastAddress = new InetSocketAddress(group, udpPort);
                // Si ottiene l'interfaccia di rete associata all'indirizzo IP locale dell'host
                NetworkInterface networkInterface = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());

                byte[] buffer = new byte[bufSizeUDP];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                while (!Thread.currentThread().isInterrupted()) {

                    synchronized (loginLockForMultiCast) {
                        while (username == null) {
                            loginLockForMultiCast.wait();
                        }
                    }
                    try{
                        // Connessione al gruppo multicast
                        multicastSocket.joinGroup(multicastAddress, networkInterface);
                        multicastSocket.receive(packet);
                        
                        String message = new String(packet.getData(), 0, packet.getLength());
                        synchronized(printSyncLock){
                            System.out.println("[Client] Messaggio dal server (UDP): " + message);
                        }
                    } catch (SocketException e) {
                        // Interruzione del thread, ad esempio quando viene invocato interrupt()
                        if(Thread.currentThread().isInterrupted()){ // thread interrotto
                            break;
                        }
                        else{ // interruzione dovuta al fatto che ci siamo sloggati
                            // riapro il socket che avevo chiuso per scatenare l'eccezione.
                            multicastSocket = new MulticastSocket(udpPort);
                            continue;
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                // Questa interruzione viene sollevata quando siamo in attesa sulla "variabile di condizione" e veniamo interrotti, in tal caso si chiude l'esecuzione.
                return;
            }
        }
    }
    private static class ClientTerminationHandler extends Thread {
        @Override
        public void run() {
            stato = false;
            //synchronized(printSyncLock){
                System.out.printf("\n[CLIENT] Avvio terminazione...\n");
                // Chiusura del SocketChannel
                try {
                    // Chiudi bufferReader
                    if (userInput != null) {
                        userInput.close();
                        System.out.println("    Flusso di input da tastiera chiuso correttamente.");
                    }
                    // Chiudi il SocketChannel
                    if (socketChannel != null && socketChannel.isOpen()) {
                        socketChannel.close();
                        System.out.println("    SocketChannel chiuso correttamente.");
                    }
                    // Interrompi il thread UDPReceiver
                    if (udpReceiverThread != null && udpReceiverThread.isAlive()) {
                        udpReceiverThread.interrupt();
                        multicastSocket.close();
                        udpReceiverThread.join();
                        System.out.println("    Thread UDPReceiver interrotto correttamente.");
                    }
                } catch (IOException e) {
                    System.err.println("[CLIENT] Errore durante la chiusura delle risorse:");
                    e.printStackTrace();
                    System.exit(1);
                }catch (InterruptedException e) {
                    e.printStackTrace();
                    System.exit(1);
                } 
                System.out.println("[CLIENT] Terminato");
        }
    }
    private static class JsonResponse {
        // Classe per deserializzare la risposta JSON dal server
        @SerializedName("Status-code")
        private int statusCode;
    
        @SerializedName("ReasonPhrase")
        private String reasonPhrase;
    
        public int getStatusCode() {
            return statusCode;
        }
    }
    
    /*READCONFIG */
    private static void readConfig() throws FileNotFoundException, IOException {
        // Apre lo stream di input per leggere il file di configurazione
        InputStream input = new FileInputStream(configFile);
    
        // Crea un oggetto Properties per gestire i valori del file di configurazione
        Properties prop = new Properties();
    
        // Carica i valori dal file di configurazione
        prop.load(input);
    
        // Legge i valori dalle properties e li assegna alle variabili appropriate
        hostname = prop.getProperty("hostname");
        serverTCPPort = Integer.parseInt(prop.getProperty("serverTCPPort"));
        bufSizeTCP = Integer.parseInt(prop.getProperty("bufSizeTCP"));
        bufSizeUDP = Integer.parseInt(prop.getProperty("bufSizeUDP"));
        exitMessage = prop.getProperty("exitMessage");
        udpPort = Integer.parseInt(prop.getProperty("udpPort"));
        multicastAddress = prop.getProperty("multicastAddress");
    
        // Chiude lo stream di input
        input.close();
    }
 

}
