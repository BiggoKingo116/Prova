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

Posizioni dei treni e minuti di attesa sono calcolati dall'orario: ritardi, guasti e scioperi non si vedono.

## Test
`./gradlew testDebugUnitTest` prova il parser su un GTFS di esempio. Con
`METROA_GTFS_ZIP=/percorso/rome_static_gtfs.zip` verifica anche il file reale.
