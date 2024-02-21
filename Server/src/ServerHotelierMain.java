import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException; 
import java.util.Arrays;
import java.util.Collections;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import com.google.gson.*;
import java.lang.reflect.*;
import com.google.gson.reflect.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ServerHotelierMain {
    // Percorso del file di configurazione.
    private static final String configName = "server.properties";

    // Parametri di configurazione.
    private static String exitMessage;
    private static long maxDelay;
    private static int periodicDataUpdaterDelay;
    private static int hotelRankingUpdateFrequency;
    private static long timeThreshold;

    // Configurazioni di rete.
    private static int tcpPort;
    private static int bufSize;
    private static int udpPort = 4321;
    private static String udpAddress = "230.0.0.1";

    // Gestione del server socket e del selettore.
    private static ServerSocketChannel serverSocketChannel;
    private static Selector selector;

    // File JSON degli hotel, degli utenti e delle recensioni.
    private static String HOTELS_JSON_FILE;
    private static String USERS_JSON_FILE;
    private static String REVIEWS_JSON_FILE;

    // Dati memorizzati usando ConcurrentHashMap per gestire concorrenza.
    private static ConcurrentHashMap<String, User> registeredUsers = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, ConcurrentLinkedQueue<Hotel>> hotelsByCity = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, ConcurrentLinkedQueue<Review>> reviews = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, CityStats> cityStatsMap = new ConcurrentHashMap<>();

    // Pool di thread per gestire l'esecuzione concorrente.
    private static ScheduledExecutorService scheduler;

    // Oggetto di blocco per sincronizzare l'output su console.
    private static final Object printSyncLock = new Object();  

    /* MAIN */
    public static void main(String[] args) {
        // Lettura della configurazione server con gestione di eventuale eccezione
        try {
            readConfig();
        }catch (IOException e) {
            System.err.println("Errore durante la lettura del file di configurazione");            
            e.printStackTrace();
            System.exit(1);
        }

        // Aggiungo un hook per gestire la terminazione del server
        try{
            Runtime.getRuntime().addShutdownHook(new ServerTerminationHandler(Thread.currentThread()));
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            System.err.println("Errore durante l'aggiunta del ShutdownHook");
            e.printStackTrace();
            System.exit(1);
        }


        // Recupero le informazioni dai file JSON
        loadHotelsFromJSON();
        loadUsersFromJSON();
        loadReviewsFromJSON();

    
        // Crea un pool di thread per l'esecuzione periodica delle attività
        scheduler = Executors.newScheduledThreadPool(2); 

        // Pianifica e avvia le attività periodiche, persiste i dati su json, aggiorna il ranking locale degli hotel per ogni città.
        scheduler.scheduleWithFixedDelay(
            new DataUpdater(),
            30,
            periodicDataUpdaterDelay,
            TimeUnit.SECONDS
        );
        
        scheduler.scheduleWithFixedDelay(
            new HotelRankingUpdater(),
            30,
            hotelRankingUpdateFrequency, 
            TimeUnit.SECONDS
        );

         
        // Si apre quindi un ServerSocketChannel per gestire le connessioni dei client
        // e un selettore per gestire l'I/O multiplexato dei canali
        try {
            serverSocketChannel = ServerSocketChannel.open();
            selector = Selector.open(); 
            // Binding del ServerSocketChannel a una specifica porta TCP
            serverSocketChannel.bind(new InetSocketAddress(tcpPort));
            // Configuro il canale in modalita' non bloccante.
            serverSocketChannel.configureBlocking(false);
            // Registrazione del ServerSocketChannel presso il selettore per accettare le richieste di connessione (OP_ACCEPT)
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            // Il server è ora in ascolto e pronto ad accettare connessioni dai client sulla porta specificata (tcpPort)

            synchronized(printSyncLock){
                System.out.println("[SERVER] In ascolto su porta "+ tcpPort);
            }
            // Il server entra in un ciclo infinito nel quale:
            // 1)Attende (e accetta) richieste di connessione da parte dei client.
            // 2)Controlla (tramite il selettore) se ci sono canali
            // pronti per essere letti o scritti.
            while (!Thread.currentThread().isInterrupted()) {
                selector.select();
                
                /*if (Thread.currentThread().isInterrupted()) {
                    break; // Uscita dal ciclo se il thread è stato interrotto
                }*/
                // Ottieni le chiavi selezionate dal selettore
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();

                // Itera sulle chiavi selezionate
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();

                    // Controllo se sul canale associato alla chiave vi è la possibilita’ di accettare una nuova connessione.
                    // Nel caso, la accetto e registro il canale sul selettore.
                    if (key.isAcceptable()) {
                        // Si gestisce l'accettazione di una nuova connessione.
                        try{
                            handleAccept();
                        }
                        catch(IOException e){
                            System.err.println("Errore durante l'accettazione della connessione: " + e.getMessage());
                            System.exit(1);
                        }
                    } 
                    // Se sul canale ci sono dati pronti per essere letti, procedo con la lettura.
                    else if (key.isReadable()) {
                        synchronized(printSyncLock){
                            System.out.println("[SERVER] Dati pronti per la lettura");
                        }
                        try{
                            handleRead(key);
                        }
                        catch(IOException e){
                            System.err.println("Errore durante la lettura: " + e.getMessage());
                            System.exit(1);
                        }
                    } 
                    // Se il canale e' pronto per la scrittura, posso inviare la risposta.
                    else if(key.isWritable()){
                        synchronized(printSyncLock){
                            System.out.println("[SERVER] Dati pronti per la scrittura");
                        }
                        try{
                            handleWrite(key);
                        }
                        catch(IOException e){
                            System.err.println("[SERVER] Errore durante la scrittura: " + e.getMessage());
                            System.exit(1);
                        }                   
                    }
                    // Rimuove la chiave dal set delle chiavi pronte
                    iterator.remove();

                }
            }
            return;
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        } 
    }


    /* HANDLE */
    private static void handleAccept() throws IOException {
        // Accetta la connessione dal client
        SocketChannel client = serverSocketChannel.accept();
        // Crea un oggetto di stato per la corretta gestione della lettura e per memorizzare l'id del client
        State state = new State(bufSize);
        synchronized(printSyncLock){
            System.out.println("[SERVER] Nuova connessione ricevuta, client: " + state.id);
        }
        // Configura il canale del client in modalità non bloccante
        client.configureBlocking(false);
        // Registra il canale del client presso il selettore per leggere dati dal client (OP_READ)
        client.register(selector, SelectionKey.OP_READ, state);
    }
    private static void handleWrite(SelectionKey key) throws IOException {
        // Ottiene il SocketChannel e l'oggetto State associato alla chiave di selezione
        SocketChannel channel = (SocketChannel) key.channel();
        State state = (State) key.attachment();
    
        // Prova a scrivere i dati contenuti nel buffer sul canale
        channel.write(state.buffer);
    
        // Controlla se dopo la scrittura i dati nel buffer non sono stati completamente consumati
        if (state.buffer.hasRemaining()){
            // Se ci sono dati rimanenti nel buffer dopo la scrittura, compatta il buffer e termina la funzione
            state.buffer.compact(); // Sposta i dati non scritti all'inizio del buffer
            return; // Termina la funzione per ritentare più tardi
        }
    
        // Se la scrittura è stata completata, stampa un messaggio di conferma dell'invio al client
        synchronized(printSyncLock){
            System.out.println("[SERVER] Risposta inviata al client " + state.id);
        }
    
        // Resetta il buffer per prepararsi alla prossima lettura
        state.buffer.clear();
    
        // Imposta l'interesse del canale per le operazioni di lettura (OP_READ)
        key.interestOps(SelectionKey.OP_READ);
    }
    private static void handleRead(SelectionKey key) throws IOException {
        // Ottiene il SocketChannel e l'oggetto State associato alla chiave di selezione
        SocketChannel channel = (SocketChannel) key.channel();
        State state = (State) key.attachment();
        
        // Legge dati dal canale e li scrive nel buffer
        state.count += channel.read(state.buffer);
    
        String messageString;
    
        synchronized(printSyncLock){
            // Stampa un messaggio di lettura richiesta del client
            System.out.println("[SERVER] Lettura richiesta del client " + state.id);
            
            // Verifica se la connessione con il client è stata chiusa
            if (state.count == -1) {
                System.out.println("[SERVER] Connessione con il client " + state.id + " chiusa.");
                // Esegue la disconnessione dell'utente loggato tramite l'informazione relativa all'ID e chiude il canale
                disconnectLoggedInUserById(state.id);
                channel.close();
                return;
            }
            
            // Verifica se non ha ancora letto la lunghezza del messaggio (4 bytes)
            if (state.count < Integer.BYTES) return;
            
            // Se non ha ancora memorizzato la lunghezza del messaggio nell'oggetto State, la memorizza
            if (state.length == 0) {
                state.buffer.flip();
                state.length = state.buffer.getInt();
                state.buffer.compact();
            }
    
            // Verifica se ha ricevuto il messaggio completo, se non lo ha ancora fatto return 
            if (state.count < Integer.BYTES + state.length) return;
    
            // Estrae il messaggio dal buffer
            state.buffer.flip(); // si passa da scrittura a lettura del buffer quindi chiamo la flip
            byte[] messageBytes = new byte[state.length];
            state.buffer.get(messageBytes);
            messageString = new String(messageBytes);
            
            // Stampa il messaggio ricevuto dal client
            System.out.println("[SERVER] Ricevuto messaggio dal client " + state.id + ": " + messageString);
    
            // Verifica se il messaggio ricevuto è una richiesta di chiusura della connessione
            if (messageString.equalsIgnoreCase(exitMessage)) {
                System.out.println("[SERVER] Richiesta chiusura connessione da parte del client " + state.id);
                disconnectLoggedInUserById(state.id);
                channel.close();
                System.out.println("[SERVER] Connessione con il client " + state.id + " chiusa.");
                return;
            }
        }
    
        // Prepara la risposta e registra il canale per la scrittura
        state.buffer.clear();
        state.buffer = buildReplyBuffer(messageString, channel, state.id);
        key.interestOps(SelectionKey.OP_WRITE);
    
        // Reimposta parametri length e count dell'oggetto state per la prossima lettura
        state.length = 0;
        state.count = 0;
    }
        private static void disconnectLoggedInUserById(int idClient){
            // Si scorre tutti gli utenti registrati nel sistema
            for (User user : registeredUsers.values()) {
                // Si va alla ricerca dell'utente relativo a quel client e si disconnette
                if (user.getIdClient() == idClient) {
                    user.setLoggedIn(false);
                    user.setIdClient(-1);
                }
            }
        }
        private static ByteBuffer buildReplyBuffer(String request, SocketChannel socketChannel, int idClient) {
            // Si ottiene la risposta al messaggio di richiesta
            String replyString = handleRequest(request, socketChannel, idClient);

            // Si converte la risposta in array di byte
            byte[] replyBytes = replyString.getBytes();

            // Si alloca quindi un nuovo ByteBuffer con la dimensione necessaria per contenere la risposta e la sua lunghezza
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + replyBytes.length);

            // Posiziona la lunghezza della risposta come intero all'inizio del buffer, successivamente aggiunge i byte della risposta stessa
            // ed infine si chiama la flip() per rendere il buffer pronto per una successiva lettura
            buffer.putInt(replyBytes.length).put(replyBytes).flip();
            // Restituisce il buffer pronto per la scrittura sul canale
            return buffer;
        }

    private static String handleRequest(String request, SocketChannel clientSocket, int idClient) {
        // Estrae i parametri dalla richiesta
        // Utilizzo del metodo split() con l'opzione limit per evitare elementi vuoti nell'array, caso di nomerichiesta,,, si andrà ad inserire stringhe vuote ""
        String[] parameters = request.split(",",-1);
        switch (parameters[0]) {
            case "register":
                return register(parameters[1], parameters[2]);
            case "login":
                return login(parameters[1], parameters[2], idClient); 
            case "logout":
                return logout(parameters[1]); 
            case "searchHotel":
                return searchHotel(parameters[1], parameters[2]);
            case "searchAllHotels":
                return searchAllHotels(parameters[1]);
            case "insertReview":
                return insertReview(parameters[1], parameters[2], parameters[3],parameters[4], Arrays.asList(parameters[5].split("\\.",-1)));
            case "showMyBadge":
                return showMyBadge(parameters[1]);
            default:
                // Per come è implementato il client questa porzione di codice non verrà mai eseguita, per robustezza ad eventuali cambiamenti futuri lato client è comunque presente
                // Richiesta non valida
                JsonObject data = new JsonObject();
                data.addProperty("Rchiesta", "non valida" );
                return toJson(ServerResponse.INVALID_REQUEST,data);
        }
}

    
/* GESTIONE RICHIESTE */
        /*Registrazione */
        private static String register(String username, String password) {
            // se lo username contiene spazi allora restituisci registrazione fallita
            if(username.contains(" ") || username.length() == 0){
                ServerResponse serverResponse = ServerResponse.REGISTER_FAILED_INVALID_USERNAME;
                return toJson(serverResponse, null);
            }

            // Controlla se l'username esiste già nell'elenco degli utenti registrati
            if (!registeredUsers.containsKey(username)) {
                System.out.println("username: "+username);
                // L'username non esiste, procedi con la registrazione dell'utente
        
                // Verifica la validità della password
                if (!isValidPassword(password)) {
                    // Password non soddisfa i requisiti di sicurezza
                    ServerResponse serverResponse = ServerResponse.REGISTER_FAILED_INVALID_PASSWORD;
                    return toJson(serverResponse, null);
                }
        
                // Effettua l'hash della password prima di salvarla
                String hashedPassword = hashPassword(password);
                // Se c'è stato un errore durante l'hashed della password, chiudi il server.
                if(hashedPassword == null){
                    System.exit(1);
                }
                User newUser = new User(username, hashedPassword);
                registeredUsers.put(username, newUser);
        
                // Invia una risposta di successo al client
                ServerResponse serverResponse = ServerResponse.REGISTER_SUCCESS;
                return toJson(serverResponse, null);
            } else {
                // L'username esiste già, invia un messaggio di errore al client
                ServerResponse serverResponse = ServerResponse.REGISTER_FAILED_USERNAME_EXISTS;
                return toJson(serverResponse, null);
            }
        }
            private static boolean isValidPassword(String password) {

                // Verifica la lunghezza della password
                if (password.length() < 8 || password.length() > 16) {
                    return false;
                }
            
                // Verifica la presenza di caratteri minuscoli
                if (!password.matches(".*[a-z].*")) {
                    return false;
                }
            
                // Verifica la presenza di caratteri maiuscoli
                if (!password.matches(".*[A-Z].*")) {
                    return false;
                }
            
                // Verifica la presenza di numeri
                if (!password.matches(".*[0-9].*")) {
                    return false;
                }

                // Verifica se contiene spazi
                if(password.contains(" ")){
                    return false;
                }
            
                return true;
            }            
            private static String hashPassword(String password) {
                try {
                    // Crea un oggetto MessageDigest per l'algoritmo SHA-256
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
                    // Ottiene l'hash della password come array di byte
                    byte[] hashedBytes = digest.digest(password.getBytes());
            
                    // Converti i byte dell'hash in una rappresentazione esadecimale
                    StringBuilder stringBuilder = new StringBuilder();
                    for (byte hashedByte : hashedBytes) {
                        // Converte ogni byte in una stringa esadecimale e lo aggiunge al StringBuilder
                        stringBuilder.append(String.format("%02x", hashedByte));
                    }
            
                    // Restituisce l'hash della password come stringa esadecimale
                    return stringBuilder.toString();
                } catch (NoSuchAlgorithmException e) {
                    // Gestisce eventuali eccezioni NoSuchAlgorithmException
                    e.printStackTrace();
                    return null;
                }

            }
        /*Login */
        private static String login(String username, String password, int idClient) {
            // Verifica se l'username è registrato
            if (registeredUsers.containsKey(username)) {
                User user = registeredUsers.get(username);
                
                // Controlla se l'utente è già loggato
                if (user.isLoggedIn()) {
                    // L'utente è già loggato, invia un messaggio di errore al client
                    ServerResponse serverResponse = ServerResponse.LOGIN_FAILED_ALREADY_LOGGED_IN;
                    return toJson(serverResponse, null);
                }
                
                // Verifica se la password fornita corrisponde a quella dell'utente
                if (user.getPassword().equals(hashPassword(password))) {
                    // La password è corretta, esegue dunque l'accesso e invia un messaggio di successo al client
                    user.setLoggedIn(true); // Manteniamo traccia dello stato di accesso dell'utente lato server
                    user.setIdClient(idClient); // Imposta l'ID del client per l'utente loggato, questo mi serve per identificare l'utente su quale client è loggato
                    ServerResponse serverResponse = ServerResponse.LOGIN_SUCCESS;
                    return toJson(serverResponse, null);
                } else {
                    // La password è errata, invia un messaggio di errore al client
                    ServerResponse serverResponse = ServerResponse.LOGIN_FAILED_PASSWORD_INCORRECT;
                    return toJson(serverResponse, null);
                }
            } else {
                // L'username non esiste, invia un messaggio di errore al client
                ServerResponse serverResponse = ServerResponse.LOGIN_FAILED_USERNAME_DOES_NOT_EXIST;
                return toJson(serverResponse, null);        
            }
        }
        /*Logout */
        private static String logout(String username) {
            // Ottiene l'utente associato all'username dalla struttura dati degli utenti registrati
            User user = registeredUsers.get(username);
        
            // Verifica se l'utente esiste ed è attualmente loggato
            if (user != null && user.isLoggedIn()) {
                // Effettua il logout impostando isLoggedIn a false e azzerando l'ID del client ponendolo al valore di default -1
                user.setLoggedIn(false);
                user.setIdClient(-1);
        
                // Restituisci un messaggio di successo al client
                return toJson(ServerResponse.LOGOUT_SUCCESS, null);
            } else {
                // In tal caso L'utente non è attualmente loggato, dunque si invia un messaggio di errore al client
                return toJson(ServerResponse.LOGOUT_FAILED_NOT_LOGGED_IN, null);
            }
        }
         /*Cerca hotel */
        private static String searchHotel(String nomeHotel, String citta) {
            // Ottiene la lista degli hotel associati alla città fornita
            ConcurrentLinkedQueue<Hotel> hotelsInCity = hotelsByCity.get(citta);
        
            // Verifica se ci sono hotel per quella città
            if (hotelsInCity == null) {
                // Non ci sono hotel per quella città, allora restituisci un messaggio di errore al client
                return toJson(ServerResponse.HOTEL_SEARCH_FAILED_CITY_NOT_FOUND, null);
            }
            
            Hotel foundHotel = null;
            // Cerca l'hotel per nome nella lista degli hotel per quella città
            for (Hotel hotel : hotelsInCity) {
                // Confronta i nomi degli hotel ignorando differenze tra maiuscole e minuscole
                if (hotel.getName().equalsIgnoreCase(nomeHotel)) {
                    foundHotel = hotel;
                    break; // Interrompe il ciclo una volta trovato l'hotel
                }
            }
        
            if (foundHotel != null) {
                // Se l'hotel è stato trovato, serializza l'oggetto Hotel in formato JSON
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                String hotelJson = gson.toJson(foundHotel);
        
                // Converte la stringa JSON in un oggetto JsonObject
                JsonObject nestedData = gson.fromJson(hotelJson, JsonObject.class);

                // Rimuove la parte relativa alla normalizzazione dall'oggetto JsonObject che non è di interessa al client
                nestedData.remove("normalization");
        
                // Restituisce un messaggio di successo al client con i dati dell'hotel trovato
                return toJson(ServerResponse.HOTEL_SEARCH_SUCCESS, nestedData);
            } else {
                // Se l'hotel non è stato trovato, invia una risposta di "non trovato" al client
                return toJson(ServerResponse.HOTEL_SEARCH_FAILED_NOT_FOUND_IN_CITY, null);
            }
        }
        /*Cerca tutti gli hotel */
        private static String searchAllHotels(String citta) {
            // Ottiene la lista degli hotel associati alla città fornita
            ConcurrentLinkedQueue<Hotel> hotelsInCity = hotelsByCity.get(citta);
        
            // Verifica se ci sono hotel per quella città
            if (hotelsInCity == null) {
                // Non ci sono hotel per quella città, restituisce un messaggio di errore al client
                return toJson(ServerResponse.ALL_HOTEL_SEARCH_FAILED_CITY_NOT_FOUND, null);
            }
        
            // Inizializza una nuova istanza di Gson per la manipolazione JSON
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            
            // Crea un array JSON per contenere tutti gli hotel della città
            JsonArray hotelsArray = new JsonArray();
            
            // Itera gli hotel nella città e li aggiunge all'array JSON
            for (Hotel hotel : hotelsInCity) {
                String hotelJson = gson.toJson(hotel);
                JsonObject nestedData = gson.fromJson(hotelJson, JsonObject.class);
                // Rimuove la parte relativa alla normalizzazione dall'oggetto JsonObject che non è di interessa al client
                nestedData.remove("normalization");
                hotelsArray.add(nestedData);
            }
        
            // Crea un oggetto JSON contenente la lista di hotel in formato array
            JsonObject jsonHotels = new JsonObject();
            jsonHotels.add("hotels", hotelsArray);
            
            // Restituisce un messaggio di successo al client con la lista degli hotel in formato JSON
            return toJson(ServerResponse.ALL_HOTELS_SUCCESS, jsonHotels);
        }
        /*Inserimento recensione hotel */
        private static String insertReview(String username, String nomeHotel, String citta, String globalScore, List<String> singleScores) {
            // Verifica se l'utente è "null" (non loggato)
            if(username.equals("null")){
                // Invia un messaggio di errore al client indicando utente non è loggato
                return toJson(ServerResponse.REVIEW_INSERT_FAILED_NOT_LOGGED_IN_CLIENT_CHECK,null);
            }
            
            // Ottiene l'utente dalla struttura dati apposita
            User user = registeredUsers.get(username);
            if(user == null){
                // Tipicamente non dovrebbe mai verificarsi questa condizione per come è stato implementato il client, tengo comunque questo controllo per eventuali modifiche future che potrebbero essere fatte lato client, il controllo lato server comuque è presente
                // L'utente non è attualmente loggato, invia un messaggio di errore al client
                return toJson(ServerResponse.REVIEW_INSERT_FAILED_NOT_LOGGED_IN_SERVER_CHECK,null);
            }

            if (user.isLoggedIn()) {
                // L'utente è loggato, si procede con l'inserimento della recensione
                ConcurrentLinkedQueue<Hotel> hotelsInCity;

                // Controlla se la città specificata esiste
                if( (hotelsInCity = hotelsByCity.get(citta)) == null){
                    // Non esistono hotel per quella citta
                    return toJson(ServerResponse.REVIEW_INSERT_FAILED_CITY_NOT_FOUND,null);
                }

                // Trova l'hotel specificato nella città
                Hotel foundHotel = null;
                for (Hotel hotel : hotelsInCity) {
                    if (hotel.getName().equals(nomeHotel)) {
                        foundHotel = hotel;
                        break;
                    }
                }

                if(foundHotel == null){
                    // Se l'hotel non è stato trovato, invia una messaggio di not found al client
                    return toJson(ServerResponse.REVIEW_INSERT_FAILED_HOTEL_NOT_FOUND,null);
                }

                // Controlla il punteggio globale (verifica se è un intero e se è compreso tra 0 e 5 inclusi)
                int globalScoreInt;
                try {
                    globalScoreInt = Integer.parseInt(globalScore);
                    if (globalScoreInt < 0 || globalScoreInt > 5) {
                        // Il punteggio globale non è valido, invia un messaggio di errore al client
                        return toJson(ServerResponse.REVIEW_INSERT_FAILED_GLOBAL_SCORE_OUT_OF_RANGE, null);
                    }
                } catch (NumberFormatException e) {
                    // Il punteggio globale non è valido, invia un messaggio di errore al client
                    return toJson(ServerResponse.REVIEW_INSERT_FAILED_GLOBAL_SCORE_NOT_INT, null);
                }

                // Controlla i punteggi singoli
                    // Controlla numero di punteggi singoli, questa condizione non dovrebbe verificarsi mai per come è fatto il client
                    if (singleScores.size() != 4) {
                        // Il numero di punteggi singoli non è corretto, invia un messaggio di errore al client
                        return toJson(ServerResponse.REVIEW_INSERT_FAILED_SINGLE_SCORES_INVALID_NUMBER_OF_PARAMETERS, null);
                    }

                    // Crea un array di interi per salvare i punteggi individuali
                    int[] singleScoresInts = new int[4];
                    // Itera sull'elenco dei punteggi individuali
                    for (int i = 0; i < singleScores.size(); i++) {
                        try {
                            // Converte il punteggio individuale in un intero
                            int singleScoreInt = Integer.parseInt(singleScores.get(i));

                            // Controlla che il valore del punteggio individuale sia valido (compreso tra 0 e 5 inclusi)
                            if (singleScoreInt < 0 || singleScoreInt > 5) {
                                // Il valore di un punteggio individuale non è valido, invia un messaggio di errore al client
                                return toJson(ServerResponse.REVIEW_INSERT_FAILED_SINGLE_SCORE_OUT_OF_RANGE, null);
                            }

                            // Salva il punteggio individuale nell'array
                            singleScoresInts[i] = singleScoreInt;
                        } catch (NumberFormatException e) {
                            // Il valore di un punteggio individuale non è un intero, invia un messaggio di errore al client
                            return toJson(ServerResponse.REVIEW_INSERT_FAILED_SINGLE_SCORE_NOT_INT, null);
                        }
                    }

                // Ottiene la coda delle recensioni per l'hotel specifico e crea una nuova coda se non esiste inserendo in reviews una nuova coppia chiave valore
                ConcurrentLinkedQueue<Review> hotelReviews = reviews.get(nomeHotel);
                if (hotelReviews == null) {
                    hotelReviews = new ConcurrentLinkedQueue<>();
                    reviews.put(nomeHotel, hotelReviews);
                }


                long currentTime = System.currentTimeMillis();

                // Controlla se l'utente può inserire una nuova recensione
                for (Review review : hotelReviews) {
                    if (review.getUserId().equals(username)) {
                        long reviewTime = review.getTime(); 
                        long elapsedTime = currentTime - reviewTime;
                        // Verifica se è passato meno tempo di quanto specificato (timeThreshold) dall'ultima recensione
                        if (elapsedTime < timeThreshold) {
                            return toJson(ServerResponse.REVIEW_INSERT_FAILED_WITHIN_TIME_THRESHOLD, null);// Utente non può inserire una nuova recensione entro l'intervallo di tempo specificato
                        }
                    }
                }

                // Crea un oggetto Review con i punteggi
                Ratings singleScoresObject = new Ratings(singleScoresInts[0],singleScoresInts[1],singleScoresInts[2],singleScoresInts[3]);
                Review review = new Review(username,currentTime,nomeHotel,citta,globalScoreInt,singleScoresObject);
                
                // Aggiungi la nuova recensione alla coda delle recensioni dell'hotel
                hotelReviews.add(review);

                // Aumenta il numero di recensioni scritte dall'utente per aggiornamento badge
                user.increaseNumberOfReviewsWritten();

                // Aggiorna i valori delle recensioni dell'hotel
                updateHotelReviewScores(review);

                // Aggiorna il valore del ranking dell'hotel per quella città
                updateHotelScoresAndRanking(foundHotel);

                // Invia un messaggio di risposta al client
                return toJson(ServerResponse.REVIEW_INSERT_SUCCESS, null);      
            }
            else {
                // L'utente non è attualmente loggato, invia un messaggio di errore al client 
                return toJson(ServerResponse.REVIEW_INSERT_FAILED_NOT_LOGGED_IN_SERVER_CHECK,null);
            }

        }
            private static void updateHotelReviewScores(Review review) {
                // Si assume che la citta e l'hotel esistano di già poichè ho fatto i controlli prima di chiamare il metodo

                // Recupera gli hotel nella città della recensione
                ConcurrentLinkedQueue<Hotel> hotelsInCity = hotelsByCity.get(review.getCity());
                String nomeHotel = review.getHotelName();
                Hotel foundHotel = null;

                // Trova l'hotel corrispondente nella città
                for (Hotel hotel : hotelsInCity) {
                    if (hotel.getName().equals(nomeHotel)) {
                        foundHotel = hotel;
                        break;
                    }
                }

                // Ottiene il numero di recensioni attuali dell'hotel
                int numberOfReviews = foundHotel.getNumberOfReviews();

                // Se ci sono recensioni precedenti per l'hotel
                if (numberOfReviews > 0) {
                    // Calcola la nuova valutazione globale dell'hotel
                    float currentRate = foundHotel.getRate();
                    float newGlobalScore = ((currentRate * numberOfReviews) + review.getGlobalScore()) / (numberOfReviews + 1);
                    foundHotel.setRate(newGlobalScore);
            
                    // Aggiorna le valutazioni singole
                    Ratings r = review.getSingleScores();
                    Ratings x = foundHotel.getRatings();
            
                    double newCleaning = ((x.getCleaning() * numberOfReviews) + r.getCleaning()) / (numberOfReviews + 1);
                    x.setCleaning(newCleaning);
            
                    double newPosition = ((x.getPosition() * numberOfReviews) + r.getPosition()) / (numberOfReviews + 1);
                    x.setPosition(newPosition);
            
                    double newServices = ((x.getServices() * numberOfReviews) + r.getServices()) / (numberOfReviews + 1);
                    x.setServices(newServices);
            
                    double newQuality = ((x.getQuality() * numberOfReviews) + r.getQuality()) / (numberOfReviews + 1);
                    x.setQuality(newQuality);
                }else {
                    // Se è la prima recensione per l'hotel, imposta direttamente i valori
                    foundHotel.setRate(review.getGlobalScore());
                    foundHotel.setRatings(review.getSingleScores());
                }

                // Incrementa il numero di recensioni dell'hotel
                foundHotel.setNumberOfReviews(numberOfReviews + 1);

                updateHotelScoresAndRanking(foundHotel);
            }
            private static void updateHotelScoresAndRanking(Hotel hotel) {
                // Calcola il punteggio di qualità delle recensioni
                Ratings ratingHotel = hotel.getRatings();
                double qualityScore = ratingHotel.calculateAverage();

                // Calcola il punteggio di quantità delle recensioni
                double quantityScore = hotel.getNumberOfReviews();

                // Calcola il punteggio di attualità delle recensioni, questo però viene ricalcolato per ogni hotel in modo che siano coerenti i valori degli altri hotel per il ranking finale.
                String city = hotel.getCity();
                ConcurrentLinkedQueue<Hotel> hotelsInCity = hotelsByCity.get(city);
                long currentTime = System.currentTimeMillis();
                double maxRelevanceScore = Double.MIN_VALUE;
                for(Hotel h : hotelsInCity){
                    double relevanceScore = 0;
                    if (quantityScore > 0) {
                        ConcurrentLinkedQueue<Review> hotelReviews = reviews.get(h.getName());
                        if (hotelReviews != null) {
                            for (Review review : hotelReviews) {
                                //long daysDifference = (currentTime - review.getTime()) / (1000 * 60 * 60 * 24); // Calcolo dei giorni trascorsi
                                long daysDifference = (currentTime - review.getTime()) / (1000*60); // Calcolo dei minuti trascorsi

                                // Scala inversa: quanto più recente è la recensione, tanto maggiore è il punteggio
                                double scaledDaysDifference = 1.0 / (daysDifference + 1); // Assicura che il punteggio aumenti man mano che i giorni diminuiscono(con +1 evito divisioni per zero)

                                // Aggiungi il contributo al punteggio di attualità
                                relevanceScore += scaledDaysDifference;
                            }
                            relevanceScore /= h.getNumberOfReviews(); // Calcolo la media del punteggio di attualità
                            // Setta i punteggi di attualità per ogni hotel
                            h.setOriginalRelevanceScore(relevanceScore);
                            // Aggiorno il maxRelevanceScore per normalizzare grazie a questo massimo, che viene chiaramente ogni volta aggiornato in quanto questi punteggio varia per tutti gli hotel al passare del tempo.
                            if(maxRelevanceScore < relevanceScore){
                                maxRelevanceScore = relevanceScore;
                            }
                        }
                    }
                }

                // Imposta i punteggi originali per la normalizzazione min/max in caso di nuovi min/max
                hotel.setOriginalQualityScore(qualityScore);
                hotel.setOriginalQuantityScore(quantityScore);
                
                // Normalizza e aggiorna i punteggi massimi per la città
                CityStats cityStats = cityStatsMap.get(city);
                cityStats.setMaxRelevanceScore(maxRelevanceScore);
                cityStats.normalizeAndUpdateMaxScores(hotelsInCity, hotel);
            }
        /* Visualizzazione Badge */
        private static String showMyBadge(String username) {
            // Controlla se l'utente è loggato, client check
            if(username.equals("null")){
                return toJson(ServerResponse.BADGES_FAILED_NOT_LOGGED_IN_CLIENT_CHECK,null);
            }
            
            // Se l'utente non è loggato username sarà pari a null per come è implementato il client, si tiene comunque questo controllo per rimanere robusti contro possibili modifiche future lato client
            // Controlla se l'utente è loggato (prima verifica se registrato, server check)
            User user = registeredUsers.get(username);
            if(user == null){
                // L'utente non è registrato, invia un messaggio di errore al client(quesio perche se si ha l'associazione in qesta hashmap significa che ti sei loggato, dunque se non trova nulla allora non sei loggato)
                return toJson(ServerResponse.BADGES_FAILED_USER_NOT_REGISTERED_SERVER_CHECK,null);
            }

            // Verifica se l'utente è attualmente loggato
            if (user.isLoggedIn()) {
                // Se l'utente è loggato, ottiene il livello dell'utente e crea una risposta JSON
                String userLevel = user.getLevel();
                JsonObject nestedData = new JsonObject();
                nestedData.addProperty("level", userLevel);
                return toJson(ServerResponse.BADGES_SUCCESS, nestedData);
            }
            else {
                // Se l'utente non è attualmente loggato, restituisce un messaggio di errore al client
                // (Se c'è una voce nell'HashMap ma l'utente non è segnato come loggato)
                return toJson(ServerResponse.BADGES_FAILED_NOT_LOGGED_IN_SERVER_CHECK,null);
            }

        }
    
        /* Utility per gestione richieste */
        private static String toJson(ServerResponse serverResponse, JsonObject data) {
            // Creazione di un'istanza di Gson per la conversione dell'oggetto JSON in una stringa JSON formattata
            Gson gson = new GsonBuilder().setPrettyPrinting().create();

            // Creazione di un nuovo oggetto JSON per rappresentare la risposta
            JsonObject jsonResponse = new JsonObject();

            // Aggiunta del codice di stato e della frase di stato alla risposta JSON
            jsonResponse.addProperty("Status-code", serverResponse.getCode());
            jsonResponse.addProperty("ReasonPhrase", serverResponse.getReasonPhrase());

            // Aggiunta dei dati alla risposta JSON se sono presenti
            if (data != null) {
                jsonResponse.add("Data", data);
            }

            // Conversione dell'oggetto JSON in una stringa JSON formattata e restituzione
            return gson.toJson(jsonResponse);
        }        
        private enum ServerResponse {
            // Risposte per il processo di registrazione
            REGISTER_SUCCESS(200, "Registrazione avvenuta con successo"),
            REGISTER_FAILED_USERNAME_EXISTS(400, "Registrazione fallita: username gia esistente"),
            REGISTER_FAILED_INVALID_USERNAME(401, "Registrazione fallita: username non valido. Non può contenere spazi e non può essere una stringa vuota"),
            REGISTER_FAILED_INVALID_PASSWORD(402, "Registrazione fallita: password non valida. Deve contenere almeno 1 carattere minuscolo, 1 carattere maiuscolo, 1 numero, essere lunga da 8 a 16 caratteri, non deve contenere spazi."),

            // Risposte per il processo di login
            LOGIN_SUCCESS(201, "Login avvenuto con successo"),
            LOGIN_FAILED_ALREADY_LOGGED_IN(403, "Login fallito: utente gia loggato"),
            LOGIN_FAILED_USERNAME_DOES_NOT_EXIST(404, "Login fallito: username non esistente, registrati prima"),
            LOGIN_FAILED_PASSWORD_INCORRECT(405,"Login fallito: password incorretta"),

            // Risposte per il processo di logout
            LOGOUT_SUCCESS(202, "Logout avvenuto con successo"),
            LOGOUT_FAILED_NOT_LOGGED_IN(406, "Logout fallito: non ti sei ancora loggato"),

            // Risposte per la ricerca degli hotel
            HOTEL_SEARCH_SUCCESS(203, "Ricerca Hotel avvenuta con successo"),
            HOTEL_SEARCH_FAILED_CITY_NOT_FOUND(407, "Ricerca Hotel fallita: La citta specificata non esiste"),
            HOTEL_SEARCH_FAILED_NOT_FOUND_IN_CITY(408, "Ricerca Hotel fallita: hotel specificato non esiste in questa citta"),

            // Risposte per la ricerca di tutti gli hotel
            ALL_HOTELS_SUCCESS(204, "Ricerca di tutti gli hotel avvenuta con successo"),
            ALL_HOTEL_SEARCH_FAILED_CITY_NOT_FOUND(409, "Ricerca di tutti gli hotel fallita: non esistono hotel per questa città"),

            // Risposte per l'inserimento delle recensioni
            REVIEW_INSERT_SUCCESS(205, "Inserimento recensione avvenuto con successo"),
            REVIEW_INSERT_FAILED_NOT_LOGGED_IN_SERVER_CHECK(410, "Inserimento recensione fallita: utente non loggato (check server-side)"),
            REVIEW_INSERT_FAILED_NOT_LOGGED_IN_CLIENT_CHECK(411, "Inserimento recensione fallita: utente non loggato (check client-side)"),
            REVIEW_INSERT_FAILED_CITY_NOT_FOUND(412, "Inserimento recensione fallita: citta non trovata"),
            REVIEW_INSERT_FAILED_HOTEL_NOT_FOUND(413, "Inserimento recensione fallita: hotel non trovato per quella città"),
            REVIEW_INSERT_FAILED_GLOBAL_SCORE_OUT_OF_RANGE(414, "Inserimento recensione fallita: valutazione globale deve essere compresa tra 0 e 5"),
            REVIEW_INSERT_FAILED_GLOBAL_SCORE_NOT_INT(415, "Inserimento recensione fallita: valutazione globale deve essere un numero intero"),
            REVIEW_INSERT_FAILED_SINGLE_SCORES_INVALID_NUMBER_OF_PARAMETERS(416, "Inserimento recensione fallita: il numero di valutazioni singole deve essere 4"),
            REVIEW_INSERT_FAILED_SINGLE_SCORE_NOT_INT(417, "Inserimento recensione fallita: le valutazioni singole devono essere tutte intere"),
            REVIEW_INSERT_FAILED_SINGLE_SCORE_OUT_OF_RANGE(418,"Inserimento recensione fallita: le valutazioni singolo devono essere tutte tra 0 e 5 inclusi"),
            REVIEW_INSERT_FAILED_WITHIN_TIME_THRESHOLD(419, "Inserimento recensione fallita: utente non puo inserire una recensione nuovamente non e passato abbastanza tempo dall ultima, riprova tra qualche istante"),

            // Risposte per la visualizzazione dei badge
            BADGES_SUCCESS(206, "Visualizzazione badge eseguita con successo"),
            BADGES_FAILED_NOT_LOGGED_IN_SERVER_CHECK(420,"Visualizzazione badge fallita: utente non loggato (check server-side)"),
            BADGES_FAILED_NOT_LOGGED_IN_CLIENT_CHECK(421,"Visualizzazione badge fallita: utente non loggato (check client-side)"),
            BADGES_FAILED_USER_NOT_REGISTERED_SERVER_CHECK(422,"Visualizzazione badge fallita: utente non registrato (check server-side)"),

            // Risposta per richieste non valide
            INVALID_REQUEST(500, "Richiesta non valida: codice di richiesta sconosciuto");

            private final int code;
            private final String reasonPhrase;

            ServerResponse(int code, String reasonPhrase) {
                this.code = code;
                this.reasonPhrase = reasonPhrase;
            }

            public int getCode() {
                return code;
            }

            public String getReasonPhrase() {
                return reasonPhrase;
            }
        }
/* FINE GESTIONE RICHIESTE */

    /* CLASSI */
    private static class CityStats {

        private double maxQualityScore; // Il massimo punteggio di qualità per gli hotel in questa città
        private double maxQuantityScore; // Il massimo punteggio di quantità per gli hotel in questa città
        private double maxRelevanceScore; // Il massimo punteggio di pertinenza per gli hotel in questa città
    
        public CityStats() {
            this.maxQualityScore = Double.MIN_VALUE; // Inizializza i massimi a valori molto bassi
            this.maxQuantityScore = Double.MIN_VALUE;
            this.maxRelevanceScore = Double.MIN_VALUE; 
        }

        public void normalizeAndUpdateMaxScores(ConcurrentLinkedQueue<Hotel> hotels, Hotel h) {
            boolean updatedQuality = false;
            boolean updatedQuantity = false;
            double newQualityScore = h.getOriginalQualityScore();
            double newQuantityScore = h.getOriginalQuantityScore();

            // Aggiorna i massimi se i punteggi correnti sono più alti
            if (newQualityScore > maxQualityScore) {
                maxQualityScore = newQualityScore;
                updatedQuality = true;
            }
            if (newQuantityScore > maxQuantityScore) {
                maxQuantityScore = newQuantityScore;
                updatedQuantity = true;
            }

            // Se almeno uno dei massimi è stato aggiornato, normalizza gli hotel tranne quello in particolare del quale si deve normalizzare tutti i valori non solo quelli che hanno riscontrato un nuovo massimo
            for (Hotel hotel : hotels) {
                if(!hotel.equals(h)){
                    double originalQualityScore = hotel.getOriginalQualityScore();
                    double originalQuantityScore = hotel.getOriginalQuantityScore();
                    double originalRelevanceScore = hotel.getOriginalRelevanceScore();
                    // questo peche nel caso in cui non si ha un aggiornamento dei valori di utilizzano i valori di default
                    double normalizedQuality = hotel.getNormalizedQualityScore();
                    double normalizedQuantity = hotel.getNormalizedQuantityScore();
                    
                    // Normalizza i punteggi degli hotel se sono stati aggiornati i massimi
                    if (updatedQuality) {
                        normalizedQuality = normalizeValue(originalQualityScore, maxQualityScore);
                        hotel.setNormalizedQualityScore(normalizedQuality);
                    }
                    if (updatedQuantity) {
                        normalizedQuantity = normalizeValue(originalQuantityScore, maxQuantityScore);
                        hotel.setNormalizedQuantityScore(normalizedQuantity);
                    }
                    
                    // Questi li Normalizzo a prescindere in quanto i valori di attualità saranno sicuramente cambiati
                    double normalizedRelevance = normalizeValue(originalRelevanceScore, maxRelevanceScore);
                    hotel.setNormalizedRelevanceScore(normalizedRelevance);
                    
                    // Calcola e imposta il punteggio complessivo di ranking per l'hotel
                    double rankingScore = normalizedQuality + normalizedQuantity + normalizedRelevance;
                    hotel.setRankingScore(rankingScore);
                }
            }
                       // Sia nel caso di nessun massimo aggiornato che nella'altro, normalizza e imposta i punteggi dell'hotel specificato
            double normalizedQuality = normalizeValue(h.getOriginalQualityScore(),maxQualityScore);
            double normalizedQuantity = normalizeValue(h.getOriginalQuantityScore(),maxQuantityScore);
            double normalizedRelevance = normalizeValue(h.getOriginalRelevanceScore(),maxRelevanceScore);
            h.setNormalizedQualityScore(normalizedQuality);
            h.setNormalizedQuantityScore(normalizedQuantity);
            h.setNormalizedRelevanceScore(normalizedRelevance);
            h.setRankingScore(normalizedQuality + normalizedQuantity + normalizedRelevance);
        }

        // Metodo privato per la normalizzazione dei punteggi
        private double normalizeValue(double value, double maxValue) {
            if (maxValue == 0) {
                // Gestione del caso in cui il valore massimo è zero per evitare divisioni per zero
                return 0.0; 
            } else {
                // Normalizza il valore rispetto al massimo e scala il risultato tra 0 e 10
                return 10.0 * (value / maxValue);
            }
        }

        private void setMaxRelevanceScore(double maxRelevanceScore){
            this.maxRelevanceScore = maxRelevanceScore;
        }
    }
    private static class User {
        private String username;
        private String password;
        private boolean isLoggedIn;
        private int numberOfReviewsWritten = 0;
        private Level level;
        private int idClient; 

        // Costruttore della classe User
        public User(String username, String password) {
            this.username = username;
            this.password = password;
            this.isLoggedIn = false; // L'utente è inizialmente disconnesso
            this.level = Level.RECENSORE; // Livello iniziale
            this.idClient = -1; // Valore nullo per l'ID del client

        }
    
        public int getIdClient() {
            return idClient;
        }

        public void setIdClient(int newIdClient) {
            this.idClient = newIdClient;
        } 

        public String getUsername(){
            return this.username;
        }

        public String getPassword(){
            return this.password;
        }

        public boolean isLoggedIn() {
            return this.isLoggedIn;
        }

        public void setLoggedIn(boolean loggedIn) {
            this.isLoggedIn = loggedIn;
        }

        public void increaseNumberOfReviewsWritten(){
            this.numberOfReviewsWritten++;
            updateLevel(); // Aggiorna il livello ogni volta che viene aggiunta una recensione

        }

        // Metodo per aggiornare il livello dell'utente in base al numero di recensioni
        private void updateLevel() {
            if (numberOfReviewsWritten >= Level.RECENSORE_ESPERTO.getRequiredReviews() && numberOfReviewsWritten < Level.CONTRIBUTORE.getRequiredReviews()) {
                level = Level.RECENSORE_ESPERTO;
            } else if (numberOfReviewsWritten >= Level.CONTRIBUTORE.getRequiredReviews() && numberOfReviewsWritten < Level.CONTRIBUTORE_ESPERTO.getRequiredReviews()) {
                level = Level.CONTRIBUTORE;
            } else if (numberOfReviewsWritten >= Level.CONTRIBUTORE_ESPERTO.getRequiredReviews() && numberOfReviewsWritten < Level.CONTRIBUTORE_SUPER.getRequiredReviews()) {
                level = Level.CONTRIBUTORE_ESPERTO;
            } else if (numberOfReviewsWritten >= Level.CONTRIBUTORE_SUPER.getRequiredReviews()) {
                level = Level.CONTRIBUTORE_SUPER;
            }
        }

        public String getLevel(){
            return this.level.getBadge();
        }
        // Enum per i livelli di esperienza e i relativi distintivi
        public enum Level {
            RECENSORE(0, "Recensore"),
            RECENSORE_ESPERTO(10, "Recensore esperto"),
            CONTRIBUTORE(20, "Contributore"),
            CONTRIBUTORE_ESPERTO(30, "Contributore esperto"),
            CONTRIBUTORE_SUPER(40, "Contributore Super");

            private final int requiredReviews;
            private final String badge;

            Level(int requiredReviews, String badge) {
                this.requiredReviews = requiredReviews;
                this.badge = badge;
            }

            public int getRequiredReviews() {
                return requiredReviews;
            }

            public String getBadge() {
                return badge;
            }
        }

        // Metodo toString per ottenere una rappresentazione testuale dell'oggetto User
        @Override
        public String toString() {
            return "User{" +
                    "username='" + username + '\'' +
                    ", password='" + password + '\'' +
                    ", isLoggedIn=" + isLoggedIn +
                    ", level=" + level.getBadge() + // Otteniamo il distintivo del livello
                    ", numberOfReviewsWritten=" + numberOfReviewsWritten +
                    '}';
        }
    }
    private static class Ratings {
        private double cleaning;
        private double position;
        private double services;
        private double quality;

        public Ratings(double cleaning, double position, double services, double quality) {
            this.cleaning = cleaning;
            this.position = position;
            this.services = services;
            this.quality = quality;
        }

        public double getCleaning() {
            return cleaning;
        }

        public void setCleaning(double cleaning) {
            this.cleaning = cleaning;
        }

        public double getPosition() {
            return position;
        }

        public void setPosition(double position) {
            this.position = position;
        }

        public double getServices() {
            return services;
        }

        public void setServices(double services) {
            this.services = services;
        }

        public double getQuality() {
            return quality;
        }

        public void setQuality(double quality) {
            this.quality = quality;
        }

        public double calculateAverage() {
            double sum = cleaning + position + services + quality;
            return sum / 4.0;
        }

        @Override
        public String toString() {
            return "Ratings{" +
                    "cleaning=" + cleaning +
                    ", position=" + position +
                    ", services=" + services +
                    ", quality=" + quality +
                    '}';
        }


}  
    private class Hotel implements Comparable<Hotel> {
        private int id;
        private String name;
        private String description;
        private String city;
        private String phone;
        private List<String> services;
        private float rate;
        private Ratings ratings;
        private double rankingScore;
        private int numberOfReviews; // Numero di recensioni ricevute
        private Normalization normalization; // Oggetto che serve 


        // Override del metodo compareTo per definire l'ordinamento degli oggetti Hotel
        @Override
        public int compareTo(Hotel other) {
            // Confronto basato sul rankingScore (ordine decrescente)
            int scoreComparison = Double.compare(other.rankingScore, this.rankingScore);
        
            if (scoreComparison != 0) {
                return scoreComparison;
            } else {
                // Confronto per la lunghezza dei nomi in caso di score uguale
                int lengthComparison = this.name.length() - other.name.length();
                if (lengthComparison != 0) {
                    return lengthComparison;
                } else {
                    // Se le stringhe hanno la stessa lunghezza, si esegue un confrono lessicografico
                    return this.name.compareTo(other.name);
                }
            }
        }
        

        // Classe interna Normalization
        private class Normalization {
            private double originalQualityScore;
            private double originalQuantityScore;
            private double originalRelevanceScore;
            private double normalizedQualityScore;
            private double normalizedQuantityScore;
            private double normalizedRelevanceScore;

            public void setOriginalQualityScore(double qualityScore) {
                this.originalQualityScore = qualityScore;
            }
            
            public void setOriginalQuantityScore(double quantityScore) {
                this.originalQuantityScore = quantityScore;
            }

            public void setOriginalRelevanceScore(double originalRelevanceScore){
                this.originalRelevanceScore = originalRelevanceScore;
            }

            // Getters per i valori originali e normalizzati
            public double getOriginalQualityScore() {

                return originalQualityScore;
            }

            public double getOriginalQuantityScore() {
                return originalQuantityScore;
            }

            public double getOriginalRelevanceScore() {
                return originalRelevanceScore;
            }

            public double getNormalizedQualityScore() {
                return normalizedQualityScore;
            }

            public double getNormalizedQuantityScore() {
                return normalizedQuantityScore;
            }

            // Setters per i valori normalizzati
            public void setNormalizedQualityScore(double normalizedQualityScore) {
                this.normalizedQualityScore = normalizedQualityScore;
            }

            public void setNormalizedQuantityScore(double normalizedQuantityScore) {
                this.normalizedQuantityScore = normalizedQuantityScore;
            }

            public void setNormalizedRelevanceScore(double normalizedRelevanceScore) {
                this.normalizedRelevanceScore = normalizedRelevanceScore;
            }

            @Override
            public String toString() {
                return "Normalization{" +
                        "originalQualityScore=" + originalQualityScore +
                        ", originalQuantityScore=" + originalQuantityScore +
                        ", originalRelevanceScore=" + originalRelevanceScore +
                        ", normalizedQualityScore=" + normalizedQualityScore +
                        ", normalizedQuantityScore=" + normalizedQuantityScore +
                        ", normalizedRelevanceScore=" + normalizedRelevanceScore +
                        '}';
            }

        }

        // Metodi per impostare e recuperare i valori normalizzati
        public void setOriginalQualityScore(double qualityScore) {
            this.normalization.setOriginalQualityScore(qualityScore);
        }
            
        public void setOriginalQuantityScore(double quantityScore) {
            this.normalization.setOriginalQuantityScore(quantityScore);
        }

        public void setOriginalRelevanceScore(double relevanceScore){
            this.normalization.setOriginalRelevanceScore(relevanceScore);
        }

        public double getOriginalQualityScore() {
            return this.normalization.getOriginalQualityScore();
        }

        public double getOriginalQuantityScore() {
            return this.normalization.getOriginalQuantityScore();
        }

        public double getOriginalRelevanceScore() {
            return this.normalization.getOriginalRelevanceScore();
        }

        public double getNormalizedQualityScore() {
            return this.normalization.getNormalizedQualityScore();
        }

        public double getNormalizedQuantityScore() {
            return this.normalization.getNormalizedQuantityScore();
        }

        public void setNormalizedQualityScore(double normalizedQualityScore) {
            this.normalization.setNormalizedQualityScore(normalizedQualityScore);
        }

        public void setNormalizedQuantityScore(double normalizedQuantityScore) {
            this.normalization.setNormalizedQuantityScore(normalizedQuantityScore);
        }

        public void setNormalizedRelevanceScore(double normalizedRelevanceScore) {
            this.normalization.setNormalizedRelevanceScore(normalizedRelevanceScore);
        }


        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getCity() {
            return city;
        }

        public float getRate() {
            return rate;
        }

        public void setRate(float rate) {
            this.rate = rate;
        }

        public Ratings getRatings() {
            return ratings;
        }

        public void setRatings(Ratings ratings) {
            this.ratings = ratings;
        }

        public void setRankingScore(double rankingScore) {
            this.rankingScore = rankingScore;
        }

        public int getNumberOfReviews() {
            return numberOfReviews;
        }

        public void setNumberOfReviews(int numberOfReviews) {
            this.numberOfReviews = numberOfReviews;
        }

        @Override
        public String toString() {
            return "Hotel{" +
                    "Identificativo:" + id + "  " +
                    ", Nome hotel:" + name + "  " +
                    ", description:" + description + "  " +
                    ", city:" + city + "  " +
                    ", phone:" + phone + "  " +
                    ", services:" + services + "  " +
                    ", rate:" + rate + "  " +
                    ", ratings:" + (ratings != null ? ratings.toString() : "null") + "  " +
                    ", rankingScore:" + rankingScore + "  " +
                    ", numberOfReviews:" + numberOfReviews + "  " +
                    ", normalization:" + (normalization != null ? normalization.toString() : "null") + "  " +
                    '}';
        }
    }
    private static class Review {

        private String userId;
        private long time;
        private String hotelName;
        private String city;
        private int globalScore;
        private Ratings singleScores;

        public Review(String userId, long time, String hotelName, String city, int globalScore, Ratings singleScores) {
            this.userId = userId;
            this.time = time;
            this.hotelName = hotelName;
            this.city = city;
            this.globalScore = globalScore;
            this.singleScores = singleScores;
        }

        public String getUserId() {
            return userId;
        }

        public long getTime() {
            return time;
        }

        public String getHotelName() {
            return hotelName;
        }

        public String getCity() {
            return city;
        }

        public int getGlobalScore() {
            return globalScore;
        }

        public Ratings getSingleScores() {
            return singleScores;
        }

        @Override
        public String toString() {
            return "Review{" +
                    "userId='" + userId + '\'' +
                    ", time=" + time +
                    ", hotelName='" + hotelName + '\'' +
                    ", city='" + city + '\'' +
                    ", globalScore=" + globalScore +
                    ", singleScores=" + singleScores +
                    '}';
        }
    }
    private static class State {
        private static int lastAssignedId = 0;
        // Numero totale di byte letti.
        public int count;
        // Dimensione del messaggio da ricevere.
        public int length;
        // Buffer per memorizzare il messaggio e la sua lunghezza.
        public ByteBuffer buffer;
        // Mantengo identificativo del client.
        public int id;
        
        public State(int bufSize) {
            this.count = 0;
            this.length = 0;
            this.buffer = ByteBuffer.allocate(bufSize);
            this.id = getNextId(); // Assegna un nuovo ID all'istanza di State
        }

        // Metodo privato sincronizzato per ottenere il prossimo ID disponibile
        private synchronized int getNextId() {
            return ++lastAssignedId; // Incrementa e restituisce l'ultimo ID assegnato
        }
    }
    private static class ServerTerminationHandler extends Thread {
        Thread main;

        public ServerTerminationHandler(Thread main){
            this.main = main;
        }
        @Override
        public void run() {
            main.interrupt();
            // Avvio la procedura di terminazione del server.
            System.out.println("[SERVER] Avvio terminazione...");

            // Chiudi il pool scheduler di thread in modo corretto.
            if (scheduler != null && !scheduler.isShutdown()) {
                System.out.println("[SERVER] Chiusura pool scheduler");
                scheduler.shutdown(); // Avvia l'arresto ordinato dei thread nel pool
                try {
                    if (!scheduler.awaitTermination(maxDelay, java.util.concurrent.TimeUnit.MILLISECONDS)) { // Attendi fino a un massimo di 60 secondi che tutti i thread terminino
                        scheduler.shutdownNow(); // Se non terminano entro il timeout, interrompi forzatamente i thread
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow(); // Se viene interrotta l'attesa, interrompi forzatamente i thread
                }
            }

            try {
                // Persisto i dati in formato JSON
                saveDataToJson(HOTELS_JSON_FILE, USERS_JSON_FILE,REVIEWS_JSON_FILE);
            } catch (IOException e) {
                System.err.println("[SERVER] Errore durante il salvataggio dei dati: " + e.getMessage());
            }

            try {
                // Chiudo le risorse del server
                if (serverSocketChannel != null && serverSocketChannel.isOpen()) {
                    serverSocketChannel.close();
                    System.out.println("[SERVER] ServerSocketChannel chiuso.");
                }
                if (selector != null && selector.isOpen()) {
                    selector.close();
                    System.out.println("[SERVER] Selector chiuso.");
                }
            } 
            catch (IOException e) {
                System.err.println("[SERVER] Errore durante la chiusura delle risorse:"+ e.getMessage());
            }

            System.out.println("[SERVER] Terminato.");
        }

        private void saveDataToJson(String HOTELS_JSON_FILE, String USERS_JSON_FILE, String REVIEWS_JSON_FILE) throws IOException {
            // Prima di salvare i dati, setto tutti gli utenti come disconnessi
            setAllLoggedOutUsers();

            // Eseguo l'aggiornamento dei dati nei file JSON
            DataUpdater dataUpdater = new DataUpdater();
            dataUpdater.run();
        }

        private static void setAllLoggedOutUsers() {
            // Itero attraverso gli utenti registrati e li setto tutti come disconnessi
            for (Map.Entry<String, User> entry : registeredUsers.entrySet()) {
                User user = entry.getValue();
                if (user.isLoggedIn()) {
                    user.setLoggedIn(false);
                    user.setIdClient(-1);
                }
            }
        }
    }
    private static class HotelRankingUpdater implements Runnable {
    
        @Override
        public void run() {
            // Iterazione per ogni città nella mappa
            for (Map.Entry<String, ConcurrentLinkedQueue<Hotel>> entry : hotelsByCity.entrySet()) {
                String city = entry.getKey(); // Ottiene il nome della città
                ConcurrentLinkedQueue<Hotel> hotels = entry.getValue(); // Ottiene gli hotel per la città corrente

                // Ottiene il primo hotel nella coda
                Hotel firstHotel = hotels.peek();

                if (firstHotel != null) {
                    // Esegui il sort() e ottieni il potenziale nuovo hotel primo classificato
                    sort(hotels);
                    Hotel potentialNewTopHotel = hotels.peek(); // Ottiene il nuovo hotel primo classificato dopo il sort

                    // Verifica se c'è un nuovo hotel primo classificato e se è diverso dal precedente
                    if (potentialNewTopHotel != null && potentialNewTopHotel.getId() != firstHotel.getId()) {
                        sendUDPMessage("Aggiornamento rankking locale per la citta: "+city+", nuovo primo Hotel:"+potentialNewTopHotel.getName(), udpAddress,udpPort);
                    }
                }
            }
        }

    
        private static void sendUDPMessage(String message, String ipAddress, int port) {
            // Crea una nuova istanza di MulticastSocket
            try (MulticastSocket multicastSocket = new MulticastSocket(port)) {
                // Ottiene l'indirizzo IP del gruppo multicast
                InetAddress group = InetAddress.getByName(ipAddress);

                // Converte il messaggio in un array di byte
                byte[] msg = message.getBytes();

                // Crea un DatagramPacket con i dati del messaggio, l'indirizzo del gruppo e la porta
                DatagramPacket packet = new DatagramPacket(msg, msg.length, group, port);

                // Invia il pacchetto attraverso il MulticastSocket
                multicastSocket.send(packet);
            } catch (IOException e){
                System.err.println("Impossibile inviare il messaggio");
                e.printStackTrace();
                System.exit(1);
            }
            // Il MulticastSocket viene chiuso automaticamente grazie all'uso del try-with-resources
        }   

    }
    private static class DataUpdater implements Runnable {

        @Override
        public void run() {
            // Aggiornamento dei dati degli hotel
            writeHotelsToJson();

            // Aggiornamento dei dati degli utenti
            writeUsersToJson();

            // Aggiornamento dei dati delle recensioni
            writeReviewsToJson();

            // Stampa esecuzione dell'aggiornamento
            synchronized(printSyncLock){
                System.out.println("[SERVER] Eseguito persistenza dei dati su Json");
            }
        }

        // Metodo per scrivere i dati dalla mappa hotelsByCity in un file JSON
        private static void writeHotelsToJson() {
            File file = new File(HOTELS_JSON_FILE);
            // Verifica se il file esiste
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                if (!file.exists()) {
                    throw new FileNotFoundException("File non trovato: " + HOTELS_JSON_FILE);
                }
            
                // Converte hotelsByCity in una lista di oggetti Hotel
                ArrayList<Hotel> hotelsList = new ArrayList<>();
                for (Map.Entry<String, ConcurrentLinkedQueue<Hotel>> entry : hotelsByCity.entrySet()) {
                    ConcurrentLinkedQueue<Hotel> hotelSet = entry.getValue();
                    hotelsList.addAll(hotelSet);
                }

                // Converte hotelsList in formato JSON e scrive nel file specificato   
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(hotelsList, writer);
            } catch (FileNotFoundException e) {
                System.err.println("Errore: File non trovato: " + HOTELS_JSON_FILE);
                e.printStackTrace();
                System.exit(1);
            } catch (JsonIOException e) {
                System.err.println("Errore durante la serializzazione JSON.");
                e.printStackTrace();
                System.exit(1);
            } catch (Exception e) {
                System.err.println("Errore imprevisto durante la scrittura del file JSON");
                e.printStackTrace();
                System.exit(1);
            }
        }

        // Metodo per scrivere i dati dalla mappa registeredUsers in un file JSON
        private static void writeUsersToJson() {
            File file = new File(USERS_JSON_FILE);
            // Verifica se il file esiste
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                if (!file.exists()) {
                    throw new FileNotFoundException("File non trovato: " + USERS_JSON_FILE);
                }
            
                // Converting registeredUsers to an ArrayList
                ArrayList<User> userList = new ArrayList<>(registeredUsers.values());

                // Converte hotelsList in formato JSON e scrive nel file specificato   
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(userList, writer);
            } catch (FileNotFoundException e) {
                System.err.println("Errore: File non trovato: " + USERS_JSON_FILE);
                e.printStackTrace();
                System.exit(1);
            } catch (JsonIOException e) {
                System.err.println("Errore durante la serializzazione JSON");
                 e.printStackTrace();
                System.exit(1);
            } catch (Exception e) {
                System.err.println("Errore imprevisto durante la scrittura del file JSON");
                e.printStackTrace();
                System.exit(1);
            }
        }
    
        // Metodo per scrivere i dati dalla coda reviews in un file JSON
        private static void writeReviewsToJson() {
            File file = new File(REVIEWS_JSON_FILE);
            // Verifica se il file esiste
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                if (!file.exists()) {
                    throw new FileNotFoundException("File non trovato: " + REVIEWS_JSON_FILE);
                }

                // Converte reviews in una lista di oggetti Review
                // Lista che conterrà tutte le recensioni
                ArrayList<Review> reviewsList = new ArrayList<>();
                for (Map.Entry<String, ConcurrentLinkedQueue<Review>> entry : reviews.entrySet()) {
                    ConcurrentLinkedQueue<Review> reviewsSet = entry.getValue();
                    reviewsList.addAll(reviewsSet);
                }

                // Converte hotelsList in formato JSON e scrive nel file specificato   
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(reviewsList, writer);
            } catch (FileNotFoundException e) {
                System.err.println("Errore: File non trovato: " + REVIEWS_JSON_FILE);
                e.printStackTrace();
                System.exit(1);
            } catch (JsonIOException e) {
                System.err.println("Errore durante la serializzazione JSON");
                e.printStackTrace();
                System.exit(1);
            } catch (Exception e) {
                System.err.println("Errore imprevisto durante la scrittura del file JSON");
                e.printStackTrace();
                System.exit(1);
            }
        }

    }





    /* UTILITY GENERALI*/
    private static void readConfig() throws FileNotFoundException, IOException {
        // Crea un nuovo oggetto File per il file di configurazione ed un FileInputStream per leggerlo
        File file = new File(configName); 
        FileInputStream input = new FileInputStream(file); 

        // Crea un oggetto Properties per caricare le proprietà dal file di configurazione
        Properties prop = new Properties(); 
        
        prop.load(input); // Carica le proprietà dal file
    
        // Legge e assegna le proprietà da file alle variabili definite globalmente o all'interno del metodo
        tcpPort = Integer.parseInt(prop.getProperty("tcpPort"));
        bufSize = Integer.parseInt(prop.getProperty("bufSize"));
        exitMessage = prop.getProperty("exitMessage");
        periodicDataUpdaterDelay = Integer.parseInt(prop.getProperty("periodicDataUpdaterDelay"));
        hotelRankingUpdateFrequency = Integer.parseInt(prop.getProperty("hotelRankingUpdateFrequency"));
        timeThreshold = Long.parseLong(prop.getProperty("timeThreshold"));
        udpPort = Integer.parseInt(prop.getProperty("udpPort"));
        udpAddress = prop.getProperty("udpAddress");
        maxDelay = Long.parseLong(prop.getProperty("maxDelay"));
        HOTELS_JSON_FILE = prop.getProperty("HOTELS_JSON_FILE");
        USERS_JSON_FILE = prop.getProperty("USERS_JSON_FILE");
        REVIEWS_JSON_FILE = prop.getProperty("REVIEWS_JSON_FILE");
    
        input.close(); // Chiude il flusso dopo aver utilizzato il file di configurazione
    }
    private static void loadHotelsFromJSON() {
        try {
            File file = new File(HOTELS_JSON_FILE);

            // Verifica se il file esiste
            if (!file.exists()) {
                throw new FileNotFoundException("File non trovato: " + HOTELS_JSON_FILE);
            }
            // Verifica se il file è vuoto
            if (file.length() == 0) {
                throw new EmptyFileException("il file è vuoto: " + HOTELS_JSON_FILE);
            }

            // Creazione di un'istanza Gson per la gestione della conversione da JSON a oggetti Java
            Gson gson = new Gson();
            // Definizione del tipo di dato per la deserializzazione del JSON in una lista di Hotel
            Type listType = new TypeToken<ArrayList<Hotel>>() {}.getType();
            // Deserializzazione del contenuto del file JSON in una lista di oggetti Hotel utilizzando Gson
            ArrayList<Hotel> hotelsList = gson.fromJson(new BufferedReader(new FileReader(file)), listType);


            // Riempimento della struttura dati hotelsByCity
            for (Hotel hotel : hotelsList) {
                String city = hotel.getCity();
                // Verifica se la mappa contiene già la citta
                hotelsByCity.computeIfAbsent(city, k -> new ConcurrentLinkedQueue<>()); // Inizializza la coda se la città non è presente nella mappa
                // Aggiunge l'hotel al set per la citta corrispondente
                hotelsByCity.get(city).add(hotel);

                // Questa serve per normalizzare i punteggi per fare ranking
                cityStatsMap.computeIfAbsent(city, k -> new CityStats()); // Inizializza le statistiche della città se non sono presenti nella mappa
            }

        } catch (FileNotFoundException e) {
            System.err.println("Errore: File non trovato: " + HOTELS_JSON_FILE);
            e.printStackTrace();
            System.exit(1);
        } catch (EmptyFileException e) {
            System.err.println("Errore: Il file JSON è vuoto: " + HOTELS_JSON_FILE);
            e.printStackTrace();
            System.exit(1);
        } catch (JsonSyntaxException e) {
            System.err.println("Errore di formato JSON nel file: " + HOTELS_JSON_FILE);
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Errore imprevisto durante il caricamento dei dati: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    private static void loadUsersFromJSON() {
        try {
            File file = new File(USERS_JSON_FILE);

            // Verifica se il file esiste
            if (!file.exists()) {
                throw new FileNotFoundException("File non trovato: " + USERS_JSON_FILE);
            }
            // Verifica se il file è vuoto
            if (file.length() == 0) {
                throw new EmptyFileException("il file è vuoto: " + USERS_JSON_FILE);
            }

            // Crea un'istanza di Gson per la gestione della conversione da JSON a oggetti Java
            Gson gson = new Gson();
            // Definisce il tipo di dato per la deserializzazione del JSON in una lista di oggetti User
            Type listType = new TypeToken<ArrayList<User>>() {}.getType();
            // Legge i dati dal file JSON e li deserializza in una lista di oggetti User utilizzando Gson
            ArrayList<User> usersList = gson.fromJson(new BufferedReader(new FileReader(file)), listType);


            // Riempimento della struttura dati usersList
            for (User user : usersList) {
                String username = user.getUsername();

                registeredUsers.putIfAbsent(username, user);
            }
        } catch (FileNotFoundException e) {
            System.err.println("Errore di I/O durante la lettura del file: " + USERS_JSON_FILE);
            e.printStackTrace();
            System.exit(1);
        } catch (EmptyFileException e) {
            System.err.println("Errore: Il file JSON è vuoto: " + USERS_JSON_FILE);
            e.printStackTrace();
            System.exit(1);
        } catch (JsonSyntaxException e) {
            System.err.println("Errore di formato JSON nel file: " + USERS_JSON_FILE);
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Errore imprevisto durante il caricamento dei dati: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    private static void loadReviewsFromJSON() {
        try {
            File file = new File(REVIEWS_JSON_FILE);

            // Verifica se il file esiste
            if (!file.exists()) {
                throw new FileNotFoundException("File non trovato: " + REVIEWS_JSON_FILE);
            }

            // Verifica se il file è vuoto
            if (file.length() == 0) {
                throw new EmptyFileException("Il file è vuoto: " + REVIEWS_JSON_FILE);
            }

            // Crea un'istanza di Gson per la gestione della conversione da JSON a oggetti Java
            Gson gson = new Gson();
            // Definisce il tipo di dato per la deserializzazione del JSON in una lista di oggetti Review
            Type listType = new TypeToken<ArrayList<Review>>() {}.getType();
            // Legge i dati dal file JSON e li deserializza in una lista di oggetti Review utilizzando Gson
            ArrayList<Review> reviewsList = gson.fromJson(new BufferedReader(new FileReader(file)), listType);


            // Riempimento della struttura dati reviewsList
            for (Review review : reviewsList) {
                String hotelName = review.getHotelName();
                // Usa computeIfAbsent() per aggiungere la recensione alla mappa
                reviews.computeIfAbsent(hotelName, k -> new ConcurrentLinkedQueue<>());
                // Aggiungi la recensione al set per l'hotel corrispondente
                reviews.get(hotelName).add(review);
            }

        } catch (FileNotFoundException e) {
            System.err.println("Errore di I/O durante la lettura del file: " + REVIEWS_JSON_FILE);
            e.printStackTrace();
            System.exit(1);
        } catch (EmptyFileException e) {
            System.err.println("Errore: Il file JSON è vuoto: " + REVIEWS_JSON_FILE);
            e.printStackTrace();
            System.exit(1);
        } catch (JsonSyntaxException e) {
            System.err.println("Errore di formato JSON nel file: " + REVIEWS_JSON_FILE);
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Errore imprevisto durante il caricamento dei dati: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    private static void sort(ConcurrentLinkedQueue<Hotel> hotels) {
        List<Hotel> hotelList = new ArrayList<>(hotels);

        // Ordina la lista di Hotel
        Collections.sort(hotelList);

        // Svuota la ConcurrentLinkedQueue
        hotels.clear();

        // Aggiungi gli elementi ordinati dalla lista alla ConcurrentLinkedQueue
        hotels.addAll(hotelList);
    }
    

    /* ECCEZIONI */
    private static class EmptyFileException extends Exception {
        public EmptyFileException(String message) {
            super(message);
        }
    }

}
