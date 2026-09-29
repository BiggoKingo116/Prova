# Metro A – Roma (Android)

App minimale che mostra i treni della linea A sui due binari, con i prossimi arrivi per stazione.

## Come compilarla
1. Installa Android Studio (gratuito).
2. File → Open → scegli la cartella `MetroA`. Android Studio scarica Gradle e le librerie da solo.
3. Collega il telefono con il debug USB attivo (o usa un emulatore) e premi ▶ Run.
   Per un file APK da installare: Build → Build APK(s).

## Da dove arrivano i dati
Feed GTFS-Realtime "Vehicle Positions" di Roma Servizi per la Mobilità (dati ATAC, licenza CC-BY 3.0),
aggiornato ogni 15 secondi mentre l'app è aperta.

## Se non vedi treni
Il feed potrebbe usare un route_id diverso per la Metro A, oppure non trasmettere affatto le posizioni
dei treni della metropolitana. In basso nell'app compare un pannello con i route_id dei mezzi vicini alla linea:
se la metro è tra questi, metti quel valore in `METRO_A_ROUTE_IDS` dentro `FeedRepository.kt`.
