# Metro A – Roma (Android)

App minimale che mostra i treni della linea A sui due binari, con i prossimi arrivi per stazione.

## Come compilarla
1. Installa Android Studio (gratuito).
2. File → Open → scegli la cartella `MetroA`. Android Studio scarica Gradle e le librerie da solo.
3. Collega il telefono con il debug USB attivo (o usa un emulatore) e premi ▶ Run.
   Per un file APK da installare: Build → Build APK(s).

Dallo stesso progetto escono **due app** (in Android Studio: Build Variants → `userDebug` o `devDebug`):
- **MetroA** (`user`): l'app per tutti.
- **MetroA Dev** (`dev`): l'app sviluppatore, installabile accanto (vedi sotto).

Da riga di comando (serve l'Android SDK, con `ANDROID_HOME` impostato o `sdk.dir` in `local.properties`):
`./gradlew assembleUserDebug assembleDevDebug` → gli APK finiscono in
`app/build/outputs/apk/user/debug/app-user-debug.apk` e `app/build/outputs/apk/dev/debug/app-dev-debug.apk`.

## Da dove arrivano i dati
Il feed GTFS-Realtime di Roma Mobilità non trasmette la metropolitana (né posizioni né arrivi previsti),
quindi l'app usa l'**orario programmato**: al primo avvio scarica il GTFS statico di Roma Servizi per la Mobilità
(dati ATAC, licenza CC-BY 3.0, ~48 MB), estrae solo le corse della Metro A (route_id `MEA`) e le salva
sul telefono (poche centinaia di KB). L'orario viene riscaricato ogni 7 giorni, o prima se non copre più la data di oggi.

Posizioni dei treni e minuti di attesa sono calcolati dall'orario e corretti con le segnalazioni degli utenti.
I treni fermi in banchina sono in rosso; il pannello della stazione usa le stesse soglie ("In stazione",
"In arrivo", "N min"), quindi linea e pannello dicono sempre la stessa cosa.

## Impostazioni
Dall'icona ⚙ in alto, con un'anteprima dal vivo di un tratto di linea:
- **Linea**: distanza fra le stazioni (da compatta a larga); "distanze reali", con ogni tratta lunga in
  proporzione al tempo medio di viaggio dall'orario (da ~50 s Repubblica–Termini a ~165 s Cinecittà–Anagnina);
  linea capovolta (Anagnina in alto); minuti al prossimo treno accanto a ogni stazione, per una o due direzioni.
- **Treni**: forma (freccia, pallino, vagone), dimensione, movimento fluido o a scatti, treni fermi che pulsano.
- **Generale**: tema chiaro/scuro/di sistema, schermo sempre acceso, uso della posizione.
- **Linea orizzontale** col telefono girato (disattivabile).

## Stazioni preferite, widget e avvisi
- **Preferite**: ☆ nel pannello di una stazione; compaiono in cima con il prossimo treno per direzione e
  si gestiscono (ordine, rimozione) dalle impostazioni.
- **Widget** per la schermata home: prossimi arrivi della prima preferita (o Termini) come orari, perché
  Android aggiorna i widget al massimo ogni 30 minuti; mentre l'app è aperta si aggiorna ogni minuto.
- **Avviso "il treno sta arrivando"**: 🔔 nel pannello di una stazione imposta una notifica 1, 2, 3 o 5 minuti
  prima del prossimo treno (serve il permesso per le notifiche). Il momento è calcolato quando lo si imposta.

## "Sono sul treno"
Si sceglie la stazione di salita (quella vicina è già proposta) e la direzione. L'app riconosce la corsa
programmata e, finché il viaggio è in corso, segue la posizione anche a schermo spento (servizio con
notifica fissa): quando il telefono è entro ~120 m da una delle prossime 3 stazioni, la registra. Sottoterra
il segnale può mancare: la notifica e il riquadro hanno "Siamo a …" per segnarla a mano. Al capolinea o con
"Sono sceso" il viaggio si chiude e mostra quanto ci ha messo il treno, tratto per tratto, rispetto all'orario.

Una stazione si registra quando due posizioni di fila la indicano entro ~180 m (le posizioni con errore
oltre 250 m, tipiche della sola rete in galleria, non contano); saltare stazioni senza segnale è possibile solo
se è passato il tempo per arrivarci. Appena registrata, il riquadro dice "A Flaminio" e poi "Prossima: …".

Ogni stazione passata diventa una segnalazione online (tipo `TRIP`, con lo stesso `trip_id` per tutto il
viaggio): corregge subito le stime di tutti. Di base si inviano solo stazione e ora.

**Percorso GPS (facoltativo, spento di base)**: chi lo attiva (all'inizio del viaggio o nelle impostazioni)
condivide anche un punto GPS ogni 10 s durante il viaggio, nella tabella `trip_points`. L'app può solo
aggiungere punti: li legge solo il gestore dalla dashboard di Supabase, non gli altri utenti.
Nel database le viste `trip_segments` (ogni tratto di ogni viaggio) e `segment_times` (media per tratta negli
ultimi 60 giorni) mostrano quanto ci mettono davvero i treni.

## Segnalazioni condivise
Dalla sezione **Segnala** (o dal pannello di una stazione) si indica se i treni sono in anticipo o in ritardo:
- "Treno arrivato adesso": l'app calcola lo scarto dal passaggio programmato più vicino;
- oppure a mano: in anticipo / in orario / in ritardo di 1, 2, 3, 5 o 10 minuti.

Quando per una direzione ci sono segnalazioni recenti, l'app chiede "È così anche per te?": **Confermo**
aggiunge una segnalazione con lo stesso ritardo, **No, in orario** una "in orario". Così una segnalazione
sbagliata viene smentita in fretta.

**Avviso guasti**: con almeno 3 segnalazioni di ritardi oltre 5 minuti nella stessa direzione negli ultimi
15 minuti (e più di quelle che dicono il contrario) compare "Possibili problemi" in cima.

**Stazione vicina**: con il permesso di localizzazione (meglio "precisa": quella approssimativa sbaglia di
1–3 km) l'app segue la posizione finché è aperta, da GPS, rete e "fused", tenendo la più precisa. Entro 500 m
mostra "Sei a …" con i prossimi treni, la segna sulla linea e la propone in Segnala; più lontano indica la
stazione più vicina e la distanza. Avvisa se la localizzazione è spenta. La posizione non viene mai inviata.

Le segnalazioni vanno in un database online condiviso (Supabase) e sono salvate anche sul telefono, che le
invia appena c'è rete. Per la stima del ritardo di una direzione l'app usa, in ordine:
1. le segnalazioni degli ultimi 20 minuti (almeno 2): la situazione di adesso;
2. lo storico degli ultimi 60 giorni nella stessa fascia oraria (±1 h, almeno 3);
3. tutto lo storico della direzione (almeno 3).

### Configurare il server (una volta sola)
1. Crea un progetto gratuito su https://supabase.com.
2. SQL Editor → incolla il contenuto di `supabase/schema.sql` → Run (va rieseguito quando lo script cambia:
   aggiorna il database senza perdere dati). Crea la tabella `reports` con i controlli:
   valori nei limiti, niente modifiche o cancellazioni, al massimo una segnalazione ogni 30 s per stazione e
   direzione e 30 all'ora per telefono.
3. Project Settings → API: copia "Project URL" e la chiave "anon public" in `local.properties`:
   ```
   metroa.supabaseUrl=https://xxxx.supabase.co
   metroa.supabaseAnonKey=eyJ...
   ```
   (oppure le variabili d'ambiente `METROA_SUPABASE_URL` e `METROA_SUPABASE_ANON_KEY`), poi ricompila.

Se il server rifiuta una segnalazione (per esempio una seconda sulla stessa stazione e direzione entro 30 s)
l'app lo dice con il motivo e non la conta nelle stime; quelle doppie vengono fermate già prima dell'invio.

Le segnalazioni cancellate dalla dashboard (Table Editor → reports) spariscono anche dai telefoni: ogni
10 minuti l'app confronta la sua copia con il server.

La chiave "anon" finisce nell'APK ed è pubblica per costruzione: cosa si può fare lo decidono i permessi del database.
Senza configurazione l'app funziona lo stesso e tiene le segnalazioni solo sul telefono.

## MetroA Dev (app sviluppatore)
Si entra con un account Supabase; solo chi è nella tabella `developers` può usarla. Ha tre schede:
- **Treni**: numero del convoglio (la matricola scritta sul treno) associato alla corsa dell'orario vista in una
  stazione ("treno delle 10:55 da Battistini"), con nota facoltativa; elenco dei numeri registrati e quante volte
  è stato visto ogni convoglio. I numeri restano nell'app sviluppatore (tabella `train_numbers`).
- **GPS**: diagnostica dal vivo. Fonti attive, ultima posizione (fonte, precisione, età), stazione riconosciuta e
  perché ("troppo vaga", "troppo lontana"), le tre stazioni più vicine, e l'elenco delle posizioni usate o scartate.
- **Dati**: tutte le segnalazioni (anche l'identificativo del telefono), i viaggi, i tempi medi per tratta e i
  percorsi GPS condivisi; si possono eliminare una segnalazione, tutto di un telefono (spam) o un viaggio intero.

Il codice sviluppatore è in `app/src/dev` ed entra solo nell'APK Dev.

### Aggiungere uno sviluppatore
1. Dashboard di Supabase → Authentication → Users → **Add user** → Create new user (email e password, con
   "Auto Confirm User").
2. SQL Editor:
   ```sql
   insert into public.developers (user_id) select id from auth.users where email = 'nome@esempio.it';
   ```
3. Consigliato: Authentication → Sign In / Providers → Email → disattivare "Allow new users to sign up", così
   nessuno si crea un account da solo (anche se lo facesse, senza la riga in `developers` non vedrebbe niente).

## Test
`./gradlew testUserDebugUnitTest testDevDebugUnitTest` prova il parser su un GTFS di esempio, il calcolo di posizioni e arrivi e il client del server. Con
`METROA_GTFS_ZIP=/percorso/rome_static_gtfs.zip` verifica anche il file reale.
