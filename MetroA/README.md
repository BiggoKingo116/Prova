# Metro A – Roma (Android)

App minimale che mostra i treni della linea A sui due binari, con i prossimi arrivi per stazione.

## Come compilarla
1. Installa Android Studio (gratuito).
2. File → Open → scegli la cartella `MetroA`. Android Studio scarica Gradle e le librerie da solo.
3. Collega il telefono con il debug USB attivo (o usa un emulatore) e premi ▶ Run.
   Per un file APK da installare: Build → Build APK(s).

Da riga di comando (serve l'Android SDK, con `ANDROID_HOME` impostato o `sdk.dir` in `local.properties`):
`./gradlew assembleDebug` → l'APK finisce in `app/build/outputs/apk/debug/app-debug.apk`.

## Da dove arrivano i dati
Il feed GTFS-Realtime di Roma Mobilità non trasmette la metropolitana (né posizioni né arrivi previsti),
quindi l'app usa l'**orario programmato**: al primo avvio scarica il GTFS statico di Roma Servizi per la Mobilità
(dati ATAC, licenza CC-BY 3.0, ~48 MB), estrae solo le corse della Metro A (route_id `MEA`) e le salva
sul telefono (poche centinaia di KB). L'orario viene riscaricato ogni 7 giorni, o prima se non copre più la data di oggi.

Posizioni dei treni e minuti di attesa sono calcolati dall'orario, quindi mostrati come intervallo (es. "3–5 min").
I treni fermi in banchina sono in rosso (sosta stimata di ~25 s attorno all'orario).

## Correzione con i passaggi reali
Nel pannello di una stazione, "Treno arrivato ora" salva in un database SQLite sul telefono lo scarto fra
il passaggio reale e quello programmato più vicino. Con almeno 3 passaggi l'app sposta stime e posizioni
del ritardo mediano, scegliendo i dati più simili: stessa stazione, direzione e fascia oraria (±1 h),
poi solo direzione e fascia oraria, poi solo direzione. Si usano gli ultimi 60 giorni; "Azzera" li cancella.

## Test
`./gradlew testDebugUnitTest` prova il parser su un GTFS di esempio. Con
`METROA_GTFS_ZIP=/percorso/rome_static_gtfs.zip` verifica anche il file reale.
