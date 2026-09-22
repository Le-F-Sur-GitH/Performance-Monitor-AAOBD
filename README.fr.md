<p align="center">
  <img src="docs/logo.png" width="112" alt="IcÃ´ne Performance Monitor AAOBD">
</p>

<h1 align="center">Performance Monitor AAOBD</h1>

<p align="center">
  Jauges moteur en temps rÃ©el sur l'Ã©cran Android Auto, alimentÃ©es directement par un adaptateur OBD-II Bluetooth (OBDLink, vLinker, ELM327...).<br>
  Sans Torque, sans application tierce, sans compte, sans internet.
</p>

<p align="center"><a href="README.md">English version</a></p>

---

## Fonctions

- **Tableaux de bord sur l'Ã©cran de la voiture** : jusqu'Ã  3 jauges et 4 valeurs par tableau, autant de tableaux que voulu, balayage pour changer.
- **Graphique en direct** des valeurs affichÃ©es (balayage vertical).
- **Alarmes** : changement de couleur au-dessus ou en dessous d'un seuil (shift light, surchauffe).
- **ThÃ¨mes, polices et fonds** inspirÃ©s des combinÃ©s d'origine, pochette d'album en fond.
- **Liaison Bluetooth directe** avec l'adaptateur, Bluetooth LE ou classique : appairage intÃ©grÃ©, reconnexion automatique, requÃªtes CAN groupÃ©es, replis automatiques pour les clones ELM327.
- **Valeurs calculÃ©es** : pression turbo (MAP - Baro), tension batterie lue par l'adaptateur.
- **Formules de conversion** par valeur (expressions EvalEx).
- **Ã‰cran de diagnostic** : Ã©tat, journal, test rapide, console ELM327.
- **Import / export** des tableaux de bord.

## MatÃ©riel

- TÃ©lÃ©phone : Android 9 ou plus avec Android Auto.
- VÃ©hicule : compatible OBD-II / EOBD (Europe : essence 2001+, diesel 2004+).
- Un adaptateur Bluetooth compatible ELM327 :

| Adaptateur | Liaison | Statut |
|---|---|---|
| OBDLink CX | Bluetooth LE | TestÃ© |
| OBDLink MX+, MX, LX | Bluetooth | Pris en charge, retours bienvenus |
| Vgate vLinker MC+, FS, BM+ | Bluetooth | Pris en charge, retours bienvenus |
| Vgate vLinker MC / iCar Pro BLE | Bluetooth LE | Pris en charge, retours bienvenus |
| Vgate iCar Pro, iCar 2 (versions Bluetooth) | Bluetooth | Pris en charge, retours bienvenus |
| Veepeak OBDCheck BLE / BLE+ | Bluetooth LE | Pris en charge, retours bienvenus |
| Konnwei KW902, KW903 | Bluetooth | Pris en charge, retours bienvenus |
| Clones ELM327 v1.5 / v2.1 gÃ©nÃ©riques | Bluetooth ou BLE | Au mieux |

Adaptateurs Wi-Fi non pris en charge : ils monopolisent le Wi-Fi du tÃ©lÃ©phone, nÃ©cessaire Ã  Android Auto sans fil.

## Installation

L'app n'est pas sur Google Play. Android Auto n'affiche que les apps installÃ©es depuis un store : installer avec **[AAEnabler](https://github.com/malebuffy/AAEnabler)**, qui installe l'APK comme Android Auto l'attend.

1. Installer AAEnabler depuis sa [page de releases](https://github.com/malebuffy/AAEnabler/releases/latest).
2. TÃ©lÃ©charger le dernier `PerformanceMonitorAAOBD-x.y.z-release.apk` dans [Releases](https://github.com/Le-F-Sur-GitH/Performance-Monitor-AAOBD/releases/latest).
3. Ouvrir AAEnabler > **Select local APK** > choisir l'APK tÃ©lÃ©chargÃ© > **Install app**.
4. Ouvrir Performance Monitor AAOBD sur le tÃ©lÃ©phone, accepter les autorisations Bluetooth.
5. Brancher l'adaptateur, mettre le contact, puis **icÃ´ne Bluetooth** > **Rechercher** et choisir l'adaptateur.
   OBDLink CX : appairage acceptÃ© uniquement dans les 5 minutes aprÃ¨s le branchement.
   Adaptateurs Bluetooth classiques : valider la demande d'appairage, code PIN gÃ©nÃ©ralement `1234` ou `0000`.
6. Connecter le tÃ©lÃ©phone Ã  la voiture (ou relancer Android Auto) : **Performance Monitor AAOBD** apparaÃ®t dans le lanceur Android Auto.

Installer chaque mise Ã  jour de la mÃªme faÃ§on, via AAEnabler.
Les jauges se configurent sur le tÃ©lÃ©phone, dans les paramÃ¨tres de l'app.

<details>
<summary>Sans AAEnabler</summary>

Installer l'APK normalement, puis dans les paramÃ¨tres Android Auto : toucher **Version** 10 fois > menu > **ParamÃ¨tres pour les dÃ©veloppeurs** > activer **Sources inconnues**.
Selon la version d'Android Auto, l'app peut rester masquÃ©e : utiliser AAEnabler dans ce cas.
</details>

> Depuis la 1.x : l'identifiant de l'app a changÃ©, la 2.0 s'installe comme une nouvelle app.
> Exporter les tableaux de bord depuis la 1.x (menu > Exporter), la dÃ©sinstaller, puis importer dans la 2.0.

## Compilation

```bash
git clone https://github.com/Le-F-Sur-GitH/Performance-Monitor-AAOBD.git
cd Performance-Monitor-AAOBD
./gradlew testDebugUnitTest assembleDebug
```

JDK 17. Les builds debug simulent des valeurs sans adaptateur, pour tester sur le [Desktop Head Unit](tools/dhu/README.md).

## Contribuer

Voir [CONTRIBUTING.md](CONTRIBUTING.md). Pour un problÃ¨me de connexion, joindre le journal de l'Ã©cran Adaptateur OBD (bouton Copier).

## Avertissement

Ne pas manipuler l'app en conduisant. Projet indÃ©pendant de Google, OBD Solutions et des constructeurs.

## CrÃ©dits et licence

DÃ©veloppÃ© par **Le F**. Licence [GNU GPL v3](LICENSE.md).
DÃ©rivÃ© de [aa-torque](https://github.com/agronick/aa-torque) de Kyle Agronick. Jauges : [SpeedView](https://github.com/anastr/SpeedView) (Apache 2.0). Voir [NOTICE.md](NOTICE.md).
